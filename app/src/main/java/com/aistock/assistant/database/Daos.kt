package com.aistock.assistant.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class ProductWithCategory(@Embedded val product: ProductEntity, val categoryName: String?)
data class CategoryWithCount(@Embedded val category: CategoryEntity, val productCount: Int)
data class CustomerWithBalance(@Embedded val customer: CustomerEntity, val balance: Long)
data class SupplierWithBalance(@Embedded val supplier: SupplierEntity, val balance: Long)
data class SaleWithParty(@Embedded val sale: SaleEntity, val partyName: String?, val itemSummary: String?)
data class PurchaseWithParty(@Embedded val purchase: PurchaseEntity, val partyName: String?, val itemSummary: String?)
data class ItemWithProduct(val productId: String, val productName: String, val quantity: Long, val unit: String, val unitPrice: Long, val total: Long)
data class MovementWithProduct(@Embedded val txn: InventoryTransactionEntity, val productName: String)
data class PaymentWithParty(@Embedded val payment: PaymentEntity, val partyName: String?)
data class CategoryTotal(val category: String, val count: Int, val total: Long)
data class ProductTotal(val productName: String, val unit: String, val quantity: Long, val total: Long)
data class MoneyTotals(val count: Int, val total: Long, val balance: Long)
data class ConversationSummary(@Embedded val conversation: ConversationEntity, val messageCount: Int, val lastText: String?)

@Dao
interface ProductDao {
    @Insert suspend fun insert(p: ProductEntity)
    @Insert suspend fun insertAll(p: List<ProductEntity>)
    @Update suspend fun update(p: ProductEntity)
    @Query("DELETE FROM products WHERE id = :id") suspend fun delete(id: String)

    @Query("SELECT * FROM products WHERE id = :id") suspend fun byId(id: String): ProductEntity?
    @Query("SELECT * FROM products WHERE active = 1 ORDER BY name COLLATE NOCASE") suspend fun active(): List<ProductEntity>
    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE") suspend fun all(): List<ProductEntity>
    @Query("SELECT COUNT(*) FROM products") suspend fun count(): Int

    @Query(
        "SELECT p.*, c.name AS categoryName FROM products p LEFT JOIN categories c ON c.id = p.categoryId " +
            "WHERE p.active = 1 AND (:q = '' OR p.name LIKE '%' || :q || '%' OR p.productCode LIKE '%' || :q || '%' OR p.barcode LIKE '%' || :q || '%' OR c.name LIKE '%' || :q || '%') " +
            "ORDER BY p.name COLLATE NOCASE"
    )
    fun observe(q: String): Flow<List<ProductWithCategory>>

    @Query("SELECT * FROM products WHERE active = 1 AND currentStock <= minimumStock ORDER BY name COLLATE NOCASE")
    fun observeLow(): Flow<List<ProductEntity>>

    @Query("SELECT COUNT(*) FROM products WHERE active = 1 AND currentStock <= minimumStock") fun observeLowCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM products WHERE active = 1") fun observeCount(): Flow<Int>
    @Query("SELECT COALESCE(SUM((currentStock * purchasePrice + 500) / 1000), 0) FROM products WHERE active = 1 AND currentStock > 0") fun observeStockValue(): Flow<Long>
    @Query("SELECT COALESCE(SUM((currentStock * purchasePrice + 500) / 1000), 0) FROM products WHERE active = 1 AND currentStock > 0") suspend fun stockValue(): Long

    @Query("UPDATE products SET currentStock = :stock, updatedAt = :now WHERE id = :id") suspend fun setStock(id: String, stock: Long, now: Long)
    @Query("UPDATE products SET purchasePrice = :price, updatedAt = :now WHERE id = :id") suspend fun setPurchasePrice(id: String, price: Long, now: Long)
    @Query("UPDATE products SET active = :active, updatedAt = :now WHERE id = :id") suspend fun setActive(id: String, active: Boolean, now: Long)
    @Query("SELECT COUNT(*) FROM inventory_transactions WHERE productId = :id") suspend fun movementCount(id: String): Int
}

