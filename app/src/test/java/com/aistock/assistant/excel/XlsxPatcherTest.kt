package com.aistock.assistant.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class XlsxPatcherTest {
    private fun link(sheet: String = "Items", size: String = "Size", price: String = "Rate", addRows: Boolean = false, createdAt: Long = 1000) = ExcelLinkEntity(
        "L1", "content://x", "shop.xlsx", sheet, "Item", 0, size, if (size.isEmpty()) -1 else 1, "Stock", 2, price, if (price.isEmpty()) -1 else 3,
        enabled = true, addNewRows = addRows, salesSheet = false, lastSyncAt = null, lastResult = "", createdAt = createdAt,
    )

    private fun workbook(): ByteArray {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(out, listOf(
            Sheet("Notes", listOf(listOf("Shop notes"), listOf("Keep this"))),
            Sheet("Items", listOf(
                listOf("Item", "Size", "Stock", "Rate", "Remarks"),
                listOf("Shirt", "XL", BigDecimal(12), BigDecimal(450), "fast"),
                listOf("Shirt", "L", BigDecimal(8), BigDecimal(430), null),
                listOf("Pant", "32", BigDecimal(5), BigDecimal(799), "slow"),
                listOf("Old item", null, BigDecimal(1), null, null),
            )),
        ))
        return out.toByteArray()
    }

    private fun sheet(bytes: ByteArray, name: String) = XlsxReader.readAll(ByteArrayInputStream(bytes)).first { it.name == name }.rows

    private val products = listOf(
        SyncProduct("Shirt XL", 9_000, 45_000, 500),
        SyncProduct("Shirt L", 8_000, 43_000, 500),
        SyncProduct("Pant 32", 5_500, 79_900, 500),
    )

    @Test
    fun onlyTheStockCellsThatChangedAreWritten() {
        val before = workbook()
        val u = ExcelSyncPlan.update(before, link(), products, null)
        val after = assertNotNullBytes(u.bytes)
        assertEquals("UPDATED", u.outcome.code)
        assertEquals(2, u.outcome.changed) // Shirt XL 12 -> 9 and Pant 32 5 -> 5.5
        assertEquals(1, u.outcome.notInApp) // "Old item" is left alone
        val rows = sheet(after, "Items")
        assertEquals(listOf("Shirt", "XL", "9", "450", "fast"), rows[1])
        assertEquals(listOf("Shirt", "L", "8", "430", ""), rows[2])
        assertEquals(listOf("Pant", "32", "5.5", "799", "slow"), rows[3])
        assertEquals(listOf("Old item", "", "1", "", ""), rows[4])
        assertEquals(sheet(before, "Notes"), sheet(after, "Notes"))
        // Nothing left to do the second time.
        val again = ExcelSyncPlan.update(after, link(), products, null)
        assertNull(again.bytes)
        assertEquals("UP_TO_DATE", again.outcome.code)
    }

    @Test
    fun rowsAreFoundByNameEvenAfterTheOwnerSortsThem() {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(out, listOf(Sheet("Items", listOf(
            listOf("Title row of the shop"),
            listOf(null),
            listOf("Remarks", "Item", "Size", "Rate", "Stock"),
            listOf(null, "Pant", "32", BigDecimal(799), BigDecimal(5)),
            listOf("x", "Shirt", "XL", BigDecimal(450), BigDecimal(12)),
        ))))
        val u = ExcelSyncPlan.update(out.toByteArray(), link(), products, null)
        val rows = sheet(assertNotNullBytes(u.bytes), "Items")
        assertEquals(listOf("x", "Shirt", "XL", "450", "9"), rows.first { it.getOrNull(1) == "Shirt" })
        assertEquals(listOf("", "Pant", "32", "799", "5.5"), rows.first { it.getOrNull(1) == "Pant" })
    }

    @Test
    fun newProductsAreAddedAtTheBottomOnlyWhenTheOwnerAskedFor() {
        val withNew = products + SyncProduct("Cap", 3_000, 15_000, 2000) + SyncProduct("Old Belt", 1_000, 0, 10)
        val off = ExcelSyncPlan.update(workbook(), link(addRows = false), withNew, null)
        assertEquals(0, off.outcome.added)
        val on = ExcelSyncPlan.update(workbook(), link(addRows = true), withNew, null)
        assertEquals(1, on.outcome.added) // only products created after the link was made
        val rows = sheet(assertNotNullBytes(on.bytes), "Items")
        assertEquals(listOf("Cap", "", "3", "150", ""), rows.last())
    }

    @Test
    fun renamedColumnsStopTheUpdateInsteadOfWritingIntoTheWrongCells() {
        try {
            ExcelSyncPlan.update(workbook(), link().copy(stockHeader = "Closing"), products, null)
            fail("should stop")
        } catch (e: ExcelSyncPlan.Problem) {
            assertEquals("COLUMNS", e.code)
        }
        assertEquals("NO_SHEET", ExcelSyncPlan.update(workbook(), link(sheet = "Gone"), products, null).outcome.code)
    }

    @Test
    fun salesSheetIsAddedOnceAndRefreshedOnlyWhenItChanges() {
        val sales = Sheet("AIStock Sales", listOf(listOf("Total revenue", BigDecimal("1250.50")), listOf("Date", "Bill no", "Total"), listOf("01-10-2026", "S-1", BigDecimal("1250.5"))))
        val first = ExcelSyncPlan.update(workbook(), link(), products, sales)
        val bytes = assertNotNullBytes(first.bytes)
        val all = XlsxReader.readAll(ByteArrayInputStream(bytes))
        assertEquals(listOf("Notes", "Items", "AIStock Sales"), all.map { it.name })
        assertEquals(listOf("Total revenue", "1250.5", ""), all[2].rows[0])
        val parts = XlsxPatcher.parts(bytes)
        assertTrue(String(parts.getValue("[Content_Types].xml")).contains("/xl/worksheets/aistock_sales.xml"))
        assertTrue(String(parts.getValue("xl/workbook.xml")).contains("fullCalcOnLoad=\"1\""))
        // Same content again: the file is not rewritten.
        assertNull(ExcelSyncPlan.update(bytes, link(), products, sales).bytes)
        // A new bill: the existing sheet is replaced, not added a second time.
        val more = sales.copy(rows = sales.rows + listOf(listOf("02-10-2026", "S-2", BigDecimal(10))))
        val second = assertNotNullBytes(ExcelSyncPlan.update(bytes, link(), products, more).bytes)
        val names = XlsxReader.readAll(ByteArrayInputStream(second)).map { it.name }
        assertEquals(listOf("Notes", "Items", "AIStock Sales"), names)
    }

    /** A file written the way Excel writes it: shared strings, styles, a formula, a prefixed namespace, a dimension. */
    private fun excelStyleFile(): ByteArray {
        val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val parts = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0"?><x:workbook xmlns:x="$ns" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><x:sheets><x:sheet name="Stock &amp; Price" sheetId="7" r:id="rId3"/></x:sheets><x:extLst/></x:workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="/xl/worksheets/sheet1.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to """<?xml version="1.0"?><sst xmlns="$ns"><si><t>Item</t></si><si><t>Stock</t></si><si><t>Shirt XL</t></si><si><t>Pant 32</t></si><si><t>Total</t></si></sst>""",
            "xl/worksheets/sheet1.xml" to """<?xml version="1.0"?><x:worksheet xmlns:x="$ns"><x:dimension ref="A1:B4"/><x:sheetData>""" +
                """<x:row r="1" spans="1:2"><x:c r="A1" t="s"><x:v>0</x:v></x:c><x:c r="B1" t="s"><x:v>1</x:v></x:c></x:row>""" +
                """<x:row r="2" spans="1:2"><x:c r="A2" s="4" t="s"><x:v>2</x:v></x:c><x:c r="B2" s="5"><x:v>12</x:v></x:c></x:row>""" +
                """<x:row r="3" spans="1:2"><x:c r="A3" s="4" t="s"><x:v>3</x:v></x:c><x:c r="B3" s="5"><x:f>2+3</x:f><x:v>5</x:v></x:c></x:row>""" +
                """<x:row r="4"><x:c r="A4" t="s"><x:v>4</x:v></x:c><x:c r="B4"><x:f>SUM(B2:B3)</x:f><x:v>17</x:v></x:c></x:row>""" +
                """</x:sheetData><x:pageMargins left="0.7"/></x:worksheet>""",
            "xl/media/logo.bin" to "\u0001\u0002binary",
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((k, v) in parts) { z.putNextEntry(ZipEntry(k)); z.write(v.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test
    fun excelFilesKeepFormulasStylesAndEveryOtherPart() {
        val before = excelStyleFile()
        val l = link(sheet = "Stock & Price", size = "", price = "").copy(nameCol = 0, stockCol = 1)
        val list = listOf(SyncProduct("Shirt XL", 9_000, 0, 0), SyncProduct("Pant 32", 7_000, 0, 0), SyncProduct("Belt", 2_000, 0, 5000))
        val u = ExcelSyncPlan.update(before, l.copy(addNewRows = true), list, null)
        assertEquals(1, u.outcome.formula) // Pant 32's stock is a formula: left as it is
        assertEquals(1, u.outcome.added)
        val after = assertNotNullBytes(u.bytes)
        val parts = XlsxPatcher.parts(after)
        val xml = String(parts.getValue("xl/worksheets/sheet1.xml"))
        assertTrue(xml.contains("""<x:c r="B2" s="5"><x:v>9</x:v></x:c>"""))
        assertTrue(xml.contains("<x:f>2+3</x:f>"))
        assertTrue(xml.contains("<x:f>SUM(B2:B3)</x:f>"))
        assertTrue(xml.contains("""<x:row r="5"><x:c r="A5" t="inlineStr"><x:is><x:t xml:space="preserve">Belt</x:t></x:is></x:c><x:c r="B5"><x:v>2</x:v></x:c></x:row>"""))
        assertTrue(xml.contains("""<x:dimension ref="A1:B5""""))
        assertFalse(xml.contains("""<x:row r="2" spans"""))
        assertTrue(xml.contains("""<x:row r="1" spans="1:2">""")) // untouched rows stay byte for byte
        val wb = String(parts.getValue("xl/workbook.xml"))
        assertTrue(wb.contains("""<x:calcPr fullCalcOnLoad="1"/><x:extLst/>"""))
        assertEquals("\u0001\u0002binary", String(parts.getValue("xl/media/logo.bin")))
        assertEquals(String(XlsxPatcher.parts(before).getValue("xl/sharedStrings.xml")), String(parts.getValue("xl/sharedStrings.xml")))
        val grid = XlsxPatcher.grid(after, "Stock & Price")!!
        assertEquals("9", grid.cell(2, 1)!!.text)
        assertEquals("Belt", grid.cell(5, 0)!!.text)
        assertTrue(grid.cell(3, 1)!!.formula)
    }

    @Test
    fun anExistingCalcSettingIsKept() {
        assertEquals("<calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/>", XlsxPatcher.recalcOnOpen("<calcPr calcId=\"191029\"/>"))
        assertEquals("<calcPr fullCalcOnLoad=\"1\"/>", XlsxPatcher.recalcOnOpen("<calcPr fullCalcOnLoad=\"0\"/>"))
    }

    @Test
    fun anythingThatIsNotAWorkbookIsRefused() {
        try { XlsxPatcher.parts("hello".toByteArray()); fail() } catch (e: XlsxPatcher.Problem) { assertEquals("NOT_XLSX", e.code) }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("a.txt")); z.write(1); z.closeEntry() }
        try { XlsxPatcher.parts(out.toByteArray()); fail() } catch (e: XlsxPatcher.Problem) { assertEquals("NOT_XLSX", e.code) }
    }

    @Test
    fun outcomesSurviveBeingStored() {
        val o = SyncOutcome(true, "UPDATED", 3, 1, 2, 4)
        assertEquals(o, SyncOutcome.decode(o.encode()))
        val e = SyncOutcome(false, "COLUMNS", detail = "a|b")
        assertEquals("COLUMNS", SyncOutcome.decode(e.encode())!!.code)
        assertNull(SyncOutcome.decode(""))
    }

    private fun assertNotNullBytes(b: ByteArray?): ByteArray { assertNotNull(b); return b!! }

    @Suppress("unused")
    private fun entries(bytes: ByteArray): List<String> = ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
}
