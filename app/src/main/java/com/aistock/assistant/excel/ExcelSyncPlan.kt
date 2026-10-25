package com.aistock.assistant.excel

import com.aistock.assistant.utils.Money
import com.aistock.assistant.utils.NameMatcher
import com.aistock.assistant.utils.Qty
import java.math.BigDecimal

/**
 * Outcome of one update, kept in [ExcelLinkEntity.lastResult] as "OK|changed|added|formula|notInApp" or "ERR|code|detail".
 */
data class SyncOutcome(val ok: Boolean, val code: String, val changed: Int = 0, val added: Int = 0, val formula: Int = 0, val notInApp: Int = 0, val detail: String = "") {
    fun encode(): String = if (ok) "OK|$code|$changed|$added|$formula|$notInApp" else "ERR|$code|${detail.replace('|', ' ').take(200)}"

    companion object {
        fun decode(s: String): SyncOutcome? {
            val p = s.split('|')
            return when {
                p.size >= 6 && p[0] == "OK" -> SyncOutcome(true, p[1], p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: 0, p[4].toIntOrNull() ?: 0, p[5].toIntOrNull() ?: 0)
                p.size >= 2 && p[0] == "ERR" -> SyncOutcome(false, p[1], detail = p.getOrNull(2).orEmpty())
                else -> null
            }
        }
    }
}

/** A product as the app knows it now. */
data class SyncProduct(val name: String, val stockMilli: Long, val sellPaise: Long, val createdAt: Long, val code: String = "")

/** Where the linked columns are and what to write. */
data class SyncPlan(
    val edits: List<XlsxPatcher.Edit>,
    val headerRow: Int,
    val matched: Int,
    val changed: Int,
    val appended: Int,
    val notInApp: Int,
)

/**
 * Works out which cells of the owner's sheet must change so that it shows the app's current stock (and price).
 * Rows are matched by product name (plus size, when the sheet has a size column), never by position, so rows the
 * owner sorted, inserted or deleted are still found. Columns are found again by their title.
 */
object ExcelSyncPlan {
    class Problem(val code: String, message: String) : Exception(message)

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ").trim()

    data class Columns(val headerRow: Int, val name: Int, val size: Int, val stock: Int, val price: Int)

    /** Finds the header row and the linked columns by their titles; null when the titles are no longer there. */
    fun columns(grid: XlsxPatcher.Grid, link: ExcelLinkEntity): Columns? {
        for ((rowNo, cells) in grid.rows.entries.take(40)) {
            fun find(title: String): Int? {
                if (title.isBlank()) return null
                val want = norm(title)
                val hits = cells.filter { norm(it.value.text) == want }.keys
                return when {
                    hits.isEmpty() -> null
                    hits.size == 1 -> hits.first()
                    // The same title twice: prefer the column it was in when the link was made.
                    else -> hits.minByOrNull { kotlin.math.abs(it - (if (want == norm(link.nameHeader)) link.nameCol else link.stockCol)) }
                }
            }
            val name = find(link.nameHeader) ?: continue
            val stock = find(link.stockHeader) ?: continue
            if (name == stock) continue
            val size = if (link.sizeHeader.isBlank()) -1 else find(link.sizeHeader) ?: -1
            val price = if (link.priceHeader.isBlank()) -1 else find(link.priceHeader) ?: -1
            return Columns(rowNo, name, size, stock, price)
        }
        return null
    }

