package com.aistock.assistant.utils

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class DateKey { TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH, ALL }

/** Half-open time window in epoch milliseconds: [start, end). */
data class TimeWindow(val start: Long, val end: Long)

object DateRanges {
    fun range(key: DateKey, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): TimeWindow {
        val today = now.atZone(zone).toLocalDate()
        fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        return when (key) {
            DateKey.TODAY -> TimeWindow(ms(today), ms(today.plusDays(1)))
            DateKey.YESTERDAY -> TimeWindow(ms(today.minusDays(1)), ms(today))
            DateKey.THIS_WEEK -> {
                val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                TimeWindow(ms(start), ms(start.plusWeeks(1)))
            }
            DateKey.LAST_WEEK -> {
                val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
                TimeWindow(ms(start), ms(start.plusWeeks(1)))
            }
            DateKey.THIS_MONTH -> {
                val start = today.withDayOfMonth(1)
                TimeWindow(ms(start), ms(start.plusMonths(1)))
            }
            DateKey.LAST_MONTH -> {
                val start = today.withDayOfMonth(1).minusMonths(1)
                TimeWindow(ms(start), ms(start.plusMonths(1)))
            }
            DateKey.ALL -> TimeWindow(0L, Long.MAX_VALUE)
        }
    }

    /** Finds a date phrase in already-normalised lower-case text. */
    fun detect(text: String): DateKey? = when {
        Regex("\\byesterday\\b").containsMatchIn(text) -> DateKey.YESTERDAY
        Regex("\\blast week\\b").containsMatchIn(text) -> DateKey.LAST_WEEK
        Regex("\\bthis week\\b").containsMatchIn(text) -> DateKey.THIS_WEEK
        Regex("\\blast month\\b").containsMatchIn(text) -> DateKey.LAST_MONTH
        Regex("\\bthis month\\b").containsMatchIn(text) -> DateKey.THIS_MONTH
        Regex("\\b(today|now)\\b").containsMatchIn(text) -> DateKey.TODAY
        Regex("\\b(all time|overall|so far|till now|total)\\b").containsMatchIn(text) -> DateKey.ALL
        else -> null
    }

    fun strip(text: String): String =
        text.replace(Regex("\\b(yesterday|last week|this week|last month|this month|today|all time|overall|so far|till now)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()

    fun label(key: DateKey, tamil: Boolean = false): String = if (tamil) when (key) {
        DateKey.TODAY -> "இன்று"; DateKey.YESTERDAY -> "நேற்று"; DateKey.THIS_WEEK -> "இந்த வாரம்"
        DateKey.LAST_WEEK -> "கடந்த வாரம்"; DateKey.THIS_MONTH -> "இந்த மாதம்"; DateKey.LAST_MONTH -> "கடந்த மாதம்"
        DateKey.ALL -> "மொத்தம்"
    } else when (key) {
        DateKey.TODAY -> "Today"; DateKey.YESTERDAY -> "Yesterday"; DateKey.THIS_WEEK -> "This week"
        DateKey.LAST_WEEK -> "Last week"; DateKey.THIS_MONTH -> "This month"; DateKey.LAST_MONTH -> "Last month"
        DateKey.ALL -> "All time"
    }
}
