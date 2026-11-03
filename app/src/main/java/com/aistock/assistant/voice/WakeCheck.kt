package com.aistock.assistant.voice

import org.json.JSONObject

/**
 * Decides whether a finished result from the offline wake-word recogniser really is the owner saying "Hey Stocky".
 * Plain JVM code (no Android), so it is unit tested.
 */
object WakeCheck {
    private val GREETINGS = setOf("hey", "hi", "ok", "okay")
    private val NAMES = setOf("stocky", "stalky")
    /** Both words must be heard this surely (0..1); weaker matches are usually other voices forced onto the name. */
    const val MIN_CONF = 0.80

    fun accept(result: JSONObject, otherAudioPlaying: Boolean): Boolean {
        if (otherAudioPlaying) return false
        val text = result.optString("text").lowercase().trim()
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() && it != "[unk]" }
        // Exactly the greeting and the name: anything else around it means it was picked out of other speech.
        if (words.size != 2 || words[0] !in GREETINGS || words[1] !in NAMES) return false
        val detail = result.optJSONArray("result") ?: return true // no word details from this model: the phrase decides
        val confs = (0 until detail.length()).mapNotNull { detail.optJSONObject(it) }.filter { it.optString("word").lowercase() in GREETINGS + NAMES }.map { it.optDouble("conf", 0.0) }
        return confs.size >= 2 && confs.all { it >= MIN_CONF }
    }
}