@Dao
interface CategoryDao {
    @Insert suspend fun insert(c: CategoryEntity)
    @Update suspend fun update(c: CategoryEntity)
    @Query("DELETE FROM categories WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM categories WHERE id = :id") suspend fun byId(id: String): CategoryEntity?
    @Query("SELECT * FROM categories WHERE active = 1 ORDER BY name COLLATE NOCASE") suspend fun active(): List<CategoryEntity>
    @Query("SELECT * FROM categories WHERE active = 1 ORDER BY name COLLATE NOCASE") fun observe(): Flow<List<CategoryEntity>>
    @Query(
        "SELECT c.*, (SELECT COUNT(*) FROM products p WHERE p.categoryId = c.id AND p.active = 1) AS productCount " +
            "FROM categories c WHERE c.active = 1 ORDER BY c.name COLLATE NOCASE"
    )
    fun observeWithCount(): Flow<List<CategoryWithCount>>
}

private const val CUSTOMER_BALANCE =
    "(c.openingBalance + COALESCE((SELECT SUM(s.balanceAmount) FROM sales s WHERE s.customerId = c.id AND s.status != 'CANCELLED'), 0) " +
        "- COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.partyType = 'CUSTOMER' AND p.partyId = c.id AND p.status = 'ACTIVE'), 0))"
private const val SUPPLIER_BALANCE =
    "(c.openingBalance + COALESCE((SELECT SUM(s.balanceAmount) FROM purchases s WHERE s.supplierId = c.id AND s.status != 'CANCELLED'), 0) " +
        "- COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.partyType = 'SUPPLIER' AND p.partyId = c.id AND p.status = 'ACTIVE'), 0))"

@Dao
interface PartyDao {
    @Insert suspend fun insertCustomer(c: CustomerEntity)
    @Insert suspend fun insertCustomers(c: List<CustomerEntity>)
    @Update suspend fun updateCustomer(c: CustomerEntity)
    @Query("DELETE FROM customers WHERE id = :id") suspend fun deleteCustomer(id: String)
    @Query("SELECT * FROM customers WHERE id = :id") suspend fun customer(id: String): CustomerEntity?
    @Query("SELECT * FROM customers WHERE active = 1 ORDER BY name COLLATE NOCASE") suspend fun activeCustomers(): List<CustomerEntity>
    @Query("UPDATE customers SET active = :active, updatedAt = :now WHERE id = :id") suspend fun setCustomerActive(id: String, active: Boolean, now: Long)
    @Query("SELECT (SELECT COUNT(*) FROM sales WHERE customerId = :id) + (SELECT COUNT(*) FROM payments WHERE partyType = 'CUSTOMER' AND partyId = :id)")
    suspend fun customerHistoryCount(id: String): Int

    @Query(
        "SELECT c.*, $CUSTOMER_BALANCE AS balance FROM customers c WHERE c.active = 1 " +
            "AND (:q = '' OR c.name LIKE '%' || :q || '%' OR c.phone LIKE '%' || :q || '%') ORDER BY c.name COLLATE NOCASE"
    )
    fun observeCustomers(q: String): Flow<List<CustomerWithBalance>>

    @Query("SELECT c.*, $CUSTOMER_BALANCE AS balance FROM customers c WHERE c.active = 1 ORDER BY c.name COLLATE NOCASE")
    suspend fun customersWithBalance(): List<CustomerWithBalance>

    @Query("SELECT COALESCE(SUM(b), 0) FROM (SELECT $CUSTOMER_BALANCE AS b FROM customers c WHERE c.active = 1) WHERE b > 0")
    fun observeCustomerOutstanding(): Flow<Long>

