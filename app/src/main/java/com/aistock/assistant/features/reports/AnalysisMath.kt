package com.aistock.assistant.features.reports

import com.aistock.assistant.database.DateAmount
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** The periods the Analysis page offers, and how each is split into bars. */
enum class AnalysisPeriod(val days: Int, val step: Step) { WEEK(7, Step.DAY), MONTH(30, Step.DAY), QUARTER(91, Step.WEEK), YEAR(365, Step.MONTH) }

enum class Step { DAY, WEEK, MONTH }

data class Bucket(val label: String, val start: Long, val end: Long, var amount: Long = 0L)

/** Pure date arithmetic for the Analysis page (no Android, so it is unit tested). */
object AnalysisMath {
    /** [from, to) of the period ending today (local time), and the same length just before it. */
    fun range(period: AnalysisPeriod, today: LocalDate, zone: ZoneId): Pair<LongRange, LongRange> {
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val from = when (period.step) {
            Step.MONTH -> today.withDayOfMonth(1).minusMonths(11).atStartOfDay(zone).toInstant().toEpochMilli()
            else -> today.minusDays(period.days - 1L).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        return (from until to) to ((from - (to - from)) until from)
    }

    /** Empty bars covering [from, to): days, weeks (starting Monday) or calendar months. */
    fun buckets(from: Long, to: Long, step: Step, zone: ZoneId, locale: Locale = Locale.getDefault()): List<Bucket> {
        val out = ArrayList<Bucket>()
        var d = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        if (step == Step.WEEK) d = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if (step == Step.MONTH) d = d.withDayOfMonth(1)
        while (true) {
            val start = d.atStartOfDay(zone).toInstant().toEpochMilli()
            if (start >= to) break
            val next = when (step) { Step.DAY -> d.plusDays(1); Step.WEEK -> d.plusWeeks(1); Step.MONTH -> d.plusMonths(1) }
            val label = when (step) {
                Step.DAY -> d.dayOfMonth.toString()
                Step.WEEK -> "${d.dayOfMonth}/${d.monthValue}"
                Step.MONTH -> d.month.getDisplayName(TextStyle.SHORT, locale)
            }
            out.add(Bucket(label, start, next.atStartOfDay(zone).toInstant().toEpochMilli()))
            d = next
        }
        return out
    }

    /** Adds each amount to the bar its date falls in. */
    fun fill(buckets: List<Bucket>, rows: List<DateAmount>): List<Bucket> {
        val copy = buckets.map { it.copy(amount = 0L) }
        for (r in rows) copy.firstOrNull { r.date >= it.start && r.date < it.end }?.let { it.amount += r.amount }
        return copy
    }

    /** Sales by day of the week, Monday first. */
    fun weekdays(rows: List<DateAmount>, zone: ZoneId): LongArray {
        val out = LongArray(7)
        for (r in rows) out[Instant.ofEpochMilli(r.date).atZone(zone).dayOfWeek.value - 1] += r.amount
        return out
    }

    /** Percentage change from the previous period, or null when there is nothing to compare with. */
    fun change(now: Long, before: Long): Int? = if (before <= 0L) null else Math.round((now - before) * 100.0 / before).toInt()

    /** Short amounts for chart labels: ₹950, ₹12.5k, ₹3.2L, ₹1.1Cr (paise in, Indian units out). */
    fun short(paise: Long, symbol: String = "₹"): String {
        val r = paise / 100.0
        val a = kotlin.math.abs(r)
        val s = when {
            a >= 1e7 -> trim(r / 1e7) + "Cr"
            a >= 1e5 -> trim(r / 1e5) + "L"
            a >= 1e3 -> trim(r / 1e3) + "k"
            else -> Math.round(r).toString()
        }
        return symbol + s
    }

    private fun trim(x: Double): String = if (kotlin.math.abs(x) >= 100) Math.round(x).toString() else String.format(Locale.US, "%.1f", x).removeSuffix(".0")

    fun daysBetween(from: Long, to: Long): Long = ChronoUnit.DAYS.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))
}
