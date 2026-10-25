package com.aistock.assistant.excel

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.SAXParserFactory

/** One worksheet: the first row is the header. Cells may be String, Number or null. */
data class Sheet(val name: String, val rows: List<List<Any?>>)

/** Small, dependency-free writer for real .xlsx files that Excel, Google Sheets and LibreOffice open. */
object XlsxWriter {
    fun write(out: OutputStream, sheets: List<Sheet>) {
        require(sheets.isNotEmpty()) { "at least one sheet" }
        val names = uniqueNames(sheets.map { it.name })
        ZipOutputStream(out).use { zip ->
            fun entry(path: String, body: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            entry("[Content_Types].xml", head + buildString {
                append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
                for (i in sheets.indices) append("<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
                append("</Types>")
            })
            entry("_rels/.rels", head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            entry("xl/workbook.xml", head + buildString {
                append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
                for (i in sheets.indices) append("<sheet name=\"${esc(names[i])}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
                append("</sheets></workbook>")
            })
            entry("xl/_rels/workbook.xml.rels", head + buildString {
                append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                for (i in sheets.indices) append("<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i + 1}.xml\"/>")
                append("<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
                append("</Relationships>")
            })
            entry("xl/styles.xml", head + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
                "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
                "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
                "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs></styleSheet>")
            for ((i, sheet) in sheets.withIndex()) {
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet${i + 1}.xml"))
                val sb = StringBuilder(head)
                sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                val cols = sheet.rows.maxOfOrNull { it.size } ?: 0
                if (cols > 0) {
                    sb.append("<cols>")
                    for (c in 0 until cols) {
                        val w = sheet.rows.take(200).maxOfOrNull { (it.getOrNull(c)?.toString()?.length ?: 0) }?.coerceIn(8, 50) ?: 10
                        sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"${w + 2}\" customWidth=\"1\"/>")
                    }
                    sb.append("</cols>")
                }
                sb.append("<sheetData>")
                for ((r, row) in sheet.rows.withIndex()) {
                    sb.append("<row r=\"${r + 1}\">")
                    for ((c, cell) in row.withIndex()) {
                        if (cell == null) continue
                        val ref = colName(c) + (r + 1)
                        val style = if (r == 0) " s=\"1\"" else ""
                        if (cell is Number) {
                            val v = if (cell is BigDecimal) cell.toPlainString() else cell.toString()
                            sb.append("<c r=\"$ref\"$style><v>$v</v></c>")
                        } else {
                            sb.append("<c r=\"$ref\"$style t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(cell.toString())}</t></is></c>")
                        }
                    }
                    sb.append("</row>")
                    if (sb.length > 64_000) { zip.write(sb.toString().toByteArray(Charsets.UTF_8)); sb.setLength(0) }
                }
                sb.append("</sheetData></worksheet>")
                zip.write(sb.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun uniqueNames(raw: List<String>): List<String> {
        val used = HashSet<String>()
        return raw.mapIndexed { i, n ->
            var name = n.replace(Regex("[\\\\/?*\\[\\]:]"), " ").trim().take(31).ifEmpty { "Sheet${i + 1}" }
            var k = 2
            while (!used.add(name.lowercase())) { name = (n.take(27) + " " + k++).trim() }
            name
        }
    }

    fun colName(index: Int): String {
        var i = index
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + i % 26))
            i = i / 26 - 1
        } while (i >= 0)
        return sb.toString()
    }

    internal fun esc(s: String): String = buildString(s.length + 8) {
        for (ch in s) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch == '\n' || ch == '\t' || ch == '\r' -> append(ch)
            ch < ' ' -> append(' ')
            else -> append(ch)
        }
    }
}

/** One worksheet as plain rows of text. */
data class RawSheet(val name: String, val hidden: Boolean, val rows: List<List<String>>)

/** Reads .xlsx files into rows of text. */
object XlsxReader {
    private const val MAX_ENTRY = 40L * 1024 * 1024

    fun read(input: InputStream): List<List<String>> {
        val parts = unzip(input)
        val sheetPath = firstSheetPath(parts) ?: throw IllegalArgumentException("This is not a valid Excel file")
        val shared = parts["xl/sharedStrings.xml"]?.let { readSharedStrings(it) } ?: emptyList()
        return readSheet(parts.getValue(sheetPath), shared)
    }

    /** Every sheet of the workbook, in workbook order, with its name and whether it is hidden. */
    fun readAll(input: InputStream): List<RawSheet> {
        val parts = unzip(input)
        val shared = parts["xl/sharedStrings.xml"]?.let { readSharedStrings(it) } ?: emptyList()
        val listed = ArrayList<Triple<String, String, Boolean>>() // name, relationship id, hidden
        parts["xl/workbook.xml"]?.let { bytes ->
            parser().parse(InputSource(ByteArrayInputStream(bytes)), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    if (local(qName) == "sheet") {
                        listed.add(Triple(a.getValue("name").orEmpty(), (a.getValue("r:id") ?: a.getValue("id")).orEmpty(), (a.getValue("state") ?: "visible") != "visible"))
                    }
                }
            })
        }
        val targets = HashMap<String, String>()
        parts["xl/_rels/workbook.xml.rels"]?.let { bytes ->
            parser().parse(InputSource(ByteArrayInputStream(bytes)), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    if (local(qName) == "Relationship") targets[a.getValue("Id").orEmpty()] = a.getValue("Target").orEmpty()
                }
            })
        }
        val out = ArrayList<RawSheet>()
        for ((name, rid, hidden) in listed) {
            val t = targets[rid] ?: continue
            val path = if (t.startsWith("/")) t.removePrefix("/") else "xl/$t"
            val bytes = parts[path] ?: continue
            out.add(RawSheet(name.ifBlank { "Sheet${out.size + 1}" }, hidden, readSheet(bytes, shared)))
        }
        if (out.isEmpty()) {
            val path = firstSheetPath(parts) ?: throw IllegalArgumentException("This is not a valid Excel file")
            out.add(RawSheet("Sheet1", false, readSheet(parts.getValue(path), shared)))
        }
        return out
    }

    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                if (name == "xl/sharedStrings.xml" || name == "xl/workbook.xml" || name == "xl/_rels/workbook.xml.rels" || name.startsWith("xl/worksheets/")) {
                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ENTRY) throw IllegalArgumentException("File is too large")
                        bos.write(buf, 0, n)
                    }
                    parts[name] = bos.toByteArray()
                }
            }
        }
        return parts
    }

    internal fun parser() = SAXParserFactory.newInstance().apply {
        isNamespaceAware = false
        for (f in listOf("http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false)) {
            try { setFeature(f.first, f.second) } catch (e: Exception) { /* not supported by this parser */ }
        }
    }.newSAXParser()

    internal fun local(q: String) = q.substringAfter(':')

    private fun firstSheetPath(parts: Map<String, ByteArray>): String? {
        var firstRid: String? = null
        parts["xl/workbook.xml"]?.let { bytes ->
            parser().parse(InputSource(ByteArrayInputStream(bytes)), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                    if (local(qName) == "sheet" && firstRid == null) firstRid = a.getValue("r:id") ?: a.getValue("id")
                }
            })
        }
        var target: String? = null
        val rid = firstRid
        if (rid != null) {
            parts["xl/_rels/workbook.xml.rels"]?.let { bytes ->
                parser().parse(InputSource(ByteArrayInputStream(bytes)), object : DefaultHandler() {
                    override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                        if (local(qName) == "Relationship" && a.getValue("Id") == rid) target = a.getValue("Target")
                    }
                })
            }
        }
        val t = target
        if (t != null) {
            val path = if (t.startsWith("/")) t.removePrefix("/") else "xl/$t"
            if (parts.containsKey(path)) return path
        }
        return parts.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.minOrNull()
    }

    internal fun readSharedStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        parser().parse(InputSource(ByteArrayInputStream(bytes)), object : DefaultHandler() {
            val sb = StringBuilder()
            var inT = false
            var inPhonetic = false
            override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                when (local(qName)) {
                    "si" -> sb.setLength(0)
                    "rPh" -> inPhonetic = true
                    "t" -> inT = !inPhonetic
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (local(qName)) {
                    "si" -> out.add(sb.toString())
                    "rPh" -> inPhonetic = false
                    "t" -> inT = false
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inT) sb.append(ch, start, length)
            }
        })
        return out
    }

    private fun readSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<MutableList<String>>()
        parser().parse(InputSource(ByteArrayInputStream(bytes)), object : DefaultHandler() {
            var row: MutableList<String>? = null
            var col = 0
            var type: String? = null
            val v = StringBuilder()
            var capture = false
            override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                when (local(qName)) {
                    "row" -> { row = ArrayList(); col = 0 }
                    "c" -> {
                        val ref = a.getValue("r")
                        if (ref != null) col = colIndex(ref) else if (row != null) col = row!!.size
                        type = a.getValue("t")
                        v.setLength(0)
                    }
                    "v" -> capture = true
                    "t" -> capture = type == "inlineStr"
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (local(qName)) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val r = row ?: return
                        val raw = v.toString()
                        val text = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            "inlineStr", "str", "e" -> raw
                            else -> cleanNumber(raw.trim())
                        }
                        while (r.size < col) r.add("")
                        if (r.size == col) r.add(text) else r[col] = text
                    }
                    "row" -> { row?.let { rows.add(it) }; row = null }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture) v.append(ch, start, length)
            }
        })
        return Tables.rectangular(rows)
    }

    internal fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1)
            else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1)
            else break
        }
        return (n - 1).coerceAtLeast(0)
    }

    /** Excel stores 250 as "250" but sometimes "250.0" or "2.5E2": show it the way a person typed it. */
    internal fun cleanNumber(raw: String): String {
        if (raw.isEmpty()) return ""
        return try {
            val bd = BigDecimal(raw).stripTrailingZeros()
            (if (bd.scale() < 0) bd.setScale(0) else bd).toPlainString()
        } catch (e: NumberFormatException) {
            raw
        }
    }
}

