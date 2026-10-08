package com.aistock.assistant.data

import com.aistock.assistant.database.AppDatabase
import com.aistock.assistant.database.CategoryEntity
import com.aistock.assistant.database.InventoryTransactionEntity
import com.aistock.assistant.database.ProductEntity
import com.aistock.assistant.utils.NameMatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ProductInput(
    val name: String,
    val productCode: String = "",
    val description: String = "",
    val categoryId: String? = null,
    val unit: String = "PCS",
    val barcode: String = "",
    val purchasePrice: Long = 0,
    val sellingPrice: Long = 0,
    val minimumStock: Long = 0,
    val openingStock: Long = 0,
)

object TxnType {
    const val OPENING = "OPENING"
    const val PURCHASE = "PURCHASE"
    const val SALE = "SALE"
    const val ADJUSTMENT_IN = "ADJUSTMENT_IN"
    const val ADJUSTMENT_OUT = "ADJUSTMENT_OUT"
    const val DAMAGE = "DAMAGE"
    const val RETURN_IN = "RETURN_IN"
    const val RETURN_OUT = "RETURN_OUT"
    const val OTHER = "OTHER"
    val INCOMING = listOf(ADJUSTMENT_IN, RETURN_IN, OTHER)
    val OUTGOING = listOf(ADJUSTMENT_OUT, DAMAGE, RETURN_OUT, OTHER)
}

