package com.aistock.assistant.voice.live

import com.aistock.assistant.ai.llm.Gemini
import com.aistock.assistant.ai.llm.ToolCall
import com.aistock.assistant.ai.llm.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/** Everything the voice server can tell us, already read from its JSON. */
sealed class LiveEvent {
    object SetupComplete : LiveEvent()
    class Audio(val base64: String) : LiveEvent()
    class UserText(val text: String) : LiveEvent()
    class StockyText(val text: String) : LiveEvent()
    object Interrupted : LiveEvent()
    object GenerationComplete : LiveEvent()
    object TurnComplete : LiveEvent()
    class Tools(val calls: List<ToolCall>) : LiveEvent()
    class ToolsCancelled(val ids: List<String>) : LiveEvent()
    class GoAway(val secondsLeft: Int) : LiveEvent()
    class Resumable(val handle: String) : LiveEvent()
    class Tokens(val total: Int) : LiveEvent()
}

/**
 * The Gemini Live API messages (https://ai.google.dev/api/live). Kept free of Android so it can be tested:
 * one JSON text frame in, a list of events out; and the few messages the app sends.
 */
object LiveProtocol {
    const val INPUT_MIME = "audio/pcm;rate=16000"

    fun setup(model: String, instructions: String, tools: List<ToolSpec>, resumeHandle: String?, voiceName: String? = null): String {
        val gen = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
        voiceName?.let { gen.put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", it)))) }
        val s = JSONObject()
            .put("model", if (model.startsWith("models/")) model else "models/$model")
            .put("generationConfig", gen)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instructions))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("realtimeInputConfig", JSONObject()
                // A shop is noisy: start less eagerly, and wait a moment of silence before answering.
                .put("automaticActivityDetection", JSONObject()
                    .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                    .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                    .put("prefixPaddingMs", 120)
                    .put("silenceDurationMs", 700))
                .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS"))
            .put("sessionResumption", if (resumeHandle != null) JSONObject().put("handle", resumeHandle) else JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        if (tools.isNotEmpty()) s.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", Gemini.declarations(tools))))
        return JSONObject().put("setup", s).toString()
    }

    fun audio(base64: String): String =
        JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject().put("mimeType", INPUT_MIME).put("data", base64))).toString()

    fun audioStreamEnd(): String = JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString()

    /** Something the app tells Stocky between turns (e.g. "the owner pressed Confirm and it was saved"). */
    fun note(text: String): String = JSONObject().put("clientContent", JSONObject()
        .put("turns", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
        .put("turnComplete", true)).toString()

    fun toolResponse(results: List<Triple<String, String, String>>): String {
        val arr = JSONArray()
        for ((id, name, text) in results) arr.put(JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", text)))
        return JSONObject().put("toolResponse", JSONObject().put("functionResponses", arr)).toString()
    }

    /** "12.5s" -> 12 */
    private fun seconds(d: String?): Int = d?.removeSuffix("s")?.toDoubleOrNull()?.toInt() ?: 0

    fun parse(frame: String): List<LiveEvent> {
        val o = try { JSONObject(frame) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<LiveEvent>()
        if (o.has("setupComplete")) out.add(LiveEvent.SetupComplete)
        o.optJSONObject("serverContent")?.let { sc ->
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { out.add(LiveEvent.UserText(it)) }
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    p.optJSONObject("inlineData")?.let { d -> if (d.optString("mimeType").startsWith("audio/")) out.add(LiveEvent.Audio(d.optString("data"))) }
                }
            }
            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { out.add(LiveEvent.StockyText(it)) }
            if (sc.optBoolean("interrupted")) out.add(LiveEvent.Interrupted)
            if (sc.optBoolean("generationComplete")) out.add(LiveEvent.GenerationComplete)
            if (sc.optBoolean("turnComplete")) out.add(LiveEvent.TurnComplete)
        }
        o.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { arr ->
            val calls = ArrayList<ToolCall>()
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val args = LinkedHashMap<String, String>()
                val a = c.optJSONObject("args") ?: JSONObject()
                for (k in a.keys()) if (!a.isNull(k)) {
                    val v = a.get(k)
                    args[k] = if (v is Number) java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString() else v.toString()
                }
                calls.add(ToolCall(c.optString("id").ifBlank { "live_$i" }, c.optString("name"), args))
            }
            if (calls.isNotEmpty()) out.add(LiveEvent.Tools(calls))
        }
        o.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { ids -> out.add(LiveEvent.ToolsCancelled((0 until ids.length()).map { ids.optString(it) })) }
        o.optJSONObject("goAway")?.let { out.add(LiveEvent.GoAway(seconds(it.optString("timeLeft")))) }
        o.optJSONObject("sessionResumptionUpdate")?.let { u -> if (u.optBoolean("resumable") && u.optString("newHandle").isNotEmpty()) out.add(LiveEvent.Resumable(u.optString("newHandle"))) }
        o.optJSONObject("usageMetadata")?.let { out.add(LiveEvent.Tokens(it.optInt("totalTokenCount"))) }
        return out
    }
}

/**
 * Keeps the turn bookkeeping of a voice conversation, separate from audio so it can be tested:
 * when a new owner turn starts, what was said, and which tool calls already ran (the same id never runs twice).
 */
class LiveTurns {
    var inTurn = false; private set
    private val user = StringBuilder()
    private val stocky = StringBuilder()
    private val ran = HashSet<String>()
    private val cancelled = HashSet<String>()

    /** @return true when this owner speech starts a new turn */
    fun onUser(text: String): Boolean {
        val started = !inTurn
        if (started) { inTurn = true; user.clear(); stocky.clear() }
        user.append(text)
        return started
    }

    fun onStocky(text: String) { stocky.append(text) }

    /** @return (what the owner said, what Stocky said) for the finished turn */
    fun onTurnComplete(): Pair<String, String> {
        val r = user.toString().trim() to stocky.toString().trim()
        inTurn = false; user.clear(); stocky.clear()
        return r
    }

    fun onInterrupted() { stocky.clear() }

    fun shouldRun(id: String): Boolean = id !in cancelled && ran.add(id)
    fun cancel(ids: List<String>) { cancelled.addAll(ids) }
}
