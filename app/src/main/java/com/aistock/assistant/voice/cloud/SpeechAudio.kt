package com.aistock.assistant.voice.cloud

/** Plain audio helpers for Google Cloud speech, kept free of Android so they can be unit tested. */
object Wav {
    class Pcm(val sampleRate: Int, val channels: Int, val bits: Int, val data: ByteArray)

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)

    /** Reads a RIFF/WAVE file with 16-bit PCM; null for anything else. Raw PCM without a header is not guessed at. */
    fun parse(b: ByteArray): Pcm? {
        if (b.size < 12 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var i = 12
        var rate = 0; var channels = 0; var bits = 0; var format = 0
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val size = le32(b, i + 4)
            val start = i + 8
            if (size < 0) return null
            when (id) {
                "fmt " -> {
                    if (start + 16 > b.size) return null
                    format = le16(b, start); channels = le16(b, start + 2); rate = le32(b, start + 4); bits = le16(b, start + 14)
                }
                "data" -> {
                    if (format != 1 || bits != 16 || channels !in 1..2 || rate <= 0) return null
                    // Some writers put a placeholder size for streams: take what is there.
                    val end = if (size == 0 || start + size > b.size) b.size else start + size
                    return Pcm(rate, channels, bits, b.copyOfRange(start, end - (end - start) % (2 * channels)))
                }
            }
            i = start + size + (size and 1)
        }
        return null
    }
}

/**
 * Decides when a spoken request has finished, from the loudness of short frames of microphone audio:
 * it waits for speech to start, then stops after a pause, or at a time limit.
 */
class Endpointer(
    private val frameMs: Int = 30,
    private val waitForSpeechMs: Int = 7_000,
    private val endSilenceMs: Int = 1_100,
    private val maxMs: Int = 30_000,
    private val minLevel: Double = 450.0,
) {
    enum class State { WAITING, SPEAKING, DONE, NO_SPEECH }

    var state = State.WAITING
        private set
    private var elapsed = 0
    private var silent = 0
    private var voiced = 0
    private var floor = minLevel / 3

    /** Loudness (root mean square) of one frame of 16-bit samples. */
    fun level(samples: ShortArray, n: Int): Double {
        if (n <= 0) return 0.0
        var sum = 0.0
        for (k in 0 until n) { val v = samples[k].toDouble(); sum += v * v }
        return Math.sqrt(sum / n)
    }

    /** Feeds one frame; returns the state after it. */
    fun feed(samples: ShortArray, n: Int): State {
        if (state == State.DONE || state == State.NO_SPEECH) return state
        val lvl = level(samples, n)
        elapsed += frameMs
        val speech = lvl > maxOf(minLevel, floor * 3.0)
        // The background noise level is learnt from frames that are not speech, so talking from the first moment still counts.
        if (!speech) floor = if (lvl < floor) lvl else floor * 0.98 + lvl * 0.02
        when (state) {
            State.WAITING -> {
                voiced = if (speech) voiced + frameMs else 0
                // A short click is not speech: about a tenth of a second of sound is.
                if (voiced >= 90) { state = State.SPEAKING; silent = 0 }
                else if (elapsed >= waitForSpeechMs) state = State.NO_SPEECH
            }
            State.SPEAKING -> {
                silent = if (speech) 0 else silent + frameMs
                if (silent >= endSilenceMs || elapsed >= maxMs) state = State.DONE
            }
            else -> {}
        }
        return state
    }
}
