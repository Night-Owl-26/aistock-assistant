package com.aistock.assistant.excel

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Changes individual cells inside the owner's own .xlsx file and leaves everything else exactly as it was:
 * other sheets, formatting, formulas, charts, column widths and macros' parts are copied byte for byte.
 *
 * Only plain value cells are written. A cell that holds a formula is never replaced (it is counted in
 * [Result.formulaSkipped] instead), and the workbook is marked to recalculate when it is opened so totals that
 * depend on the changed cells are correct.
 */
object XlsxPatcher {
    private const val MAX_ENTRY = 40L * 1024 * 1024
    private const val MAX_TOTAL = 120L * 1024 * 1024
    private const val WS_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
    private const val WS_CONTENT = "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"

    class Problem(val code: String, message: String) : Exception(message)

    data class Cell(val text: String, val formula: Boolean)

    /** One sheet as it is stored: Excel row number (1-based) -> column (0-based) -> cell. */
    class Grid(val rows: TreeMap<Int, Map<Int, Cell>>) {
        fun cell(row: Int, col: Int): Cell? = rows[row]?.get(col)
        val lastRow: Int get() = if (rows.isEmpty()) 0 else rows.lastKey()
    }

    /** Write [value] (a BigDecimal number or a String) into the cell at Excel row [row] (1-based) and column [col] (0-based). */
    data class Edit(val row: Int, val col: Int, val value: Any)

    data class Result(val bytes: ByteArray, val written: Int, val formulaSkipped: Int)

    // ------------------------------------------------------------------ reading

