package com.aistock.assistant.ai.llm

import com.aistock.assistant.server.ServerApi
import com.aistock.assistant.server.ServerException
import org.json.JSONObject

/**
 * Stocky's conversation through the owner's AIStock server, which holds the Gemini key and counts usage.
 * The request and answer use Gemini's own format, so the same parsing and tools work as with a direct key.
 */
class ServerLlmClient(private val api: ServerApi, private val model: String) : LlmClient {
    override val name: String get() = model

    override suspend fun chat(messages: List<LlmMessage>, tools: List<ToolSpec>): LlmReply {
        val known = thinking[model]
        val options = if (known != null) listOf(known) else Gemini.thinkingOptions(model)
        var last: LlmException? = null
        for (o in options) {
            val (code, body) = try {
                api.generate(JSONObject(Gemini.request(messages, tools, o, Gemini.temperatureFor(model))))
            } catch (e: ServerException) {
                throw LlmException(e.status, e.message ?: e.code)
            }
            if (code in 200..299) { thinking[model] = o; return Gemini.parse(body) }
            val err = LlmException(code, OpenAiCompat.errorMessage(body))
            // 400 here can mean this model does not take that thinking setting: try the next way.
            if (code != 400 || o == "none" || err.detail.contains("key", true)) throw err
            last = err
        }
        throw last ?: LlmException(0, "no answer")
    }

    companion object { private val thinking = java.util.concurrent.ConcurrentHashMap<String, String>() }
}
