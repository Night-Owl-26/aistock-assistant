package com.aistock.assistant.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BusinessEntity::class, UserEntity::class, CategoryEntity::class, ProductEntity::class,
        CustomerEntity::class, SupplierEntity::class, SaleEntity::class, SaleItemEntity::class,
        PurchaseEntity::class, PurchaseItemEntity::class, ExpenseEntity::class, PaymentEntity::class,
        InventoryTransactionEntity::class, ConversationEntity::class, ConversationMessageEntity::class,
        AuditLogEntity::class, AppSettingEntity::class,
        BusinessAliasEntity::class, AiActionEntity::class, SheetEntity::class, SheetRowEntity::class,
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
    abstract fun stockyDao(): StockyDao

    companion object {
        const val VERSION = 2
        const val NAME = "aistock.db"

        /**
         * Schema changes must be added here as real migrations (1 -> 2, 2 -> 3 ...).
         * Destructive fallback is deliberately NOT enabled: user data is never dropped on upgrade.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // V2 only adds tables for Stocky; nothing existing is touched.
                db.execSQL("CREATE TABLE IF NOT EXISTS `business_aliases` (`id` TEXT NOT NULL, `phrase` TEXT NOT NULL, `phraseKey` TEXT NOT NULL, `productName` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `ai_actions` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `command` TEXT NOT NULL, `intent` TEXT NOT NULL, `action` TEXT NOT NULL, `subject` TEXT NOT NULL, `beforeValue` TEXT NOT NULL, `afterValue` TEXT NOT NULL, `confirmed` INTEGER NOT NULL, `result` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `workbook_sheets` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `purpose` TEXT NOT NULL, `columns` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `workbook_rows` (`id` TEXT NOT NULL, `sheetId` TEXT NOT NULL, `position` INTEGER NOT NULL, `cells` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        /** Every table, parents before children, used by backup and restore. */
        val TABLES = listOf(
            "business", "users", "app_settings", "categories", "products", "customers", "suppliers",
            "sales", "sale_items", "purchases", "purchase_items", "expenses", "payments",
            "inventory_transactions", "conversations", "conversation_messages", "audit_logs",
            "business_aliases", "ai_actions", "workbook_sheets", "workbook_rows",
        )
    }
}
