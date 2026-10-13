package com.aistock.assistant.voice

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * The phone's speech recogniser plays a short tone when it starts listening. When the microphone reopens by itself,
 * the channels that can carry that tone are silenced for a moment and given back right after.
 * Each channel is handled on its own (Do Not Disturb may protect one), and which channels were silenced is written
 * down, so sound is always given back, even if the app was closed in that moment.
 */
class BeepMuter(context: Context) {
    private val app = context.applicationContext

    fun mute(forMs: Long = 1_500) = Shared.mute(app, forMs)
    fun unmuteSoon(afterMs: Long = 600) = Shared.unmuteSoon(app, afterMs)
    fun unmuteNow() = Shared.unmuteNow(app)

    companion object {
        /** Gives back any channel left silenced by an earlier run. */
        fun restoreLeftovers(context: Context) = Shared.restore(context.applicationContext)
        fun unmuteNow(context: Context) = Shared.unmuteNow(context.applicationContext)
    }

    private object Shared {
        private val main = Handler(Looper.getMainLooper())
        private val streams = intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_MUSIC)
        private val mutedNow = HashSet<Int>()
        private var release: Runnable? = null

        private fun audio(c: Context) = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        private fun prefs(c: Context) = c.getSharedPreferences("beep_muter", Context.MODE_PRIVATE)

        @Synchronized fun mute(c: Context, forMs: Long) {
            val a = audio(c) ?: return
            release?.let { main.removeCallbacks(it) }
            for (s in streams) {
                if (s in mutedNow) continue
                // Music the owner is playing is never interrupted.
                if (s == AudioManager.STREAM_MUSIC && a.isMusicActive) continue
                try {
                    if (!a.isStreamMute(s)) { a.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0); mutedNow.add(s) }
                } catch (e: Exception) { /* protected channel; the others are still silenced */ }
            }
            prefs(c).edit().putString("muted", mutedNow.joinToString(",")).apply()
            schedule(c, forMs)
        }

        @Synchronized fun unmuteSoon(c: Context, afterMs: Long) {
            if (mutedNow.isEmpty()) return
            schedule(c, afterMs)
        }

        private fun schedule(c: Context, ms: Long) {
            release?.let { main.removeCallbacks(it) }
            val r = Runnable { unmuteNow(c) }
            release = r
            main.postDelayed(r, ms)
        }

        @Synchronized fun unmuteNow(c: Context) {
            release?.let { main.removeCallbacks(it) }
            release = null
            val a = audio(c)
            if (a != null) for (s in mutedNow) try { a.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
            mutedNow.clear()
            prefs(c).edit().remove("muted").apply()
        }

        @Synchronized fun restore(c: Context) {
            val left = prefs(c).getString("muted", null).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
            val a = audio(c)
            if (a != null) for (s in left) if (s !in mutedNow) try { a.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
            if (mutedNow.isEmpty()) prefs(c).edit().remove("muted").apply()
        }
    }
}
