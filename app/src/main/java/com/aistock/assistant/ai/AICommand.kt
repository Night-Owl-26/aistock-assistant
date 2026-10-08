package com.aistock.assistant.ai

import com.aistock.assistant.utils.DateKey

enum class Intent {
    ADD_PRODUCT, UPDATE_PRODUCT,
    STOCK_IN, STOCK_OUT, STOCK_ADJUST, STOCK_QUERY, LOW_STOCK_QUERY,
    CREATE_SALE, SALES_QUERY,
    CREATE_PURCHASE, PURCHASE_QUERY,
    CREATE_CUSTOMER, CUSTOMER_QUERY,
    CREATE_SUPPLIER, SUPPLIER_QUERY,
    CREATE_EXPENSE, EXPENSE_QUERY,
    CUSTOMER_BALANCE, SUPPLIER_BALANCE,
    REPORT_SALES, REPORT_PURCHASES, REPORT_STOCK, REPORT_EXPENSES,
    HELP, UNKNOWN;

    val isWrite: Boolean
        get() = this in WRITES

    companion object {
        private val WRITES = setOf(
            ADD_PRODUCT, UPDATE_PRODUCT, STOCK_IN, STOCK_OUT, STOCK_ADJUST,
            CREATE_SALE, CREATE_PURCHASE, CREATE_CUSTOMER, CREATE_SUPPLIER, CREATE_EXPENSE
        )

        fun fromName(name: String?): Intent? = entries.firstOrNull { it.name == name?.trim()?.uppercase() }
    }
}

/** Everything the parser pulled out of a sentence. Quantities are thousandths, money is paise. */
data class Entities(
    val productName: String? = null,
    val quantityMilli: Long? = null,
    val unit: String? = null,
    val unitPricePaise: Long? = null,
    val totalPaise: Long? = null,
    val amountPaise: Long? = null,
    val customerName: String? = null,
    val supplierName: String? = null,
    val phone: String? = null,
    val reason: String? = null,
    val note: String? = null,
    val expenseCategory: String? = null,
    val dateKey: DateKey? = null,
    val paid: Boolean? = null,
    val sellPaise: Long? = null,
    val buyPaise: Long? = null,
    val minMilli: Long? = null,
    /** The sentence referred to "them"/"it": use the product from the conversation so far. */
    val usesContext: Boolean = false,
)

/** The single structured command both voice and text produce. It is only a request: business rules decide. */
data class AICommand(
    val intent: Intent,
    val confidence: Double,
    val requiresConfirmation: Boolean,
    val entities: Entities = Entities(),
    val missingFields: List<String> = emptyList(),
    val response: String = "",
    val source: String = "local",
) {
    fun toJson(): String {
        val e = entities
        val parts = ArrayList<String>()
        fun s(k: String, v: String?) { if (v != null) parts.add("\"$k\":\"${esc(v)}\"") }
        fun n(k: String, v: Long?) { if (v != null) parts.add("\"$k\":$v") }
        s("productName", e.productName); n("quantityMilli", e.quantityMilli); s("unit", e.unit)
        n("unitPricePaise", e.unitPricePaise); n("totalPaise", e.totalPaise); n("amountPaise", e.amountPaise)
        s("customerName", e.customerName); s("supplierName", e.supplierName); s("phone", e.phone)
        s("reason", e.reason); s("note", e.note); s("expenseCategory", e.expenseCategory)
        s("dateKey", e.dateKey?.name)
        if (e.paid != null) parts.add("\"paid\":${e.paid}")
        n("sellPaise", e.sellPaise); n("buyPaise", e.buyPaise); n("minMilli", e.minMilli)
        if (e.usesContext) parts.add("\"usesContext\":true")
        val missing = missingFields.joinToString(",") { "\"${esc(it)}\"" }
        return "{\"intent\":\"${intent.name}\",\"confidence\":$confidence,\"requiresConfirmation\":$requiresConfirmation," +
            "\"entities\":{${parts.joinToString(",")}},\"missingFields\":[$missing],\"source\":\"${esc(source)}\"}"
    }

    private fun esc(v: String): String = buildString {
        for (c in v) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c < ' ' -> append(' ')
            else -> append(c)
        }
    }
}

data class ChatTurn(val role: String, val text: String)

/** Any engine that turns a sentence into an [AICommand]. The app never depends on a specific vendor. */
interface AIProvider {
    val name: String
    suspend fun understand(conversation: List<ChatTurn>, input: String): AICommand
}

/** Structural validation. Malformed AI output is rejected here, before any business logic runs. */
object CommandValidator {
    data class Result(val ok: Boolean, val errors: List<String>)

    private const val MAX_QTY = 1_000_000_000_000L      // 1 billion units
    private const val MAX_MONEY = 100_000_000_000_000L  // ₹1 lakh crore

    fun validate(c: AICommand): Result {
        val errors = ArrayList<String>()
        val e = c.entities
        if (c.confidence.isNaN() || c.confidence < 0.0 || c.confidence > 1.0) errors.add("confidence out of range")
        e.quantityMilli?.let {
            val min = if (c.intent == Intent.STOCK_ADJUST || c.intent == Intent.ADD_PRODUCT) 0L else 1L
            if (it < min || it > MAX_QTY) errors.add("quantity out of range")
        }
        for ((label, v) in listOf(
            "unit price" to e.unitPricePaise, "total" to e.totalPaise, "amount" to e.amountPaise,
            "selling price" to e.sellPaise, "purchase price" to e.buyPaise
        )) {
            if (v != null && (v < 0 || v > MAX_MONEY)) errors.add("$label out of range")
        }
        e.minMilli?.let { if (it < 0 || it > MAX_QTY) errors.add("minimum stock out of range") }
        for ((label, v) in listOf(
            "product name" to e.productName, "customer name" to e.customerName, "supplier name" to e.supplierName,
            "category" to e.expenseCategory, "reason" to e.reason, "note" to e.note
        )) {
            if (v != null && (v.length > 120 || v.any { it < ' ' })) errors.add("$label is not valid text")
        }
        e.unit?.let { if (it.isBlank() || it.length > 12) errors.add("unit is not valid") }
        e.phone?.let { if (!Regex("[0-9+ -]{5,16}").matches(it)) errors.add("phone is not valid") }
        if (c.intent == Intent.CREATE_EXPENSE && e.amountPaise != null && e.amountPaise <= 0) errors.add("amount must be positive")
        return Result(errors.isEmpty(), errors)
    }
}
