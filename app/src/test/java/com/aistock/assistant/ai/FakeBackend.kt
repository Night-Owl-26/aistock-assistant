package com.aistock.assistant.ai

import java.util.UUID

/** In-memory stand-in for the database so the conversation logic can be tested on its own. */
open class FakeBackend : AssistantBackend {
    var business: com.aistock.assistant.business.BusinessBackend? = null

    val productList = ArrayList<ProductRef>()
    val customers = ArrayList<PartyRef>()
    val suppliers = ArrayList<PartyRef>()
    val audits = ArrayList<List<String>>()
    val executed = ArrayList<Plan>()
    val expenseTotals = ArrayList<Long>()
    val saleTotals = ArrayList<Long>()
    val purchaseTotals = ArrayList<Long>()
    var failNext = false
    val aliasList = ArrayList<AliasRef>()
    /** sheet name -> rows, the first row being the header */
    val sheetData = LinkedHashMap<String, ArrayList<List<String>>>()
    val sheetPurpose = HashMap<String, String>()
    val actions = ArrayList<ActionRecord>()
    val activity = ArrayList<String>()
    private val undo = HashMap<String, () -> Unit>()

    fun addProduct(name: String, unit: String = "PCS", stock: Long = 0, min: Long = 0, sell: Long = 0, buy: Long = 0): ProductRef {
        val p = ProductRef(UUID.randomUUID().toString(), name, unit, stock * 1000, min * 1000, sell * 100, buy * 100)
        productList.add(p); return p
    }

    fun addCustomer(name: String): PartyRef {
        val c = PartyRef(UUID.randomUUID().toString(), name, "", 0, PartyKind.CUSTOMER); customers.add(c); return c
    }

    fun addSupplier(name: String): PartyRef {
        val c = PartyRef(UUID.randomUUID().toString(), name, "", 0, PartyKind.SUPPLIER); suppliers.add(c); return c
    }

    fun stock(name: String): Long = productList.first { it.name == name }.stockMilli / 1000

    private fun setStock(id: String, milli: Long) {
        val i = productList.indexOfFirst { it.id == id }
        productList[i] = productList[i].copy(stockMilli = milli)
    }

    private fun bump(list: ArrayList<PartyRef>, id: String, delta: Long) {
        val i = list.indexOfFirst { it.id == id }
        list[i] = list[i].copy(balancePaise = list[i].balancePaise + delta)
    }

    override suspend fun products(): List<ProductRef> = productList.toList()
    override suspend fun parties(kind: PartyKind): List<PartyRef> = if (kind == PartyKind.CUSTOMER) customers.toList() else suppliers.toList()

