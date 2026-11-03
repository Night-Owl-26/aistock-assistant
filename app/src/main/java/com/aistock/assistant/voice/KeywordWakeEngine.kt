package com.aistock.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Waits for "Hey Stocky" with a small offline speech model (Vosk) that reads the microphone directly.
 * Unlike the phone's speech service it runs continuously, so there is no start tone every few seconds,
 * and nothing leaves the phone while it waits. Only after the name is heard does the normal recogniser
 * (which understands Tamil and full sentences) take the request, through [follow].
 */
class KeywordWakeEngine(private val context: Context, private val follow: WakeWordEngine) : WakeWordEngine {
    private val main = Handler(Looper.getMainLooper())
    private var service: SpeechService? = null
    private var active = false

    override fun listen(languageTag: String, needWake: Boolean, listener: WakeWordEngine.Listener) {
        if (!needWake) { stopSpotting(); follow.listen(languageTag, false, listener); return }
        follow.stop()
        active = true
        loadModel({ model -> main.post { if (active) spot(model, listener) } }, { follow.listen(languageTag, true, listener) })
    }

    private fun spot(model: Model, listener: WakeWordEngine.Listener) {
        stopSpotting()
        try {
            // Only these phrases (plus "unknown") are recognised, which makes the name easy to catch and cheap to run.
            val rec = Recognizer(model, 16000f, GRAMMAR)
            // Word confidences let a weak, guessed match (a video's voice, music) be told from the name really said.
            try { rec.setWords(true) } catch (e: Exception) { }
            val s = SpeechService(rec, 16000f)
            service = s
            s.startListening(object : RecognitionListener {
                // Only finished phrases count: partial guesses fire on almost any sound when the word list is this short.
                override fun onPartialResult(hypothesis: String?) {}
                override fun onResult(hypothesis: String?) = check(hypothesis)
                override fun onFinalResult(hypothesis: String?) = check(hypothesis)
                override fun onError(exception: Exception?) { main.post { if (active) { stopSpotting(); main.postDelayed({ if (active) spot(model, listener) }, 2_000) } } }
                override fun onTimeout() {}

                private fun check(json: String?) {
                    val o = try { JSONObject(json ?: "{}") } catch (e: Exception) { return }
                    if (!WakeCheck.accept(o, otherAudioPlaying())) return
                    main.post {
                        if (!active) return@post
                        active = false
                        stopSpotting()
                        listener.onHeard(true, "")
                    }
                }
            })
        } catch (e: Exception) {
            stopSpotting()
            listener.onFatal(-1)
        }
    }

    /** A video, reel, song or call playing on the phone: its voices must not wake Stocky. */
    private fun otherAudioPlaying(): Boolean = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.isMusicActive || am.mode == android.media.AudioManager.MODE_IN_CALL || am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION
    } catch (e: Exception) { false }

    private fun stopSpotting() {
        val s = service ?: return
        service = null
        try { s.stop(); s.shutdown() } catch (e: Exception) { }
    }

    override fun stop() {
        active = false
        main.post { stopSpotting() }
        follow.stop()
    }

    override fun release() {
        active = false
        main.post { stopSpotting() }
        follow.release()
    }

    private fun loadModel(ok: (Model) -> Unit, failed: () -> Unit) {
        model?.let { ok(it); return }
        try {
            // The model ships inside the app and is unpacked once to the app's private storage.
            StorageService.unpack(context.applicationContext, "model-wake", "model-wake", { m -> model = m; ok(m) }, { _ -> failed() })
        } catch (e: Exception) {
            failed()
        }
    }

    companion object {
        @Volatile private var model: Model? = null
        // Two-word phrases only: a lone "stocky" was matched far too easily by other voices.
        private const val GRAMMAR = "[\"hey stocky\", \"hi stocky\", \"ok stocky\", \"okay stocky\", \"hey stalky\", \"[unk]\"]"

        /** The offline model is part of this build. */
        fun available(context: Context): Boolean = try { context.assets.list("model-wake")?.isNotEmpty() == true } catch (e: Exception) { false }
    }
}
