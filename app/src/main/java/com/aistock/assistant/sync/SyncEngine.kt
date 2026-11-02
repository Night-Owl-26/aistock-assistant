package com.aistock.assistant.sync

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aistock.assistant.database.AppDatabase
import com.aistock.assistant.database.AppSettingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** How the engine reaches the server (the real [com.aistock.assistant.server.ServerApi], or a fake in tests). */
interface SyncTransport {
    /** The business this phone is signed in to, or null when not signed in. */
    fun business(): String?
    /** owner, manager or staff. */
    fun role(): String
    suspend fun push(changes: JSONArray): JSONObject
    suspend fun pull(since: Long, limit: Int): JSONObject
}

/** What the More screen shows: last successful sync, changes still waiting to go up, and the last problem. */
data class SyncStatus(val lastOk: Long = 0L, val waiting: Int = 0, val problem: String? = null, val running: Boolean = false)

/**
 * Keeps the phones of one business in step (owner, managers, staff), through the owner's Thodar server.
 *
 * Every phone keeps working on its own database, online or not. Triggers ([SyncSchema]) note each changed record;
 * this engine sends them up and brings the other phones' changes down. Pulled records are applied all together once a
 * complete set has arrived, parents before children. Stock is never copied as a number: each phone adds the stock
 * movements it receives, so two phones selling the same item at the same time both count. A record waiting to go up
 * from this phone is never overwritten by an older copy from the server.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val transport: SyncTransport,
    /** Called before this phone's data is replaced by another business's data (makes a backup). */
    private val beforeReplace: suspend () -> Unit = {},
    /** Called after each successful round (brings the business setup down too). */
    private val afterSync: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private var started = false
    private val columnsCache = HashMap<String, Map<String, Col>>()

    private data class Col(val type: String, val notNull: Boolean, val hasDefault: Boolean)

    private val sdb: SupportSQLiteDatabase get() = db.openHelper.writableDatabase
    private suspend fun meta(key: String): String? = db.metaDao().setting(key)
    private suspend fun putMeta(key: String, value: String) = db.metaDao().putSetting(AppSettingEntity(key, value, System.currentTimeMillis()))

    /** Starts syncing in the background: soon after every local change, and every [everyMs] in any case. */
    fun start(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO), everyMs: Long = 20_000L) {
        if (started) return
        started = true
        scope.launch {
            // Off the main thread: Room may touch the database to set up its change tracking.
            try {
                db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(SyncSchema.TABLES.toTypedArray()) {
                    override fun onInvalidated(tables: Set<String>) { kicks.trySend(Unit) }
                })
            } catch (e: Exception) { /* the timer below still syncs */ }
            while (true) {
                withTimeoutOrNull(everyMs) { kicks.receive() }
                delay(1_500) // let a burst of writes (one bill = several rows) finish first
                try { syncNow() } catch (e: Exception) { /* reported in status */ }
            }
        }
    }

    fun kick() { kicks.trySend(Unit) }

    /** One full round: send this phone's changes, then bring and apply everyone else's. @return true when it all worked */
    suspend fun syncNow(): Boolean = mutex.withLock {
        val business = transport.business() ?: return false
        _status.value = _status.value.copy(running = true)
        try {
            prepare(business)
            push()
            pull()
            try { afterSync() } catch (e: Exception) { }
            _status.value = SyncStatus(System.currentTimeMillis(), waiting(), null, false)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = _status.value.copy(waiting = try { waiting() } catch (x: Exception) { 0 }, problem = e.message ?: e.toString(), running = false)
            false
        }
    }

    suspend fun waiting(): Int = withContext(Dispatchers.IO) {
        sdb.query("SELECT COUNT(*) FROM sync_outbox").use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    // ------------------------------------------------------------------ joining a business

    /**
     * The first time this phone syncs with [business]: the owner's existing records are all queued to go up; a staff
     * or manager phone (or a phone that held another business) is backed up and emptied first, then filled from the server.
     */
    private suspend fun prepare(business: String) {
        val bound = meta(BOUND)
        if (bound == business) return
        val hasData = withContext(Dispatchers.IO) { SyncSchema.TABLES.any { t -> sdb.query("SELECT 1 FROM `$t` LIMIT 1").use { it.moveToFirst() } } }
        if (bound == null && transport.role() == "owner") {
            db.withTransaction {
                val now = System.currentTimeMillis()
                for (t in SyncSchema.TABLES) sdb.execSQL("INSERT OR REPLACE INTO sync_outbox (tbl, id, deleted, at) SELECT '$t', id, 0, $now FROM `$t`")
                putMeta(CURSOR, "0")
                putMeta(BOUND, business)
            }
            return
        }
        if (hasData) beforeReplace()
        db.withTransaction {
            applying(true)
            try {
                for (t in SyncSchema.TABLES.reversed()) sdb.execSQL("DELETE FROM `$t`")
                sdb.execSQL("DELETE FROM sync_outbox")
                sdb.execSQL("DELETE FROM sync_inbox")
            } finally { applying(false) }
            putMeta(CURSOR, "0")
            putMeta(BOUND, business)
        }
    }

    // ------------------------------------------------------------------ sending

    private suspend fun push() {
        repeat(MAX_ROUNDS) {
            val batch = withContext(Dispatchers.IO) {
                val out = ArrayList<Triple<Long, String, String>>()
                sdb.query("SELECT rowid, tbl, id FROM sync_outbox ORDER BY rowid LIMIT $PUSH_BATCH").use { c ->
                    while (c.moveToNext()) out.add(Triple(c.getLong(0), c.getString(1), c.getString(2)))
                }
                out
            }
            if (batch.isEmpty()) return
            val changes = JSONArray()
            withContext(Dispatchers.IO) {
                for ((_, t, id) in batch) {
                    val row = readRow(t, id)
                    val ch = JSONObject().put("t", t).put("id", id)
                    if (row == null) ch.put("deleted", true) else ch.put("data", row)
                    changes.put(ch)
                }
            }
            val reply = transport.push(changes)
            val rejected = reply.optJSONArray("rejected") ?: JSONArray()
            db.withTransaction {
                // Only the entries that were sent: a record changed again meanwhile has a newer entry and stays queued.
                for ((rowid, _, _) in batch) sdb.execSQL("DELETE FROM sync_outbox WHERE rowid = ?", arrayOf<Any>(rowid))
                // Refused (for example a staff phone changing a price): put the server's copy back on this phone.
                if (rejected.length() > 0) {
                    applying(true)
                    try {
                        for (i in 0 until rejected.length()) {
                            val r = rejected.getJSONObject(i)
                            val data = r.optJSONObject("data") ?: continue
                            try { applyRow(r.optString("t"), r.optString("id"), data, r.optBoolean("deleted"), force = true) } catch (e: Exception) { }
                        }
                    } finally { applying(false) }
                }
            }
            if (batch.size < PUSH_BATCH) return
        }
    }

    /** One record as JSON, as it is on this phone. Products also carry the stock not explained by their movements. */
    private fun readRow(t: String, id: String): JSONObject? {
        val o = sdb.query("SELECT * FROM `$t` WHERE id = ?", arrayOf<Any>(id)).use { c ->
            if (!c.moveToFirst()) return null
            val o = JSONObject()
            for (i in 0 until c.columnCount) {
                val name = c.getColumnName(i)
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> o.put(name, JSONObject.NULL)
                    Cursor.FIELD_TYPE_INTEGER -> o.put(name, c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> o.put(name, c.getDouble(i))
                    else -> o.put(name, c.getString(i))
                }
            }
            o
        }
        if (t == "products") o.put(BASE, o.optLong("currentStock") - movementSum(id))
        return o
    }

    private fun movementSum(productId: String): Long =
        sdb.query("SELECT COALESCE(SUM(CASE WHEN afterQuantity >= beforeQuantity THEN quantity ELSE -quantity END), 0) FROM inventory_transactions WHERE productId = ?", arrayOf<Any>(productId))
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    // ------------------------------------------------------------------ receiving

    private suspend fun pull() {
        var since = meta(CURSOR)?.toLongOrNull() ?: 0L
        repeat(MAX_ROUNDS) {
            val page = transport.pull(since, PULL_PAGE)
            val rows = page.optJSONArray("rows") ?: JSONArray()
            val next = page.optLong("next", since)
            db.withTransaction {
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    val cv = ContentValues()
                    cv.put("tbl", r.getString("t")); cv.put("id", r.getString("id"))
                    cv.put("data", r.optJSONObject("data")?.toString()); cv.put("deleted", if (r.optBoolean("deleted")) 1 else 0)
                    cv.put("seq", r.optLong("seq"))
                    sdb.insert("sync_inbox", SQLiteDatabase.CONFLICT_REPLACE, cv)
                }
                putMeta(CURSOR, next.toString())
            }
            since = next
            if (!page.optBoolean("more")) { applyInbox(); return }
        }
        // Still more to fetch: apply when the next round has the rest, so a bill never arrives without its lines.
    }

    /** Applies everything pulled so far, parents before children, in one transaction. */
    private suspend fun applyInbox() {
        db.withTransaction {
            applying(true)
            try {
                for (t in SyncSchema.TABLES) {
                    val rows = ArrayList<Triple<String, String?, Boolean>>()
                    sdb.query("SELECT id, data, deleted FROM sync_inbox WHERE tbl = ? ORDER BY seq", arrayOf<Any>(t)).use { c ->
                        while (c.moveToNext()) rows.add(Triple(c.getString(0), if (c.isNull(1)) null else c.getString(1), c.getInt(2) == 1))
                    }
                    for ((id, data, deleted) in rows) {
                        try { applyRow(t, id, data?.let { JSONObject(it) }, deleted, force = false) } catch (e: Exception) { /* a record that no longer fits is skipped */ }
                    }
                }
                sdb.execSQL("DELETE FROM sync_inbox")
            } finally { applying(false) }
        }
    }

    /**
     * Writes one record from the server. Skipped when this phone has its own newer change of it waiting to go up
     * (unless [force], used when the server refused that change). Stock moves only through movements.
     */
    private fun applyRow(t: String, id: String, data: JSONObject?, deleted: Boolean, force: Boolean) {
        if (t !in SyncSchema.TABLES) return
        if (!force && sdb.query("SELECT 1 FROM sync_outbox WHERE tbl = ? AND id = ?", arrayOf<Any>(t, id)).use { it.moveToFirst() }) return
        if (force) sdb.execSQL("DELETE FROM sync_outbox WHERE tbl = ? AND id = ?", arrayOf<Any>(t, id))
        val existing = if (t == "inventory_transactions") readMovement(id) else null
        val exists = existing != null || sdb.query("SELECT 1 FROM `$t` WHERE id = ?", arrayOf<Any>(id)).use { it.moveToFirst() }
        if (deleted || data == null) {
            if (!exists) return
            sdb.execSQL("DELETE FROM `$t` WHERE id = ?", arrayOf<Any>(id))
            existing?.let { (pid, delta) -> sdb.execSQL("UPDATE products SET currentStock = currentStock - ? WHERE id = ?", arrayOf<Any>(delta, pid)) }
            return
        }
        val cols = columns(t)
        val cv = ContentValues()
        for ((name, col) in cols) {
            if (t == "products" && name == "currentStock") continue
            if (!data.has(name)) continue
            put(cv, name, col, data.opt(name))
        }
        if (exists) {
            if (t == "inventory_transactions") return // a movement never changes once made
            if (cv.size() > 0) sdb.update(t, SQLiteDatabase.CONFLICT_ABORT, cv, "id = ?", arrayOf(id))
            return
        }
        if (t == "products") cv.put("currentStock", data.optLong(BASE, 0L) + movementSum(id))
        for ((name, col) in cols) {
            if (cv.containsKey(name) && cv.get(name) != null) continue
            if (col.notNull && !col.hasDefault) {
                if (col.type.contains("INT") || col.type.contains("REAL")) cv.put(name, 0L) else cv.put(name, if (name == "id") id else "")
            }
        }
        cv.put("id", id)
        sdb.insert(t, SQLiteDatabase.CONFLICT_ABORT, cv)
        if (t == "inventory_transactions") {
            readMovement(id)?.let { (pid, delta) -> sdb.execSQL("UPDATE products SET currentStock = currentStock + ? WHERE id = ?", arrayOf<Any>(delta, pid)) }
        }
    }

    /** A stock movement's product and signed quantity. */
    private fun readMovement(id: String): Pair<String, Long>? =
        sdb.query("SELECT productId, CASE WHEN afterQuantity >= beforeQuantity THEN quantity ELSE -quantity END FROM inventory_transactions WHERE id = ?", arrayOf<Any>(id))
            .use { if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null }

    private fun put(cv: ContentValues, name: String, col: Col, v: Any?) {
        val isInt = col.type.contains("INT")
        val isReal = col.type.contains("REAL") || col.type.contains("FLOA") || col.type.contains("DOUB")
        when {
            v == null || v == JSONObject.NULL -> if (!col.notNull) cv.putNull(name)
            v is Boolean -> cv.put(name, if (v) 1L else 0L)
            v is Number -> if (isReal) cv.put(name, v.toDouble()) else if (isInt) cv.put(name, v.toLong()) else cv.put(name, v.toString())
            else -> {
                val s = v.toString()
                if (isInt) s.toLongOrNull()?.let { cv.put(name, it) } else if (isReal) s.toDoubleOrNull()?.let { cv.put(name, it) } else cv.put(name, s)
            }
        }
    }

    private fun columns(t: String): Map<String, Col> = columnsCache.getOrPut(t) {
        val out = LinkedHashMap<String, Col>()
        sdb.query("PRAGMA table_info(`$t`)").use { c ->
            val n = c.getColumnIndexOrThrow("name"); val ty = c.getColumnIndexOrThrow("type")
            val nn = c.getColumnIndexOrThrow("notnull"); val d = c.getColumnIndexOrThrow("dflt_value")
            while (c.moveToNext()) out[c.getString(n)] = Col(c.getString(ty).uppercase(), c.getInt(nn) == 1, !c.isNull(d))
        }
        out
    }

    private fun applying(on: Boolean) = sdb.execSQL("UPDATE sync_flags SET v = ? WHERE k = 'applying'", arrayOf<Any>(if (on) 1 else 0))

    companion object {
        const val CURSOR = "sync_cursor"
        const val BOUND = "sync_business"
        /** Extra field on a product: its stock minus the sum of its movements (stock from before movements were kept). */
        const val BASE = "_base"
        const val PUSH_BATCH = 400
        const val PULL_PAGE = 500
        const val MAX_ROUNDS = 40
    }
}
