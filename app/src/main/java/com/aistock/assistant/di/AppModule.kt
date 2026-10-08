package com.aistock.assistant.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import com.aistock.assistant.ai.AssistantBackend
import com.aistock.assistant.ai.llm.LlmClient
import com.aistock.assistant.ai.llm.OpenAiCompatClient
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
     * The language model behind Stocky. Nothing is built in: the address, model and key are entered in
     * Settings > AI Provider and the key is stored encrypted on the device.
     */
    @Provides
    @Singleton
    fun llmSource(settings: SettingsStore, connectivity: Connectivity): LlmSource = LlmSource(settings, connectivity)
}

/** Gives the conversation a model client when one is configured and the phone is online; otherwise null. */
class LlmSource(private val settings: SettingsStore, private val connectivity: Connectivity) {
    suspend fun client(): LlmClient? {
        val p = settings.current.value
        if (p.aiMode != "cloud" || !p.cloudReady || !connectivity.isOnline()) return null
        val key = settings.cloudKey() ?: return null
        return OpenAiCompatClient(p.cloudEndpoint, p.cloudModel, key)
    }
}