    @Insert suspend fun insertSupplier(c: SupplierEntity)
    @Insert suspend fun insertSuppliers(c: List<SupplierEntity>)
    @Update suspend fun updateSupplier(c: SupplierEntity)
    @Query("DELETE FROM suppliers WHERE id = :id") suspend fun deleteSupplier(id: String)
    @Query("SELECT * FROM suppliers WHERE id = :id") suspend fun supplier(id: String): SupplierEntity?
    @Query("SELECT * FROM suppliers WHERE active = 1 ORDER BY name COLLATE NOCASE") suspend fun activeSuppliers(): List<SupplierEntity>
    @Query("UPDATE suppliers SET active = :active, updatedAt = :now WHERE id = :id") suspend fun setSupplierActive(id: String, active: Boolean, now: Long)
    @Query("SELECT (SELECT COUNT(*) FROM purchases WHERE supplierId = :id) + (SELECT COUNT(*) FROM payments WHERE partyType = 'SUPPLIER' AND partyId = :id)")
    suspend fun supplierHistoryCount(id: String): Int

    @Query(
        "SELECT c.*, $SUPPLIER_BALANCE AS balance FROM suppliers c WHERE c.active = 1 " +
            "AND (:q = '' OR c.name LIKE '%' || :q || '%' OR c.phone LIKE '%' || :q || '%') ORDER BY c.name COLLATE NOCASE"
    )
    fun observeSuppliers(q: String): Flow<List<SupplierWithBalance>>

    @Query("SELECT c.*, $SUPPLIER_BALANCE AS balance FROM suppliers c WHERE c.active = 1 ORDER BY c.name COLLATE NOCASE")
    suspend fun suppliersWithBalance(): List<SupplierWithBalance>

    @Query("SELECT COALESCE(SUM(b), 0) FROM (SELECT $SUPPLIER_BALANCE AS b FROM suppliers c WHERE c.active = 1) WHERE b > 0")
    fun observeSupplierOutstanding(): Flow<Long>
}

private const val SALE_ITEMS_SUMMARY =
    "(SELECT GROUP_CONCAT(pp.name, ', ') FROM sale_items ii JOIN products pp ON pp.id = ii.productId WHERE ii.saleId = s.id)"
private const val PURCHASE_ITEMS_SUMMARY =
    "(SELECT GROUP_CONCAT(pp.name, ', ') FROM purchase_items ii JOIN products pp ON pp.id = ii.productId WHERE ii.purchaseId = s.id)"

@Dao
interface TradeDao {
    // ---- sales
    @Insert suspend fun insertSale(s: SaleEntity)
    @Insert suspend fun insertSaleItems(items: List<SaleItemEntity>)
    @Update suspend fun updateSale(s: SaleEntity)
    @Query("SELECT * FROM sales WHERE id = :id") suspend fun sale(id: String): SaleEntity?
    @Query("SELECT * FROM sale_items WHERE saleId = :id") suspend fun saleItems(id: String): List<SaleItemEntity>
    @Query("SELECT i.productId AS productId, p.name AS productName, i.quantity AS quantity, i.unit AS unit, i.unitPrice AS unitPrice, i.total AS total FROM sale_items i JOIN products p ON p.id = i.productId WHERE i.saleId = :id")
    suspend fun saleLines(id: String): List<ItemWithProduct>

    @Query(
        "SELECT s.*, c.name AS partyName, $SALE_ITEMS_SUMMARY AS itemSummary FROM sales s LEFT JOIN customers c ON c.id = s.customerId " +
            "WHERE (:q = '' OR s.invoiceNumber LIKE '%' || :q || '%' OR c.name LIKE '%' || :q || '%' OR $SALE_ITEMS_SUMMARY LIKE '%' || :q || '%') " +
            "ORDER BY s.date DESC, s.createdAt DESC LIMIT 500"
    )
    fun observeSales(q: String): Flow<List<SaleWithParty>>

