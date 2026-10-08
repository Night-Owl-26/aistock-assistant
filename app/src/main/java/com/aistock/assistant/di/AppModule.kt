package com.aistock.assistant.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import com.aistock.assistant.ai.AIProvider
import com.aistock.assistant.ai.AssistantBackend
import com.aistock.assistant.ai.CloudAIProvider
import com.aistock.assistant.ai.CloudConfig
import com.aistock.assistant.ai.LocalFallbackAIProvider
import com.aistock.assistant.ai.RoutingAIProvider
import com.aistock.assistant.data.RoomAssistantBackend
import com.aistock.assistant.data.SettingsStore
import com.aistock.assistant.data.StockRules
import com.aistock.assistant.database.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Tells whether the device currently has an internet connection. */
class Connectivity(private val context: Context) {
    fun isOnline(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        false
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

    @Provides
    fun stockRules(settings: SettingsStore): StockRules = settings

    @Provides
    fun backend(impl: RoomAssistantBackend): AssistantBackend = impl

    @Provides
    @Singleton
    fun connectivity(@ApplicationContext context: Context): Connectivity = Connectivity(context)

    /**
     * The AI provider used by the assistant. To use a production cloud model, enter its endpoint, model and key in
     * Settings > AI Provider. Credentials are stored encrypted on the device and are never part of the APK.
     */
    @Provides
    @Singleton
    fun routingProvider(settings: SettingsStore, connectivity: Connectivity, backend: RoomAssistantBackend): RoutingAIProvider {
        val cloud = CloudAIProvider(
            config = {
                val p = settings.current.value
                val key = settings.cloudKey()
                if (p.cloudReady && key != null) CloudConfig(p.cloudEndpoint, p.cloudModel, key) else null
            },
            snapshot = { backend.snapshotText(settings.current.value.currencySymbol) },
        )
        return RoutingAIProvider(
            useCloud = { settings.current.value.let { it.aiMode == "cloud" && it.cloudReady } },
            online = { connectivity.isOnline() },
            cloud = cloud,
            local = LocalFallbackAIProvider(),
        )
    }

    @Provides
    fun aiProvider(routing: RoutingAIProvider): AIProvider = routing
}
