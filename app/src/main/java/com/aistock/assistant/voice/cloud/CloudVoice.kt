package com.aistock.assistant.voice.cloud

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.speech.SpeechRecognizer
import com.aistock.assistant.server.ServerApi
import com.aistock.assistant.server.ServerException
import com.aistock.assistant.voice.SpeechInput
import com.aistock.assistant.voice.SpeechOutput
import com.aistock.assistant.voice.VoiceRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Voice input through Google Cloud Speech-to-Text: the phone records one request (stopping by itself after a pause),
 * sends the audio to the owner's AIStock server, and the server asks Google with its own key.
 * Product and customer names are sent as hints so "Shirt XL" or "Raju Traders" come back spelled as saved.
 */
class CloudRecognizer(
    private val server: ServerApi,
    private val phrases: suspend () -> List<String>,
) : SpeechInput {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    @Volatile private var stopRecording = false

    override fun isAvailable(): Boolean = server.signedIn

    override fun start(languageTag: String, callback: VoiceRecognizer.Callback, quiet: Boolean) {
        stop()
        stopRecording = false
        job = scope.launch {
            callback.onListening()
            val pcm = try {
                withContext(Dispatchers.IO) { record() }
            } catch (e: SecurityException) {
                callback.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                callback.onError(SpeechRecognizer.ERROR_AUDIO); return@launch
            }
            if (pcm == null) { callback.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT); return@launch }
            callback.onPartial("…")
            try {
                val hints = try { phrases() } catch (e: Exception) { emptyList() }
                val heard = server.speechToText(pcm, languageTag, hints)
                if (heard.text.isBlank()) callback.onError(SpeechRecognizer.ERROR_NO_MATCH) else callback.onResult(heard.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServerException) {
                callback.onError(if (e.status == 0) SpeechRecognizer.ERROR_NETWORK else SpeechRecognizer.ERROR_SERVER)
            } catch (e: Exception) {
                callback.onError(SpeechRecognizer.ERROR_NETWORK)
            }
        }
    }

    /** One request as 16 kHz mono 16-bit PCM, or null when nobody spoke. */
    @SuppressLint("MissingPermission") // checked by the screen before the microphone is opened
    private suspend fun record(): ByteArray? {
        val rate = 16_000
        val frame = rate * 30 / 1000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 2 * 8))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("microphone busy") }
        val ns = try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null } catch (e: Exception) { null }
        val out = ByteArrayOutputStream(rate * 2 * 8)
        val ep = Endpointer()
        val buf = ShortArray(frame)
        val bytes = ByteArray(frame * 2)
        // A little audio from before speech was detected is kept, so the first syllable is not cut off.
        val lead = ArrayDeque<ByteArray>()
        try {
            rec.startRecording()
            while (kotlin.coroutines.coroutineContext.isActive && !stopRecording) {
                val n = rec.read(buf, 0, frame)
                if (n <= 0) { delay(5); continue }
                for (k in 0 until n) { bytes[2 * k] = (buf[k].toInt() and 0xff).toByte(); bytes[2 * k + 1] = ((buf[k].toInt() shr 8) and 0xff).toByte() }
                val before = ep.state
                val st = ep.feed(buf, n)
                when {
                    st == Endpointer.State.NO_SPEECH -> return null
                    before == Endpointer.State.WAITING && st == Endpointer.State.WAITING -> {
                        lead.addLast(bytes.copyOf(n * 2)); if (lead.size > 10) lead.removeFirst()
                    }
                    else -> {
                        while (lead.isNotEmpty()) out.write(lead.removeFirst())
                        out.write(bytes, 0, n * 2)
                        if (st == Endpointer.State.DONE) break
                    }
                }
            }
        } finally {
            try { rec.stop() } catch (e: Exception) { }
            rec.release()
            try { ns?.release() } catch (e: Exception) { }
        }
        return if (ep.state == Endpointer.State.WAITING || out.size() < rate / 5) null else out.toByteArray()
    }

    override fun stop() {
        stopRecording = true
        job?.cancel()
        job = null
    }
}

/**
 * Replies read aloud by Google Cloud Text-to-Speech (a Tamil voice for Tamil text, an Indian English voice otherwise),
 * through the owner's server. If the server or Google cannot be reached, the phone's own voice reads it instead.
 */
class CloudSpeaker(private val server: ServerApi, private val fallback: SpeechOutput) : SpeechOutput {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    @Volatile private var talking = false
    @Volatile private var track: AudioTrack? = null

    override fun speak(text: String, tamil: Boolean) {
        stop()
        val t = text.trim()
        if (t.isEmpty()) return
        talking = true
        job = scope.launch {
            try {
                val wav = server.textToSpeech(t.take(2000), null)
                val pcm = Wav.parse(wav) ?: throw IllegalStateException("not a WAV file")
                withContext(Dispatchers.IO) { play(pcm) }
            } catch (e: CancellationException) {
                // stopped by the owner
            } catch (e: Exception) {
                talking = false
                fallback.speak(t, tamil)
            } finally {
                talking = false
            }
        }
    }

    private suspend fun play(pcm: Wav.Pcm) {
        val channel = if (pcm.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(pcm.sampleRate, channel, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(pcm.sampleRate).setChannelMask(channel).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, pcm.sampleRate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        try {
            t.play()
            var off = 0
            val chunk = pcm.sampleRate / 5 * 2 * pcm.channels
            while (off < pcm.data.size && kotlin.coroutines.coroutineContext.isActive) {
                val n = t.write(pcm.data, off, minOf(chunk, pcm.data.size - off))
                if (n <= 0) break
                off += n
            }
            // Wait until what was written has actually been played.
            val frames = pcm.data.size / (2 * pcm.channels)
            var waited = 0
            while (kotlin.coroutines.coroutineContext.isActive && t.playbackHeadPosition < frames && waited < 10_000) { delay(50); waited += 50 }
        } finally {
            track = null
            try { t.stop() } catch (e: Exception) { }
            t.release()
        }
    }

    override fun isSpeaking(): Boolean = talking || fallback.isSpeaking()

    override fun stop() {
        job?.cancel()
        job = null
        talking = false
        try { track?.pause(); track?.flush() } catch (e: Exception) { }
        fallback.stop()
    }

    override fun shutdown() {
        stop()
        fallback.shutdown()
    }
}
