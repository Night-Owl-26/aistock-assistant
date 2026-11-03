package com.aistock.assistant.voice

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WakeCheckTest {
    private fun r(text: String, vararg conf: Pair<String, Double>) = JSONObject().put("text", text).apply {
        if (conf.isNotEmpty()) put("result", org.json.JSONArray().apply { for ((w, c) in conf) put(JSONObject().put("word", w).put("conf", c)) })
    }

    @Test
    fun onlyTheClearlySaidNameWakesStocky() {
        assertTrue(WakeCheck.accept(r("hey stocky", "hey" to 0.97, "stocky" to 0.93), otherAudioPlaying = false))
        assertTrue(WakeCheck.accept(r("[unk] ok stocky", "ok" to 0.9, "stocky" to 0.88), false))
        // A reel or song playing: never.
        assertFalse(WakeCheck.accept(r("hey stocky", "hey" to 1.0, "stocky" to 1.0), otherAudioPlaying = true))
        // Other voices forced onto the name come with low confidence.
        assertFalse(WakeCheck.accept(r("hey stocky", "hey" to 0.95, "stocky" to 0.42), false))
        // The name alone, or picked out of a longer run of speech, does not count.
        assertFalse(WakeCheck.accept(r("stocky", "stocky" to 0.99), false))
        assertFalse(WakeCheck.accept(r("hey stocky hey stocky"), false))
        assertFalse(WakeCheck.accept(r("[unk]"), false))
        assertFalse(WakeCheck.accept(r(""), false))
    }
}
