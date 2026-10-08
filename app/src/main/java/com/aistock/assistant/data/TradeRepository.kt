package com.aistock.assistant.data

import com.aistock.assistant.database.AppDatabase
import com.aistock.assistant.database.AppSettingEntity
import com.aistock.assistant.database.ExpenseEntity
import com.aistock.assistant.database.PaymentEntity
import com.aistock.assistant.database.PurchaseEntity
import com.aistock.assistant.database.PurchaseItemEntity
import com.aistock.assistant.database.SaleEntity
import com.aistock.assistant.database.SaleItemEntity
import com.aistock.assistant.utils.Money
import javax.inject.Inject
import javax.inject.Singleton

data class LineInput(val productId: String, val quantity: Long, val unitPrice: Long)

object Status {
    const val PAID = "PAID"
    const val PARTIAL = "PARTIAL"
    const val UNPAID = "UNPAID"
    const val CANCELLED = "CANCELLED"
    const val ACTIVE = "ACTIVE"
}

/** Sales, purchases, expenses and payments. Each operation is one all-or-nothing database transaction. */
@Singleton
class TradeRepository @Inject constructor(
    private val db: AppDatabase,
    private val inventory: InventoryRepository,
) {
    private val dao get() = db.tradeDao()
    private val products get() = db.productDao()
    private val parties get() = db.partyDao()
    private val meta get() = db.metaDao()

    private suspend fun nextNumber(key: String, prefix: String): String {
        val n = (meta.setting(key)?.toLongOrNull() ?: 0L) + 1
        meta.putSetting(AppSettingEntity(key, n.toString(), System.currentTimeMillis()))
        return prefix + "-" + n.toString().padStart(4, '0')
    }

    private fun statusFor(total: Long, paid: Long) = when {
        paid >= total -> Status.PAID
        paid > 0 -> Status.PARTIAL
        else -> Status.UNPAID
    }

    // ------------------------------------------------------------ sales

    /**
     * @param paid amount received now; null means paid in full
     * @param totalOverride agreed total for a single-line sale (e.g. "for 1000 rupees")
     */
    suspend fun createSale(
        customerId: String?, lines: List<LineInput>, discount: Long = 0, tax: Long = 0, paid: Long? = null,
        notes: String = "", date: Long = System.currentTimeMillis(), totalOverride: Long? = null,
    ): Op<SaleEntity> = db.op {
        if (lines.isEmpty() || discount < 0 || tax < 0) fail(ErrCode.INVALID)
        if (customerId != null) parties.customer(customerId) ?: fail(ErrCode.NOT_FOUND)
        val id = newId()
        val items = lines.map { l ->
            if (l.quantity <= 0 || l.unitPrice < 0) fail(ErrCode.INVALID)
            val p = products.byId(l.productId) ?: fail(ErrCode.NOT_FOUND)
            val lineTotal = if (totalOverride != null && lines.size == 1) totalOverride else Money.lineTotal(l.quantity, l.unitPrice)
            SaleItemEntity(newId(), id, p.id, l.quantity, p.unit, l.unitPrice, 0, 0, lineTotal)
        }
        val subtotal = items.sumOf { it.total }
        val total = subtotal - discount + tax
        if (total < 0) fail(ErrCode.INVALID)
        val paidNow = paid ?: total
        if (paidNow < 0 || paidNow > total) fail(ErrCode.INVALID)
        val balance = total - paidNow
        if (balance > 0 && customerId == null) fail(ErrCode.NEEDS_PARTY)
        val sale = SaleEntity(
            id, nextNumber("sale_seq", "INV"), customerId, date, subtotal, discount, tax, total, paidNow, balance,
            statusFor(total, paidNow), notes.trim().take(300), System.currentTimeMillis(),
        )
        dao.insertSale(sale)
        dao.insertSaleItems(items)
        for (item in items) {
            val cost = products.byId(item.productId)?.purchasePrice
            inventory.move(item.productId, -item.quantity, TxnType.SALE, cost, "SALE", id, sale.invoiceNumber)
        }
        sale
    }

    /** Cancels a sale: the stock comes back through return movements and the sale stays on record as CANCELLED. */
    suspend fun cancelSale(id: String): Op<Unit> = db.op {
        val sale = dao.sale(id) ?: fail(ErrCode.NOT_FOUND)
        if (sale.status == Status.CANCELLED) fail(ErrCode.ALREADY_DONE)
        for (item in dao.saleItems(id)) {
            inventory.move(item.productId, item.quantity, TxnType.RETURN_IN, null, "SALE_CANCEL", id, "Cancelled ${sale.invoiceNumber}")
        }
        dao.updateSale(sale.copy(status = Status.CANCELLED))
    }

    // ------------------------------------------------------------ purchases

    suspend fun createPurchase(
        supplierId: String?, lines: List<LineInput>, discount: Long = 0, tax: Long = 0, paid: Long? = null,
        notes: String = "", date: Long = System.currentTimeMillis(), totalOverride: Long? = null,
    ): Op<PurchaseEntity> = db.op {
        if (lines.isEmpty() || discount < 0 || tax < 0) fail(ErrCode.INVALID)
        if (supplierId != null) parties.supplier(supplierId) ?: fail(ErrCode.NOT_FOUND)
        val id = newId()
        val items = lines.map { l ->
            if (l.quantity <= 0 || l.unitPrice < 0) fail(ErrCode.INVALID)
            val p = products.byId(l.productId) ?: fail(ErrCode.NOT_FOUND)
            val lineTotal = if (totalOverride != null && lines.size == 1) totalOverride else Money.lineTotal(l.quantity, l.unitPrice)
            PurchaseItemEntity(newId(), id, p.id, l.quantity, p.unit, l.unitPrice, 0, 0, lineTotal)
        }
        val subtotal = items.sumOf { it.total }
        val total = subtotal - discount + tax
        if (total < 0) fail(ErrCode.INVALID)
        val paidNow = paid ?: total
        if (paidNow < 0 || paidNow > total) fail(ErrCode.INVALID)
        val balance = total - paidNow
        if (balance > 0 && supplierId == null) fail(ErrCode.NEEDS_PARTY)
        val purchase = PurchaseEntity(
            id, nextNumber("purchase_seq", "PUR"), supplierId, date, subtotal, discount, tax, total, paidNow, balance,
            statusFor(total, paidNow), notes.trim().take(300), System.currentTimeMillis(),
        )
        dao.insertPurchase(purchase)
        dao.insertPurchaseItems(items)
        val now = System.currentTimeMillis()
        for (item in items) {
            inventory.move(item.productId, item.quantity, TxnType.PURCHASE, item.unitPrice, "PURCHASE", id, purchase.purchaseNumber)
            if (item.unitPrice > 0) products.setPurchasePrice(item.productId, item.unitPrice, now)
        }
        purchase
    }

    suspend fun cancelPurchase(id: String): Op<Unit> = db.op {
        val purchase = dao.purchase(id) ?: fail(ErrCode.NOT_FOUND)
        if (purchase.status == Status.CANCELLED) fail(ErrCode.ALREADY_DONE)
        for (item in dao.purchaseItems(id)) {
            inventory.move(item.productId, -item.quantity, TxnType.RETURN_OUT, item.unitPrice, "PURCHASE_CANCEL", id, "Cancelled ${purchase.purchaseNumber}")
        }
        dao.updatePurchase(purchase.copy(status = Status.CANCELLED))
    }

    // ------------------------------------------------------------ expenses

    suspend fun addExpense(
        category: String, description: String, amount: Long, paymentMethod: String = "CASH",
        date: Long = System.currentTimeMillis(), notes: String = "",
    ): Op<ExpenseEntity> = db.op {
        val c = category.trim()
        if (c.isEmpty() || c.length > 60 || amount <= 0) fail(ErrCode.INVALID)
        val e = ExpenseEntity(newId(), c, description.trim().take(200), amount, paymentMethod, date, notes.trim().take(300), Status.ACTIVE, System.currentTimeMillis())
        dao.insertExpense(e)
        e
    }

    suspend fun cancelExpense(id: String): Op<Unit> = db.op {
        val e = dao.expense(id) ?: fail(ErrCode.NOT_FOUND)
        if (e.status == Status.CANCELLED) fail(ErrCode.ALREADY_DONE)
        dao.setExpenseStatus(id, Status.CANCELLED)
    }

    // ------------------------------------------------------------ payments

    /** partyType CUSTOMER = money received from a customer; SUPPLIER = money paid to a supplier. */
    suspend fun addPayment(
        partyType: String, partyId: String, amount: Long, paymentMethod: String = "CASH", reference: String = "",
        date: Long = System.currentTimeMillis(), notes: String = "",
    ): Op<PaymentEntity> = db.op {
        if (amount <= 0) fail(ErrCode.INVALID)
        when (partyType) {
            "CUSTOMER" -> parties.customer(partyId) ?: fail(ErrCode.NOT_FOUND)
            "SUPPLIER" -> parties.supplier(partyId) ?: fail(ErrCode.NOT_FOUND)
            else -> fail(ErrCode.INVALID)
        }
        val p = PaymentEntity(newId(), partyType, partyId, amount, paymentMethod, reference.trim().take(80), date, notes.trim().take(300), Status.ACTIVE, System.currentTimeMillis())
        dao.insertPayment(p)
        p
    }

    suspend fun cancelPayment(id: String): Op<Unit> = db.op {
        val p = dao.payment(id) ?: fail(ErrCode.NOT_FOUND)
        if (p.status == Status.CANCELLED) fail(ErrCode.ALREADY_DONE)
        dao.setPaymentStatus(id, Status.CANCELLED)
    }

    // ------------------------------------------------------------ reads

    fun observeSales(query: String) = dao.observeSales(query.trim())
    fun observePurchases(query: String) = dao.observePurchases(query.trim())
    fun observeExpenses(query: String) = dao.observeExpenses(query.trim())
    fun observePayments() = dao.observePayments()
    suspend fun saleLines(id: String) = dao.saleLines(id)
    suspend fun purchaseLines(id: String) = dao.purchaseLines(id)
    suspend fun expenseCategories() = dao.expenseCategories()
}
