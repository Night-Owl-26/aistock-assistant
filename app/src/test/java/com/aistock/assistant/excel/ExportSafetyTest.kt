package com.aistock.assistant.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

/** Exported files must not turn a customer's or product's name into a spreadsheet formula. */
class ExportSafetyTest {
    private fun csv(rows: List<List<Any?>>): String = ByteArrayOutputStream().also { Csv.write(it, rows) }.toString("UTF-8").removePrefix("﻿")

    @Test
    fun formulaLikeTextStaysText() {
        val out = csv(listOf(listOf("=HYPERLINK(\"http://x\",\"click\")", "+91 98765", "-cash", "@SUM(A1)", "Kumar Traders", BigDecimal("-12.50"), 42L)))
        val cells = out.trim()
        assertTrue(cells, cells.startsWith("\"'=HYPERLINK"))
        assertTrue(cells.contains(",'+91 98765,"))
        assertTrue(cells.contains(",'-cash,"))
        assertTrue(cells.contains(",'@SUM(A1),"))
        assertTrue("numbers stay numbers", cells.endsWith(",Kumar Traders,-12.50,42"))
    }

    @Test
    fun ordinaryValuesUnchanged() {
        assertEquals("Shirt XL,450\r\n", csv(listOf(listOf("Shirt XL", 450L))))
    }

    @Test
    fun hugeCsvIsRefused() {
        val big = ByteArray(Csv.MAX_FILE + 10) { 'a'.code.toByte() }
        try { Csv.read(big.inputStream()); throw AssertionError("should refuse") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("too large")) }
    }
}
