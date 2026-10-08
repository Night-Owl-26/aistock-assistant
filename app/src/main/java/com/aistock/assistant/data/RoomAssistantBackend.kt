package com.aistock.assistant.data

import com.aistock.assistant.ai.AssistantBackend
import com.aistock.assistant.ai.ExecResult
import com.aistock.assistant.ai.OutReason
import com.aistock.assistant.ai.PartyKind
import com.aistock.assistant.ai.PartyRef
import com.aistock.assistant.ai.Plan
import com.aistock.assistant.ai.ProductRef
import com.aistock.assistant.ai.StockSummary
import com.aistock.assistant.ai.SummaryLine
import com.aistock.assistant.ai.TxnSummary
import com.aistock.assistant.database.AppDatabase
import com.aistock.assistant.database.AuditLogEntity
import com.aistock.assistant.utils.Qty
import javax.inject.Inject
import javax.inject.Singleton

/** Connects the assistant to the real business logic. Every plan runs as one all-or-nothing transaction. */
@Singleton
class RoomAssistantBackend @Inject constructor(
    private val db: AppDatabase,
    private val inventory: InventoryRepository,
    private val trade: TradeRepository,
    private val parties: PartyRepository,
) : AssistantBackend {

    /** Set by the assistant screen so audit entries point at the conversation they came from. */
    @Volatile var conversationId: String = ""

    override suspend fun products(): List<ProductRef> = inventory.activeProducts().map {
        ProductRef(it.id, it.name, it.unit, it.currentStock, it.minimumStock, it.sellingPrice, it.purchasePrice)
    }

    override suspend fun parties(kind: PartyKind): List<PartyRef> =
        parties.list(kind).map { PartyRef(it.id, it.name, it.phone, it.balance, kind) }

    override suspend fun execute(plan: Plan): ExecResult {
        val r: Op<ExecResult> = db.op {
            when (plan) {
                is Plan.AddProduct -> {
                    val p = inventory.createProduct(
                        ProductInput(
                            name = plan.name, unit = plan.unit, openingStock = plan.openingMilli,
                            sellingPrice = plan.sellPaise ?: 0, purchasePrice = plan.buyPaise ?: 0, minimumStock = plan.minMilli ?: 0,
                        )
                    ).orThrow()
                    ExecResult(true, stockAfterMilli = p.currentStock, reference = p.productCode)
                }
                is Plan.UpdateProduct -> {
                    val p = inventory.product(plan.product.id) ?: fail(ErrCode.NOT_FOUND)
                    inventory.updateProduct(
                        p.id,
                        ProductInput(
                            name = p.name, productCode = p.productCode, description = p.description, categoryId = p.categoryId,
                            unit = p.unit, barcode = p.barcode, purchasePrice = plan.buyPaise ?: p.purchasePrice,
                            sellingPrice = plan.sellPaise ?: p.sellingPrice, minimumStock = plan.minMilli ?: p.minimumStock,
                        )
                    ).orThrow()
                    ExecResult(true)
                }
                is Plan.StockIn -> {
                    val productId = plan.product?.id ?: inventory.createProduct(
                        ProductInput(name = plan.newProductName.orEmpty(), unit = plan.unit, purchasePrice = plan.unitCostPaise ?: 0)
                    ).orThrow().id
                    val supplierName = plan.supplier?.name ?: plan.newSupplierName
                    if (plan.asPurchase) {
                        val supplierId = plan.supplier?.id
                            ?: plan.newSupplierName?.let { parties.create(PartyKind.SUPPLIER, PartyInput(it)).orThrow() }
                        val total = plan.totalPaise ?: 0L
                        val purchase = trade.createPurchase(
                            supplierId, listOf(LineInput(productId, plan.qtyMilli, plan.unitCostPaise ?: 0)),
                            paid = if (plan.paid) null else 0L, notes = "Assistant", totalOverride = total,
                        ).orThrow()
                        ExecResult(true, undoToken = "PURCHASE:${purchase.id}", stockAfterMilli = inventory.product(productId)?.currentStock, reference = purchase.purchaseNumber)
                    } else {
                        val type = when {
                            plan.returnIn -> TxnType.RETURN_IN
                            supplierName != null -> TxnType.PURCHASE
                            else -> TxnType.ADJUSTMENT_IN
                        }
                        val t = inventory.stockIn(
                            productId, plan.qtyMilli, type, plan.unitCostPaise,
                            notes = supplierName?.let { "From $it" } ?: "Assistant", referenceType = "ASSISTANT",
                        ).orThrow()
                        ExecResult(true, undoToken = "TXN:${t.id}", stockAfterMilli = t.afterQuantity)
                    }
                }
                is Plan.StockOut -> {
                    val type = when (plan.reason) {
                        OutReason.DAMAGE -> TxnType.DAMAGE
                        OutReason.RETURN_OUT -> TxnType.RETURN_OUT
                        OutReason.OTHER -> TxnType.ADJUSTMENT_OUT
                    }
                    val t = inventory.stockOut(plan.product.id, plan.qtyMilli, type, plan.note.ifBlank { "Assistant" }, "ASSISTANT").orThrow()
                    ExecResult(true, undoToken = "TXN:${t.id}", stockAfterMilli = t.afterQuantity)
                }
                is Plan.StockAdjust -> {
                    val t = inventory.adjustTo(plan.product.id, plan.newQtyMilli, "Stock count (assistant)").orThrow()
                    ExecResult(true, undoToken = "TXN:${t.id}", stockAfterMilli = t.afterQuantity)
                }
                is Plan.Sale -> {
                    val customerId = plan.customer?.id
                        ?: plan.newCustomerName?.let { parties.create(PartyKind.CUSTOMER, PartyInput(it)).orThrow() }
                    val sale = trade.createSale(
                        customerId, listOf(LineInput(plan.product.id, plan.qtyMilli, plan.unitPricePaise)),
                        paid = if (plan.paid) null else 0L, notes = "Assistant", totalOverride = plan.totalPaise,
                    ).orThrow()
                    ExecResult(true, undoToken = "SALE:${sale.id}", stockAfterMilli = inventory.product(plan.product.id)?.currentStock, reference = sale.invoiceNumber)
                }
                is Plan.CreateParty -> {
                    parties.create(plan.kind, PartyInput(plan.name, phone = plan.phone)).orThrow()
                    ExecResult(true)
                }
                is Plan.Expense -> {
                    trade.addExpense(plan.category, plan.description, plan.amountPaise).orThrow()
                    ExecResult(true)
                }
            }
        }
        return when (r) {
            is Op.Ok -> r.value
            is Op.Err -> ExecResult(false, r.code)
        }
    }

    override suspend fun undo(token: String): ExecResult {
        val id = token.substringAfter(':', "")
        val r: Op<Unit> = when (token.substringBefore(':')) {
            "TXN" -> when (val x = inventory.undoMovement(id)) { is Op.Ok -> Op.Ok(Unit); is Op.Err -> x }
            "SALE" -> trade.cancelSale(id)
            "PURCHASE" -> trade.cancelPurchase(id)
            else -> Op.Err(ErrCode.INVALID)
        }
        return if (r is Op.Err) ExecResult(false, r.code) else ExecResult(true)
    }

    override suspend fun sales(from: Long, to: Long): TxnSummary {
        val t = db.tradeDao().saleTotals(from, to)
        val lines = db.tradeDao().salesByProduct(from, to).map { SummaryLine(it.productName, Qty.format(it.quantity) + " " + it.unit.lowercase(), it.total) }
        return TxnSummary(t.count, t.total, t.balance, lines)
    }

    override suspend fun purchases(from: Long, to: Long): TxnSummary {
        val t = db.tradeDao().purchaseTotals(from, to)
        val lines = db.tradeDao().purchasesByProduct(from, to).map { SummaryLine(it.productName, Qty.format(it.quantity) + " " + it.unit.lowercase(), it.total) }
        return TxnSummary(t.count, t.total, t.balance, lines)
    }

    override suspend fun expenses(from: Long, to: Long): TxnSummary {
        val by = db.tradeDao().expensesByCategory(from, to)
        return TxnSummary(by.sumOf { it.count }, by.sumOf { it.total }, 0, by.map { SummaryLine(it.category, if (it.count > 1) "×${it.count}" else "", it.total) })
    }

    override suspend fun stockSummary(): StockSummary {
        val all = inventory.activeProducts()
        return StockSummary(all.size, db.productDao().stockValue(), all.count { it.currentStock <= it.minimumStock }, all.count { it.currentStock <= 0 })
    }

    override suspend fun audit(intent: String, command: String, result: String, status: String) {
        val user = db.metaDao().user()
        db.metaDao().insertAudit(
            AuditLogEntity(newId(), user?.id.orEmpty(), conversationId, intent, command.take(2000), result.take(500), status, System.currentTimeMillis())
        )
    }
}
