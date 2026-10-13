package com.aistock.assistant.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aistock.assistant.MainActivity
import com.aistock.assistant.R
import com.aistock.assistant.ai.ReplyKind
import com.aistock.assistant.conversation.Stocky
import com.aistock.assistant.conversation.StockyFactory
import com.aistock.assistant.data.BusinessRepository
import com.aistock.assistant.data.SettingsStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Starting, stopping and pausing hands-free listening. */
object HandsFree {
    private val _paused = MutableStateFlow(0)
    /** More than 0 while the chat screen is using the microphone itself. */
    val paused: StateFlow<Int> = _paused
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    internal fun setRunning(on: Boolean) { _running.value = on }

    fun pause(on: Boolean) { _paused.value = (_paused.value + if (on) 1 else -1).coerceAtLeast(0) }

    fun canStart(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Must be called while the app is on screen: Android only lets a visible app start using the microphone. */
    fun start(context: Context) {
        if (!canStart(context)) return
        try { ContextCompat.startForegroundService(context, Intent(context, HandsFreeService::class.java)) } catch (e: Exception) { /* not allowed right now */ }
    }

    fun stop(context: Context) {
        try { context.startService(Intent(context, HandsFreeService::class.java).setAction(HandsFreeService.ACTION_STOP)) } catch (e: Exception) { }
        context.stopService(Intent(context, HandsFreeService::class.java))
    }
}

/**
 * "Hey Stocky" without opening the app. Runs as a visible foreground service with a permanent notification,
 * so the owner always knows the microphone is in use and can stop it from the notification.
 * Changes still need confirmation by voice, and money or big corrections must be confirmed inside the app.
 */
@AndroidEntryPoint
class HandsFreeService : Service() {
    @Inject lateinit var factory: StockyFactory
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var business: BusinessRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var engine: WakeWordEngine
    private lateinit var speaker: Speaker
    private lateinit var stocky: Stocky
    private var conversationId: String? = null
    private var busy = false
    private var followUps = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The offline keyword spotter listens without the phone's start tone; the phone's recogniser only takes the request.
        val speech = SpeechWakeWordEngine(this)
        engine = if (KeywordWakeEngine.available(this)) KeywordWakeEngine(this, speech) else speech
        BeepMuter.restoreLeftovers(this)
        speaker = Speaker(this)
        stocky = factory.create()
        channel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(getString(R.string.handsfree_listening)),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (e: Exception) {
            stopSelf(); return
        }
        HandsFree.setRunning(true)
        scope.launch {
            HandsFree.paused.collect { p -> if (p > 0) engine.stop() else if (!busy) waitForWake() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { settings.setHandsFree(false) }
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private val language: String get() = if (settings.current.value.tamil) "ta-IN" else "en-IN"

    private fun waitForWake() {
        if (HandsFree.paused.value > 0) return
        followUps = 0
        update(getString(R.string.handsfree_listening))
        engine.listen(language, needWake = true, listener)
    }

    private fun listenForReply() {
        if (HandsFree.paused.value > 0) return
        update(getString(R.string.handsfree_conversation))
        engine.listen(language, needWake = false, listener)
    }

    private val listener = object : WakeWordEngine.Listener {
        override fun onHeard(wake: Boolean, command: String) {
            engine.stop()
            if (WakePhrase.isStop(command)) { say(getString(R.string.handsfree_bye)) { waitForWake() }; return }
            if (command.isBlank()) {
                // Only the name was said: answer and listen for the request.
                say(getString(R.string.handsfree_yes)) { listenForReply() }
                return
            }
            handle(command)
        }

        override fun onSilence() { waitForWake() }

        override fun onFatal(code: Int) {
            update(getString(R.string.handsfree_problem))
            scope.launch { kotlinx.coroutines.delay(15_000); waitForWake() }
        }
    }

    private fun handle(command: String) {
        busy = true
        update(getString(R.string.understanding))
        scope.launch {
            val reply = try { stocky.handle(command) } catch (e: Exception) { null }
            busy = false
            if (reply == null) { say(getString(R.string.handsfree_problem)) { waitForWake() }; return@launch }
            if (reply.kind == ReplyKind.CONFIRM && stocky.needsOwnerCheck) {
                // Money and big corrections are never approved by voice alone: they are done in the app with fingerprint or PIN.
                try { stocky.cancel() } catch (e: Exception) { }
                val msg = getString(R.string.handsfree_do_in_app)
                save(command, msg)
                openAppToConfirm()
                say(msg) { waitForWake() }
                return@launch
            }
            save(command, reply.text)
            val more = reply.kind == ReplyKind.CONFIRM || reply.kind == ReplyKind.CLARIFY || followUps < 2
            followUps++
            say(reply.spoken.ifBlank { reply.text }) { if (more) listenForReply() else waitForWake() }
        }
    }

    private fun say(text: String, then: () -> Unit) {
        engine.stop()
        // The reply is read out on the media channel, which may still be silenced for the recogniser's tone.
        BeepMuter.unmuteNow(this)
        if (!settings.current.value.ttsEnabled || text.isBlank()) { then(); return }
        update(text.take(80))
        speaker.speak(text, settings.current.value.tamil)
        scope.launch { speaker.awaitDone(); then() }
    }

    private suspend fun save(user: String, assistant: String) {
        try {
            val id = conversationId ?: business.newConversation(getString(R.string.handsfree_conversation_title)).also { conversationId = it }
            business.addMessage(id, "user", user, "", "", null)
            business.addMessage(id, "assistant", assistant, "", "", null)
        } catch (e: Exception) { /* history is best effort */ }
    }

    private fun openAppToConfirm() {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ai)
            .setContentTitle(getString(R.string.handsfree_confirm_title))
            .setContentText(getString(R.string.handsfree_confirm_body))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { getSystemService(NotificationManager::class.java)?.notify(CONFIRM_ID, n) } catch (e: Exception) { }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 1, Intent(this, MainActivity::class.java).putExtra("chat", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 2, Intent(this, HandsFreeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ai)
            .setContentTitle(getString(R.string.handsfree_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.handsfree_stop), stop)
            .build()
    }

    private fun update(text: String) {
        try { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text)) } catch (e: Exception) { }
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.handsfree_title), NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        HandsFree.setRunning(false)
        BeepMuter.unmuteNow(this)
        if (::engine.isInitialized) engine.release()
        if (::speaker.isInitialized) speaker.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.aistock.assistant.HANDS_FREE_STOP"
        private const val CHANNEL = "hands_free"
        private const val NOTIFICATION_ID = 41
        private const val CONFIRM_ID = 42
    }
}