    private fun number(milli: Long): BigDecimal = BigDecimal(milli).movePointLeft(3).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
    private fun rupees(paise: Long): BigDecimal = BigDecimal(paise).movePointLeft(2).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    fun plan(grid: XlsxPatcher.Grid, link: ExcelLinkEntity, products: List<SyncProduct>): SyncPlan {
        val cols = columns(grid, link) ?: throw Problem("COLUMNS", "Columns '${link.nameHeader}' and '${link.stockHeader}' were not found in sheet '${link.sheetName}'")
        val byKey = HashMap<String, SyncProduct>()
        val byCode = HashMap<String, SyncProduct>()
        for (p in products) {
            byKey.putIfAbsent(NameMatcher.key(p.name), p)
            if (p.code.isNotBlank()) byCode.putIfAbsent(NameMatcher.key(p.code), p)
        }
        val seen = HashSet<String>()
        val edits = ArrayList<XlsxPatcher.Edit>()
        var matched = 0
        var changed = 0
        var notInApp = 0
        var lastUsedRow = cols.headerRow
        for ((rowNo, cells) in grid.rows.tailMap(cols.headerRow + 1)) {
            if (cells.values.any { it.text.isNotBlank() }) lastUsedRow = rowNo
            val nameText = cells[cols.name]?.text?.trim().orEmpty()
            if (nameText.isEmpty()) continue
            val sizeText = if (cols.size >= 0) cells[cols.size]?.text else null
            val key = NameMatcher.key(ImportSpec.nameWithSize(nameText, sizeText))
            val p = byKey[key] ?: byKey[NameMatcher.key(nameText)] ?: byCode[NameMatcher.key(nameText)]
            if (p == null) { notInApp++; continue }
            if (!seen.add(NameMatcher.key(p.name))) continue // the same product twice: only the first row is kept up to date
            matched++
            var rowChanged = false
            val stockNow = cells[cols.stock]
            val wantStock = number(p.stockMilli)
            if (stockNow?.formula != true && Qty.parse(stockNow?.text) != p.stockMilli) { edits.add(XlsxPatcher.Edit(rowNo, cols.stock, wantStock)); rowChanged = true }
            else if (stockNow?.formula == true) edits.add(XlsxPatcher.Edit(rowNo, cols.stock, wantStock)) // reported as skipped by the patcher
            if (cols.price >= 0 && p.sellPaise > 0) {
                val priceNow = cells[cols.price]
                if (priceNow?.formula != true && Money.parse(priceNow?.text) != p.sellPaise) { edits.add(XlsxPatcher.Edit(rowNo, cols.price, rupees(p.sellPaise))); rowChanged = true }
            }
            if (rowChanged) changed++
        }
        var appended = 0
        if (link.addNewRows) {
            var next = maxOf(lastUsedRow, cols.headerRow) + 1
            for (p in products.filter { it.createdAt > link.createdAt && NameMatcher.key(it.name) !in seen }.sortedBy { it.createdAt }) {
                edits.add(XlsxPatcher.Edit(next, cols.name, p.name))
                edits.add(XlsxPatcher.Edit(next, cols.stock, number(p.stockMilli)))
                if (cols.price >= 0 && p.sellPaise > 0) edits.add(XlsxPatcher.Edit(next, cols.price, rupees(p.sellPaise)))
                seen.add(NameMatcher.key(p.name))
                next++; appended++
            }
        }
        return SyncPlan(edits, cols.headerRow, matched, changed, appended, notInApp)
    }

    /** The new file content, or null bytes when nothing needs to change. */
    class Update(val bytes: ByteArray?, val outcome: SyncOutcome)

    /**
     * Everything one update does to the file, without touching storage: find the rows, patch the cells, add or
     * refresh the optional sales sheet. [salesSheet] is null when the owner did not ask for it.
     */
    fun update(bytes: ByteArray, link: ExcelLinkEntity, products: List<SyncProduct>, salesSheet: Sheet?): Update {
        val grid = XlsxPatcher.grid(bytes, link.sheetName) ?: return Update(null, SyncOutcome(false, "NO_SHEET", detail = link.sheetName))
        val plan = plan(grid, link, products)
        val extra = salesSheet?.takeUnless { same(XlsxPatcher.grid(bytes, it.name), it) }
        if (plan.edits.isEmpty() && extra == null) return Update(null, SyncOutcome(true, "UP_TO_DATE", notInApp = plan.notInApp))
        val result = XlsxPatcher.patch(bytes, link.sheetName, plan.edits, extra)
        if (result.written == 0 && extra == null) return Update(null, SyncOutcome(true, "UP_TO_DATE", formula = result.formulaSkipped, notInApp = plan.notInApp))
        return Update(result.bytes, SyncOutcome(true, "UPDATED", plan.changed, plan.appended, result.formulaSkipped, plan.notInApp))
    }

    /** True when the sheet already in the file shows exactly this content. */
    fun same(grid: XlsxPatcher.Grid?, sheet: Sheet): Boolean {
        if (grid == null) return false
        fun text(v: Any?): String = when (v) {
            null -> ""
            is BigDecimal -> v.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
            else -> v.toString()
        }
        if (grid.lastRow > sheet.rows.size) return false
        for ((r, row) in sheet.rows.withIndex()) {
            val cells = grid.rows[r + 1].orEmpty()
            if (cells.any { it.key >= row.size && it.value.text.isNotEmpty() }) return false
            for ((c, v) in row.withIndex()) if ((cells[c]?.text ?: "") != text(v)) return false
        }
        return true
    }
}