    @Query(
        "SELECT s.*, c.name AS partyName, $SALE_ITEMS_SUMMARY AS itemSummary FROM sales s LEFT JOIN customers c ON c.id = s.customerId " +
            "WHERE s.date >= :from AND s.date < :to ORDER BY s.date DESC, s.createdAt DESC"
    )
    suspend fun salesBetween(from: Long, to: Long): List<SaleWithParty>

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(total), 0) AS total, COALESCE(SUM(balanceAmount), 0) AS balance FROM sales WHERE status != 'CANCELLED' AND date >= :from AND date < :to")
    suspend fun saleTotals(from: Long, to: Long): MoneyTotals

    @Query("SELECT COALESCE(SUM(total), 0) FROM sales WHERE status != 'CANCELLED' AND date >= :from AND date < :to")
    fun observeSaleTotal(from: Long, to: Long): Flow<Long>

    @Query(
        "SELECT p.name AS productName, i.unit AS unit, SUM(i.quantity) AS quantity, SUM(i.total) AS total FROM sale_items i " +
            "JOIN sales s ON s.id = i.saleId JOIN products p ON p.id = i.productId " +
            "WHERE s.status != 'CANCELLED' AND s.date >= :from AND s.date < :to GROUP BY i.productId, i.unit ORDER BY total DESC"
    )
    suspend fun salesByProduct(from: Long, to: Long): List<ProductTotal>

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(total), 0) AS total, COALESCE(SUM(balanceAmount), 0) AS balance FROM sales WHERE status != 'CANCELLED' AND customerId = :customerId AND date >= :from AND date < :to")
    suspend fun saleTotalsFor(customerId: String, from: Long, to: Long): MoneyTotals

    @Query(
        "SELECT p.name AS productName, i.unit AS unit, SUM(i.quantity) AS quantity, SUM(i.total) AS total FROM sale_items i " +
            "JOIN sales s ON s.id = i.saleId JOIN products p ON p.id = i.productId " +
            "WHERE s.status != 'CANCELLED' AND s.customerId = :customerId AND s.date >= :from AND s.date < :to GROUP BY i.productId, i.unit ORDER BY total DESC"
    )
    suspend fun salesByProductFor(customerId: String, from: Long, to: Long): List<ProductTotal>

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(total), 0) AS total, COALESCE(SUM(balanceAmount), 0) AS balance FROM purchases WHERE status != 'CANCELLED' AND supplierId = :supplierId AND date >= :from AND date < :to")
    suspend fun purchaseTotalsFor(supplierId: String, from: Long, to: Long): MoneyTotals

    @Query(
        "SELECT p.name AS productName, i.unit AS unit, SUM(i.quantity) AS quantity, SUM(i.total) AS total FROM purchase_items i " +
            "JOIN purchases s ON s.id = i.purchaseId JOIN products p ON p.id = i.productId " +
            "WHERE s.status != 'CANCELLED' AND s.supplierId = :supplierId AND s.date >= :from AND s.date < :to GROUP BY i.productId, i.unit ORDER BY total DESC"
    )
    suspend fun purchasesByProductFor(supplierId: String, from: Long, to: Long): List<ProductTotal>

    // ---- purchases
    @Insert suspend fun insertPurchase(s: PurchaseEntity)
    @Insert suspend fun insertPurchaseItems(items: List<PurchaseItemEntity>)
    @Update suspend fun updatePurchase(s: PurchaseEntity)
    @Query("SELECT * FROM purchases WHERE id = :id") suspend fun purchase(id: String): PurchaseEntity?
    @Query("SELECT * FROM purchase_items WHERE purchaseId = :id") suspend fun purchaseItems(id: String): List<PurchaseItemEntity>
    @Query("SELECT i.productId AS productId, p.name AS productName, i.quantity AS quantity, i.unit AS unit, i.unitPrice AS unitPrice, i.total AS total FROM purchase_items i JOIN products p ON p.id = i.productId WHERE i.purchaseId = :id")
    suspend fun purchaseLines(id: String): List<ItemWithProduct>

    @Query(
        "SELECT s.*, c.name AS partyName, $PURCHASE_ITEMS_SUMMARY AS itemSummary FROM purchases s LEFT JOIN suppliers c ON c.id = s.supplierId " +
            "WHERE (:q = '' OR s.purchaseNumber LIKE '%' || :q || '%' OR c.name LIKE '%' || :q || '%' OR $PURCHASE_ITEMS_SUMMARY LIKE '%' || :q || '%') " +
            "ORDER BY s.date DESC, s.createdAt DESC LIMIT 500"
    )
    fun observePurchases(q: String): Flow<List<PurchaseWithParty>>

    @Query(
        "SELECT s.*, c.name AS partyName, $PURCHASE_ITEMS_SUMMARY AS itemSummary FROM purchases s LEFT JOIN suppliers c ON c.id = s.supplierId " +
            "WHERE s.date >= :from AND s.date < :to ORDER BY s.date DESC, s.createdAt DESC"
    )
    suspend fun purchasesBetween(from: Long, to: Long): List<PurchaseWithParty>

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(total), 0) AS total, COALESCE(SUM(balanceAmount), 0) AS balance FROM purchases WHERE status != 'CANCELLED' AND date >= :from AND date < :to")
    suspend fun purchaseTotals(from: Long, to: Long): MoneyTotals

    @Query("SELECT COALESCE(SUM(total), 0) FROM purchases WHERE status != 'CANCELLED' AND date >= :from AND date < :to")
    fun observePurchaseTotal(from: Long, to: Long): Flow<Long>

    @Query(
        "SELECT p.name AS productName, i.unit AS unit, SUM(i.quantity) AS quantity, SUM(i.total) AS total FROM purchase_items i " +
            "JOIN purchases s ON s.id = i.purchaseId JOIN products p ON p.id = i.productId " +
            "WHERE s.status != 'CANCELLED' AND s.date >= :from AND s.date < :to GROUP BY i.productId, i.unit ORDER BY total DESC"
    )
    suspend fun purchasesByProduct(from: Long, to: Long): List<ProductTotal>

    // ---- expenses
    @Insert suspend fun insertExpense(e: ExpenseEntity)
    @Query("SELECT * FROM expenses WHERE id = :id") suspend fun expense(id: String): ExpenseEntity?
    @Query("UPDATE expenses SET status = :status WHERE id = :id") suspend fun setExpenseStatus(id: String, status: String)
    @Query("SELECT * FROM expenses WHERE (:q = '' OR category LIKE '%' || :q || '%' OR description LIKE '%' || :q || '%') ORDER BY date DESC, createdAt DESC LIMIT 500")
    fun observeExpenses(q: String): Flow<List<ExpenseEntity>>
    @Query("SELECT * FROM expenses WHERE date >= :from AND date < :to ORDER BY date DESC, createdAt DESC") suspend fun expensesBetween(from: Long, to: Long): List<ExpenseEntity>
    @Query("SELECT category AS category, COUNT(*) AS count, SUM(amount) AS total FROM expenses WHERE status = 'ACTIVE' AND date >= :from AND date < :to GROUP BY category ORDER BY total DESC")
    suspend fun expensesByCategory(from: Long, to: Long): List<CategoryTotal>
    @Query("SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE status = 'ACTIVE' AND date >= :from AND date < :to")
    fun observeExpenseTotal(from: Long, to: Long): Flow<Long>
    @Query("SELECT DISTINCT category FROM expenses ORDER BY category COLLATE NOCASE") suspend fun expenseCategories(): List<String>

    // ---- payments
    @Insert suspend fun insertPayment(p: PaymentEntity)
    @Query("SELECT * FROM payments WHERE id = :id") suspend fun payment(id: String): PaymentEntity?
    @Query("UPDATE payments SET status = :status WHERE id = :id") suspend fun setPaymentStatus(id: String, status: String)
    @Query(
        "SELECT p.*, CASE WHEN p.partyType = 'CUSTOMER' THEN (SELECT name FROM customers WHERE id = p.partyId) " +
            "ELSE (SELECT name FROM suppliers WHERE id = p.partyId) END AS partyName FROM payments p ORDER BY p.date DESC, p.createdAt DESC LIMIT 500"
    )
    fun observePayments(): Flow<List<PaymentWithParty>>
}

