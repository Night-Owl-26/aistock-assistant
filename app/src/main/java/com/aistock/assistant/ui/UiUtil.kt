package com.aistock.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource
import com.aistock.assistant.R
import com.aistock.assistant.data.TxnType
import com.aistock.assistant.ui.components.LocalCurrency
import com.aistock.assistant.utils.Money
import com.aistock.assistant.utils.Qty
import com.aistock.assistant.utils.Units
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

/** True when the app language is Tamil. */
val LocalTamil = compositionLocalOf { false }

/** Navigation handed to every screen. */
class AppNav(val go: (String) -> Unit, val back: () -> Unit)

@Composable
fun money(paise: Long): String = Money.format(paise, LocalCurrency.current)

@Composable
fun qtyText(milli: Long, unit: String): String = Qty.format(milli) + " " + Units.label(unit, milli, LocalTamil.current)

fun qtyPlain(milli: Long, unit: String, tamil: Boolean): String = Qty.format(milli) + " " + Units.label(unit, milli, tamil)

fun fmtDate(ms: Long): String = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ms))
fun fmtDateTime(ms: Long): String = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date(ms))

/** Converts the date picker's UTC-midnight value into a timestamp on that local calendar day. */
fun pickedDateToMillis(utcMidnight: Long): Long {
    val day = Instant.ofEpochMilli(utcMidnight).atZone(ZoneOffset.UTC).toLocalDate()
    val zone = ZoneId.systemDefault()
    val time = if (day == LocalDate.now(zone)) LocalTime.now(zone) else LocalTime.NOON
    return day.atTime(time).atZone(zone).toInstant().toEpochMilli()
}

/** The local calendar day of a timestamp, as the UTC-midnight value the date picker expects. */
fun millisToPickerDate(ms: Long): Long =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun startOfLocalDay(utcMidnight: Long): Long =
    Instant.ofEpochMilli(utcMidnight).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun txnTypeRes(type: String): Int = when (type) {
    TxnType.OPENING -> R.string.txn_opening
    TxnType.PURCHASE -> R.string.txn_purchase
    TxnType.SALE -> R.string.txn_sale
    TxnType.ADJUSTMENT_IN -> R.string.txn_adjust_in
    TxnType.ADJUSTMENT_OUT -> R.string.txn_adjust_out
    TxnType.DAMAGE -> R.string.txn_damage
    TxnType.RETURN_IN -> R.string.txn_return_in
    TxnType.RETURN_OUT -> R.string.txn_return_out
    else -> R.string.txn_other
}

fun statusRes(status: String): Int = when (status) {
    "PAID" -> R.string.status_paid
    "PARTIAL" -> R.string.status_partial
    "UNPAID" -> R.string.status_unpaid
    "CANCELLED" -> R.string.status_cancelled
    else -> R.string.status_active
}

fun methodRes(method: String): Int = when (method) {
    "CASH" -> R.string.method_cash
    "UPI" -> R.string.method_upi
    "BANK" -> R.string.method_bank
    "CARD" -> R.string.method_card
    else -> R.string.method_other
}

val PAYMENT_METHODS = listOf("CASH", "UPI", "BANK", "CARD", "OTHER")

@Composable
fun methodOptions(): List<Pair<String, String>> = PAYMENT_METHODS.map { it to stringResource(methodRes(it)) }
