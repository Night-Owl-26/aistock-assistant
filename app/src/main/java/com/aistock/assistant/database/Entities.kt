package com.aistock.assistant.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Conventions: ids are UUID strings, timestamps are epoch milliseconds,
// money is whole paise (Long), quantities are thousandths of a unit (Long).

@Entity(tableName = "business")
data class BusinessEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ownerName: String,
    val currency: String,
    val currencySymbol: String,
    val language: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val name: String,
    val pinHash: String,
    val pinSalt: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "categories", indices = [Index(value = ["name"])])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "products",
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("name"), Index("categoryId"), Index("barcode"), Index("productCode"), Index("active")],
)
data class ProductEntity(
    @PrimaryKey val id: String,
    val productCode: String,
    val name: String,
    val description: String,
    val categoryId: String?,
    val unit: String,
    val barcode: String,
    val purchasePrice: Long,
    val sellingPrice: Long,
    val minimumStock: Long,
    val currentStock: Long,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "customers", indices = [Index("name"), Index("phone")])
data class CustomerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String,
    val email: String,
    val address: String,
    val openingBalance: Long,
    val notes: String,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "suppliers", indices = [Index("name"), Index("phone")])
data class SupplierEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String,
    val email: String,
    val address: String,
    val openingBalance: Long,
    val notes: String,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "sales",
    foreignKeys = [ForeignKey(entity = CustomerEntity::class, parentColumns = ["id"], childColumns = ["customerId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("customerId"), Index("date"), Index("invoiceNumber")],
)
data class SaleEntity(
    @PrimaryKey val id: String,
    val invoiceNumber: String,
    val customerId: String?,
    val date: Long,
    val subtotal: Long,
    val discount: Long,
    val tax: Long,
    val total: Long,
    val paidAmount: Long,
    val balanceAmount: Long,
    /** PAID, PARTIAL, UNPAID or CANCELLED */
    val status: String,
    val notes: String,
    val createdAt: Long,
)

@Entity(
    tableName = "sale_items",
    foreignKeys = [
        ForeignKey(entity = SaleEntity::class, parentColumns = ["id"], childColumns = ["saleId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("saleId"), Index("productId")],
)
data class SaleItemEntity(
    @PrimaryKey val id: String,
    val saleId: String,
    val productId: String,
    val quantity: Long,
    val unit: String,
    val unitPrice: Long,
    val discount: Long,
    val tax: Long,
    val total: Long,
)

@Entity(
    tableName = "purchases",
    foreignKeys = [ForeignKey(entity = SupplierEntity::class, parentColumns = ["id"], childColumns = ["supplierId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("supplierId"), Index("date"), Index("purchaseNumber")],
)
data class PurchaseEntity(
    @PrimaryKey val id: String,
    val purchaseNumber: String,
    val supplierId: String?,
    val date: Long,
    val subtotal: Long,
    val discount: Long,
    val tax: Long,
    val total: Long,
    val paidAmount: Long,
    val balanceAmount: Long,
    val status: String,
    val notes: String,
    val createdAt: Long,
)

@Entity(
    tableName = "purchase_items",
    foreignKeys = [
        ForeignKey(entity = PurchaseEntity::class, parentColumns = ["id"], childColumns = ["purchaseId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("purchaseId"), Index("productId")],
)
data class PurchaseItemEntity(
    @PrimaryKey val id: String,
    val purchaseId: String,
    val productId: String,
    val quantity: Long,
    val unit: String,
    val unitPrice: Long,
    val discount: Long,
    val tax: Long,
    val total: Long,
)

@Entity(tableName = "expenses", indices = [Index("date"), Index("category")])
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val category: String,
    val description: String,
    val amount: Long,
    val paymentMethod: String,
    val date: Long,
    val notes: String,
    /** ACTIVE or CANCELLED. Expenses are never physically deleted. */
    val status: String,
    val createdAt: Long,
)

@Entity(tableName = "payments", indices = [Index(value = ["partyType", "partyId"]), Index("date")])
data class PaymentEntity(
    @PrimaryKey val id: String,
    /** CUSTOMER (money received) or SUPPLIER (money paid) */
    val partyType: String,
    val partyId: String,
    val amount: Long,
    val paymentMethod: String,
    val reference: String,
    val date: Long,
    val notes: String,
    /** ACTIVE or CANCELLED */
    val status: String,
    val createdAt: Long,
)

@Entity(
    tableName = "inventory_transactions",
    foreignKeys = [ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("productId"), Index("createdAt"), Index(value = ["referenceType", "referenceId"])],
)
data class InventoryTransactionEntity(
    @PrimaryKey val id: String,
    val productId: String,
    /** OPENING, PURCHASE, SALE, ADJUSTMENT_IN, ADJUSTMENT_OUT, DAMAGE, RETURN_IN, RETURN_OUT, OTHER */
    val type: String,
    /** Always positive; the type and before/after show the direction. */
    val quantity: Long,
    val unit: String,
    val beforeQuantity: Long,
    val afterQuantity: Long,
    val unitCost: Long?,
    val referenceType: String,
    val referenceId: String,
    val notes: String,
    val createdAt: Long,
)

@Entity(tableName = "conversations", indices = [Index("updatedAt")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "conversation_messages",
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId"), Index("createdAt")],
)
data class ConversationMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    /** user or assistant */
    val role: String,
    val text: String,
    val intent: String,
    val metadata: String,
    val createdAt: Long,
)

@Entity(tableName = "audit_logs", indices = [Index("createdAt")])
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val conversationId: String,
    val intent: String,
    val command: String,
    val result: String,
    val status: String,
    val createdAt: Long,
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)
