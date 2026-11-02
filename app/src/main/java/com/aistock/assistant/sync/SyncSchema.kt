package com.aistock.assistant.sync

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The tables and triggers that record what changed on this phone, so it can be sent to the server and reach the other
 * phones of the business (owner, managers, staff).
 *
 * - `sync_outbox`: one row per changed record (table + id), written by triggers; sent with /sync/push.
 * - `sync_inbox`: records pulled from the server, kept until a complete set has arrived, then applied together.
 * - `sync_flags.applying`: 1 while pulled records are being written, so they are not sent back as this phone's changes.
 *
 * Everything here is created with IF NOT EXISTS and is run on every open, so it is safe to call again.
 */
object SyncSchema {
    /** Shared tables, parents before children (the order pulled records are applied in). Must match the server's list. */
    val TABLES = listOf(
        "categories", "products", "customers", "suppliers", "sales", "sale_items", "purchases", "purchase_items",
        "expenses", "payments", "inventory_transactions", "staff", "work_items", "work_events", "followups",
        "contact_log", "product_batches", "business_aliases",
    )

    /**
     * Product columns whose change is worth sending. Stock is not one of them: it travels as stock movements, so two
     * phones selling at the same time add up instead of overwriting each other.
     */
    private val PRODUCT_DETAILS = listOf("productCode", "name", "description", "categoryId", "unit", "barcode", "purchasePrice", "sellingPrice", "minimumStock", "active")

    fun install(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_outbox (tbl TEXT NOT NULL, id TEXT NOT NULL, deleted INTEGER NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(tbl, id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_inbox (tbl TEXT NOT NULL, id TEXT NOT NULL, data TEXT, deleted INTEGER NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY(tbl, id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_flags (k TEXT NOT NULL PRIMARY KEY, v INTEGER NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO sync_flags (k, v) VALUES ('applying', 0)")
        // A crash in the middle of applying must not leave the flag on (that would stop recording changes).
        db.execSQL("UPDATE sync_flags SET v = 0 WHERE k = 'applying'")
        // Inside a trigger, the outer statement's conflict rule (Room's UPDATE OR ABORT) would turn INSERT OR REPLACE
        // into an error when a record changes twice before it is sent: delete the old entry, then insert.
        val idle = "(SELECT v FROM sync_flags WHERE k = 'applying') = 0"
        val now = "CAST(strftime('%s','now') AS INTEGER) * 1000"
        for (t in TABLES) {
            for (k in listOf("ins", "upd", "del")) db.execSQL("DROP TRIGGER IF EXISTS sync_${k}_$t") // first test builds' triggers
            db.execSQL("CREATE TRIGGER IF NOT EXISTS sync2_ins_$t AFTER INSERT ON `$t` WHEN $idle BEGIN " +
                "DELETE FROM sync_outbox WHERE tbl = '$t' AND id = NEW.id; INSERT INTO sync_outbox (tbl, id, deleted, at) VALUES ('$t', NEW.id, 0, $now); END")
            val changed = if (t == "products") " AND (" + PRODUCT_DETAILS.joinToString(" OR ") { "OLD.`$it` IS NOT NEW.`$it`" } + ")" else ""
            db.execSQL("CREATE TRIGGER IF NOT EXISTS sync2_upd_$t AFTER UPDATE ON `$t` WHEN $idle$changed BEGIN " +
                "DELETE FROM sync_outbox WHERE tbl = '$t' AND id = NEW.id; INSERT INTO sync_outbox (tbl, id, deleted, at) VALUES ('$t', NEW.id, 0, $now); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS sync2_del_$t AFTER DELETE ON `$t` WHEN $idle BEGIN " +
                "DELETE FROM sync_outbox WHERE tbl = '$t' AND id = OLD.id; INSERT INTO sync_outbox (tbl, id, deleted, at) VALUES ('$t', OLD.id, 1, $now); END")
        }
    }
}
