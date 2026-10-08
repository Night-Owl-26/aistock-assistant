package com.aistock.assistant.utils

import com.aistock.assistant.ai.AICommand
import com.aistock.assistant.ai.CommandValidator
import com.aistock.assistant.ai.Entities
import com.aistock.assistant.ai.Intent
import com.aistock.assistant.ai.LocalParser
import com.aistock.assistant.ai.NumberWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ParsingTest {
    // ---- money
    @Test
    fun moneyParsing() {
        assertEquals(50_000L, Money.parse("₹500"))
        assertEquals(50_000L, Money.parse("500 rupees"))
        assertEquals(50_000L, Money.parse("500 INR"))
        assertEquals(125_050L, Money.parse("1,250.50"))
        assertEquals(1L, Money.parse("0.005"))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("-5"))
        assertEquals(50_000L, LocalParser.parse("paid five hundred rupees for tea").entities.amountPaise)
    }

    @Test
    fun moneyFormattingUsesIndianGrouping() {
        assertEquals("₹0", Money.format(0))
        assertEquals("₹2,500", Money.format(250_000))
        assertEquals("₹12,34,567.50", Money.format(123_456_750))
        assertEquals("-₹99.05", Money.format(-9_905))
        assertEquals("1250.5", Money.plain(125_050))
        assertEquals("100", Money.plain(10_000))
    }

    @Test
    fun lineTotalsAreExactIntegers() {
        assertEquals(1_250_000L, Money.lineTotal(Qty.of(50), 25_000))
        assertEquals(8_250L, Money.lineTotal(1_500, 5_500))       // 1.5 kg x ₹55
        assertEquals(3L, Money.lineTotal(333, 10))                // 3.33 paise rounds to 3
        assertEquals(10L, Money.lineTotal(1, 9_999))              // 9.999 paise rounds up
    }

    // ---- quantities and units
    @Test
    fun quantityAndUnitParsing() {
        fun q(text: String) = LocalParser.parse(text).entities.let { it.quantityMilli to it.unit }
        assertEquals(20_000L to "PCS", q("add 20 pieces of pen"))
        assertEquals(20_000L to "PCS", q("add 20 pcs pen"))
        assertEquals(20_000L to "BOX", q("add 20 boxes of pen"))
        assertEquals(5_000L to "KG", q("add 5 kg sugar"))
        assertEquals(5_000L to "KG", q("add five kilos of sugar"))
        assertEquals(2_000L to "LITRE", q("add 2 litres of oil"))
        assertEquals(100_000L to "METER", q("add 100 meters of cloth"))
        assertEquals(2_500L to "KG", q("add 2.5 kg sugar"))
        assertEquals("5.5", Qty.format(5_500))
        assertEquals("100", Qty.format(100_000))
        assertEquals(1_000_000L, Units.convert(1_000, "KG", "GRAM"))
        assertEquals(2_000L, Units.convert(2_000_000, "GRAM", "KG"))
        assertNull(Units.convert(1_000, "BOX", "KG"))
    }

    @Test
    fun numberWords() {
        assertEquals("500", NumberWords.replace("five hundred"))
        assertEquals("2500 rupees", NumberWords.replace("two thousand five hundred rupees"))
        assertEquals("add 25 shirts", NumberWords.replace("add twenty five shirts"))
        assertEquals("150000", NumberWords.replace("one lakh fifty thousand"))
        assertEquals("a4 paper", NumberWords.replace("a4 paper"))
    }

    // ---- dates
    @Test
    fun dateRangesUseLocalDayBoundaries() {
        val zone = ZoneId.of("Asia/Kolkata")
        // Wednesday 14 Oct 2026, 00:30 local time = still 13 Oct in UTC.
        val now = LocalDate.of(2026, 10, 14).atTime(0, 30).atZone(zone).toInstant()
        fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(TimeWindow(day(2026, 10, 14), day(2026, 10, 15)), DateRanges.range(DateKey.TODAY, zone, now))
        assertEquals(TimeWindow(day(2026, 10, 13), day(2026, 10, 14)), DateRanges.range(DateKey.YESTERDAY, zone, now))
        assertEquals(TimeWindow(day(2026, 10, 12), day(2026, 10, 19)), DateRanges.range(DateKey.THIS_WEEK, zone, now))
        assertEquals(TimeWindow(day(2026, 10, 5), day(2026, 10, 12)), DateRanges.range(DateKey.LAST_WEEK, zone, now))
        assertEquals(TimeWindow(day(2026, 10, 1), day(2026, 11, 1)), DateRanges.range(DateKey.THIS_MONTH, zone, now))
        assertEquals(TimeWindow(day(2026, 9, 1), day(2026, 10, 1)), DateRanges.range(DateKey.LAST_MONTH, zone, now))
        val jan = LocalDate.of(2027, 1, 3).atTime(9, 0).atZone(zone).toInstant()
        assertEquals(TimeWindow(day(2026, 12, 1), day(2027, 1, 1)), DateRanges.range(DateKey.LAST_MONTH, zone, jan))
        assertTrue(Instant.ofEpochMilli(DateRanges.range(DateKey.TODAY, zone, now).start).isBefore(now.plusSeconds(1)))
    }

    @Test
    fun datePhrases() {
        assertEquals(DateKey.YESTERDAY, LocalParser.parse("Show yesterday's sales").entities.dateKey)
        assertEquals(DateKey.THIS_WEEK, LocalParser.parse("sales this week").entities.dateKey)
        assertEquals(DateKey.LAST_WEEK, LocalParser.parse("what did we purchase last week").entities.dateKey)
        assertEquals(DateKey.THIS_MONTH, LocalParser.parse("expenses this month").entities.dateKey)
        assertEquals(DateKey.LAST_MONTH, LocalParser.parse("sales report last month").entities.dateKey)
        assertEquals(DateKey.TODAY, LocalParser.parse("what were today's sales").entities.dateKey)
    }

    // ---- intents: every example sentence from the specification
    @Test
    fun specificationExamples() {
        fun i(t: String) = LocalParser.parse(t).intent
        assertEquals(Intent.STOCK_IN, i("Add 100 pieces of Blue Shirt."))
        assertEquals(Intent.STOCK_QUERY, i("How many Blue Shirts do I have?"))
        assertEquals(Intent.STOCK_IN, i("Received 50 boxes of A4 paper from Kumar Traders at 250 rupees each."))
        assertEquals(Intent.CREATE_SALE, i("Sold 20 Blue Shirts to Kumar Traders."))
        assertEquals(Intent.STOCK_OUT, i("Remove 10 Blue Shirts because they were damaged."))
        assertEquals(Intent.LOW_STOCK_QUERY, i("Show low stock products."))
        assertEquals(Intent.LOW_STOCK_QUERY, i("Which products are low?"))
        assertEquals(Intent.CREATE_CUSTOMER, i("Add Kumar Traders as a customer."))
        assertEquals(Intent.CREATE_SUPPLIER, i("Add ABC Traders as a supplier."))
        assertEquals(Intent.CREATE_EXPENSE, i("Paid 2500 rupees for transport."))
        assertEquals(Intent.SALES_QUERY, i("What were today's sales?"))
        assertEquals(Intent.PURCHASE_QUERY, i("What did we purchase yesterday?"))
        assertEquals(Intent.CUSTOMER_BALANCE, i("How much does Kumar Traders owe me?"))
        assertEquals(Intent.SUPPLIER_BALANCE, i("How much do I owe ABC Traders?"))
        assertEquals(Intent.EXPENSE_QUERY, i("Show today's expenses."))
        assertEquals(Intent.STOCK_ADJUST, i("We counted Blue Shirt and there are actually 72 pieces."))
        assertEquals(Intent.STOCK_IN, i("Blue shirt 20 pieces stock-la add pannunga."))
        assertEquals(Intent.CUSTOMER_QUERY, i("Find Kumar Traders."))
        assertEquals(Intent.REPORT_STOCK, i("What is my current stock?"))
        assertEquals(Intent.HELP, i("help"))
        assertEquals(Intent.UNKNOWN, i("the weather is nice"))
    }

    @Test
    fun entitiesAreExtracted() {
        var e = LocalParser.parse("Received 100 kg black yarn from Ravi at 180 per kg.").entities
        assertEquals("Black Yarn", e.productName); assertEquals(100_000L, e.quantityMilli); assertEquals("KG", e.unit)
        assertEquals("Ravi", e.supplierName); assertEquals(18_000L, e.unitPricePaise)
        e = LocalParser.parse("Remove 25 kg black yarn because it was damaged.").entities
        assertEquals("DAMAGE", e.reason); assertEquals(25_000L, e.quantityMilli)
        e = LocalParser.parse("Paid 2500 rupees for transport.").entities
        assertEquals("Transport", e.expenseCategory); assertEquals(250_000L, e.amountPaise)
        e = LocalParser.parse("Remove 10 because they were damaged.").entities
        assertNull(e.productName); assertTrue(e.usesContext)
        val c = LocalParser.parse("Add 50.")
        assertEquals(listOf("productName"), c.missingFields); assertFalse(c.entities.usesContext)
    }

    @Test
    fun tamilAndTanglish() {
        assertEquals(Intent.STOCK_QUERY, LocalParser.parse("blue shirt evlo irukku").intent)
        assertEquals("Blue Shirt", LocalParser.parse("blue shirt evlo irukku").entities.productName)
        assertEquals(Intent.SALES_QUERY, LocalParser.parse("innaiku sales evlo").intent)
        assertEquals(Intent.SALES_QUERY, LocalParser.parse("இன்றைய விற்பனை எவ்வளவு").intent)
        assertEquals(Intent.LOW_STOCK_QUERY, LocalParser.parse("குறைவாக உள்ள பொருட்கள் காட்டு").intent)
        val c = LocalParser.parse("ravi kitta irundhu 100 kg yarn vandhuchu")
        assertEquals(Intent.STOCK_IN, c.intent); assertEquals("Ravi", c.entities.supplierName)
        assertEquals(Intent.CREATE_EXPENSE, LocalParser.parse("transport selavu 300 rupees").intent)
    }

    // ---- validation
    @Test
    fun validatorRejectsMalformedCommands() {
        fun ok(e: Entities, intent: Intent = Intent.STOCK_IN, conf: Double = 0.95) = CommandValidator.validate(AICommand(intent, conf, true, e)).ok
        assertTrue(ok(Entities(productName = "Blue Shirt", quantityMilli = 5000)))
        assertFalse(ok(Entities(quantityMilli = -1)))
        assertFalse(ok(Entities(quantityMilli = 0)))
        assertTrue(ok(Entities(quantityMilli = 0), Intent.STOCK_ADJUST))
        assertFalse(ok(Entities(unitPricePaise = -100)))
        assertFalse(ok(Entities(productName = "x".repeat(500))))
        assertFalse(ok(Entities(productName = "bad\u0000name")))
        assertFalse(ok(Entities(), conf = 1.5))
        assertFalse(ok(Entities(), conf = Double.NaN))
        assertFalse(ok(Entities(amountPaise = 0), Intent.CREATE_EXPENSE))
    }

    // ---- name matching
    @Test
    fun nameMatching() {
        val items = listOf("Blue Cotton Shirt", "Black Shirt", "A4 Paper")
        assertTrue(NameMatcher.match("black shirts", items) { it } is Match.Exact)
        assertEquals(Match.Suggest("Blue Cotton Shirt"), NameMatcher.match("blue shirt", items) { it })
        assertTrue(NameMatcher.match("shirt", items) { it } is Match.Multiple)
        assertTrue(NameMatcher.match("a4 papr", items) { it } is Match.Suggest)
        assertEquals(Match.None, NameMatcher.match("red saree", items) { it })
        assertEquals("Blue Shirt", NameMatcher.titleCase("blue shirt"))
        assertEquals("ABC Traders", NameMatcher.titleCase("ABC traders"))
    }
}
