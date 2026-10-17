package com.aistock.assistant.voice.live

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Base64
import com.aistock.assistant.ai.AssistantReply
import com.aistock.assistant.ai.ReplyAction
import com.aistock.assistant.ai.llm.Http
import com.aistock.assistant.conversation.Stocky
import com.aistock.assistant.server.LiveTicket
import com.aistock.assistant.server.ServerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

enum class LiveState { CONNECTING, LISTENING, THINKING, SPEAKING, RECONNECTING, ENDED, ERROR }

sealed class LiveUpdate {
    class UserSpeech(val text: String, val final: Boolean) : LiveUpdate()
    class StockySpeech(val text: String, val final: Boolean) : LiveUpdate()
    /** A confirmation card (CONFIRM) or a verified result (DONE / ERROR) from a tool. */
    class Card(val reply: AssistantReply) : LiveUpdate()
    class Actions(val actions: List<ReplyAction>) : LiveUpdate()
    class Ended(val reason: String) : LiveUpdate()
}

/**
 * A real-time spoken conversation with Stocky (Gemini Live API).
 *
 * The microphone streams 16 kHz audio to Google over a WebSocket opened with a short-lived token from the owner's
 * AIStock server; Stocky's voice streams back at 24 kHz and is played as it arrives. Google's voice detection decides
 * when the owner has finished and stops Stocky when the owner talks over it (barge-in). Tool calls are run by the app
 * through [Stocky.runVoiceTool], with the same confirmation rules as typed chat.
 */
