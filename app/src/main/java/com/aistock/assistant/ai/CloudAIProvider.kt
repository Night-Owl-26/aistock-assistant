package com.aistock.assistant.ai

import com.aistock.assistant.utils.DateKey
import com.aistock.assistant.utils.Money
import com.aistock.assistant.utils.Qty
import com.aistock.assistant.utils.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class CloudConfig(val endpoint: String, val model: String, val apiKey: String)

/**
 * Optional cloud understanding. It speaks the widely supported "chat completions" JSON format, so any compatible
 * service can be used: the endpoint URL, model name and key are entered in Settings and are never built into the app.
 * The reply is only ever read as an [AICommand]; it cannot run SQL or bypass business rules.
 */
class CloudAIProvider(private val config: suspend () -> CloudConfig?) : AIProvider {
    override val name: String = "cloud"

    override suspend fun understand(conversation: List<ChatTurn>, input: String): AICommand = withContext(Dispatchers.IO) {
        val cfg = config() ?: throw IllegalStateException("Cloud AI is not configured")
        if (!cfg.endpoint.startsWith("https://")) throw IllegalStateException("Endpoint must use https")
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", AICommandJson.SYSTEM_PROMPT))
        for (t in conversation.takeLast(8).dropLast(1)) {
            messages.put(JSONObject().put("role", if (t.role == "user") "user" else "assistant").put("content", t.text.take(500)))
        }
        messages.put(JSONObject().put("role", "user").put("content", input.take(1000)))
        val body = JSONObject().put("model", cfg.model).put("temperature", 0).put("messages", messages).toString()

        val conn = URL(cfg.endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("AI service answered $code")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val content = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            AICommandJson.parse(content).copy(source = "cloud")
        } finally {
            conn.disconnect()
        }
    }
}

/** Strict reader for the JSON an AI service returns. Anything unexpected throws and the local rules take over. */
object AICommandJson {
    val SYSTEM_PROMPT: String = """
You convert what a small-business owner says (English, Tamil, or mixed Tamil-English) into ONE JSON object for an inventory app. Reply with JSON only, no prose.
Schema:
{"intent": one of [${Intent.entries.joinToString(", ") { it.name }}],
 "confidence": number 0..1,
 "entities": {"productName": string, "quantity": number, "unit": one of [${Units.ALL.joinToString(", ")}],
   "unitPrice": number in rupees, "total": number in rupees, "amount": number in rupees,
   "customerName": string, "supplierName": string, "phone": string,
   "reason": one of [DAMAGE, RETURN_OUT, RETURN_IN, OTHER], "note": string, "expenseCategory": string,
   "dateRange": one of [TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH, ALL],
   "paid": boolean, "sellingPrice": number, "purchasePrice": number, "minimumStock": number, "usesContext": boolean},
 "missingFields": [string], "response": short sentence}
Rules: include only entities that were actually said. Never invent a product, name, quantity or price. If the user refers to "it" or "them", set usesContext true and omit productName. For STOCK_ADJUST, quantity is the new counted stock. Payments and deletions are not supported: use UNKNOWN. If unsure, use UNKNOWN with low confidence.
""".trim()

    fun parse(text: String): AICommand {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw IllegalArgumentException("No JSON object in reply")
        val o = JSONObject(text.substring(start, end + 1))
        val intent = Intent.fromName(o.optString("intent")) ?: throw IllegalArgumentException("Unknown intent")
        val confidence = o.optDouble("confidence", Double.NaN)
        if (confidence.isNaN() || confidence < 0.0 || confidence > 1.0) throw IllegalArgumentException("Bad confidence")
        val e = o.optJSONObject("entities") ?: JSONObject()

        fun str(key: String): String? = if (e.isNull(key)) null else e.opt(key)?.let { v -> if (v is String) v.trim().ifEmpty { null } else throw IllegalArgumentException("$key must be text") }
        fun num(key: String): String? = if (e.isNull(key)) null else when (val v = e.opt(key)) {
            null -> null
            is Number -> java.math.BigDecimal(v.toString()).toPlainString()
            is String -> v.trim().ifEmpty { null }
            else -> throw IllegalArgumentException("$key must be a number")
        }
        fun money(key: String): Long? = num(key)?.let { Money.parse(it) ?: throw IllegalArgumentException("$key is not an amount") }
        fun qty(key: String): Long? = num(key)?.let { Qty.parse(it) ?: throw IllegalArgumentException("$key is not a quantity") }
        fun bool(key: String): Boolean? = if (e.isNull(key)) null else (e.opt(key) as? Boolean ?: throw IllegalArgumentException("$key must be true or false"))

        val unit = str("unit")?.let { Units.normalize(it) ?: throw IllegalArgumentException("Unknown unit") }
        val reason = str("reason")?.uppercase()?.also { if (it !in setOf("DAMAGE", "RETURN_OUT", "RETURN_IN", "OTHER")) throw IllegalArgumentException("Unknown reason") }
        val date = str("dateRange")?.let { d -> DateKey.entries.firstOrNull { it.name == d.uppercase() } ?: throw IllegalArgumentException("Unknown date range") }
        val entities = Entities(
            productName = str("productName"), quantityMilli = qty("quantity"), unit = unit,
            unitPricePaise = money("unitPrice"), totalPaise = money("total"), amountPaise = money("amount"),
            customerName = str("customerName"), supplierName = str("supplierName"), phone = str("phone"),
            reason = reason, note = str("note"), expenseCategory = str("expenseCategory"), dateKey = date,
            paid = bool("paid"), sellPaise = money("sellingPrice"), buyPaise = money("purchasePrice"),
            minMilli = qty("minimumStock"), usesContext = bool("usesContext") ?: false,
        )
        val missing = ArrayList<String>()
        o.optJSONArray("missingFields")?.let { arr -> for (i in 0 until arr.length()) missing.add(arr.optString(i)) }
        // Writes always need confirmation, whatever the service says.
        val cmd = AICommand(intent, confidence, intent.isWrite, entities, missing.filter { it != LocalParser.FIELD_RECORD_TYPE }, o.optString("response").take(300), "cloud")
        val v = CommandValidator.validate(cmd)
        if (!v.ok) throw IllegalArgumentException(v.errors.joinToString("; "))
        return cmd
    }
}

/** Uses the cloud service when it is switched on, configured and reachable; otherwise the on-device rules. */
class RoutingAIProvider(
    private val useCloud: () -> Boolean,
    private val online: () -> Boolean,
    private val cloud: AIProvider,
    private val local: AIProvider,
) : AIProvider {
    @Volatile var lastSource: String = "local"
        private set

    override val name: String get() = lastSource

    override suspend fun understand(conversation: List<ChatTurn>, input: String): AICommand {
        if (useCloud() && online()) {
            val r = try {
                withTimeoutOrNull(20_000) { cloud.understand(conversation, input) }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (r != null && r.intent != Intent.UNKNOWN) {
                lastSource = "cloud"
                return r
            }
        }
        lastSource = "local"
        return local.understand(conversation, input)
    }
}
