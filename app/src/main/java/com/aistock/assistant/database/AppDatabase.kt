package com.aistock.assistant.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aistock.assistant.business.*

@Database(
    entities = [
        BusinessEntity::class, UserEntity::class, CategoryEntity::class, ProductEntity::class,
        CustomerEntity::class, SupplierEntity::class, SaleEntity::class, SaleItemEntity::class,
        PurchaseEntity::class, PurchaseItemEntity::class, ExpenseEntity::class, PaymentEntity::class,
        InventoryTransactionEntity::class, ConversationEntity::class, ConversationMessageEntity::class,
        AuditLogEntity::class, AppSettingEntity::class,
        BusinessAliasEntity::class, AiActionEntity::class, SheetEntity::class, SheetRowEntity::class,
        BusinessProfileEntity::class, StaffEntity::class, WorkItemEntity::class, WorkEventEntity::class,
        FollowupEntity::class, ContactLogEntity::class, ReminderEntity::class, ProductBatchEntity::class,
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
    abstract fun workDao(): WorkDao

    companion object {
        const val VERSION = 3
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

        /** V3 adds the business platform (profiles, staff, work items, follow-ups, reminders, batches). Nothing existing changes. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (sql in V3_SQL) db.execSQL(sql)
            }
        }

        val V3_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `business_profiles` (`version` INTEGER NOT NULL, `config` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `serverVersion` INTEGER NOT NULL, PRIMARY KEY(`version`))",
            "CREATE TABLE IF NOT EXISTS `staff` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `phone` TEXT NOT NULL, `role` TEXT NOT NULL, `active` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `work_items` (`id` TEXT NOT NULL, `module` TEXT NOT NULL, `number` TEXT NOT NULL, `title` TEXT NOT NULL, `customerId` TEXT, `productId` TEXT, `staffId` TEXT, `status` TEXT NOT NULL, `stage` TEXT, `priority` INTEGER NOT NULL, `quantityMilli` INTEGER, `doneMilli` INTEGER NOT NULL, `amountPaise` INTEGER, `startAt` INTEGER, `dueAt` INTEGER, `fields` TEXT NOT NULL, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `closedAt` INTEGER, `clientRef` TEXT, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_work_items_module_status` ON `work_items` (`module`, `status`)",
            "CREATE INDEX IF NOT EXISTS `index_work_items_dueAt` ON `work_items` (`dueAt`)",
            "CREATE INDEX IF NOT EXISTS `index_work_items_staffId` ON `work_items` (`staffId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_work_items_clientRef` ON `work_items` (`clientRef`)",
            "CREATE TABLE IF NOT EXISTS `work_events` (`id` TEXT NOT NULL, `workItemId` TEXT NOT NULL, `kind` TEXT NOT NULL, `fromValue` TEXT NOT NULL, `toValue` TEXT NOT NULL, `note` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_work_events_workItemId` ON `work_events` (`workItemId`)",
            "CREATE TABLE IF NOT EXISTS `followups` (`id` TEXT NOT NULL, `customerId` TEXT, `personName` TEXT NOT NULL, `phone` TEXT NOT NULL, `kind` TEXT NOT NULL, `note` TEXT NOT NULL, `dueAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `result` TEXT NOT NULL, `workItemId` TEXT, `createdAt` INTEGER NOT NULL, `completedAt` INTEGER, `clientRef` TEXT, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_followups_status_dueAt` ON `followups` (`status`, `dueAt`)",
            "CREATE INDEX IF NOT EXISTS `index_followups_customerId` ON `followups` (`customerId`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_followups_clientRef` ON `followups` (`clientRef`)",
            "CREATE TABLE IF NOT EXISTS `contact_log` (`id` TEXT NOT NULL, `customerId` TEXT, `personName` TEXT NOT NULL, `channel` TEXT NOT NULL, `summary` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_contact_log_customerId` ON `contact_log` (`customerId`)",
            "CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, `text` TEXT NOT NULL, `dueAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `followupId` TEXT, `workItemId` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_reminders_status_dueAt` ON `reminders` (`status`, `dueAt`)",
            "CREATE TABLE IF NOT EXISTS `product_batches` (`id` TEXT NOT NULL, `productId` TEXT NOT NULL, `batchNo` TEXT NOT NULL, `expiryDate` INTEGER NOT NULL, `quantityMilli` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_product_batches_productId` ON `product_batches` (`productId`)",
            "CREATE INDEX IF NOT EXISTS `index_product_batches_expiryDate` ON `product_batches` (`expiryDate`)",
        )

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        /** Every table, parents before children, used by backup and restore. */
        val TABLES = listOf(
            "business", "users", "app_settings", "categories", "products", "customers", "suppliers",
            "sales", "sale_items", "purchases", "purchase_items", "expenses", "payments",
            "inventory_transactions", "conversations", "conversation_messages", "audit_logs",
            "business_aliases", "ai_actions", "workbook_sheets", "workbook_rows",
            "business_profiles", "staff", "work_items", "work_events", "followups", "contact_log", "reminders", "product_batches",
        )
    }
}
