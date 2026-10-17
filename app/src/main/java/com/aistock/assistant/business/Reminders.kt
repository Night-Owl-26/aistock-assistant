package com.aistock.assistant.business

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aistock.assistant.MainActivity
import com.aistock.assistant.R
import com.aistock.assistant.data.SettingsStore
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reminders through AlarmManager. Android may deliver them a few minutes late to save battery
 * (exact alarms need a special permission that most phones deny); that is said on the reminder screen.
 */
@Singleton
class AlarmReminders @Inject constructor(@ApplicationContext private val context: Context) : ReminderSink {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    private fun intent(id: String, text: String) = PendingIntent.getBroadcast(
        context, id.hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND).putExtra("id", id).putExtra("text", text),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun schedule(id: String, text: String, at: Long) {
        val pi = intent(id, text)
        val whenMs = maxOf(at, System.currentTimeMillis() + 5_000)
        val am = alarms ?: return
        if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
    }

    override fun cancel(id: String) { alarms?.cancel(intent(id, "")) }

    /** The daily briefing at [hour] (0-23); -1 switches it off. */
    fun scheduleBriefing(hour: Int) {
        val pi = PendingIntent.getBroadcast(context, 77_001, Intent(context, ReminderReceiver::class.java).setAction(ACTION_BRIEFING), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = alarms ?: return
        am.cancel(pi)
        if (hour !in 0..23) return
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
    }

    companion object {
        const val ACTION_REMIND = "com.aistock.assistant.REMIND"
        const val ACTION_BRIEFING = "com.aistock.assistant.BRIEFING"
        const val CHANNEL = "reminders"

        fun channel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.reminders), NotificationManager.IMPORTANCE_HIGH))
        }

        fun post(context: Context, id: Int, title: String, text: String) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            channel(context)
            val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java).putExtra("open", "followups").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
            try { NotificationManagerCompat.from(context).notify(id, n) } catch (e: SecurityException) { }
        }
    }
}

/** Shows a reminder or the daily briefing, and sets all alarms again after the phone restarts or the app updates. */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var business: RoomBusinessBackend
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var alarms: AlarmReminders

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    AlarmReminders.ACTION_REMIND -> {
                        val id = intent.getStringExtra("id") ?: return@launch
                        AlarmReminders.post(context, id.hashCode(), context.getString(R.string.reminder_title), intent.getStringExtra("text").orEmpty())
                        business.reminderShown(id)
                    }
                    AlarmReminders.ACTION_BRIEFING -> {
                        val list = Insights.build(business.insightsInput(System.currentTimeMillis(), 1), settings.current.value.currencySymbol)
                        if (list.isNotEmpty()) AlarmReminders.post(context, 77_001, context.getString(R.string.briefing_title), list.take(5).joinToString("\n") { "• " + it.text })
                        alarms.scheduleBriefing(settings.current.value.briefingHour)
                    }
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        business.rescheduleAll()
                        alarms.scheduleBriefing(settings.current.value.briefingHour)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
