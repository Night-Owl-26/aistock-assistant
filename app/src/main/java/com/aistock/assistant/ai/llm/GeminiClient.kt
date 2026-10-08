package com.aistock.assistant.ai.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Gemini through its own API (generateContent with function calling).
 * The key travels in the x-goog-api-key header and comes from Settings; it is never built into the app.
 * If the chosen model is unknown or busy, the next one in a short list is tried.
 */
class GeminiClient(private val model: String, private val apiKey: String) : LlmClient {
    override val name: String get() = working ?: model

    override suspend fun chat(messages: List<LlmMessage>, tools: List<ToolSpec>): LlmReply = withContext(Dispatchers.IO) {
        val body = Gemini.request(messages, tools)
        val order = (listOfNotNull(working, model) + FALLBACKS).distinct()
        var last: LlmException? = null
        for (m in order) {
            try {
                val reply = call(m, body)
                working = m
                return@withContext reply
            } catch (e: LlmException) {
                last = e
                // Unknown model, quota for this model, or overloaded: another model may still answer.
                if (e.status != 404 && e.status != 429 && e.status != 503 && e.status != 500) throw e
            }
        }
        throw last ?: LlmException(0, "no model answered")
    }

    private fun call(m: String, body: String): LlmReply {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" + URLEncoder.encode(m, "UTF-8") + ":generateContent"
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12_000
            conn.readTimeout = 45_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty() } catch (e: Exception) { "" }
                throw LlmException(code, OpenAiCompat.errorMessage(err))
            }
            return Gemini.parse(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        val FALLBACKS = listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-2.5-flash-lite", "gemini-2.0-flash")
        @Volatile private var working: String? = null
        fun forget() { working = null }
    }
}

/** Building and reading Gemini JSON. Kept separate so it can be tested without a network. */
object Gemini {
    fun request(messages: List<LlmMessage>, tools: List<ToolSpec>): String {
        val root = JSONObject()
        val system = messages.filterIsInstance<LlmMessage.System>().joinToString("\n\n") { it.text }
        if (system.isNotBlank()) root.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))

        // Gemini wants turns to alternate, so neighbours with the same role are merged.
        val roles = ArrayList<String>()
        val parts = ArrayList<JSONArray>()
        fun add(role: String, part: JSONObject) {
            if (roles.isNotEmpty() && roles.last() == role) parts.last().put(part)
            else { roles.add(role); parts.add(JSONArray().put(part)) }
        }
        for (m in messages) when (m) {
            is LlmMessage.System -> Unit
            is LlmMessage.User -> if (m.text.isNotBlank()) add("user", JSONObject().put("text", m.text))
            is LlmMessage.Assistant -> {
                if (roles.isEmpty()) continue // a conversation must open with the user
                if (m.text.isNotBlank()) add("model", JSONObject().put("text", m.text))
                for (c in m.toolCalls) {
                    val args = JSONObject()
                    for ((k, v) in c.args) args.put(k, v)
                    val part = JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", args))
                    if (c.signature.isNotEmpty()) part.put("thoughtSignature", c.signature)
                    add("model", part)
                }
            }
            is LlmMessage.ToolResult -> add("user", JSONObject().put("functionResponse", JSONObject().put("name", m.name).put("response", JSONObject().put("result", m.text))))
        }
        val contents = JSONArray()
        for (i in roles.indices) contents.put(JSONObject().put("role", roles[i]).put("parts", parts[i]))
        root.put("contents", contents)

        if (tools.isNotEmpty()) {
            val decls = JSONArray()
            for (t in tools) {
                val d = JSONObject().put("name", t.name).put("description", t.description)
                if (t.params.isNotEmpty()) {
                    val props = JSONObject()
                    val required = JSONArray()
                    for (p in t.params) {
                        val po = JSONObject().put("type", p.type.uppercase()).put("description", p.description)
                        if (p.options.isNotEmpty() && p.type == "string") po.put("enum", JSONArray(p.options))
                        props.put(p.name, po)
                        if (p.required) required.put(p.name)
                    }
                    val params = JSONObject().put("type", "OBJECT").put("properties", props)
                    if (required.length() > 0) params.put("required", required)
                    d.put("parameters", params)
                }
                decls.put(d)
            }
            root.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
        }
        root.put("generationConfig", JSONObject().put("temperature", 0.4))
        return root.toString()
    }

    fun parse(body: String): LlmReply {
        val root = JSONObject(body)
        val cand = root.optJSONArray("candidates")?.optJSONObject(0)
        if (cand == null) {
            val why = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw LlmException(200, if (why.isNotBlank()) "The request was blocked ($why)" else "The AI sent back an empty answer")
        }
        val arr = cand.optJSONObject("content")?.optJSONArray("parts")
        val text = StringBuilder()
        val calls = ArrayList<ToolCall>()
        if (arr != null) for (i in 0 until arr.length()) {
            val part = arr.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            val fc = part.optJSONObject("functionCall")
            if (fc != null) {
                val name = fc.optString("name")
                if (name.isBlank()) continue
                val args = LinkedHashMap<String, String>()
                val obj = fc.optJSONObject("args") ?: JSONObject()
                for (k in obj.keys()) if (!obj.isNull(k)) {
                    val v = obj.get(k)
                    args[k] = if (v is Number) java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString() else v.toString()
                }
                calls.add(ToolCall(fc.optString("id").ifBlank { "call_${i}_$name" }, name, args, part.optString("thoughtSignature")))
            } else if (part.has("text")) text.append(part.optString("text"))
        }
        if (text.isBlank() && calls.isEmpty()) {
            val reason = cand.optString("finishReason")
            if (reason.isNotBlank() && reason != "STOP") throw LlmException(200, "The AI stopped without answering ($reason)")
        }
        return LlmReply(text.toString().trim(), calls)
    }
}
