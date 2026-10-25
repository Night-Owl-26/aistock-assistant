package com.aistock.assistant.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import com.aistock.assistant.ai.AssistantBackend
import com.aistock.assistant.ai.llm.LlmClient
import com.aistock.assistant.ai.llm.GeminiClient
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
    fun llmSource(settings: SettingsStore, connectivity: Connectivity, @ApplicationContext context: Context, server: com.aistock.assistant.server.ServerApi): LlmSource =
        LlmSource(settings, connectivity, AiDiagnostics(context), server)

    /**
     * The model that reads workbook column titles. Same model and key as Stocky (on the server or the owner's own key);
     * only titles and a few masked example values are sent, see [com.aistock.assistant.excel.HeaderMapper.prompt].
     */
    @Provides
    @Singleton
    fun columnAi(llm: LlmSource): com.aistock.assistant.excel.ColumnAi = object : com.aistock.assistant.excel.ColumnAi {
        override suspend fun ask(prompt: String): String? {
            if (!llm.online()) return null
            val client = llm.client() ?: return null
            return client.chat(listOf(com.aistock.assistant.ai.llm.LlmMessage.User(prompt)), emptyList()).text
        }
    }

    @Provides
    fun reminderSink(impl: com.aistock.assistant.business.AlarmReminders): com.aistock.assistant.business.ReminderSink = impl
}

/** Gives the conversation a model client when one is configured and the phone is online; otherwise null. */
class LlmSource(private val settings: SettingsStore, private val connectivity: Connectivity, private val diagnostics: AiDiagnostics, private val server: com.aistock.assistant.server.ServerApi) {
    /** The model name the server uses, learnt from the account call; used to pick the right "thinking" setting. */
    @Volatile var serverModel: String = "gemini-flash-latest"

    /** The step-by-step connection check shown in Settings. */
    suspend fun diagnose(): List<Check> {
        val p = settings.current.value
        if (p.aiSource == "server") return diagnostics.runServer(server, p.serverUrl, p.tamil) { serverModel = it }
        return diagnostics.run(settings.cloudKey(), p.cloudModel, p.tamil)
    }

    suspend fun client(): LlmClient? {
        val p = settings.current.value
        // Signed in to the owner's AIStock server: the key stays on the server.
        if (p.aiSource == "server" && server.signedIn) return com.aistock.assistant.ai.llm.ServerLlmClient(server, serverModel)
        if (p.aiSource == "server") return null
        if (p.aiMode != "cloud" || !p.cloudReady) return null
        val key = settings.cloudKey()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Google's own address gets Google's own API; anything else is treated as a chat-completions service.
        return if (p.cloudEndpoint.contains("generativelanguage.googleapis.com")) GeminiClient(p.cloudModel.removePrefix("models/"), key)
        else OpenAiCompatClient(p.cloudEndpoint, p.cloudModel, key)
    }

    /** Sends one tiny message and reports what happened, in words the owner can act on. null = it works. */
    suspend fun test(): String? {
        val c = client() ?: return "No key is saved yet."
        return try {
            c.chat(listOf(com.aistock.assistant.ai.llm.LlmMessage.User("Say OK")), emptyList()); null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.aistock.assistant.ai.llm.LlmProblem.explain(e, connectivity.isOnline())
        }
    }

    fun online(): Boolean = connectivity.isOnline()
}