    fun parts(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val parts = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var size = 0L
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        size += n; total += n
                        if (size > MAX_ENTRY || total > MAX_TOTAL) throw Problem("TOO_LARGE", "The file is too large")
                        bos.write(buf, 0, n)
                    }
                    parts[e.name] = bos.toByteArray()
                }
            }
        } catch (e: Problem) {
            throw e
        } catch (e: Exception) {
            throw Problem("NOT_XLSX", "This is not a valid Excel file")
        }
        if (!parts.containsKey("xl/workbook.xml")) throw Problem("NOT_XLSX", "This is not a valid Excel file")
        return parts
    }

    private fun text(b: ByteArray?) = b?.toString(Charsets.UTF_8).orEmpty()

    private fun attrs(s: String): Map<String, String> =
        Regex("([\\w:.-]+)\\s*=\\s*\"([^\"]*)\"").findAll(s).associate { it.groupValues[1] to unesc(it.groupValues[2]) }

    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private data class SheetRef(val name: String, val path: String)

    private fun sheetRefs(parts: Map<String, ByteArray>): List<SheetRef> {
        val rels = HashMap<String, String>()
        for (m in Regex("<(?:\\w+:)?Relationship\\b([^>]*?)/?>").findAll(text(parts["xl/_rels/workbook.xml.rels"]))) {
            val a = attrs(m.groupValues[1])
            val id = a["Id"] ?: continue
            rels[id] = a["Target"] ?: continue
        }
        val out = ArrayList<SheetRef>()
        for (m in Regex("<(?:\\w+:)?sheet\\b([^>]*?)/?>").findAll(text(parts["xl/workbook.xml"]))) {
            val a = attrs(m.groupValues[1])
            val rid = a.entries.firstOrNull { it.key == "id" || it.key.endsWith(":id") }?.value ?: continue
            val t = rels[rid] ?: continue
            out.add(SheetRef(a["name"].orEmpty(), if (t.startsWith("/")) t.removePrefix("/") else "xl/" + t.removePrefix("./")))
        }
        return out
    }

    fun sheetNames(bytes: ByteArray): List<String> = sheetRefs(parts(bytes)).map { it.name }

    /** The cells of one sheet by real row number, or null when the workbook has no sheet with that name. */
    fun grid(bytes: ByteArray, sheetName: String): Grid? = grid(parts(bytes), sheetName)

    private fun grid(parts: Map<String, ByteArray>, sheetName: String): Grid? {
        val ref = sheetRefs(parts).firstOrNull { it.name == sheetName } ?: return null
        val xml = parts[ref.path] ?: return null
        val shared = parts["xl/sharedStrings.xml"]?.let { XlsxReader.readSharedStrings(it) } ?: emptyList()
        val rows = TreeMap<Int, Map<Int, Cell>>()
        XlsxReader.parser().parse(InputSource(ByteArrayInputStream(xml)), object : DefaultHandler() {
            var rowNo = 0
            var row: HashMap<Int, Cell>? = null
            var col = -1
            var type: String? = null
            var formula = false
            val v = StringBuilder()
            var capture = false
            override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                when (XlsxReader.local(qName)) {
                    "row" -> { rowNo = a.getValue("r")?.toIntOrNull() ?: (rowNo + 1); row = HashMap(); col = -1 }
                    "c" -> {
                        val r = a.getValue("r")
                        col = if (r != null) XlsxReader.colIndex(r) else col + 1
                        type = a.getValue("t"); formula = false; v.setLength(0)
                    }
                    "f" -> formula = true
                    "v" -> capture = true
                    "t" -> capture = type == "inlineStr"
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (XlsxReader.local(qName)) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val raw = v.toString()
                        val t = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            "inlineStr", "str", "e" -> raw
                            else -> XlsxReader.cleanNumber(raw.trim())
                        }
                        row?.put(col, Cell(t, formula))
                    }
                    "row" -> { row?.let { if (it.isNotEmpty()) rows[rowNo] = it }; row = null }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture) v.append(ch, start, length)
            }
        })
        return Grid(rows)
    }

    // ------------------------------------------------------------------ writing

    /**
     * Applies [edits] to the sheet named [sheetName] and, when [extra] is given, writes that sheet too: it replaces
     * the sheet with the same name if AIStock added it before, otherwise it is added at the end of the workbook.
     */
    fun patch(bytes: ByteArray, sheetName: String, edits: List<Edit>, extra: Sheet? = null): Result {
        val parts = parts(bytes)
        val refs = sheetRefs(parts)
        val ref = refs.firstOrNull { it.name == sheetName } ?: throw Problem("NO_SHEET", "Sheet '$sheetName' is not in the file")
        val xml = parts[ref.path]?.let { text(it) } ?: throw Problem("NO_SHEET", "Sheet '$sheetName' is not in the file")
        val (newXml, written, skipped) = patchSheet(xml, edits)
        if (written > 0) parts[ref.path] = newXml.toByteArray(Charsets.UTF_8)
        if (extra != null) putSheet(parts, refs, extra)
        if (written > 0 || extra != null) parts["xl/workbook.xml"] = recalcOnOpen(text(parts["xl/workbook.xml"])).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size + 4096)
        ZipOutputStream(out).use { zip ->
            for ((name, body) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return Result(out.toByteArray(), written, skipped)
    }

    private class RowSeg(val start: Int, val end: Int, val num: Int, val attrs: String, val inner: String?)

    private fun patchSheet(xml: String, edits: List<Edit>): Triple<String, Int, Int> {
        if (edits.isEmpty()) return Triple(xml, 0, 0)
        val open = Regex("<((?:\\w+:)?)sheetData\\b[^>]*?(/?)>").find(xml) ?: throw Problem("LAYOUT", "The sheet has no data area")
        val p = open.groupValues[1]
        val selfClosing = open.groupValues[2] == "/"
        val bodyStart = open.range.last + 1
        val bodyEnd = if (selfClosing) bodyStart else xml.indexOf("</${p}sheetData>", bodyStart).takeIf { it >= 0 } ?: throw Problem("LAYOUT", "The sheet is damaged")
        val body = xml.substring(bodyStart, bodyEnd)

        val rowRe = Regex("<${Regex.escape(p)}row\\b([^>]*?)(?:/>|>(.*?)</${Regex.escape(p)}row>)", RegexOption.DOT_MATCHES_ALL)
        val rows = rowRe.findAll(body).map { m ->
            val num = attrs(m.groupValues[1])["r"]?.toIntOrNull() ?: throw Problem("LAYOUT", "This file stores rows without numbers, so it cannot be updated in place")
            RowSeg(m.range.first, m.range.last + 1, num, m.groupValues[1], if (m.groups[2] != null) m.groupValues[2] else null)
        }.toList()
        val byRow = edits.groupBy { it.row }
        val existing = rows.associateBy { it.num }
        var written = 0
        var skipped = 0

        // Styles for new rows are copied from the last row that has data, so added products look like the rest.
        val templateStyles = HashMap<Int, String>()
        rows.lastOrNull()?.inner?.let { inner -> for (c in cells(inner, p)) c.style?.let { templateStyles[c.col] = it } }

        val sb = StringBuilder(body.length + edits.size * 64)
        var pos = 0
        val newRows = byRow.keys.filter { it !in existing }.sorted().toMutableList()
        fun flushNewRowsBefore(limit: Int) {
            while (newRows.isNotEmpty() && newRows[0] < limit) {
                val r = newRows.removeAt(0)
                val cellsXml = StringBuilder()
                for (e in byRow.getValue(r).sortedBy { it.col }.distinctBy { it.col }) {
                    cellsXml.append(cellXml(p, ref(e.col, r), templateStyles[e.col], e.value)); written++
                }
                sb.append("<${p}row r=\"$r\">").append(cellsXml).append("</${p}row>")
            }
        }
        for (seg in rows) {
            flushNewRowsBefore(seg.num)
            sb.append(body, pos, seg.start)
            val rowEdits = byRow[seg.num]
            if (rowEdits == null) {
                sb.append(body, seg.start, seg.end)
            } else {
                val (rowXml, w, s) = patchRow(seg, rowEdits, p)
                sb.append(rowXml); written += w; skipped += s
            }
            pos = seg.end
        }
        sb.append(body, pos, body.length)
        flushNewRowsBefore(Int.MAX_VALUE)

        val newBody = sb.toString()
        var out = if (selfClosing) xml.substring(0, open.range.first) + "<${p}sheetData>" + newBody + "</${p}sheetData>" + xml.substring(open.range.last + 1)
        else xml.substring(0, bodyStart) + newBody + xml.substring(bodyEnd)
        val maxRow = maxOf(rows.maxOfOrNull { it.num } ?: 0, edits.maxOf { it.row })
        val maxCol = edits.maxOf { it.col }
        out = widenDimension(out, p, maxRow, maxCol)
        return Triple(out, written, skipped)
    }

    private class CellSeg(val start: Int, val end: Int, val col: Int, val style: String?, val formula: Boolean)

    private fun cells(inner: String, p: String): List<CellSeg> {
        val cellRe = Regex("<${Regex.escape(p)}c\\b([^>]*?)(?:/>|>(.*?)</${Regex.escape(p)}c>)", RegexOption.DOT_MATCHES_ALL)
        val fRe = Regex("<(?:\\w+:)?f[\\s>/]")
        return cellRe.findAll(inner).map { m ->
            val a = attrs(m.groupValues[1])
            val r = a["r"] ?: throw Problem("LAYOUT", "This file stores cells without addresses, so it cannot be updated in place")
            CellSeg(m.range.first, m.range.last + 1, XlsxReader.colIndex(r), a["s"], fRe.containsMatchIn(m.groupValues[2]))
        }.toList()
    }

    private fun patchRow(seg: RowSeg, edits: List<Edit>, p: String): Triple<String, Int, Int> {
        val inner = seg.inner.orEmpty()
        val cells = cells(inner, p)
        val wanted = edits.associateBy { it.col }.toSortedMap()
        val sb = StringBuilder(inner.length + edits.size * 64)
        var pos = 0
        var written = 0
        var skipped = 0
        val pending = wanted.keys.toMutableList()
        fun insertBefore(col: Int) {
            while (pending.isNotEmpty() && pending[0] < col) {
                val c = pending.removeAt(0)
                if (cells.none { it.col == c }) { sb.append(cellXml(p, ref(c, seg.num), null, wanted.getValue(c).value)); written++ }
            }
        }
        for (c in cells) {
            insertBefore(c.col)
            sb.append(inner, pos, c.start)
            val e = wanted[c.col]
            when {
                e == null -> sb.append(inner, c.start, c.end)
                c.formula -> { sb.append(inner, c.start, c.end); skipped++; pending.remove(c.col) }
                else -> { sb.append(cellXml(p, ref(c.col, seg.num), c.style, e.value)); written++; pending.remove(c.col) }
            }
            pos = c.end
        }
        sb.append(inner, pos, inner.length)
        insertBefore(Int.MAX_VALUE)
        // "spans" is only a loading hint and may be wrong once cells are added, so it is dropped.
        val attrs = seg.attrs.replace(Regex("\\sspans=\"[^\"]*\""), "")
        return Triple("<${p}row$attrs>$sb</${p}row>", written, skipped)
    }

    private fun ref(col: Int, row: Int) = XlsxWriter.colName(col) + row

    private fun cellXml(p: String, ref: String, style: String?, value: Any): String {
        val s = style?.let { " s=\"$it\"" } ?: ""
        return when (value) {
            is BigDecimal -> "<${p}c r=\"$ref\"$s><${p}v>${value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()}</${p}v></${p}c>"
            is Number -> "<${p}c r=\"$ref\"$s><${p}v>$value</${p}v></${p}c>"
            else -> "<${p}c r=\"$ref\"$s t=\"inlineStr\"><${p}is><${p}t xml:space=\"preserve\">${XlsxWriter.esc(value.toString())}</${p}t></${p}is></${p}c>"
        }
    }

    private fun widenDimension(xml: String, p: String, maxRow: Int, maxCol: Int): String {
        val re = Regex("<${Regex.escape(p)}dimension\\s+ref=\"([A-Za-z]+)(\\d+)(?::([A-Za-z]+)(\\d+))?\"")
        val m = re.find(xml) ?: return xml
        val startCol = m.groupValues[1]; val startRow = m.groupValues[2]
        val endCol = m.groupValues[3].ifEmpty { startCol }; val endRow = m.groupValues[4].ifEmpty { startRow }.toInt()
        val col = maxOf(XlsxReader.colIndex(endCol), maxCol)
        val row = maxOf(endRow, maxRow)
        return xml.replaceRange(m.range, "<${p}dimension ref=\"$startCol$startRow:${XlsxWriter.colName(col)}$row\"")
    }

    /** Asks Excel to recalculate every formula when the file is next opened, so totals use the new values. */
    fun recalcOnOpen(workbook: String): String {
        val calc = Regex("<((?:\\w+:)?)calcPr\\b([^>]*?)(/?)>").find(workbook)
        if (calc != null) {
            val a = calc.groupValues[2]
            val newAttrs = if (a.contains("fullCalcOnLoad=")) a.replace(Regex("fullCalcOnLoad=\"[^\"]*\""), "fullCalcOnLoad=\"1\"") else "$a fullCalcOnLoad=\"1\""
            return workbook.replaceRange(calc.range, "<${calc.groupValues[1]}calcPr$newAttrs${calc.groupValues[3]}>")
        }
        val root = Regex("<((?:\\w+:)?)workbook\\b").find(workbook) ?: return workbook
        val p = root.groupValues[1]
        // calcPr must come before these elements in a workbook part.
        val after = listOf("oleSize", "customWorkbookViews", "pivotCaches", "smartTagPr", "smartTagTypes", "webPublishing", "fileRecoveryPr", "webPublishObjects", "extLst")
        val at = after.mapNotNull { e -> Regex("<${Regex.escape(p)}$e\\b").find(workbook)?.range?.first }.minOrNull()
            ?: workbook.lastIndexOf("</${p}workbook>").takeIf { it >= 0 } ?: return workbook
        return workbook.substring(0, at) + "<${p}calcPr fullCalcOnLoad=\"1\"/>" + workbook.substring(at)
    }

    private fun putSheet(parts: LinkedHashMap<String, ByteArray>, refs: List<SheetRef>, sheet: Sheet) {
        val body = sheetXml(sheet)
        val existing = refs.firstOrNull { it.name == sheet.name }
        if (existing != null) { parts[existing.path] = body.toByteArray(Charsets.UTF_8); return }

        var path = "xl/worksheets/aistock_sales.xml"
        var k = 2
        while (parts.containsKey(path)) path = "xl/worksheets/aistock_sales${k++}.xml"
        parts[path] = body.toByteArray(Charsets.UTF_8)

        val relsName = "xl/_rels/workbook.xml.rels"
        var rels = text(parts[relsName])
        var rid = "rIdAIStock1"
        k = 2
        while (rels.contains("Id=\"$rid\"")) rid = "rIdAIStock${k++}"
        val relClose = rels.lastIndexOf("</Relationships>").takeIf { it >= 0 } ?: throw Problem("LAYOUT", "The workbook's parts list is damaged")
        rels = rels.substring(0, relClose) + "<Relationship Id=\"$rid\" Type=\"$WS_TYPE\" Target=\"${path.removePrefix("xl/")}\"/>" + rels.substring(relClose)
        parts[relsName] = rels.toByteArray(Charsets.UTF_8)

        var wb = text(parts["xl/workbook.xml"])
        val first = Regex("<((?:\\w+:)?)sheet\\b([^>]*?)/?>").find(wb) ?: throw Problem("LAYOUT", "The workbook has no sheets")
        val p = first.groupValues[1]
        val idAttr = attrs(first.groupValues[2]).keys.firstOrNull { it == "id" || it.endsWith(":id") } ?: "r:id"
        val maxId = Regex("<(?:\\w+:)?sheet\\b([^>]*?)/?>").findAll(wb).mapNotNull { attrs(it.groupValues[1])["sheetId"]?.toIntOrNull() }.maxOrNull() ?: 0
        val close = wb.indexOf("</${p}sheets>").takeIf { it >= 0 } ?: throw Problem("LAYOUT", "The workbook has no sheet list")
        wb = wb.substring(0, close) + "<${p}sheet name=\"${XlsxWriter.esc(sheet.name)}\" sheetId=\"${maxId + 1}\" $idAttr=\"$rid\"/>" + wb.substring(close)
        parts["xl/workbook.xml"] = wb.toByteArray(Charsets.UTF_8)

        val ctName = "[Content_Types].xml"
        var ct = text(parts[ctName])
        val ctClose = ct.lastIndexOf("</Types>")
        if (ctClose >= 0 && !ct.contains("PartName=\"/$path\"")) {
            ct = ct.substring(0, ctClose) + "<Override PartName=\"/$path\" ContentType=\"$WS_CONTENT\"/>" + ct.substring(ctClose)
            parts[ctName] = ct.toByteArray(Charsets.UTF_8)
        }
    }

    private fun sheetXml(sheet: Sheet): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        val cols = sheet.rows.maxOfOrNull { it.size } ?: 0
        if (cols > 0) {
            append("<cols>")
            for (c in 0 until cols) {
                val w = sheet.rows.take(200).maxOfOrNull { (it.getOrNull(c)?.toString()?.length ?: 0) }?.coerceIn(8, 50) ?: 10
                append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"${w + 2}\" customWidth=\"1\"/>")
            }
            append("</cols>")
        }
        append("<sheetData>")
        for ((r, row) in sheet.rows.withIndex()) {
            append("<row r=\"${r + 1}\">")
            for ((c, cell) in row.withIndex()) if (cell != null) append(cellXml("", ref(c, r + 1), null, cell))
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }
}