/** Products, categories and every stock movement. Stock is never changed without an inventory transaction. */
@Singleton
class InventoryRepository @Inject constructor(
    private val db: AppDatabase,
    private val rules: StockRules,
) {
    private val products get() = db.productDao()
    private val categories get() = db.categoryDao()
    private val inventory get() = db.inventoryDao()

    private val _lowStock = MutableSharedFlow<ProductEntity>(extraBufferCapacity = 16)
    /** Emits a product at the moment it drops to or below its minimum stock. */
    val lowStockEvents: SharedFlow<ProductEntity> = _lowStock

    // ------------------------------------------------------------ products

    suspend fun createProduct(input: ProductInput): Op<ProductEntity> = db.op {
        val name = input.name.trim()
        checkProduct(input, name)
        val key = NameMatcher.key(name)
        if (products.active().any { NameMatcher.key(it.name) == key }) fail(ErrCode.DUPLICATE)
        val now = System.currentTimeMillis()
        val id = newId()
        products.insert(
            ProductEntity(
                id = id, productCode = input.productCode.trim().ifEmpty { nextCode() }, name = name,
                description = input.description.trim(), categoryId = input.categoryId, unit = input.unit.trim().ifEmpty { "PCS" },
                barcode = input.barcode.trim(), purchasePrice = input.purchasePrice, sellingPrice = input.sellingPrice,
                minimumStock = input.minimumStock, currentStock = 0, active = true, createdAt = now, updatedAt = now,
            )
        )
        if (input.openingStock > 0) {
            move(id, input.openingStock, TxnType.OPENING, input.purchasePrice.takeIf { it > 0 }, "OPENING", id, "Opening stock")
        }
        products.byId(id) ?: fail(ErrCode.FAILED)
    }

    /** Updates the product details. Stock is not touched here: use a stock movement for that. */
    suspend fun updateProduct(id: String, input: ProductInput): Op<ProductEntity> = db.op {
        val existing = products.byId(id) ?: fail(ErrCode.NOT_FOUND)
        val name = input.name.trim()
        checkProduct(input, name)
        val key = NameMatcher.key(name)
        if (products.active().any { it.id != id && NameMatcher.key(it.name) == key }) fail(ErrCode.DUPLICATE)
        products.update(
            existing.copy(
                name = name, productCode = input.productCode.trim().ifEmpty { existing.productCode }, description = input.description.trim(),
                categoryId = input.categoryId, unit = input.unit.trim().ifEmpty { existing.unit }, barcode = input.barcode.trim(),
                purchasePrice = input.purchasePrice, sellingPrice = input.sellingPrice, minimumStock = input.minimumStock,
                updatedAt = System.currentTimeMillis(),
            )
        )
        products.byId(id) ?: fail(ErrCode.FAILED)
    }

    private fun checkProduct(input: ProductInput, name: String) {
        if (name.isEmpty() || name.length > 120) fail(ErrCode.INVALID)
        if (input.purchasePrice < 0 || input.sellingPrice < 0 || input.minimumStock < 0 || input.openingStock < 0) fail(ErrCode.INVALID)
    }

    private suspend fun nextCode(): String {
        var n = products.count() + 1
        val used = products.all().map { it.productCode }.toHashSet()
        while (true) {
            val code = "P" + n.toString().padStart(4, '0')
            if (code !in used) return code
            n++
        }
    }

    /**
     * Removes a product. With stock history it is only hidden (active = false) so records stay intact.
     * @return true when physically deleted, false when hidden.
     */
    suspend fun removeProduct(id: String): Op<Boolean> = db.op {
        products.byId(id) ?: fail(ErrCode.NOT_FOUND)
        if (products.movementCount(id) == 0) {
            products.delete(id); true
        } else {
            products.setActive(id, false, System.currentTimeMillis()); false
        }
    }

    suspend fun product(id: String): ProductEntity? = products.byId(id)
    suspend fun activeProducts(): List<ProductEntity> = products.active()
    fun observeProducts(query: String) = products.observe(query.trim())
    fun observeLowStock() = products.observeLow()

    // ------------------------------------------------------------ categories

    suspend fun saveCategory(id: String?, name: String, description: String): Op<CategoryEntity> = db.op {
        val n = name.trim()
        if (n.isEmpty() || n.length > 60) fail(ErrCode.INVALID)
        val key = NameMatcher.key(n)
        if (categories.active().any { it.id != id && NameMatcher.key(it.name) == key }) fail(ErrCode.DUPLICATE)
        val now = System.currentTimeMillis()
        if (id == null) {
            val c = CategoryEntity(newId(), n, description.trim(), true, now, now)
            categories.insert(c); c
        } else {
            val c = (categories.byId(id) ?: fail(ErrCode.NOT_FOUND)).copy(name = n, description = description.trim(), updatedAt = now)
            categories.update(c); c
        }
    }

    /** Finds a category by name or creates it. Used by import. Must run inside a transaction. */
    suspend fun categoryIdFor(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val key = NameMatcher.key(n)
        categories.active().firstOrNull { NameMatcher.key(it.name) == key }?.let { return it.id }
        val now = System.currentTimeMillis()
        val c = CategoryEntity(newId(), n.take(60), "", true, now, now)
        categories.insert(c)
        return c.id
    }

    suspend fun removeCategory(id: String): Op<Unit> = db.op { categories.delete(id) }
    fun observeCategories() = categories.observeWithCount()
    suspend fun activeCategories() = categories.active()

    // ------------------------------------------------------------ stock movements

    suspend fun stockIn(
        productId: String, quantity: Long, type: String = TxnType.ADJUSTMENT_IN, unitCost: Long? = null,
        notes: String = "", referenceType: String = "MANUAL", referenceId: String = "",
    ): Op<InventoryTransactionEntity> = db.op {
        if (quantity <= 0) fail(ErrCode.INVALID)
        move(productId, quantity, type, unitCost, referenceType, referenceId, notes)
    }

    suspend fun stockOut(
        productId: String, quantity: Long, type: String = TxnType.ADJUSTMENT_OUT,
        notes: String = "", referenceType: String = "MANUAL", referenceId: String = "",
    ): Op<InventoryTransactionEntity> = db.op {
        if (quantity <= 0) fail(ErrCode.INVALID)
        move(productId, -quantity, type, null, referenceType, referenceId, notes)
    }

    /** Sets the stock to a counted quantity and records the difference as an adjustment. */
    suspend fun adjustTo(productId: String, newQuantity: Long, notes: String = ""): Op<InventoryTransactionEntity> = db.op {
        if (newQuantity < 0) fail(ErrCode.INVALID)
        val p = products.byId(productId) ?: fail(ErrCode.NOT_FOUND)
        val delta = newQuantity - p.currentStock
        if (delta == 0L) fail(ErrCode.INVALID)
        move(productId, delta, if (delta > 0) TxnType.ADJUSTMENT_IN else TxnType.ADJUSTMENT_OUT, null, "ADJUSTMENT", "", notes.ifBlank { "Stock count" })
    }

    /** Reverses one stock movement by adding an opposite movement. The original record is left untouched. */
    suspend fun undoMovement(transactionId: String): Op<InventoryTransactionEntity> = db.op {
        val t = inventory.byId(transactionId) ?: fail(ErrCode.NOT_FOUND)
        if (t.referenceType == "UNDO") fail(ErrCode.INVALID)
        if (inventory.countByReference("UNDO", transactionId) > 0) fail(ErrCode.ALREADY_DONE)
        val delta = t.afterQuantity - t.beforeQuantity
        move(t.productId, -delta, if (delta > 0) TxnType.ADJUSTMENT_OUT else TxnType.ADJUSTMENT_IN, t.unitCost, "UNDO", transactionId, "Undo of ${t.type}")
    }

    /**
     * The single place where stock changes. Must be called inside a database transaction.
     * @param delta positive to add, negative to remove (thousandths of a unit)
     */
    suspend fun move(
        productId: String, delta: Long, type: String, unitCost: Long?,
        referenceType: String, referenceId: String, notes: String,
    ): InventoryTransactionEntity {
        if (delta == 0L) fail(ErrCode.INVALID)
        val p = products.byId(productId) ?: fail(ErrCode.NOT_FOUND)
        val after = p.currentStock + delta
        if (after < 0 && !rules.allowNegativeStock()) fail(ErrCode.INSUFFICIENT_STOCK)
        val now = System.currentTimeMillis()
        products.setStock(productId, after, now)
        val txn = InventoryTransactionEntity(
            id = newId(), productId = productId, type = type, quantity = Math.abs(delta), unit = p.unit,
            beforeQuantity = p.currentStock, afterQuantity = after, unitCost = unitCost,
            referenceType = referenceType, referenceId = referenceId, notes = notes.trim().take(300), createdAt = now,
        )
        inventory.insert(txn)
        if (after <= p.minimumStock && p.currentStock > p.minimumStock) _lowStock.tryEmit(p.copy(currentStock = after))
        return txn
    }

    fun observeMovements(query: String) = inventory.observe(query.trim())
    suspend fun movementsFor(productId: String) = inventory.forProduct(productId)
}
