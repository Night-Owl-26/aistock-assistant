package com.aistock.assistant

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aistock.assistant.data.InventoryRepository
import com.aistock.assistant.data.LocaleHelper
import com.aistock.assistant.data.SettingsStore
import com.aistock.assistant.utils.Qty
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AIStockApp : Application() {
    @Inject lateinit var inventory: InventoryRepository
    @Inject lateinit var settings: SettingsStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL_LOW_STOCK, "Low stock", NotificationManager.IMPORTANCE_DEFAULT))
        scope.launch {
            inventory.lowStockEvents.collect { product ->
                if (!settings.current.value.lowStockAlerts) return@collect
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@AIStockApp, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@collect
                try {
                    val ctx = LocaleHelper.wrap(this@AIStockApp)
                    val open = PendingIntent.getActivity(
                        this@AIStockApp, 0, Intent(this@AIStockApp, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    val n = NotificationCompat.Builder(this@AIStockApp, CHANNEL_LOW_STOCK)
                        .setSmallIcon(R.drawable.ic_notify)
                        .setContentTitle(ctx.getString(R.string.low_stock_notification_title, product.name))
                        .setContentText(ctx.getString(R.string.low_stock_notification_body, Qty.format(product.currentStock) + " " + product.unit))
                        .setContentIntent(open).setAutoCancel(true).build()
                    NotificationManagerCompat.from(this@AIStockApp).notify(product.id.hashCode(), n)
                } catch (e: SecurityException) {
                    // Permission was revoked between the check and the post.
                }
            }
        }
    }

    companion object {
        const val CHANNEL_LOW_STOCK = "low_stock"
    }
}
