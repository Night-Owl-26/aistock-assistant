package com.aistock.assistant.ai.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Building and reading Gemini JSON. Kept separate so it can be tested without a network. */
object Gemini {
    /** Names of the text models that support generateContent, from the "list models" answer. */
    fun modelNames(body: String): List<String> {
        val arr = JSONObject(body).optJSONArray("models") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val methods = m.optJSONArray("supportedGenerationMethods")
            val ok = methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            if (ok) out.add(m.optString("name").removePrefix("models/"))
        }
        return out
    }

    /** Best first: the newest fast general model, then lighter ones, then the big ones. Special-purpose models are left out. */
    fun rank(names: List<String>): List<String> {
        val skip = Regex("image|tts|audio|live|embed|vision|learn|robot|computer|research|aqa|gemma|nano|banana|thinking|exp")
        fun version(n: String) = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        fun family(n: String) = when { n.contains("flash-lite") -> 1; n.contains("flash") -> 0; else -> 2 }
        return names.filter { it.startsWith("gemini") && !skip.containsMatchIn(it) }
            .sortedWith(compareBy<String> { family(it) }.thenBy { if (it.contains("preview")) 1 else 0 }.thenByDescending { version(it) }.thenBy { it.length })
    }

    /** Ways to keep a model from thinking at length, most likely first; "none" sends no setting at all. */
    fun thinkingOptions(model: String): List<String> {
        val v = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(model)?.groupValues?.get(1)?.toDoubleOrNull()
        return when {
            v != null && v < 2.5 -> listOf("none")
            v != null && v < 3.0 -> listOf("budget0", "none")
            else -> listOf("minimal", "low", "budget0", "none")
        }
    }

    /** Gemini 3 and newer are meant to run at their own default temperature; older ones answer more steadily at 0.4. */
    fun temperatureFor(model: String): Double? {
        val v = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(model)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return if (v < 3.0) 0.4 else null
    }

    fun request(messages: List<LlmMessage>, tools: List<ToolSpec>, thinking: String = "none", temperature: Double? = 0.4): String {
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

        if (tools.isNotEmpty()) root.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations(tools))))
        val config = JSONObject()
        if (temperature != null) config.put("temperature", temperature)
        when (thinking) {
            "budget0" -> config.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            "minimal", "low" -> config.put("thinkingConfig", JSONObject().put("thinkingLevel", thinking))
        }
        root.put("generationConfig", config)
        return root.toString()
    }

    /** Tool descriptions in Gemini's function-declaration format (also used by the real-time voice session). */
    fun declarations(tools: List<ToolSpec>): JSONArray {
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
        return decls
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