@Dao
interface InventoryDao {
    @Insert suspend fun insert(t: InventoryTransactionEntity)
    @Query("SELECT * FROM inventory_transactions WHERE id = :id") suspend fun byId(id: String): InventoryTransactionEntity?
    @Query("SELECT COUNT(*) FROM inventory_transactions WHERE referenceType = :type AND referenceId = :id") suspend fun countByReference(type: String, id: String): Int
    @Query("SELECT * FROM inventory_transactions WHERE referenceType = :type AND referenceId = :id") suspend fun byReference(type: String, id: String): List<InventoryTransactionEntity>
    @Query("SELECT * FROM inventory_transactions WHERE productId = :productId ORDER BY createdAt, rowid") suspend fun forProduct(productId: String): List<InventoryTransactionEntity>

    @Query(
        "SELECT t.*, p.name AS productName FROM inventory_transactions t JOIN products p ON p.id = t.productId " +
            "WHERE (:q = '' OR p.name LIKE '%' || :q || '%' OR t.type LIKE '%' || :q || '%' OR t.notes LIKE '%' || :q || '%') " +
            "ORDER BY t.createdAt DESC, t.rowid DESC LIMIT 500"
    )
    fun observe(q: String): Flow<List<MovementWithProduct>>

    @Query(
        "SELECT t.*, p.name AS productName FROM inventory_transactions t JOIN products p ON p.id = t.productId " +
            "WHERE t.createdAt >= :from AND t.createdAt < :to ORDER BY t.createdAt DESC, t.rowid DESC"
    )
    suspend fun between(from: Long, to: Long): List<MovementWithProduct>

