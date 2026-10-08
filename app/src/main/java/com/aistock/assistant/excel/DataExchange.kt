package com.aistock.assistant.excel

import android.content.Context
import androidx.room.withTransaction
import com.aistock.assistant.R
import com.aistock.assistant.ai.PartyKind
import com.aistock.assistant.data.InventoryRepository
import com.aistock.assistant.data.LocaleHelper
import com.aistock.assistant.data.PartyRepository
import com.aistock.assistant.data.Status
import com.aistock.assistant.data.TxnType
import com.aistock.assistant.data.newId
import com.aistock.assistant.database.AppDatabase
import com.aistock.assistant.database.CustomerEntity
import com.aistock.assistant.database.ProductEntity
import com.aistock.assistant.database.SupplierEntity
import com.aistock.assistant.utils.DateKey
import com.aistock.assistant.utils.DateRanges
import com.aistock.assistant.utils.Money
import com.aistock.assistant.utils.NameMatcher
import com.aistock.assistant.utils.Qty
import com.aistock.assistant.utils.TimeWindow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class ImportResult(val imported: Int, val skipped: List<ImportIssue>, val errors: List<ImportIssue>)

enum class ReportType { SALES, PURCHASES, STOCK, STOCK_MOVEMENT, EXPENSES, CUSTOMER_OUTSTANDING, SUPPLIER_OUTSTANDING }
enum class ExportKind { PRODUCTS, STOCK, STOCK_MOVEMENT, SALES, PURCHASES, EXPENSES, CUSTOMERS, SUPPLIERS, WORKBOOK, EVERYTHING }

/** What importing one sheet of a workbook would do. */
data class WorkbookPlanItem(val sheet: String, val kind: ImportKind, val preview: ImportPreview)

data class WorkbookResult(val imported: Map<ImportKind, Int>, val issues: List<String>, val sheetsRemembered: Int)

/** A report or export: a header row, data rows (String, BigDecimal or null) and a few headline totals. */
data class ReportData(val title: String, val headers: List<String>, val rows: List<List<Any?>>, val summary: List<Pair<String, String>>) {
    fun sheet(): Sheet = Sheet(title, listOf(headers) + rows)
}

