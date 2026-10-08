package com.aistock.assistant.utils

import java.math.BigDecimal
import java.math.RoundingMode

/** Money is always stored as whole paise (minor units) in a Long. Never Float/Double. */
object Money {
    fun format(paise: Long, symbol: String = "₹"): String {
        val neg = paise < 0
        val abs = Math.abs(paise)
        val rupees = abs / 100
        val p = (abs % 100).toInt()
        val frac = if (p == 0) "" else "." + p.toString().padStart(2, '0')
        return (if (neg) "-" else "") + symbol + groupIndian(rupees.toString()) + frac
    }

    /** Plain decimal text for edit fields and spreadsheets, e.g. 1250.5 */
    fun plain(paise: Long): String =
        BigDecimal(paise).movePointLeft(2).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()

    private fun groupIndian(d: String): String {
        if (d.length <= 3) return d
        val last3 = d.substring(d.length - 3)
        var rest = d.substring(0, d.length - 3)
        val parts = ArrayList<String>()
        while (rest.length > 2) {
            parts.add(0, rest.substring(rest.length - 2))
            rest = rest.substring(0, rest.length - 2)
        }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return parts.joinToString(",") + "," + last3
    }

    /** Parses "250", "₹1,250.50", "500 rupees", "500 INR". Returns paise, or null when not a valid non-negative amount. */
    fun parse(text: String?): Long? {
        if (text == null) return null
        val cleaned = text.lowercase()
            .replace("₹", "").replace(",", "")
            .replace(Regex("\\b(rupees|rupee|rs|inr)\\b\\.?"), "")
            .replace("/-", "").trim()
        if (cleaned.isEmpty()) return null
        return try {
            val bd = BigDecimal(cleaned)
            if (bd.signum() < 0) null
            else bd.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        } catch (e: Exception) {
            null
        }
    }

    /** quantity (thousandths) x unit price (paise), rounded half up to whole paise. */
    fun lineTotal(qtyMilli: Long, unitPaise: Long): Long =
        BigDecimal(qtyMilli).multiply(BigDecimal(unitPaise))
            .divide(BigDecimal(1000), 0, RoundingMode.HALF_UP).longValueExact()
}

/** Quantities are stored in thousandths (so 5.5 kg = 5500) in a Long. */
object Qty {
    fun format(milli: Long): String {
        val bd = BigDecimal(milli).movePointLeft(3).stripTrailingZeros()
        return (if (bd.scale() < 0) bd.setScale(0) else bd).toPlainString()
    }

    fun parse(text: String?): Long? {
        if (text == null) return null
        val cleaned = text.replace(",", "").trim()
        if (cleaned.isEmpty()) return null
        return try {
            BigDecimal(cleaned).setScale(3, RoundingMode.HALF_UP).movePointRight(3).longValueExact()
        } catch (e: Exception) {
            null
        }
    }

    fun of(whole: Long): Long = whole * 1000L
}
