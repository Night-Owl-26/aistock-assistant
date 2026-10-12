package com.aistock.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Listens for the wake phrase. The interface lets a dedicated on-device wake-word model replace the
 * speech-recogniser engine later without touching the rest of the app.
 */
interface WakeWordEngine {
    interface Listener {
        /** The owner said something. [wake] is true when it started with the wake phrase (or no phrase was needed); [command] is what followed it. */
        fun onHeard(wake: Boolean, command: String)
        /** A follow-up turn heard nothing; go back to waiting for the wake phrase. */
        fun onSilence()
        /** Listening cannot work right now (permission or no speech service). */
        fun onFatal(code: Int)
    }

    /** @param needWake false for follow-up turns, when the owner is answering Stocky and need not say the name again */
    fun listen(languageTag: String, needWake: Boolean, listener: Listener)
    fun stop()
    fun release()
}

/**
 * Wake-phrase detection with Android's own speech recogniser, restarted in a loop.
 * Every utterance is checked for "Hey Stocky"; nothing is stored or sent anywhere unless the phrase was said.
 */
class SpeechWakeWordEngine(private val context: Context) : WakeWordEngine {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var errors = 0
    private val beep = BeepMuter(context)

    override fun listen(languageTag: String, needWake: Boolean, listener: WakeWordEngine.Listener) {
        main.post { start(languageTag, needWake, listener) }
    }

    private fun start(languageTag: String, needWake: Boolean, listener: WakeWordEngine.Listener) {
        active = true
        val r = recognizer ?: try { SpeechRecognizer.createSpeechRecognizer(context) } catch (e: Exception) { null }
        if (r == null) { listener.onFatal(SpeechRecognizer.ERROR_CLIENT); return }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { beep.unmuteSoon(700) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { beep.mute(1_500) }
            override fun onPartialResults(partialResults: Bundle?) {
                // The name was heard: finish this session as soon as the owner pauses, instead of waiting out the long session.
                if (!needWake) return
                val heard = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (WakePhrase.find(heard) == null) return
                main.removeCallbacks(finish)
                main.postDelayed(finish, 1_300)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                main.removeCallbacks(finish)
                beep.unmuteSoon()
                errors = 0
                if (!active) return
                val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                if (!needWake) {
                    val text = all.firstOrNull().orEmpty().trim()
                    if (text.isEmpty()) listener.onSilence() else listener.onHeard(true, WakePhrase.find(text) ?: text)
                    return
                }
                // Any of the recogniser's guesses may hold the name.
                val cmd = all.firstNotNullOfOrNull { WakePhrase.find(it) }
                if (cmd != null) listener.onHeard(true, cmd) else again(languageTag, needWake, listener, 150)
            }
            override fun onError(error: Int) {
                beep.unmuteSoon()
                if (!active) return
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> listener.onFatal(error)
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        errors = 0
                        if (!needWake) listener.onSilence() else again(languageTag, needWake, listener, 150)
                    }
                    else -> {
                        // Busy or a passing hiccup: wait a little longer each time so the phone is not kept busy.
                        errors++
                        if (errors > 25) { listener.onFatal(error); return }
                        recreate()
                        again(languageTag, needWake, listener, minOf(8_000L, 500L * errors))
                    }
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            // Waiting for the name: keep each listening session long, so the recogniser restarts (and beeps) less often.
            if (needWake) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 15_000L)
            }
        }
        try {
            beep.mute(2_500)
            r.startListening(intent)
        } catch (e: Exception) {
            beep.unmuteSoon()
            errors++
            recreate()
            again(languageTag, needWake, listener, 1_000)
        }
    }

    private val finish = Runnable { try { recognizer?.stopListening() } catch (e: Exception) { } }

    private fun again(languageTag: String, needWake: Boolean, listener: WakeWordEngine.Listener, delayMs: Long) {
        main.postDelayed({ if (active) start(languageTag, needWake, listener) }, delayMs)
    }

    private fun recreate() {
        try { recognizer?.destroy() } catch (e: Exception) { }
        recognizer = null
    }

    override fun stop() {
        main.post {
            active = false
            main.removeCallbacksAndMessages(null)
            try { recognizer?.cancel() } catch (e: Exception) { }
            beep.unmuteSoon()
        }
    }

    override fun release() {
        main.post {
            active = false
            main.removeCallbacksAndMessages(null)
            recreate()
            beep.unmuteNow()
        }
    }
}