class LiveSession(
    private val context: Context,
    private val server: ServerApi,
    private val stocky: Stocky,
    private val scope: CoroutineScope,
    /** End by itself after this long with nobody speaking, so minutes are not wasted. */
    private val idleSeconds: Int = 40,
    private val voiceName: String? = null,
    /** Words already said with the wake phrase ("Hey Stocky, add 10 metres fabric"), sent as the first turn. */
    private val firstText: String? = null,
) {
    private var firstSent = false
    private val _state = MutableStateFlow(LiveState.CONNECTING)
    val state: StateFlow<LiveState> = _state
    private val _updates = MutableSharedFlow<LiveUpdate>(extraBufferCapacity = 128)
    val updates: SharedFlow<LiveUpdate> = _updates

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val turns = LiveTurns()
    private val toolLock = Mutex()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var ticket: LiveTicket? = null
    @Volatile private var ticketStartedAt = 0L
    @Volatile private var resumeHandle: String? = null
    @Volatile private var stopped = false
    @Volatile private var ready = false
    @Volatile private var lastActivity = System.currentTimeMillis()
    @Volatile private var generationDone = false
    @Volatile private var hasEchoCanceller = false
    private var reconnects = 0
    /** A reconnection is already under way (watchdog and connection loss must not start a second one). */
    private val renewing = java.util.concurrent.atomic.AtomicBoolean(false)

    private var recorder: AudioRecord? = null
    private var micThread: Thread? = null
    private var track: AudioTrack? = null
    private var playThread: Thread? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var framesWritten = 0L
    private var focus: AudioFocusRequest? = null
    private var oldMode = AudioManager.MODE_NORMAL
    private var watchdog: Job? = null
    private val stockyText = StringBuilder()
    private val userText = StringBuilder()

    private fun set(s: LiveState) { if (_state.value != LiveState.ENDED) _state.value = s }

    fun start() {
        scope.launch(Dispatchers.IO) { connect(resume = false) }
        watchdog = scope.launch {
            while (!stopped) {
                delay(5_000)
                val t = ticket ?: continue
                val elapsed = (System.currentTimeMillis() - ticketStartedAt) / 1000
                if (elapsed % 60 < 5) server.liveUsage(t.sessionId, elapsed.toInt(), false)
                if (_state.value == LiveState.LISTENING && System.currentTimeMillis() - lastActivity > idleSeconds * 1000L) stop("idle")
                if (elapsed > t.maxSeconds - 5) {
                    // The token is about to run out: continue the same conversation on a fresh one, or end politely.
                    if (resumeHandle != null && reconnects < 3 && t.mode == "token" && renewing.compareAndSet(false, true)) {
                        reconnects++
                        val old = ws; ws = null; ready = false
                        try { old?.close(1000, "renew") } catch (e: Exception) { }
                        launch(Dispatchers.IO) { connect(resume = true) }
                    } else if (t.mode == "token" && !renewing.get()) stop("time")
                }
            }
        }
    }

    private suspend fun connect(resume: Boolean) {
        set(if (resume) LiveState.RECONNECTING else LiveState.CONNECTING)
        val t = try {
            server.liveSession()
        } catch (e: Exception) {
            renewing.set(false)
            if (!stopped) fail(e.message ?: "Could not start a voice session."); return
        }
        // Stopped while waiting for the server: close the session we were given and open nothing.
        if (stopped) { server.liveUsage(t.sessionId, 0, true); return }
        ticket?.let { old -> server.liveUsage(old.sessionId, ((System.currentTimeMillis() - ticketStartedAt) / 1000).toInt(), true) }
        ticket = t
        ticketStartedAt = System.currentTimeMillis()
        ready = false
        val instructions = try { stocky.voiceInstructions() } catch (e: Exception) { "" }
        val setup = LiveProtocol.setup(t.model, instructions, stocky.tools, if (resume) resumeHandle else null, voiceName)
        val request = if (t.mode == "relay") {
            val bearer = try { server.bearer() } catch (e: Exception) { renewing.set(false); if (!stopped) fail(e.message ?: "Sign in again."); return }
            Request.Builder().url(t.wsUrl).header("Authorization", "Bearer $bearer").build()
        } else {
            Request.Builder().url(t.wsUrl + (if ('?' in t.wsUrl) "&" else "?") + "access_token=" + URLEncoder.encode(t.token, "UTF-8")).build()
        }
        if (stopped) return
        val client = Http.client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { if (webSocket === ws && !stopped) webSocket.send(setup) else webSocket.close(1000, null) }
            override fun onMessage(webSocket: WebSocket, text: String) { if (webSocket === ws && !stopped) handle(text) }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { if (webSocket === ws && !stopped) handle(bytes.utf8()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (webSocket === ws) lost(code, reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (webSocket === ws) lost(response?.code ?: -1, t.message ?: t.javaClass.simpleName) }
        })
    }

    private fun lost(code: Int, reason: String) {
        if (stopped) return
        ready = false
        // The server ends a session at its time limit (4000); Google ends long ones. With a resume handle the
        // conversation continues where it was, on a fresh session.
        if (resumeHandle != null && reconnects < 3 && code !in setOf(4401, 4402, 4429, 4400)) {
            if (!renewing.compareAndSet(false, true)) return
            reconnects++
            scope.launch(Dispatchers.IO) { delay(400); if (!stopped) connect(resume = true) else renewing.set(false) }
        } else if (code == 4000) stop("time") else fail(explain(code, reason))
    }

    private fun explain(code: Int, reason: String): String = when {
        reason.contains("quota", true) || code == 429 -> "The voice service is busy or its limit is reached. Typing to Stocky still works."
        code == 4402 || code == 4429 || code == 4401 -> reason.ifBlank { "The voice session was refused." }
        code == 1008 || reason.contains("auth", true) || reason.contains("token", true) -> "The voice session was refused ($reason)."
        else -> "The voice connection dropped" + if (reason.isNotBlank()) " ($reason)." else "."
    }

    private fun fail(message: String) {
        set(LiveState.ERROR)
        _updates.tryEmit(LiveUpdate.Ended(message))
        stop("error")
    }

    // ------------------------------------------------------------------ server events

    private fun handle(frame: String) {
        for (e in LiveProtocol.parse(frame)) when (e) {
            LiveEvent.SetupComplete -> {
                renewing.set(false)
                ready = true; reconnects = 0; lastActivity = System.currentTimeMillis(); startAudio(); set(LiveState.LISTENING)
                if (!firstSent && !firstText.isNullOrBlank()) {
                    firstSent = true
                    turns.onUser(firstText); stocky.newVoiceTurn(null); stocky.voiceHeard()
                    ws?.send(LiveProtocol.note(firstText))
                    set(LiveState.THINKING)
                }
            }
            is LiveEvent.UserText -> {
                lastActivity = System.currentTimeMillis()
                if (turns.onUser(e.text)) { stocky.newVoiceTurn(null); userText.clear() }
                stocky.voiceHeard()
                userText.append(e.text)
                _updates.tryEmit(LiveUpdate.UserSpeech(userText.toString().trim(), false))
                if (_state.value == LiveState.LISTENING) set(LiveState.THINKING)
            }
            is LiveEvent.Audio -> {
                lastActivity = System.currentTimeMillis()
                generationDone = false
                val pcm = try { Base64.decode(e.base64, Base64.DEFAULT) } catch (x: Exception) { null }
                if (pcm != null) { playQueue.offer(pcm); set(LiveState.SPEAKING) }
            }
            is LiveEvent.StockyText -> { turns.onStocky(e.text); stockyText.append(e.text); _updates.tryEmit(LiveUpdate.StockySpeech(stockyText.toString().trim(), false)) }
            LiveEvent.Interrupted -> {
                stocky.voiceModelDone()
                // The owner spoke over Stocky: stop talking at once.
                playQueue.clear()
                try { track?.pause(); track?.flush(); track?.play() } catch (x: Exception) { }
                framesWritten = 0
                turns.onInterrupted()
                if (stockyText.isNotBlank()) _updates.tryEmit(LiveUpdate.StockySpeech(stockyText.toString().trim() + " …", true))
                stockyText.clear()
                set(LiveState.LISTENING)
            }
            LiveEvent.GenerationComplete -> generationDone = true
            LiveEvent.TurnComplete -> {
                generationDone = true
                stocky.voiceModelDone()
                val (u, s) = turns.onTurnComplete()
                if (u.isNotBlank()) _updates.tryEmit(LiveUpdate.UserSpeech(u, true))
                if (s.isNotBlank()) _updates.tryEmit(LiveUpdate.StockySpeech(s, true))
                stocky.voiceTurnDone(u, s)
                userText.clear(); stockyText.clear()
                if (playQueue.isEmpty() && _state.value != LiveState.SPEAKING) set(LiveState.LISTENING)
            }
            is LiveEvent.Tools -> runTools(e.calls)
            is LiveEvent.ToolsCancelled -> turns.cancel(e.ids)
            is LiveEvent.GoAway -> { /* reconnection with the resume handle happens when the socket closes */ }
            is LiveEvent.Resumable -> resumeHandle = e.handle
            is LiveEvent.Tokens -> {}
        }
    }

    private fun runTools(calls: List<com.aistock.assistant.ai.llm.ToolCall>) {
        set(LiveState.THINKING)
        scope.launch {
            // One at a time, in order, and never the same call twice.
            val results = toolLock.withLock {
                calls.map { c ->
                    if (!turns.shouldRun(c.id)) return@map Triple(c.id, c.name, "Already handled.")
                    val r = stocky.runVoiceTool(c)
                    r.card?.let { _updates.tryEmit(LiveUpdate.Card(it)) }
                    r.executed?.let { _updates.tryEmit(LiveUpdate.Card(it)) }
                    if (r.actions.isNotEmpty()) _updates.tryEmit(LiveUpdate.Actions(r.actions))
                    Triple(c.id, c.name, r.text)
                }
            }
            ws?.send(LiveProtocol.toolResponse(results))
        }
    }

    /** Tells Stocky what happened on screen (Confirm or Cancel pressed), so it can say so. */
    fun tell(note: String) {
        if (ready) ws?.send(LiveProtocol.note(note))
    }

    // ------------------------------------------------------------------ audio

    private fun route() {
        oldMode = audio.mode
        try { audio.mode = AudioManager.MODE_IN_COMMUNICATION } catch (e: Exception) { }
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audio.availableCommunicationDevices
            val prefer = listOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            prefer.firstNotNullOfOrNull { t -> devices.firstOrNull { it.type == t } }?.let { try { audio.setCommunicationDevice(it) } catch (e: Exception) { } }
        } else {
            @Suppress("DEPRECATION")
            if (!audio.isWiredHeadsetOn) audio.isSpeakerphoneOn = true
        }
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build().also { try { audio.requestAudioFocus(it) } catch (e: Exception) { } }
    }

    private fun unroute() {
        focus?.let { try { audio.abandonAudioFocusRequest(it) } catch (e: Exception) { } }
        focus = null
        if (Build.VERSION.SDK_INT >= 31) try { audio.clearCommunicationDevice() } catch (e: Exception) { }
        else @Suppress("DEPRECATION") try { audio.isSpeakerphoneOn = false } catch (e: Exception) { }
        try { audio.mode = oldMode } catch (e: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (recorder != null || stopped) return
        route()
        val rate = 16_000
        val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, 6_400))
        } catch (e: Exception) { fail("The microphone could not be opened."); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); fail("The microphone is being used by another app."); return }
        recorder = rec
        // The phone's own echo cancelling lets the owner talk over Stocky without Stocky hearing itself.
        hasEchoCanceller = try { AcousticEchoCanceler.isAvailable() && AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } != null } catch (e: Exception) { false }
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true } catch (e: Exception) { }
        try { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)?.enabled = true } catch (e: Exception) { }

        val outRate = 24_000
        val minOut = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(outRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minOut, outRate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(rec.audioSessionId)
            .build()
        track = t
        t.play()

        rec.startRecording()
        micThread = Thread({
            val buf = ByteArray(3_200) // 100 ms
            while (!stopped && recorder === rec) {
                val n = try { rec.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                if (n <= 0) { if (n < 0) break else continue }
                if (!ready) continue
                // Without echo cancelling, while Stocky talks only clearly louder speech (the owner, close by) goes through.
                if (!hasEchoCanceller && _state.value == LiveState.SPEAKING && rms(buf, n) < 2_200) continue
                ws?.send(LiveProtocol.audio(Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)))
            }
        }, "stocky-mic").apply { isDaemon = true; start() }

        playThread = Thread({
            while (!stopped && track === t) {
                val chunk = playQueue.poll(80, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    try { t.write(chunk, 0, chunk.size); framesWritten += chunk.size / 2 } catch (e: Exception) { }
                } else if (_state.value == LiveState.SPEAKING && generationDone) {
                    // Everything received has been played: Stocky has finished speaking.
                    val played = try { t.playbackHeadPosition.toLong() and 0xffffffffL } catch (e: Exception) { framesWritten }
                    if (played >= framesWritten - 240) { lastActivity = System.currentTimeMillis(); set(LiveState.LISTENING) }
                }
            }
        }, "stocky-voice").apply { isDaemon = true; start() }
    }

    private fun rms(b: ByteArray, n: Int): Double {
        var sum = 0.0; var i = 0
        while (i + 1 < n) { val s = (b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8); sum += s.toDouble() * s; i += 2 }
        return sqrt(sum / maxOf(1, n / 2))
    }

    // ------------------------------------------------------------------ end

    /** @param reason user, idle, time or error */
    fun stop(reason: String = "user") {
        if (stopped) return
        stopped = true
        ready = false
        watchdog?.cancel()
        try { ws?.send(LiveProtocol.audioStreamEnd()) } catch (e: Exception) { }
        try { ws?.close(1000, "done") } catch (e: Exception) { }
        ws = null
        recorder?.let { try { it.stop() } catch (e: Exception) { }; it.release() }
        recorder = null
        playQueue.clear()
        track?.let { try { it.pause(); it.flush(); it.release() } catch (e: Exception) { } }
        track = null
        unroute()
        ticket?.let { t -> val secs = ((System.currentTimeMillis() - ticketStartedAt) / 1000).toInt(); scope.launch(Dispatchers.IO) { server.liveUsage(t.sessionId, secs, true) } }
        if (_state.value != LiveState.ERROR) _updates.tryEmit(LiveUpdate.Ended(reason))
        _state.value = LiveState.ENDED
    }
}
