package com.aistock.assistant.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        BusinessEntity::class, UserEntity::class, CategoryEntity::class, ProductEntity::class,
        CustomerEntity::class, SupplierEntity::class, SaleEntity::class, SaleItemEntity::class,
        PurchaseEntity::class, PurchaseItemEntity::class, ExpenseEntity::class, PaymentEntity::class,
        InventoryTransactionEntity::class, ConversationEntity::class, ConversationMessageEntity::class,
        AuditLogEntity::class, AppSettingEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun categoryDao(): CategoryDao
    abstract fun partyDao(): PartyDao
    abstract fun tradeDao(): TradeDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun conversationDao(): ConversationDao
    abstract fun metaDao(): MetaDao

    companion object {
        const val VERSION = 1
        const val NAME = "aistock.db"

        /**
         * Schema changes must be added here as real migrations (1 -> 2, 2 -> 3 ...).
         * Destructive fallback is deliberately NOT enabled: user data is never dropped on upgrade.
         */
        val MIGRATIONS: Array<Migration> = arrayOf()

        /** Every table, parents before children, used by backup and restore. */
        val TABLES = listOf(
            "business", "users", "app_settings", "categories", "products", "customers", "suppliers",
            "sales", "sale_items", "purchases", "purchase_items", "expenses", "payments",
            "inventory_transactions", "conversations", "conversation_messages", "audit_logs",
        )
    }
}
