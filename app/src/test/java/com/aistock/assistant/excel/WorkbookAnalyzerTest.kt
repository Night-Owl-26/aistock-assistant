package com.aistock.assistant.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WorkbookAnalyzerTest {
    private fun workbook(): ByteArray {
        val sheets = listOf(
            Sheet("ITEM MASTER", listOf(listOf<Any?>("SRI MURUGAN TEXTILES"), listOf<Any?>(), listOf<Any?>("ITEM CODE", "DESCRIPTION", "UOM", "RATE"), listOf<Any?>("BC40", "Blue Cotton", "meter", 150), listOf<Any?>("RD20", "Red Denim", "meter", 320))),
            Sheet("STOCK", listOf(listOf<Any?>("ITEM CODE", "OPENING", "INWARD", "OUTWARD", "CLOSING STOCK"), listOf<Any?>("BC40", 400, 100, 14, 486), listOf<Any?>("RD20", 50, 0, 10, 40))),
            Sheet("CUSTOMER LIST", listOf(listOf<Any?>("PARTY NAME", "MOBILE", "CITY", "GSTIN"), listOf<Any?>("Kumar Traders", "9876543210", "Tiruppur", "33ABCDE1234F1Z5"))),
            Sheet("SUPPLIER DETAILS", listOf(listOf<Any?>("VENDOR", "PHONE"), listOf<Any?>("ABC Textiles", "9123456780"))),
            Sheet("SALES 2026", listOf(listOf<Any?>("DATE", "INVOICE NO", "CUSTOMER", "AMOUNT"), listOf<Any?>("01-04-2026", "S1", "Kumar Traders", 4200))),
            Sheet("PURCHASE 2026", listOf(listOf<Any?>("DATE", "BILL NO", "SUPPLIER", "AMOUNT"), listOf<Any?>("02-04-2026", "P1", "ABC Textiles", 9000))),
            Sheet("EXPENSE", listOf(listOf<Any?>("DATE", "HEAD", "AMOUNT"), listOf<Any?>("03-04-2026", "Transport", 500))),
            Sheet("Notes", listOf(listOf<Any?>("remember to call the bank"))),
        )
        return ByteArrayOutputStream().also { XlsxWriter.write(it, sheets) }.toByteArray()
    }

    @Test
    fun everySheetIsReadAndUnderstoodWhateverItIsCalled() {
        val raw = XlsxReader.readAll(ByteArrayInputStream(workbook()))
        assertEquals(listOf("ITEM MASTER", "STOCK", "CUSTOMER LIST", "SUPPLIER DETAILS", "SALES 2026", "PURCHASE 2026", "EXPENSE", "Notes"), raw.map { it.name })
        val p = WorkbookAnalyzer.analyze(raw).associateBy { it.name }
        assertEquals(SheetPurpose.PRODUCTS, p.getValue("ITEM MASTER").purpose)
        assertEquals(SheetPurpose.STOCK, p.getValue("STOCK").purpose)
        assertEquals(SheetPurpose.CUSTOMERS, p.getValue("CUSTOMER LIST").purpose)
        assertEquals(SheetPurpose.SUPPLIERS, p.getValue("SUPPLIER DETAILS").purpose)
        assertEquals(SheetPurpose.SALES, p.getValue("SALES 2026").purpose)
        assertEquals(SheetPurpose.PURCHASES, p.getValue("PURCHASE 2026").purpose)
        assertEquals(SheetPurpose.EXPENSES, p.getValue("EXPENSE").purpose)
        assertEquals(SheetPurpose.OTHER, p.getValue("Notes").purpose)
        assertTrue(p.getValue("ITEM MASTER").confidence >= 60)
    }

    @Test
    fun titleRowsAboveTheHeaderAreSkippedAndColumnsAreTyped() {
        val item = WorkbookAnalyzer.analyze(XlsxReader.readAll(ByteArrayInputStream(workbook()))).first()
        assertEquals(listOf("ITEM CODE", "DESCRIPTION", "UOM", "RATE"), item.headers)
        assertEquals(2, item.rowCount)
        assertEquals("number", item.columns[3].type)
        assertEquals("text", item.columns[1].type)
        assertTrue(item.links.toString(), item.links.any { it.startsWith("STOCK") })
    }

    @Test
    fun unusualColumnNamesStillMapToTheRightFields() {
        val profiles = WorkbookAnalyzer.analyze(XlsxReader.readAll(ByteArrayInputStream(workbook())))
        val order = WorkbookAnalyzer.importOrder(profiles)
        assertEquals(listOf(ImportKind.PRODUCTS, ImportKind.OPENING_STOCK, ImportKind.CUSTOMERS, ImportKind.SUPPLIERS), order.map { it.second })
        val item = order[0].first
        val map = ImportSpec.autoMap(item.headers, ImportKind.PRODUCTS)
        assertEquals(1, map["name"]); assertEquals(0, map["code"]); assertEquals(2, map["unit"]); assertEquals(3, map["sellingPrice"])
        val stock = ImportSpec.autoMap(order[1].first.headers, ImportKind.OPENING_STOCK)
        assertEquals(0, stock["name"]); assertEquals(4, stock["quantity"])
        val cust = ImportSpec.autoMap(order[2].first.headers, ImportKind.CUSTOMERS)
        assertEquals(0, cust["name"]); assertEquals(1, cust["phone"])
    }

    @Test
    fun aStockSheetAloneBecomesTheProductList() {
        val raw = listOf(RawSheet("STOCK DETAILS", false, listOf(listOf("DESCRIPTION", "UNIT", "SELLING RATE", "CLOSING STOCK"), listOf("Blue Shirt", "pcs", "450", "80"))))
        val order = WorkbookAnalyzer.importOrder(WorkbookAnalyzer.analyze(raw))
        assertEquals(ImportKind.PRODUCTS, order.single().second)
        val map = ImportSpec.autoMap(order.single().first.headers, ImportKind.PRODUCTS)
        assertEquals(0, map["name"]); assertEquals(3, map["openingStock"]); assertEquals(2, map["sellingPrice"])
    }
}