    override suspend fun execute(plan: Plan): ExecResult {
        if (failNext) { failNext = false; throw IllegalStateException("disk full") }
        executed.add(plan)
        val token = UUID.randomUUID().toString()
        when (plan) {
            is Plan.AddProduct -> {
                if (productList.any { it.name.equals(plan.name, true) }) return ExecResult(false, "DUPLICATE")
                productList.add(ProductRef(UUID.randomUUID().toString(), plan.name, plan.unit, plan.openingMilli, plan.minMilli ?: 0, plan.sellPaise ?: 0, plan.buyPaise ?: 0))
                return ExecResult(true, stockAfterMilli = plan.openingMilli)
            }
            is Plan.UpdateProduct -> {
                val i = productList.indexOfFirst { it.id == plan.product.id }
                if (i < 0) return ExecResult(false, "NOT_FOUND")
                val c = productList[i]
                productList[i] = c.copy(sellPaise = plan.sellPaise ?: c.sellPaise, buyPaise = plan.buyPaise ?: c.buyPaise, minMilli = plan.minMilli ?: c.minMilli)
                return ExecResult(true)
            }
            is Plan.StockIn -> {
                val prod = plan.product?.let { ref -> productList.first { it.id == ref.id } }
                    ?: ProductRef(UUID.randomUUID().toString(), plan.newProductName!!, plan.unit, 0, 0, 0, plan.unitCostPaise ?: 0).also { productList.add(it) }
                val before = prod.stockMilli
                setStock(prod.id, before + plan.qtyMilli)
                var supplierId: String? = null
                if (plan.asPurchase) {
                    val sup = plan.supplier ?: plan.newSupplierName?.let { addSupplier(it) }
                    supplierId = sup?.id
                    purchaseTotals.add(plan.totalPaise ?: 0)
                    if (sup != null && !plan.paid) bump(suppliers, sup.id, plan.totalPaise ?: 0)
                }
                undo[token] = {
                    setStock(prod.id, productList.first { it.id == prod.id }.stockMilli - plan.qtyMilli)
                    if (supplierId != null && !plan.paid) bump(suppliers, supplierId, -(plan.totalPaise ?: 0))
                }
                return ExecResult(true, undoToken = token, stockAfterMilli = before + plan.qtyMilli)
            }
            is Plan.StockOut -> {
                val prod = productList.firstOrNull { it.id == plan.product.id } ?: return ExecResult(false, "NOT_FOUND")
                if (prod.stockMilli < plan.qtyMilli) return ExecResult(false, "INSUFFICIENT_STOCK")
                setStock(prod.id, prod.stockMilli - plan.qtyMilli)
                undo[token] = { setStock(prod.id, productList.first { it.id == prod.id }.stockMilli + plan.qtyMilli) }
                return ExecResult(true, undoToken = token, stockAfterMilli = prod.stockMilli - plan.qtyMilli)
            }
            is Plan.StockAdjust -> {
                val prod = productList.firstOrNull { it.id == plan.product.id } ?: return ExecResult(false, "NOT_FOUND")
                val before = prod.stockMilli
                setStock(prod.id, plan.newQtyMilli)
                undo[token] = { setStock(prod.id, before) }
                return ExecResult(true, undoToken = token, stockAfterMilli = plan.newQtyMilli)
            }
            is Plan.Sale -> {
                val prod = productList.firstOrNull { it.id == plan.product.id } ?: return ExecResult(false, "NOT_FOUND")
                if (prod.stockMilli < plan.qtyMilli) return ExecResult(false, "INSUFFICIENT_STOCK")
                setStock(prod.id, prod.stockMilli - plan.qtyMilli)
                val cust = plan.customer ?: plan.newCustomerName?.let { addCustomer(it) }
                saleTotals.add(plan.totalPaise)
                if (cust != null && !plan.paid) bump(customers, cust.id, plan.totalPaise)
                undo[token] = {
                    setStock(prod.id, productList.first { it.id == prod.id }.stockMilli + plan.qtyMilli)
                    if (cust != null && !plan.paid) bump(customers, cust.id, -plan.totalPaise)
                }
                return ExecResult(true, undoToken = token, stockAfterMilli = prod.stockMilli - plan.qtyMilli)
            }
            is Plan.CreateParty -> {
                val list = if (plan.kind == PartyKind.CUSTOMER) customers else suppliers
                if (list.any { it.name.equals(plan.name, true) }) return ExecResult(false, "DUPLICATE")
                list.add(PartyRef(UUID.randomUUID().toString(), plan.name, plan.phone, 0, plan.kind))
                return ExecResult(true)
            }
            is Plan.Expense -> { expenseTotals.add(plan.amountPaise); return ExecResult(true) }
            is Plan.Payment -> {
                val list = if (plan.party.kind == PartyKind.CUSTOMER) customers else suppliers
                bump(list, plan.party.id, -plan.amountPaise)
                undo[token] = { bump(list, plan.party.id, plan.amountPaise) }
                return ExecResult(true, undoToken = token)
            }
            is Plan.UndoLast -> return undo(plan.token)
            is Plan.CreateSheet -> {
                if (sheetData.keys.any { it.equals(plan.name, true) }) return ExecResult(false, "DUPLICATE")
                sheetData[plan.name] = arrayListOf(plan.columns.toList())
                return ExecResult(true)
            }
            is Plan.AddColumn -> {
                val rows = sheetData[plan.sheet] ?: return ExecResult(false, "NOT_FOUND")
                for (i in rows.indices) rows[i] = rows[i] + (if (i == 0) plan.column else "")
                return ExecResult(true)
            }
            is Plan.AddRow -> {
                val rows = sheetData[plan.sheet] ?: return ExecResult(false, "NOT_FOUND")
                rows.add(rows[0].map { plan.values[it].orEmpty() })
                return ExecResult(true)
            }
            is Plan.RememberAlias -> { aliasList.removeAll { it.phrase.equals(plan.phrase, true) }; aliasList.add(AliasRef(plan.phrase, plan.productName)); return ExecResult(true) }
            is Plan.Business -> return business?.execute(plan.action) ?: ExecResult(false, "FAILED")
        }
    }

    override suspend fun undo(token: String): ExecResult {
        val action = undo.remove(token) ?: return ExecResult(false, "NOT_FOUND")
        action()
        return ExecResult(true)
    }

    override suspend fun sales(from: Long, to: Long) = TxnSummary(saleTotals.size, saleTotals.sum(), 0, emptyList())
    override suspend fun purchases(from: Long, to: Long) = TxnSummary(purchaseTotals.size, purchaseTotals.sum(), 0, emptyList())
    override suspend fun expenses(from: Long, to: Long) = TxnSummary(expenseTotals.size, expenseTotals.sum(), 0, emptyList())
    override suspend fun stockSummary() = StockSummary(productList.size, 0, productList.count { it.isLow }, productList.count { it.stockMilli <= 0 })
    override suspend fun audit(intent: String, command: String, result: String, status: String) {
        audits.add(listOf(intent, command, result, status))
    }

    override suspend fun aliases(): List<AliasRef> = aliasList.toList()
    override suspend fun sheets(): List<SheetRef> = sheetData.map { SheetRef(it.key, sheetPurpose[it.key] ?: "OTHER", it.value[0], it.value.size - 1) }
    override suspend fun sheetRows(sheet: String, query: String, limit: Int): List<List<String>> {
        val rows = sheetData.entries.firstOrNull { it.key.equals(sheet, true) }?.value ?: return emptyList()
        return listOf(rows[0]) + rows.drop(1).filter { r -> query.isBlank() || r.any { it.contains(query, true) } }.take(limit)
    }
    override suspend fun recordAction(record: ActionRecord) { actions.add(record) }
    override suspend fun recentActivity(limit: Int, partyId: String?): List<String> = activity.take(limit)
    override suspend fun partySales(customerId: String, from: Long, to: Long) = TxnSummary(1, 420_000, 0, emptyList())
}
