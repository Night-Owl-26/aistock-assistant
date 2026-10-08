package com.aistock.assistant.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

class ExcelTest {
    @Test
    fun xlsxRoundTripKeepsTextNumbersAndTamil() {
        val rows = listOf(
            listOf<Any?>("Name", "Unit", "Selling Price", "Stock"),
            listOf<Any?>("Blue Shirt", "PCS", BigDecimal("450.50"), 100L),
            listOf<Any?>("நீல சட்டை <A&B>", "PCS", BigDecimal("1200"), null),
        )
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(bos, listOf(Sheet("Products", rows), Sheet("Second", listOf(listOf("x")))))
        val back = XlsxReader.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(3, back.size)
        assertEquals(listOf("Name", "Unit", "Selling Price", "Stock"), back[0])
        assertEquals(listOf("Blue Shirt", "PCS", "450.5", "100"), back[1])
        assertEquals(listOf("நீல சட்டை <A&B>", "PCS", "1200", ""), back[2])
    }

    @Test
    fun columnNames() {
        assertEquals("A", XlsxWriter.colName(0))
        assertEquals("Z", XlsxWriter.colName(25))
        assertEquals("AA", XlsxWriter.colName(26))
        assertEquals("AB", XlsxWriter.colName(27))
    }

    @Test
    fun csvHandlesQuotesCommasAndBom() {
        val text = "﻿Name,Phone,Address\r\n\"Kumar, Traders\",9876543210,\"12 \"\"Main\"\" Road\"\r\n\r\nRavi,,Tiruppur\r\n"
        val rows = Csv.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        assertEquals(3, rows.size)
        assertEquals("Kumar, Traders", rows[1][0])
        assertEquals("12 \"Main\" Road", rows[1][2])
        assertEquals(listOf("Ravi", "", "Tiruppur"), rows[2])
        val out = ByteArrayOutputStream()
        Csv.write(out, rows)
        assertEquals(rows, Csv.read(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun headersAreMappedAutomatically() {
        val map = ImportSpec.autoMap(listOf("Item Name", "Purchase Price", "Price", "Qty", "UOM", "Min Stock"), ImportKind.PRODUCTS)
        assertEquals(0, map["name"])
        assertEquals(1, map["purchasePrice"])
        assertEquals(2, map["sellingPrice"])
        assertEquals(3, map["openingStock"])
        assertEquals(4, map["unit"])
        assertEquals(5, map["minStock"])
    }

    @Test
    fun previewNeverOverwritesAndReportsBadRows() {
        val table = listOf(
            listOf("Name", "Price", "Stock", "Unit"),
            listOf("Blue Shirt", "450", "100", "pieces"),
            listOf("Black Shirt", "abc", "10", "pcs"),
            listOf("", "100", "5", ""),
            listOf("A4 Paper", "250", "20", "boxes"),
            listOf("a4 papers", "250", "20", "box"),
            listOf("Cotton Yarn", "180", "-4", "kg"),
        )
        val mapping = ImportSpec.autoMap(table[0], ImportKind.PRODUCTS)
        val p = ImportSpec.preview(ImportKind.PRODUCTS, table, mapping, listOf("blue shirts"))
        assertEquals(listOf("A4 Paper"), p.ready.map { it.values["name"] })
        assertEquals("BOX", p.ready[0].values["unit"])
        assertEquals(2, p.skipped.size)
        assertTrue(p.skipped[0].message, p.skipped[0].message.contains("already exists"))
        assertEquals(3, p.errors.size)
        assertEquals(listOf(3, 4, 7), p.errors.map { it.rowNumber })
    }

    @Test
    fun openingStockNeedsKnownProduct() {
        val table = listOf(listOf("Product", "Quantity"), listOf("Blue Shirt", "40"), listOf("Unknown Thing", "5"))
        val p = ImportSpec.preview(ImportKind.OPENING_STOCK, table, ImportSpec.autoMap(table[0], ImportKind.OPENING_STOCK), listOf("Blue Shirt"))
        assertEquals(1, p.ready.size)
        assertEquals(1, p.errors.size)
    }

    @Test
    fun missingRequiredColumnIsReported() {
        val table = listOf(listOf("Foo", "Bar"), listOf("x", "y"))
        val p = ImportSpec.preview(ImportKind.PRODUCTS, table, emptyMap(), emptyList())
        assertTrue(p.ready.isEmpty())
        assertEquals(1, p.errors.size)
    }
}