/** Excel/CSV import, Excel export and the V1 reports. All heavy work runs off the main thread. */
@Singleton
class DataExchange @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val db: AppDatabase,
    private val inventory: InventoryRepository,
    private val parties: PartyRepository,
) {
    private fun s(id: Int): String = LocaleHelper.wrap(appContext).getString(id)
    private fun date(ms: Long): String = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date(ms))
    private fun dateTime(ms: Long): String = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).format(Date(ms))
    private fun rupees(paise: Long): BigDecimal = BigDecimal(paise).movePointLeft(2)
    private fun qty(milli: Long): BigDecimal = BigDecimal(milli).movePointLeft(3).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    // ------------------------------------------------------------ import

    suspend fun readTable(name: String, input: InputStream): List<List<String>> = withContext(Dispatchers.IO) {
        val lower = name.lowercase()
        when {
            lower.endsWith(".csv") || lower.endsWith(".txt") -> Csv.read(input)
            lower.endsWith(".xlsx") -> XlsxReader.read(input)
            else -> {
                // Unknown name (some file pickers hide it): look at the first bytes. Zip files start with "PK".
                val bytes = input.readBytes()
                if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) XlsxReader.read(bytes.inputStream())
                else Csv.read(bytes.inputStream())
            }
        }
    }

    suspend fun existingNames(kind: ImportKind): List<String> = withContext(Dispatchers.IO) {
        when (kind) {
            ImportKind.PRODUCTS -> inventory.activeProducts().map { it.name }
            ImportKind.OPENING_STOCK -> inventory.activeProducts().flatMap { listOf(it.name, it.productCode) }
            ImportKind.CUSTOMERS -> parties.list(PartyKind.CUSTOMER).map { it.name }
            ImportKind.SUPPLIERS -> parties.list(PartyKind.SUPPLIER).map { it.name }
        }
    }

    suspend fun preview(kind: ImportKind, table: List<List<String>>, mapping: Map<String, Int>, alsoKnown: List<String> = emptyList()): ImportPreview =
        withContext(Dispatchers.Default) { ImportSpec.preview(kind, table, mapping, existingNames(kind) + alsoKnown) }

    // ------------------------------------------------------------ whole workbooks

    /** Reads every sheet of a workbook (or a single CSV) and works out what each one holds. */
    suspend fun analyzeWorkbook(name: String, input: InputStream): List<SheetProfile> = withContext(Dispatchers.IO) {
        val bytes = input.readBytes()
        val isZip = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val raw = if (isZip) XlsxReader.readAll(bytes.inputStream())
        else listOf(RawSheet(name.substringBeforeLast('.').ifBlank { "Sheet1" }, false, Csv.read(bytes.inputStream())))
        WorkbookAnalyzer.analyze(raw).filter { it.table.isNotEmpty() }
    }

    /** What an import of these sheets would do, without saving anything. */
    suspend fun workbookPlan(profiles: List<SheetProfile>): List<WorkbookPlanItem> {
        val out = ArrayList<WorkbookPlanItem>()
        val comingProducts = ArrayList<String>()
        for ((p, kind) in WorkbookAnalyzer.importOrder(profiles)) {
            val pre = preview(kind, p.table, ImportSpec.autoMap(p.headers, kind), if (kind == ImportKind.OPENING_STOCK) comingProducts else emptyList())
            if (kind == ImportKind.PRODUCTS) for (r in pre.ready) { r.values["name"]?.let { comingProducts.add(it) }; r.values["code"]?.let { comingProducts.add(it) } }
            out.add(WorkbookPlanItem(p.name, kind, pre))
        }
        return out
    }

    /** Imports products, stock, customers and suppliers, then keeps every sheet inside the app so Stocky can read it. */
    suspend fun importWorkbook(profiles: List<SheetProfile>): WorkbookResult {
        val counts = LinkedHashMap<ImportKind, Int>()
        val issues = ArrayList<String>()
        for ((p, kind) in WorkbookAnalyzer.importOrder(profiles)) {
            val pre = preview(kind, p.table, ImportSpec.autoMap(p.headers, kind))
            val r = import(kind, pre)
            counts[kind] = (counts[kind] ?: 0) + r.imported
            for (i in (r.errors + r.skipped).take(40)) issues.add("${p.name}, row ${i.rowNumber}: ${i.message}")
        }
        return WorkbookResult(counts, issues, rememberSheets(profiles))
    }

    /** What changed compared with the workbook remembered from an earlier upload: sheet name -> short note. */
    suspend fun workbookChanges(profiles: List<SheetProfile>): Map<String, String> = withContext(Dispatchers.IO) {
        val stored = db.stockyDao().sheets().filter { it.purpose != "CUSTOM" }
        if (stored.isEmpty()) return@withContext emptyMap()
        val out = LinkedHashMap<String, String>()
        for (p in profiles) {
            val old = stored.firstOrNull { NameMatcher.key(it.name) == NameMatcher.key(p.name) }
            if (old == null) { out[p.name] = "NEW_SHEET"; continue }
            val oldCols = try { org.json.JSONArray(old.columns).let { a -> (0 until a.length()).map { a.optString(it).lowercase() } } } catch (e: Exception) { emptyList() }
            val added = p.headers.filter { it.isNotBlank() && it.lowercase() !in oldCols }
            if (added.isNotEmpty()) out[p.name] = added.take(4).joinToString(", ")
        }
        for (o in stored) if (profiles.none { NameMatcher.key(it.name) == NameMatcher.key(o.name) }) out[o.name] = "MISSING"
        out
    }

    private suspend fun rememberSheets(profiles: List<SheetProfile>): Int = withContext(Dispatchers.IO) {
        var n = 0
        db.withTransaction {
            val dao = db.stockyDao()
            val existing = dao.sheets()
            val now = System.currentTimeMillis()
            for (p in profiles) {
                val headers = p.headers.mapIndexed { i, h -> h.ifBlank { "Column ${i + 1}" } }
                if (headers.isEmpty()) continue
                val old = existing.firstOrNull { NameMatcher.key(it.name) == NameMatcher.key(p.name) }
                // A sheet the owner created by talking to Stocky is never replaced by an upload.
                if (old != null && old.purpose == "CUSTOM") continue
                val id = old?.id ?: newId()
                val cols = org.json.JSONArray(headers).toString()
                if (old == null) dao.insertSheet(com.aistock.assistant.database.SheetEntity(id, p.name.take(60), p.purpose.name, cols, now, now))
                else { dao.deleteRows(id); dao.updateSheet(old.copy(purpose = p.purpose.name, columns = cols, updatedAt = now)) }
                val rows = p.table.drop(1).take(MAX_SHEET_ROWS).mapIndexed { i, row ->
                    val o = org.json.JSONObject()
                    for ((c, h) in headers.withIndex()) row.getOrNull(c)?.takeIf { it.isNotBlank() }?.let { o.put(h, it.take(300)) }
                    com.aistock.assistant.database.SheetRowEntity(newId(), id, i.toLong(), o.toString(), now)
                }
                for (chunk in rows.chunked(500)) dao.insertRows(chunk)
                n++
            }
        }
        n
    }

    /** The sheets kept inside the app (uploaded ones and ones created through Stocky), ready to be written to Excel. */
    suspend fun workbookSheets(): List<Sheet> = withContext(Dispatchers.IO) {
        val dao = db.stockyDao()
        dao.sheets().map { s ->
            val cols = try { org.json.JSONArray(s.columns).let { a -> (0 until a.length()).map { a.optString(it) } } } catch (e: Exception) { emptyList() }
            val rows = dao.rows(s.id).map { r ->
                val o = try { org.json.JSONObject(r.cells) } catch (e: Exception) { org.json.JSONObject() }
                cols.map { c -> o.optString(c).let { v -> v.replace(",", "").toBigDecimalOrNull()?.takeIf { v.length < 15 && !v.startsWith("0") || v == "0" } ?: v } as Any? }
            }
            Sheet(s.name, listOf(cols.map { it as Any? }) + rows)
        }
    }

    /** Saves the rows that passed the preview. Everything is written in one transaction. */
    suspend fun import(kind: ImportKind, preview: ImportPreview): ImportResult = withContext(Dispatchers.IO) {
        var imported = 0
        val errors = ArrayList(preview.errors)
        val skipped = ArrayList(preview.skipped)
        db.withTransaction {
            val now = System.currentTimeMillis()
            when (kind) {
                ImportKind.PRODUCTS -> {
                    val have = db.productDao().active().map { NameMatcher.key(it.name) }.toHashSet()
                    val codes = db.productDao().all().map { it.productCode }.toHashSet()
                    var seq = db.productDao().count()
                    for (row in preview.ready) {
                        val v = row.values
                        val name = v.getValue("name").trim()
                        if (!have.add(NameMatcher.key(name))) { skipped.add(ImportIssue(row.rowNumber, "'$name' already exists and was left unchanged")); continue }
                        var code = v["code"].orEmpty()
                        if (code.isEmpty() || code in codes) {
                            do { seq++; code = "P" + seq.toString().padStart(4, '0') } while (code in codes)
                        }
                        codes.add(code)
                        val id = newId()
                        val cost = Money.parse(v["purchasePrice"]) ?: 0L
                        db.productDao().insert(
                            ProductEntity(
                                id, code, name, v["description"].orEmpty(), v["category"]?.let { inventory.categoryIdFor(it) },
                                v["unit"] ?: "PCS", v["barcode"].orEmpty(), cost, Money.parse(v["sellingPrice"]) ?: 0L,
                                Qty.parse(v["minStock"]) ?: 0L, 0L, true, now, now,
                            )
                        )
                        val opening = Qty.parse(v["openingStock"]) ?: 0L
                        if (opening > 0) inventory.move(id, opening, TxnType.OPENING, cost.takeIf { it > 0 }, "IMPORT", id, "Opening stock (import)")
                        imported++
                    }
                }
                ImportKind.CUSTOMERS -> {
                    val have = db.partyDao().activeCustomers().map { NameMatcher.key(it.name) }.toHashSet()
                    for (row in preview.ready) {
                        val v = row.values
                        val name = v.getValue("name").trim()
                        if (!have.add(NameMatcher.key(name))) { skipped.add(ImportIssue(row.rowNumber, "'$name' already exists and was left unchanged")); continue }
                        db.partyDao().insertCustomer(CustomerEntity(newId(), name, v["phone"].orEmpty(), v["email"].orEmpty(), v["address"].orEmpty(), Money.parse(v["openingBalance"]) ?: 0L, v["notes"].orEmpty(), true, now, now))
                        imported++
                    }
                }
                ImportKind.SUPPLIERS -> {
                    val have = db.partyDao().activeSuppliers().map { NameMatcher.key(it.name) }.toHashSet()
                    for (row in preview.ready) {
                        val v = row.values
                        val name = v.getValue("name").trim()
                        if (!have.add(NameMatcher.key(name))) { skipped.add(ImportIssue(row.rowNumber, "'$name' already exists and was left unchanged")); continue }
                        db.partyDao().insertSupplier(SupplierEntity(newId(), name, v["phone"].orEmpty(), v["email"].orEmpty(), v["address"].orEmpty(), Money.parse(v["openingBalance"]) ?: 0L, v["notes"].orEmpty(), true, now, now))
                        imported++
                    }
                }
                ImportKind.OPENING_STOCK -> {
                    val all = db.productDao().active()
                    for (row in preview.ready) {
                        val v = row.values
                        val key = NameMatcher.key(v.getValue("name"))
                        val p = all.firstOrNull { NameMatcher.key(it.name) == key } ?: all.firstOrNull { NameMatcher.key(it.productCode) == key }
                        if (p == null) { errors.add(ImportIssue(row.rowNumber, "Product '${v["name"]}' was not found")); continue }
                        val target = Qty.parse(v["quantity"]) ?: 0L
                        val current = db.productDao().byId(p.id)?.currentStock ?: 0L
                        val delta = target - current
                        if (delta == 0L) { skipped.add(ImportIssue(row.rowNumber, "'${p.name}' already has this stock")); continue }
                        val firstMovement = db.productDao().movementCount(p.id) == 0
                        val type = when {
                            firstMovement && delta > 0 -> TxnType.OPENING
                            delta > 0 -> TxnType.ADJUSTMENT_IN
                            else -> TxnType.ADJUSTMENT_OUT
                        }
                        inventory.move(p.id, delta, type, Money.parse(v["unitCost"]), "IMPORT", p.id, "Stock set by import")
                        imported++
                    }
                }
            }
        }
        ImportResult(imported, skipped, errors)
    }

    // ------------------------------------------------------------ reports

    fun window(key: DateKey): TimeWindow = DateRanges.range(key)

    suspend fun report(type: ReportType, from: Long, to: Long): ReportData = withContext(Dispatchers.IO) {
        val sym = db.metaDao().business()?.currencySymbol ?: "₹"
        fun m(p: Long) = Money.format(p, sym)
        when (type) {
            ReportType.SALES -> {
                val list = db.tradeDao().salesBetween(from, to)
                val live = list.filter { it.sale.status != Status.CANCELLED }
                ReportData(
                    s(R.string.report_sales),
                    listOf(s(R.string.col_date), s(R.string.col_number), s(R.string.col_customer), s(R.string.col_items), s(R.string.col_total), s(R.string.col_paid), s(R.string.col_balance), s(R.string.col_status)),
                    list.map { listOf(date(it.sale.date), it.sale.invoiceNumber, it.partyName ?: s(R.string.cash_sale), it.itemSummary.orEmpty(), rupees(it.sale.total), rupees(it.sale.paidAmount), rupees(it.sale.balanceAmount), it.sale.status) },
                    listOf(s(R.string.sum_count) to live.size.toString(), s(R.string.col_total) to m(live.sumOf { it.sale.total }), s(R.string.col_balance) to m(live.sumOf { it.sale.balanceAmount })),
                )
            }
            ReportType.PURCHASES -> {
                val list = db.tradeDao().purchasesBetween(from, to)
                val live = list.filter { it.purchase.status != Status.CANCELLED }
                ReportData(
                    s(R.string.report_purchases),
                    listOf(s(R.string.col_date), s(R.string.col_number), s(R.string.col_supplier), s(R.string.col_items), s(R.string.col_total), s(R.string.col_paid), s(R.string.col_balance), s(R.string.col_status)),
                    list.map { listOf(date(it.purchase.date), it.purchase.purchaseNumber, it.partyName ?: s(R.string.cash_purchase), it.itemSummary.orEmpty(), rupees(it.purchase.total), rupees(it.purchase.paidAmount), rupees(it.purchase.balanceAmount), it.purchase.status) },
                    listOf(s(R.string.sum_count) to live.size.toString(), s(R.string.col_total) to m(live.sumOf { it.purchase.total }), s(R.string.col_balance) to m(live.sumOf { it.purchase.balanceAmount })),
                )
            }
            ReportType.STOCK -> {
                val list = db.productDao().active()
                ReportData(
                    s(R.string.report_stock),
                    listOf(s(R.string.col_product), s(R.string.col_code), s(R.string.col_unit), s(R.string.col_stock), s(R.string.col_min), s(R.string.col_cost), s(R.string.col_value), s(R.string.col_status)),
                    list.map {
                        listOf(it.name, it.productCode, it.unit, qty(it.currentStock), qty(it.minimumStock), rupees(it.purchasePrice),
                            rupees(Money.lineTotal(maxOf(it.currentStock, 0), it.purchasePrice)), if (it.currentStock <= it.minimumStock) s(R.string.low_stock) else s(R.string.in_stock))
                    },
                    listOf(s(R.string.sum_products) to list.size.toString(), s(R.string.col_value) to m(list.sumOf { Money.lineTotal(maxOf(it.currentStock, 0), it.purchasePrice) }),
                        s(R.string.low_stock) to list.count { it.currentStock <= it.minimumStock }.toString()),
                )
            }
            ReportType.STOCK_MOVEMENT -> {
                val list = db.inventoryDao().between(from, to)
                ReportData(
                    s(R.string.report_movement),
                    listOf(s(R.string.col_date), s(R.string.col_product), s(R.string.col_type), s(R.string.col_quantity), s(R.string.col_unit), s(R.string.col_before), s(R.string.col_after), s(R.string.col_notes)),
                    list.map { listOf(dateTime(it.txn.createdAt), it.productName, it.txn.type, qty(it.txn.afterQuantity - it.txn.beforeQuantity), it.txn.unit, qty(it.txn.beforeQuantity), qty(it.txn.afterQuantity), it.txn.notes) },
                    listOf(s(R.string.sum_count) to list.size.toString()),
                )
            }
            ReportType.EXPENSES -> {
                val list = db.tradeDao().expensesBetween(from, to)
                val live = list.filter { it.status == Status.ACTIVE }
                ReportData(
                    s(R.string.report_expenses),
                    listOf(s(R.string.col_date), s(R.string.col_category), s(R.string.col_description), s(R.string.col_amount), s(R.string.col_method), s(R.string.col_status)),
                    list.map { listOf(date(it.date), it.category, it.description, rupees(it.amount), it.paymentMethod, it.status) },
                    listOf(s(R.string.sum_count) to live.size.toString(), s(R.string.col_total) to m(live.sumOf { it.amount })),
                )
            }
            ReportType.CUSTOMER_OUTSTANDING -> partyReport(PartyKind.CUSTOMER, s(R.string.report_customer_outstanding), ::m, onlyDue = true)
            ReportType.SUPPLIER_OUTSTANDING -> partyReport(PartyKind.SUPPLIER, s(R.string.report_supplier_outstanding), ::m, onlyDue = true)
        }
    }

    private suspend fun partyReport(kind: PartyKind, title: String, m: (Long) -> String, onlyDue: Boolean): ReportData {
        val list = parties.list(kind).filter { !onlyDue || it.balance != 0L }.sortedByDescending { it.balance }
        return ReportData(
            title,
            listOf(s(R.string.col_name), s(R.string.col_phone), s(R.string.col_address), s(R.string.col_opening_balance), s(R.string.col_balance)),
            list.map { listOf(it.name, it.phone, it.address, rupees(it.openingBalance), rupees(it.balance)) },
            listOf(s(R.string.sum_count) to list.size.toString(), s(R.string.col_balance) to m(list.sumOf { it.balance })),
        )
    }

    // ------------------------------------------------------------ export

    suspend fun exportSheets(kind: ExportKind): List<Sheet> = withContext(Dispatchers.IO) {
        val all = TimeWindow(0L, Long.MAX_VALUE)
        suspend fun products(): Sheet {
            val cats = db.categoryDao().active().associate { it.id to it.name }
            val rows = db.productDao().active().map {
                listOf<Any?>(it.name, it.productCode, it.categoryId?.let { c -> cats[c] }.orEmpty(), it.unit, it.barcode, rupees(it.purchasePrice), rupees(it.sellingPrice), qty(it.minimumStock), qty(it.currentStock), it.description)
            }
            // Same column names the importer recognises, so an export can be edited and imported elsewhere.
            return Sheet(s(R.string.products), listOf(listOf<Any?>("Name", "Code", "Category", "Unit", "Barcode", "Purchase Price", "Selling Price", "Minimum Stock", "Opening Stock", "Description")) + rows)
        }
        suspend fun partySheet(k: PartyKind, title: String): Sheet {
            val rows = parties.list(k).map { listOf<Any?>(it.name, it.phone, it.email, it.address, rupees(it.openingBalance), it.notes, rupees(it.balance)) }
            return Sheet(title, listOf(listOf<Any?>("Name", "Phone", "Email", "Address", "Opening Balance", "Notes", "Current Balance")) + rows)
        }
        when (kind) {
            ExportKind.PRODUCTS -> listOf(products())
            ExportKind.STOCK -> listOf(report(ReportType.STOCK, all.start, all.end).sheet())
            ExportKind.STOCK_MOVEMENT -> listOf(report(ReportType.STOCK_MOVEMENT, all.start, all.end).sheet())
            ExportKind.SALES -> listOf(report(ReportType.SALES, all.start, all.end).sheet())
            ExportKind.PURCHASES -> listOf(report(ReportType.PURCHASES, all.start, all.end).sheet())
            ExportKind.EXPENSES -> listOf(report(ReportType.EXPENSES, all.start, all.end).sheet())
            ExportKind.CUSTOMERS -> listOf(partySheet(PartyKind.CUSTOMER, s(R.string.customers)))
            ExportKind.SUPPLIERS -> listOf(partySheet(PartyKind.SUPPLIER, s(R.string.suppliers)))
            ExportKind.WORKBOOK -> workbookSheets().ifEmpty { listOf(Sheet("Sheet1", listOf(listOf<Any?>("")))) }
            ExportKind.EVERYTHING -> listOf(
                products(), report(ReportType.STOCK, all.start, all.end).sheet(), report(ReportType.STOCK_MOVEMENT, all.start, all.end).sheet(),
                report(ReportType.SALES, all.start, all.end).sheet(), report(ReportType.PURCHASES, all.start, all.end).sheet(),
                report(ReportType.EXPENSES, all.start, all.end).sheet(), partySheet(PartyKind.CUSTOMER, s(R.string.customers)), partySheet(PartyKind.SUPPLIER, s(R.string.suppliers)),
            )
        }
    }

    suspend fun writeXlsx(out: OutputStream, sheets: List<Sheet>) = withContext(Dispatchers.IO) { XlsxWriter.write(out, sheets) }

    companion object {
        const val MAX_SHEET_ROWS = 20_000
    }

    fun fileName(base: String): String =
        base.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "export" } + "-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".xlsx"
}
