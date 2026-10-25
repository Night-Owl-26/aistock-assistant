package com.aistock.assistant.excel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.InvalidationTracker
import com.aistock.assistant.data.Status
import com.aistock.assistant.data.newId
import com.aistock.assistant.database.AppDatabase
import com.aistock.assistant.utils.DateKey
import com.aistock.assistant.utils.DateRanges
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the owner's own Excel file in step with the app, only after the owner turned it on for that file.
 *
 * Every time stock (or a sale) changes, a few seconds later the file is read again, the rows are matched by product
 * name, and only the stock and price cells that differ are rewritten (see [ExcelSyncPlan], [XlsxPatcher]).
 * A copy of the file as it was is saved inside the app before every write (the last [KEEP_BACKUPS] are kept), the
 * written file is read back to check it, and if that check fails the copy is put back.
 */
@Singleton
class ExcelSync @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val db: AppDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()
    @Volatile private var started = false

    val links: Flow<List<ExcelLinkEntity>> get() = db.excelDao().observeLinks()

    /** Starts watching for stock and sales changes. Safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(arrayOf("products", "sales")) {
            override fun onInvalidated(tables: Set<String>) { changes.trySend(Unit) }
        })
        scope.launch {
            changes.trySend(Unit) // changes made while the app was closed
            for (u in changes) {
                delay(DEBOUNCE_MS)
                while (changes.tryReceive().isSuccess) { /* several changes close together: one update */ }
                try { syncAll() } catch (e: Exception) { /* each link records its own result */ }
            }
        }
    }

    suspend fun syncAll() {
        for (l in db.excelDao().links()) if (l.enabled) sync(l.id)
    }

    /** Whether the app that holds this file lets AIStock change it. */
    fun canWrite(uri: Uri): Boolean = try {
        ctx.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
        } ?: false
    } catch (e: Exception) {
        false
    }

    /** Keeps the permission to open the file after the app restarts. Returns false when only reading was granted. */
    fun keepPermission(uri: Uri): Boolean {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return try {
            ctx.contentResolver.takePersistableUriPermission(uri, rw); true
        } catch (e: SecurityException) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e2: Exception) { /* not persistable */ }
            false
        }
    }

    /** Turns updating on for one sheet of the owner's file, then updates it once straight away. */
    suspend fun link(uri: Uri, fileName: String, c: LinkCandidate, addNewRows: Boolean, salesSheet: Boolean): SyncOutcome {
        val id = withContext(Dispatchers.IO) {
            val dao = db.excelDao()
            for (old in dao.links()) if (old.uri == uri.toString()) dao.deleteLink(old.id)
            val id = newId()
            fun title(i: Int) = if (i < 0) "" else HeaderMapper.title(c.headers, i)
            dao.saveLink(
                ExcelLinkEntity(
                    id, uri.toString(), fileName.take(120), c.sheet,
                    title(c.nameCol), c.nameCol, title(c.sizeCol), c.sizeCol, title(c.stockCol), c.stockCol, title(c.priceCol), c.priceCol,
                    enabled = true, addNewRows = addNewRows, salesSheet = salesSheet, lastSyncAt = null, lastResult = "", createdAt = System.currentTimeMillis(),
                )
            )
            id
        }
        return sync(id)
    }

    suspend fun setEnabled(id: String, on: Boolean) = withContext(Dispatchers.IO) {
        val dao = db.excelDao()
        dao.link(id)?.let { dao.saveLink(it.copy(enabled = on)) }
        if (on) changes.trySend(Unit)
    }

    suspend fun unlink(id: String) = withContext(Dispatchers.IO) {
        val dao = db.excelDao()
        val l = dao.link(id) ?: return@withContext
        dao.deleteLink(id)
        if (dao.links().none { it.uri == l.uri }) {
            try {
                ctx.contentResolver.releasePersistableUriPermission(Uri.parse(l.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) { /* already gone */ }
        }
    }

    fun backups(id: String): List<File> = backupDir(id).listFiles()?.filter { it.name.endsWith(".xlsx") }?.sortedByDescending { it.name } ?: emptyList()

    /** Updates one linked file now. Never throws: the outcome is also saved on the link. */
    suspend fun sync(id: String): SyncOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dao = db.excelDao()
            val link = dao.link(id) ?: return@withContext SyncOutcome(false, "GONE")
            val outcome = try { syncLink(link) } catch (e: ExcelSyncPlan.Problem) {
                SyncOutcome(false, e.code, detail = e.message.orEmpty())
            } catch (e: XlsxPatcher.Problem) {
                SyncOutcome(false, e.code, detail = e.message.orEmpty())
            } catch (e: SecurityException) {
                SyncOutcome(false, "PERMISSION")
            } catch (e: Exception) {
                SyncOutcome(false, "FAILED", detail = e.javaClass.simpleName + ": " + e.message.orEmpty())
            }
            dao.setResult(id, System.currentTimeMillis(), outcome.encode())
            outcome
        }
    }

    private suspend fun syncLink(link: ExcelLinkEntity): SyncOutcome {
        val uri = Uri.parse(link.uri)
        val cr = ctx.contentResolver
        val bytes = try { cr.openInputStream(uri)?.use { it.readBytes() } } catch (e: SecurityException) { throw e } catch (e: Exception) { null }
            ?: return SyncOutcome(false, "OPEN")
        val products = db.productDao().active().map { SyncProduct(it.name, it.currentStock, it.sellingPrice, it.createdAt, it.productCode) }
        val update = ExcelSyncPlan.update(bytes, link, products, if (link.salesSheet) salesSheet() else null)
        val newBytes = update.bytes ?: return update.outcome

        backup(link, bytes)
        write(uri, newBytes)
        // Read the file back: if it is not a workbook we can open, put the original back.
        val check = try { cr.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
        val good = check != null && try { XlsxPatcher.grid(check, link.sheetName) != null } catch (e: Exception) { false }
        if (!good) {
            try { write(uri, bytes) } catch (e: Exception) { /* the backup copy is still inside the app */ }
            return SyncOutcome(false, "VERIFY")
        }
        return update.outcome
    }

    private fun write(uri: Uri, bytes: ByteArray) {
        val cr = ctx.contentResolver
        // Only truncating modes: writing a shorter file over a longer one without truncating would damage it.
        for (mode in listOf("wt", "rwt")) {
            val out = try { cr.openOutputStream(uri, mode) } catch (e: SecurityException) { throw e } catch (e: Exception) { null } ?: continue
            out.use { it.write(bytes); it.flush() }
            return
        }
        throw XlsxPatcher.Problem("READ_ONLY", "The app that holds this file does not allow changes")
    }

    private fun backupDir(id: String) = File(ctx.filesDir, "excel-backups/$id")

    private fun backup(link: ExcelLinkEntity, bytes: ByteArray) {
        val dir = backupDir(link.id).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        File(dir, "$stamp.xlsx").writeBytes(bytes)
        backups(link.id).drop(KEEP_BACKUPS).forEach { it.delete() }
    }

    private fun rupees(paise: Long): BigDecimal = BigDecimal(paise).movePointLeft(2)

    /** The optional "AIStock Sales" sheet: total revenue and every bill, newest first. */
    internal suspend fun salesSheet(): Sheet {
        val dao = db.tradeDao()
        val all = dao.salesBetween(0L, Long.MAX_VALUE).filter { it.sale.status != Status.CANCELLED }
        val today = DateRanges.range(DateKey.TODAY)
        val month = DateRanges.range(DateKey.THIS_MONTH)
        fun total(from: Long, to: Long) = all.filter { it.sale.date in from until to }.sumOf { it.sale.total }
        val fmt = SimpleDateFormat("dd-MM-yyyy", Locale.US)
        val rows = ArrayList<List<Any?>>()
        rows.add(listOf("AIStock sales - kept up to date by the AIStock app. Changes made here are replaced."))
        rows.add(listOf("Total revenue", rupees(all.sumOf { it.sale.total })))
        rows.add(listOf("This month", rupees(total(month.start, month.end))))
        rows.add(listOf("Today", rupees(total(today.start, today.end))))
        rows.add(listOf("Bills", BigDecimal(all.size)))
        rows.add(listOf(null))
        rows.add(listOf("Date", "Bill no", "Customer", "Items", "Total", "Paid", "Balance"))
        for (s in all.take(MAX_BILLS)) {
            rows.add(listOf(fmt.format(Date(s.sale.date)), s.sale.invoiceNumber, s.partyName ?: "Cash sale", s.itemSummary.orEmpty(), rupees(s.sale.total), rupees(s.sale.paidAmount), rupees(s.sale.balanceAmount)))
        }
        return Sheet(SALES_SHEET, rows)
    }

    companion object {
        const val DEBOUNCE_MS = 4_000L
        const val KEEP_BACKUPS = 5
        const val MAX_BILLS = 5_000
        const val SALES_SHEET = "AIStock Sales"
    }
}