    @Query("SELECT t.*, p.name AS productName FROM inventory_transactions t JOIN products p ON p.id = t.productId ORDER BY t.createdAt DESC, t.rowid DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MovementWithProduct>
}

@Dao
interface ConversationDao {
    @Insert suspend fun insert(c: ConversationEntity)
    @Insert suspend fun insertMessage(m: ConversationMessageEntity)
    @Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id") suspend fun touch(id: String, title: String, now: Long)
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun byId(id: String): ConversationEntity?
    @Query("SELECT * FROM conversation_messages WHERE conversationId = :id ORDER BY createdAt, rowid") suspend fun messages(id: String): List<ConversationMessageEntity>
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun delete(id: String)
    @Query(
        "SELECT c.*, (SELECT COUNT(*) FROM conversation_messages m WHERE m.conversationId = c.id) AS messageCount, " +
            "(SELECT m.text FROM conversation_messages m WHERE m.conversationId = c.id ORDER BY m.createdAt DESC, m.rowid DESC LIMIT 1) AS lastText " +
            "FROM conversations c ORDER BY c.updatedAt DESC LIMIT 200"
    )
    fun observe(): Flow<List<ConversationSummary>>
}

@Dao
interface MetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveBusiness(b: BusinessEntity)
    @Query("SELECT * FROM business LIMIT 1") suspend fun business(): BusinessEntity?
    @Query("SELECT * FROM business LIMIT 1") fun observeBusiness(): Flow<BusinessEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveUser(u: UserEntity)
    @Query("SELECT * FROM users LIMIT 1") suspend fun user(): UserEntity?
    @Query("SELECT * FROM users LIMIT 1") fun observeUser(): Flow<UserEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSetting(s: AppSettingEntity)
    @Query("SELECT value FROM app_settings WHERE `key` = :key") suspend fun setting(key: String): String?

    @Insert suspend fun insertAudit(a: AuditLogEntity)
    @Query("SELECT * FROM audit_logs ORDER BY createdAt DESC, rowid DESC LIMIT :limit") suspend fun audits(limit: Int): List<AuditLogEntity>
    @Query("SELECT COUNT(*) FROM audit_logs") suspend fun auditCount(): Int
}