object Tables {
    /** Drops fully empty rows and pads the rest to the same width. */
    fun rectangular(rows: List<List<String>>): List<List<String>> {
        val kept = rows.map { r -> r.map { it.trim() } }.filter { r -> r.any { it.isNotEmpty() } }
        val width = kept.maxOfOrNull { it.size } ?: 0
        return kept.map { r -> if (r.size == width) r else r + List(width - r.size) { "" } }
    }
}

object Csv {
    fun read(input: InputStream): List<List<String>> {
        var text = input.readBytes().toString(Charsets.UTF_8)
        if (text.startsWith("﻿")) text = text.substring(1)
        return Tables.rectangular(parse(text))
    }

    fun parse(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delim = listOf(',', ';', '\t').maxByOrNull { d -> firstLine.count { it == d } } ?: ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                delim -> { row.add(cell.toString()); cell.setLength(0) }
                '\r' -> {}
                '\n' -> { row.add(cell.toString()); cell.setLength(0); rows.add(row); row = ArrayList() }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    fun write(out: OutputStream, rows: List<List<Any?>>) {
        val sb = StringBuilder("﻿")
        for (r in rows) {
            sb.append(r.joinToString(",") { c ->
                val s = when (c) { null -> ""; is BigDecimal -> c.toPlainString(); else -> c.toString() }
                if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
            })
            sb.append("\r\n")
        }
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }
}
