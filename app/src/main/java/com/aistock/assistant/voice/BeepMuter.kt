package com.aistock.assistant.voice

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * The phone's speech recogniser plays a short tone each time it starts and stops listening.
 * Phones play it on different sound channels, so every channel that can carry it is silenced
 * for that moment and given back right after. Each channel is handled on its own, so one that
 * Do Not Disturb protects does not stop the others from being silenced.
 */
class BeepMuter(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val streams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_ALARM)
    private val mutedNow = HashSet<Int>()
    private val release = Runnable { unmuteNow() }

    /** Silences the tone now; sound comes back after [forMs] unless muted again before that. */
    fun mute(forMs: Long = 1_500) {
        val a = audio ?: return
        main.removeCallbacks(release)
        for (s in streams) {
            if (s in mutedNow) continue
            try {
                if (!a.isStreamMute(s)) { a.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0); mutedNow.add(s) }
            } catch (e: Exception) { /* this channel is protected; the others are still silenced */ }
        }
        main.postDelayed(release, forMs)
    }

    /** Gives sound back after a short moment, so the tone that comes with the event is still swallowed. */
    fun unmuteSoon(afterMs: Long = 600) {
        main.removeCallbacks(release)
        main.postDelayed(release, afterMs)
    }

    fun unmuteNow() {
        main.removeCallbacks(release)
        val a = audio ?: run { mutedNow.clear(); return }
        for (s in mutedNow) try { a.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
        mutedNow.clear()
    }
}
