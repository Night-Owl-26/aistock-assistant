package com.aistock.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

enum class VoiceState { IDLE, LISTENING, PROCESSING, SPEAKING, CONFIRMATION, EXECUTING, SUCCESS, ERROR }

/** Thin wrapper over Android's SpeechRecognizer. Must be created and used on the main thread. */
class VoiceRecognizer(private val context: Context) {
    interface Callback {
        fun onListening()
        fun onPartial(text: String)
        fun onResult(text: String)
        /** @param code one of the SpeechRecognizer.ERROR_ constants */
        fun onError(code: Int)
    }

    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(context)
    } catch (e: Exception) {
        false
    }

    private val beep = BeepMuter(context)

    /** @param quiet true when the microphone reopens by itself: its start tone is silenced */
    fun start(languageTag: String, callback: Callback, quiet: Boolean = false) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { beep.unmuteSoon(700); callback.onListening() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) { beep.unmuteSoon(); callback.onError(error) }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) callback.onPartial(it) }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) callback.onError(SpeechRecognizer.ERROR_NO_MATCH) else callback.onResult(text)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            if (quiet) beep.mute(2_500)
            r.startListening(intent)
        } catch (e: Exception) {
            beep.unmuteNow()
            callback.onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    fun stop() {
        recognizer?.let {
            try { it.cancel(); it.destroy() } catch (e: Exception) { /* already released */ }
        }
        recognizer = null
    }
}

/** Reads replies aloud with Android Text-to-Speech. Silently does nothing when no speech engine is installed. */
class Speaker(context: Context) {
    @Volatile private var ready = false
    private var tts: TextToSpeech? = null
    /** True from the moment a reply is handed over until the speech engine says it finished. */
    @Volatile private var talking = false

    init {
        tts = try {
            TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { talking = true }
                    override fun onDone(utteranceId: String?) { talking = false }
                    @Deprecated("old signature") override fun onError(utteranceId: String?) { talking = false }
                    override fun onError(utteranceId: String?, errorCode: Int) { talking = false }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { talking = false }
                })
            }
        } catch (e: Exception) {
            null
        }
    }

    fun speak(text: String, tamil: Boolean) {
        val engine = tts ?: return
        if (!ready || text.isBlank()) return
        try {
            // The words decide the voice: a Tamil reply is read by the Tamil voice even when the app is in English.
            val tamilText = text.any { it in '\u0B80'..'\u0BFF' }
            val wanted = if (tamilText || (tamil && text.none { it in 'a'..'z' })) Locale.forLanguageTag("ta-IN") else Locale.forLanguageTag("en-IN")
            val result = engine.setLanguage(wanted)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) engine.setLanguage(Locale.ENGLISH)
            val limit = try { TextToSpeech.getMaxSpeechInputLength() - 100 } catch (e: Exception) { 3_900 }
            talking = engine.speak(text.take(limit), TextToSpeech.QUEUE_FLUSH, null, "aistock-reply") == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            // Speaking is optional; never interrupt the user's work because of it.
        }
    }

    fun isSpeaking(): Boolean = talking || try { ready && tts?.isSpeaking == true } catch (e: Exception) { false }

    /** Waits until the speech engine reports that the reply has been read out (or a safety limit passes). */
    suspend fun awaitDone(limitMs: Long = 60_000) {
        kotlinx.coroutines.delay(250)
        var waited = 250L
        while (isSpeaking() && waited < limitMs) { kotlinx.coroutines.delay(120); waited += 120 }
    }

    fun stop() {
        talking = false
        try { tts?.stop() } catch (e: Exception) { /* ignore */ }
    }

    fun shutdown() {
        talking = false
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { /* ignore */ }
        tts = null
        ready = false
    }
}
