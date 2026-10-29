package com.aistock.assistant.features

import com.aistock.assistant.data.LineInput
import com.aistock.assistant.data.TestEnv
import com.aistock.assistant.data.orThrow
import com.aistock.assistant.database.DateAmount
import com.aistock.assistant.features.account.cleanUtr
import com.aistock.assistant.features.account.upiLink
import com.aistock.assistant.features.reports.AnalysisMath
import com.aistock.assistant.features.reports.AnalysisPeriod
import com.aistock.assistant.features.reports.Step
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class AnalysisTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(d: String, hour: Int = 11) = LocalDate.parse(d).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun periodsAndBars() {
        val today = LocalDate.parse("2026-10-29")
        val (now, before) = AnalysisMath.range(AnalysisPeriod.WEEK, today, zone)
        assertEquals(at("2026-10-23", 0), now.first)
        assertEquals(at("2026-10-30", 0), now.last + 1)
        assertEquals(now.first, before.last + 1)
        val days = AnalysisMath.buckets(now.first, now.last + 1, Step.DAY, zone)
        assertEquals(listOf("23", "24", "25", "26", "27", "28", "29"), days.map { it.label })
        val filled = AnalysisMath.fill(days, listOf(DateAmount(at("2026-10-29"), 500), DateAmount(at("2026-10-29", 20), 250), DateAmount(at("2026-10-23"), 100), DateAmount(at("2026-10-30"), 999)))
        assertEquals(100L, filled.first().amount)
        assertEquals(750L, filled.last().amount)
        assertEquals("the empty bars are not changed", 0L, days.last().amount)

        val (year, _) = AnalysisMath.range(AnalysisPeriod.YEAR, today, zone)
        val months = AnalysisMath.buckets(year.first, year.last + 1, Step.MONTH, zone, Locale.ENGLISH)
        assertEquals(12, months.size)
        assertEquals("Nov", months.first().label); assertEquals("Oct", months.last().label)
        val (q, _) = AnalysisMath.range(AnalysisPeriod.QUARTER, today, zone)
        assertEquals(14, AnalysisMath.buckets(q.first, q.last + 1, Step.WEEK, zone).size)
    }

    @Test
    fun numbers() {
        assertEquals(25, AnalysisMath.change(125, 100))
        assertEquals(-50, AnalysisMath.change(50, 100))
        assertNull(AnalysisMath.change(50, 0))
        assertEquals("₹950", AnalysisMath.short(95_000))
        assertEquals("₹12.5k", AnalysisMath.short(1_250_000))
        assertEquals("₹3.2L", AnalysisMath.short(32_000_000))
        assertEquals("₹1.1Cr", AnalysisMath.short(1_100_000_000))
        // 29 Oct 2026 is a Thursday.
        assertArrayEquals(longArrayOf(0, 0, 0, 7, 0, 0, 3), AnalysisMath.weekdays(listOf(DateAmount(at("2026-10-29"), 7), DateAmount(at("2026-11-01"), 3)), zone))
    }

    @Test
    fun queriesOnTheRealDatabase(): Unit = runBlocking {
        val env = TestEnv()
        try {
            val shirt = env.product("Blue Shirt", stock = 100, sell = 450, buy = 300)
            val kumar = env.customer("Kumar Traders")
            env.trade.createSale(kumar, listOf(LineInput(shirt.id, 2_000, 45_000))).orThrow()
            env.trade.createSale(null, listOf(LineInput(shirt.id, 1_000, 45_000))).orThrow()
            val from = System.currentTimeMillis() - 86_400_000L; val to = System.currentTimeMillis() + 86_400_000L
            val t = env.db.tradeDao()
            assertEquals(listOf(90_000L, 45_000L).sorted(), t.saleAmounts(from, to).map { it.amount }.sorted())
            assertEquals("3 shirts at a ₹300 cost", 90_000L, t.costOfSales(from, to))
            val top = t.salesByCustomer(from, to, 5)
            assertEquals("Kumar Traders", top.single().category); assertEquals(90_000L, top.single().total)
        } finally { env.close() }
    }

    @Test
    fun upi() {
        val u = upiLink("shop@okaxis", "Follower", 199, "Follower Basic owner@shop.in")
        assertEquals("upi", u.scheme); assertEquals("pay", u.authority)
        assertEquals("shop@okaxis", u.getQueryParameter("pa"))
        assertEquals("199.00", u.getQueryParameter("am"))
        assertEquals("INR", u.getQueryParameter("cu"))
        assertEquals("412345678901", cleanUtr(" 4123 4567-8901 "))
    }
}
