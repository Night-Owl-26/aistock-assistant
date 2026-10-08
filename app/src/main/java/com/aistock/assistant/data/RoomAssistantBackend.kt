package com.aistock.assistant.data

import com.aistock.assistant.ai.ActionRecord
import com.aistock.assistant.ai.AliasRef
import com.aistock.assistant.ai.AssistantBackend
import com.aistock.assistant.ai.SheetRef
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
import com.aistock.assistant.database.AiActionEntity
import com.aistock.assistant.database.AuditLogEntity
import com.aistock.assistant.database.BusinessAliasEntity
import com.aistock.assistant.database.SheetEntity
import com.aistock.assistant.database.SheetRowEntity
import com.aistock.assistant.utils.NameMatcher
import org.json.JSONArray
import org.json.JSONObject
import com.aistock.assistant.utils.DateKey
import com.aistock.assistant.utils.DateRanges
import com.aistock.assistant.utils.Money
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
                is Plan.Payment -> {
                    val pay = trade.addPayment(if (plan.party.kind == PartyKind.CUSTOMER) "CUSTOMER" else "SUPPLIER", plan.party.id, plan.amountPaise, plan.method, notes = "Stocky").orThrow()
                    ExecResult(true, undoToken = "PAYMENT:${pay.id}")
                }
                is Plan.UndoLast -> {
                    val r = undo(plan.token)
                    if (!r.ok) fail(r.error ?: ErrCode.FAILED)
                    r
                }
                is Plan.RememberAlias -> {
                    val key = NameMatcher.key(plan.phrase)
                    if (key.isEmpty()) fail(ErrCode.INVALID)
                    stocky.deleteAlias(key)
                    stocky.saveAlias(BusinessAliasEntity(newId(), plan.phrase.trim().take(80), key, plan.productName, System.currentTimeMillis()))
                    ExecResult(true)
                }
                is Plan.CreateSheet -> {
                    val name = plan.name.trim().take(60)
                    if (name.isEmpty() || plan.columns.isEmpty()) fail(ErrCode.INVALID)
                    if (stocky.sheets().any { NameMatcher.key(it.name) == NameMatcher.key(name) }) fail(ErrCode.DUPLICATE)
                    val now = System.currentTimeMillis()
                    stocky.insertSheet(SheetEntity(newId(), name, "CUSTOM", JSONArray(plan.columns.map { it.trim().take(40) }).toString(), now, now))
                    ExecResult(true)
                }
                is Plan.AddColumn -> {
                    val sheet = sheetByName(plan.sheet) ?: fail(ErrCode.NOT_FOUND)
                    val cols = columnsOf(sheet)
                    val col = plan.column.trim().take(40)
                    if (col.isEmpty()) fail(ErrCode.INVALID)
                    if (cols.any { it.equals(col, true) }) fail(ErrCode.DUPLICATE)
                    // Existing rows are untouched: they simply have no value in the new column yet.
                    stocky.updateSheet(sheet.copy(columns = JSONArray(cols + col).toString(), updatedAt = System.currentTimeMillis()))
                    ExecResult(true)
                }
                is Plan.AddRow -> {
                    val sheet = sheetByName(plan.sheet) ?: fail(ErrCode.NOT_FOUND)
                    val cols = columnsOf(sheet)
                    val cells = JSONObject()
                    for (c in cols) plan.values.entries.firstOrNull { it.key.equals(c, true) }?.let { cells.put(c, it.value.take(500)) }
                    if (cells.length() == 0) fail(ErrCode.INVALID)
                    val now = System.currentTimeMillis()
                    stocky.insertRow(SheetRowEntity(newId(), sheet.id, now, cells.toString(), now))
                    stocky.updateSheet(sheet.copy(updatedAt = now))
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
            "PAYMENT" -> trade.cancelPayment(id)
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

    // ------------------------------------------------------------ V2: Stocky

    private val stocky get() = db.stockyDao()

    private suspend fun sheetByName(name: String): SheetEntity? {
        val all = stocky.sheets()
        val key = NameMatcher.key(name.removeSuffix(" sheet"))
        return all.firstOrNull { NameMatcher.key(it.name) == key } ?: all.filter { NameMatcher.key(it.name).contains(key) && key.isNotEmpty() }.singleOrNull()
    }

    private fun columnsOf(sheet: SheetEntity): List<String> = try {
        JSONArray(sheet.columns).let { a -> (0 until a.length()).map { a.optString(it) } }
    } catch (e: Exception) {
        emptyList()
    }

    override suspend fun aliases(): List<AliasRef> = stocky.aliases().map { AliasRef(it.phrase, it.productName) }

    override suspend fun sheets(): List<SheetRef> = stocky.sheets().map { SheetRef(it.name, it.purpose, columnsOf(it), stocky.rowCount(it.id)) }

    override suspend fun sheetRows(sheet: String, query: String, limit: Int): List<List<String>> {
        val s = sheetByName(sheet) ?: return emptyList()
        val cols = columnsOf(s)
        val out = ArrayList<List<String>>()
        out.add(cols)
        for (r in stocky.rows(s.id)) {
            val o = try { JSONObject(r.cells) } catch (e: Exception) { JSONObject() }
            val row = cols.map { o.optString(it) }
            if (query.isBlank() || row.any { it.contains(query.trim(), ignoreCase = true) }) out.add(row)
            if (out.size > limit) break
        }
        return out
    }

    override suspend fun recordAction(record: ActionRecord) {
        stocky.insertAction(
            AiActionEntity(newId(), conversationId, record.command.take(500), record.intent, record.action, record.subject.take(200), record.before.take(200), record.after.take(200), record.confirmed, record.result.take(500), System.currentTimeMillis())
        )
    }

    override suspend fun partySales(customerId: String, from: Long, to: Long): TxnSummary {
        val t = db.tradeDao().saleTotalsFor(customerId, from, to)
        return TxnSummary(t.count, t.total, t.balance, db.tradeDao().salesByProductFor(customerId, from, to).map { SummaryLine(it.productName, Qty.format(it.quantity) + " " + it.unit.lowercase(), it.total) })
    }

    override suspend fun partyPurchases(supplierId: String, from: Long, to: Long): TxnSummary {
        val t = db.tradeDao().purchaseTotalsFor(supplierId, from, to)
        return TxnSummary(t.count, t.total, t.balance, db.tradeDao().purchasesByProductFor(supplierId, from, to).map { SummaryLine(it.productName, Qty.format(it.quantity) + " " + it.unit.lowercase(), it.total) })
    }

    override suspend fun recentActivity(limit: Int, partyId: String?): List<String> {
        val fmt = java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.ENGLISH)
        val sym = db.metaDao().business()?.currencySymbol ?: "₹"
        if (partyId == null) {
            return db.inventoryDao().recent(limit.coerceIn(1, 30)).map {
                val delta = it.txn.afterQuantity - it.txn.beforeQuantity
                "${fmt.format(java.util.Date(it.txn.createdAt))}: ${it.txn.type} ${it.productName} ${if (delta > 0) "+" else "-"}${Qty.format(Math.abs(delta))} ${it.txn.unit.lowercase()} (stock ${Qty.format(it.txn.afterQuantity)})"
            }
        }
        val lines = ArrayList<Pair<Long, String>>()
        for (s in db.tradeDao().salesBetween(0, Long.MAX_VALUE)) if (s.sale.customerId == partyId) {
            lines.add(s.sale.date to "${fmt.format(java.util.Date(s.sale.date))}: sale ${s.sale.invoiceNumber} ${s.itemSummary.orEmpty()} ${Money.format(s.sale.total, sym)} (${s.sale.status})")
        }
        for (s in db.tradeDao().purchasesBetween(0, Long.MAX_VALUE)) if (s.purchase.supplierId == partyId) {
            lines.add(s.purchase.date to "${fmt.format(java.util.Date(s.purchase.date))}: purchase ${s.purchase.purchaseNumber} ${s.itemSummary.orEmpty()} ${Money.format(s.purchase.total, sym)} (${s.purchase.status})")
        }
        return lines.sortedByDescending { it.first }.take(limit.coerceIn(1, 30)).map { it.second }
    }

    /** Plain-text summary of the business for a conversational AI model. Read-only; never contains the PIN or keys. */
    suspend fun snapshotText(symbol: String): String {
        fun m(p: Long) = Money.format(p, symbol)
        val today = DateRanges.range(DateKey.TODAY)
        val month = DateRanges.range(DateKey.THIS_MONTH)
        val sb = StringBuilder()
        db.metaDao().business()?.let { sb.append("Business: ${it.name}, owner ${it.ownerName}\n") }
        val st = db.tradeDao().saleTotals(today.start, today.end)
        val sm = db.tradeDao().saleTotals(month.start, month.end)
        val pt = db.tradeDao().purchaseTotals(today.start, today.end)
        val et = expenses(today.start, today.end)
        sb.append("Today: sales ${m(st.total)} (${st.count}), purchases ${m(pt.total)} (${pt.count}), expenses ${m(et.totalPaise)}. This month sales ${m(sm.total)} (${sm.count}).\n")
        val prods = products()
        sb.append("Products (${prods.size}) as name | stock | minimum | selling price | cost:\n")
        for (p in prods.take(200)) {
            sb.append("- ${p.name} | ${Qty.format(p.stockMilli)} ${p.unit}${if (p.isLow) " LOW" else ""} | ${Qty.format(p.minMilli)} | ${m(p.sellPaise)} | ${m(p.buyPaise)}\n")
        }
        val cust = parties(PartyKind.CUSTOMER)
        sb.append("Customers (${cust.size}) as name | owes you:\n")
        for (c in cust.take(100)) sb.append("- ${c.name} | ${m(c.balancePaise)}\n")
        val sup = parties(PartyKind.SUPPLIER)
        sb.append("Suppliers (${sup.size}) as name | you owe:\n")
        for (c in sup.take(100)) sb.append("- ${c.name} | ${m(c.balancePaise)}\n")
        return sb.toString()
    }

    override suspend fun audit(intent: String, command: String, result: String, status: String) {
        val user = db.metaDao().user()
        db.metaDao().insertAudit(
            AuditLogEntity(newId(), user?.id.orEmpty(), conversationId, intent, command.take(2000), result.take(500), status, System.currentTimeMillis())
        )
    }
}
