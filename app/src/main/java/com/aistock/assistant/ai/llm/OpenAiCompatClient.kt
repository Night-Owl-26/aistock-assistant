package com.aistock.assistant.ai.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Building and reading OpenAI-style chat JSON (kept for its error-message reader and tests). The app never calls an AI service with its own key. */
object OpenAiCompat {
    fun request(model: String, messages: List<LlmMessage>, tools: List<ToolSpec>): String {
        val arr = JSONArray()
        for (m in messages) when (m) {
            is LlmMessage.System -> arr.put(JSONObject().put("role", "system").put("content", m.text))
            is LlmMessage.User -> arr.put(JSONObject().put("role", "user").put("content", m.text))
            is LlmMessage.Assistant -> {
                val o = JSONObject().put("role", "assistant").put("content", m.text)
                if (m.toolCalls.isNotEmpty()) {
                    val calls = JSONArray()
                    for (c in m.toolCalls) {
                        val args = JSONObject()
                        for ((k, v) in c.args) args.put(k, v)
                        calls.put(JSONObject().put("id", c.id).put("type", "function").put("function", JSONObject().put("name", c.name).put("arguments", args.toString())))
                    }
                    o.put("tool_calls", calls)
                }
                arr.put(o)
            }
            is LlmMessage.ToolResult -> arr.put(JSONObject().put("role", "tool").put("tool_call_id", m.callId).put("name", m.name).put("content", m.text))
        }
        val root = JSONObject().put("model", model).put("messages", arr).put("temperature", 0.4)
        if (tools.isNotEmpty()) {
            val ts = JSONArray()
            for (t in tools) {
                val props = JSONObject()
                val required = JSONArray()
                for (p in t.params) {
                    val po = JSONObject().put("type", p.type).put("description", p.description)
                    if (p.options.isNotEmpty()) po.put("enum", JSONArray(p.options))
                    props.put(p.name, po)
                    if (p.required) required.put(p.name)
                }
                val params = JSONObject().put("type", "object").put("properties", props)
                if (required.length() > 0) params.put("required", required)
                ts.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", t.name).put("description", t.description).put("parameters", params)))
            }
            root.put("tools", ts).put("tool_choice", "auto")
        }
        return root.toString()
    }

    fun parse(body: String): LlmReply {
        val message = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val text = if (message.isNull("content")) "" else message.optString("content")
        val calls = ArrayList<ToolCall>()
        message.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val fn = c.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                if (name.isBlank()) continue
                val args = LinkedHashMap<String, String>()
                val raw = fn.opt("arguments")
                val obj = when (raw) {
                    is JSONObject -> raw
                    is String -> try { JSONObject(raw.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
                    else -> JSONObject()
                }
                for (k in obj.keys()) if (!obj.isNull(k)) {
                    val v = obj.get(k)
                    args[k] = if (v is Number) java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString() else v.toString()
                }
                calls.add(ToolCall(c.optString("id").ifBlank { "call_${i}_$name" }, name, args))
            }
        }
        return LlmReply(text.trim(), calls)
    }

    fun errorMessage(body: String): String = try {
        val o = JSONObject(body.trim().removePrefix("[").removeSuffix("]"))
        (o.optJSONObject("error")?.optString("message") ?: o.optString("message")).take(200)
    } catch (e: Exception) {
        body.take(200)
    }
}
