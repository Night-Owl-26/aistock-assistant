package com.aistock.assistant.conversation

import com.aistock.assistant.ai.AssistantEngine
import com.aistock.assistant.ai.LocalFallbackAIProvider
import com.aistock.assistant.data.BusinessRepository
import com.aistock.assistant.data.RoomAssistantBackend
import com.aistock.assistant.data.SettingsStore
import com.aistock.assistant.di.LlmSource
import javax.inject.Inject
import javax.inject.Singleton

/** Builds a Stocky conversation wired to the real data, the same way for the chat screen and for hands-free listening. */
@Singleton
class StockyFactory @Inject constructor(
    val backend: RoomAssistantBackend,
    private val llmSource: LlmSource,
    private val settings: SettingsStore,
    private val business: BusinessRepository,
    val platform: com.aistock.assistant.business.RoomBusinessBackend,
) {
    fun create(): Stocky {
        val engine = AssistantEngine(
            backend, LocalFallbackAIProvider(),
            tamil = { settings.current.value.tamil },
            symbol = { settings.current.value.currencySymbol },
            allowNegativeStock = { settings.current.value.allowNegativeStock },
            ownerCheck = { settings.current.value.ownerCheck },
        )
        return Stocky(
            backend, engine,
            llm = { llmSource.client() },
            businessName = { business.business()?.let { it.name + " (owner " + it.ownerName + ")" }.orEmpty() },
            symbol = { settings.current.value.currencySymbol },
            online = { llmSource.online() },
            tamil = { settings.current.value.tamil },
            business = platform,
        )
    }
}
