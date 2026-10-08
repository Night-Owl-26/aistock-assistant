package com.aistock.assistant.ai

data class ProductRef(
    val id: String, val name: String, val unit: String,
    val stockMilli: Long, val minMilli: Long, val sellPaise: Long, val buyPaise: Long,
) {
    val isLow: Boolean get() = stockMilli <= minMilli
}

enum class PartyKind { CUSTOMER, SUPPLIER }

data class PartyRef(val id: String, val name: String, val phone: String, val balancePaise: Long, val kind: PartyKind)

enum class OutReason { DAMAGE, RETURN_OUT, OTHER }

/** A fully resolved, validated action that is shown to the user for confirmation and then executed. */
sealed class Plan {
    data class AddProduct(val name: String, val unit: String, val openingMilli: Long, val sellPaise: Long?, val buyPaise: Long?, val minMilli: Long?) : Plan()
    data class UpdateProduct(val product: ProductRef, val sellPaise: Long?, val buyPaise: Long?, val minMilli: Long?) : Plan()
    data class StockIn(
        val product: ProductRef?, val newProductName: String?, val unit: String, val qtyMilli: Long,
        val unitCostPaise: Long?, val supplier: PartyRef?, val newSupplierName: String?,
        /** Record a purchase bill (supplier balance changes) instead of a plain stock movement. */
        val asPurchase: Boolean, val paid: Boolean, val returnIn: Boolean,
        /** Bill total for a purchase; null for a plain stock movement. */
        val totalPaise: Long?,
    ) : Plan()
    data class StockOut(val product: ProductRef, val qtyMilli: Long, val reason: OutReason, val note: String) : Plan()
    data class StockAdjust(val product: ProductRef, val newQtyMilli: Long) : Plan()
    data class Sale(val product: ProductRef, val qtyMilli: Long, val unitPricePaise: Long, val customer: PartyRef?, val newCustomerName: String?, val paid: Boolean, val totalPaise: Long) : Plan()
    data class CreateParty(val kind: PartyKind, val name: String, val phone: String) : Plan()
    data class Expense(val category: String, val amountPaise: Long, val description: String) : Plan()
    /** Money received from a customer (kind CUSTOMER) or paid to a supplier (kind SUPPLIER). */
    data class Payment(val party: PartyRef, val amountPaise: Long, val method: String = "CASH") : Plan()
    /** Reverses an earlier action through a reversing record; history is kept. */
    data class UndoLast(val token: String, val description: String) : Plan()
    data class CreateSheet(val name: String, val columns: List<String>) : Plan()
    data class AddColumn(val sheet: String, val column: String) : Plan()
    data class AddRow(val sheet: String, val values: Map<String, String>) : Plan()
    /** Teaches Stocky that the owner's own word for something means a product. */
    data class RememberAlias(val phrase: String, val productName: String) : Plan()
}

/** The owner's own name for a product, e.g. "blue cotton" -> "BC-40-COTTON-BLUE". */
data class AliasRef(val phrase: String, val productName: String)

/** One sheet of the owner's workbook as Stocky sees it. */
data class SheetRef(val name: String, val purpose: String, val columns: List<String>, val rowCount: Int)

/** One auditable AI action: what was said, what was done, and the value before and after. */
data class ActionRecord(
    val command: String, val intent: String, val action: String, val subject: String,
    val before: String, val after: String, val confirmed: Boolean, val result: String,
)

data class ExecResult(
    val ok: Boolean,
    /** INSUFFICIENT_STOCK, NOT_FOUND, DUPLICATE, INVALID or FAILED when not ok. */
    val error: String? = null,
    val undoToken: String? = null,
    val stockAfterMilli: Long? = null,
    val reference: String? = null,
)

data class SummaryLine(val label: String, val detail: String, val amountPaise: Long)
data class TxnSummary(val count: Int, val totalPaise: Long, val unpaidPaise: Long, val lines: List<SummaryLine>)
data class StockSummary(val productCount: Int, val valuePaise: Long, val lowCount: Int, val outCount: Int)

/** The only door between the assistant and the data. The assistant never sees SQL. */
interface AssistantBackend {
    suspend fun products(): List<ProductRef>
    suspend fun parties(kind: PartyKind): List<PartyRef>
    suspend fun execute(plan: Plan): ExecResult
    suspend fun undo(token: String): ExecResult
    suspend fun sales(from: Long, to: Long): TxnSummary
    suspend fun purchases(from: Long, to: Long): TxnSummary
    suspend fun expenses(from: Long, to: Long): TxnSummary
    suspend fun stockSummary(): StockSummary
    suspend fun audit(intent: String, command: String, result: String, status: String)

    // ---- V2: everything below has a safe default so simple backends keep working
    suspend fun partySales(customerId: String, from: Long, to: Long): TxnSummary = TxnSummary(0, 0, 0, emptyList())
    suspend fun partyPurchases(supplierId: String, from: Long, to: Long): TxnSummary = TxnSummary(0, 0, 0, emptyList())
    /** Newest first, already formatted as short lines. */
    suspend fun recentActivity(limit: Int, partyId: String? = null): List<String> = emptyList()
    suspend fun aliases(): List<AliasRef> = emptyList()
    suspend fun sheets(): List<SheetRef> = emptyList()
    /** Rows of a workbook sheet containing [query] (all rows when blank). The first row returned is the header. */
    suspend fun sheetRows(sheet: String, query: String, limit: Int): List<List<String>> = emptyList()
    suspend fun recordAction(record: ActionRecord) {}
}

enum class ReplyKind { ANSWER, CLARIFY, CONFIRM, DONE, CANCELLED, ERROR }

data class ConfirmLine(val label: String, val value: String)

data class AssistantReply(
    val text: String,
    val kind: ReplyKind,
    val intent: Intent = Intent.UNKNOWN,
    val lines: List<ConfirmLine> = emptyList(),
    val choices: List<String> = emptyList(),
    val undoToken: String? = null,
    val dangerous: Boolean = false,
    /** Short sentence suitable for reading aloud. */
    val spoken: String = text,
)
