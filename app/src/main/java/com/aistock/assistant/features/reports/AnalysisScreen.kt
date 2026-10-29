package com.aistock.assistant.features.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aistock.assistant.R
import com.aistock.assistant.database.AppDatabase
import com.aistock.assistant.database.CategoryTotal
import com.aistock.assistant.database.ProductTotal
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.FormPage
import com.aistock.assistant.ui.components.LocalCurrency
import com.aistock.assistant.ui.components.SectionTitle
import com.aistock.assistant.utils.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class AnalysisState(
    val loading: Boolean = true,
    val period: AnalysisPeriod = AnalysisPeriod.MONTH,
    val sales: Long = 0, val salesBefore: Long = 0, val bills: Int = 0,
    val cost: Long = 0, val expenses: Long = 0, val purchases: Long = 0, val dues: Long = 0,
    val trend: List<Bucket> = emptyList(),
    val expenseTrend: List<Bucket> = emptyList(),
    val products: List<ProductTotal> = emptyList(),
    val customers: List<CategoryTotal> = emptyList(),
    val expenseKinds: List<CategoryTotal> = emptyList(),
    val weekdays: LongArray = LongArray(7),
) {
    val grossProfit: Long get() = sales - cost
    val netProfit: Long get() = grossProfit - expenses
    val avgBill: Long get() = if (bills > 0) sales / bills else 0
}

@HiltViewModel
class AnalysisViewModel @Inject constructor(private val db: AppDatabase) : ViewModel() {
    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state

    fun load(period: AnalysisPeriod) {
        _state.value = _state.value.copy(loading = true, period = period)
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val (now, before) = AnalysisMath.range(period, LocalDate.now(zone), zone)
            val from = now.first; val to = now.last + 1
            val s = withContext(Dispatchers.IO) {
                val t = db.tradeDao()
                val sales = t.saleAmounts(from, to)
                val exp = t.expenseAmounts(from, to)
                val totals = t.saleTotals(from, to)
                val buckets = AnalysisMath.buckets(from, to, period.step, zone)
                AnalysisState(
                    loading = false, period = period,
                    sales = totals.total, bills = totals.count, dues = totals.balance,
                    salesBefore = t.saleTotals(before.first, before.last + 1).total,
                    cost = t.costOfSales(from, to),
                    expenses = exp.sumOf { it.amount },
                    purchases = t.purchaseAmounts(from, to).sumOf { it.amount },
                    trend = AnalysisMath.fill(buckets, sales),
                    expenseTrend = AnalysisMath.fill(buckets, exp),
                    products = t.salesByProduct(from, to).take(8),
                    customers = t.salesByCustomer(from, to, 6),
                    expenseKinds = t.expensesByCategory(from, to).take(6),
                    weekdays = AnalysisMath.weekdays(sales, zone),
                )
            }
            _state.value = s
        }
    }
}

/**
 * Analysis: how the business is doing over a period, as charts worked out from the records on this phone
 * (no AI, works offline). Profit uses today's purchase prices, so it is shown as an estimate.
 */
@Composable
fun AnalysisScreen(nav: AppNav, vm: AnalysisViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val cur = LocalCurrency.current
    LaunchedEffect(Unit) { vm.load(st.period) }
    val money = { v: Long -> Money.format(v, cur) }

    FormPage(stringResource(R.string.analysis), nav.back) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((p, label) in listOf(AnalysisPeriod.WEEK to R.string.period_7d, AnalysisPeriod.MONTH to R.string.period_30d, AnalysisPeriod.QUARTER to R.string.period_3m, AnalysisPeriod.YEAR to R.string.period_12m)) {
                FilterChip(st.period == p, { vm.load(p) }, label = { Text(stringResource(label)) })
            }
        }
        if (st.loading) { Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@FormPage }

        // Headline numbers
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(stringResource(R.string.a_sales), money(st.sales), AnalysisMath.change(st.sales, st.salesBefore)?.let { stringResource(R.string.a_vs_before, (if (it >= 0) "+" else "") + it) }, Modifier.weight(1f))
            Tile(stringResource(R.string.a_gross_profit), money(st.grossProfit), stringResource(R.string.a_estimate), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(stringResource(R.string.a_expenses), money(st.expenses), null, Modifier.weight(1f))
            Tile(stringResource(R.string.a_net_profit), money(st.netProfit), stringResource(R.string.a_estimate), Modifier.weight(1f), negative = st.netProfit < 0)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(stringResource(R.string.a_bills), st.bills.toString(), stringResource(R.string.a_avg_bill, money(st.avgBill)), Modifier.weight(1f))
            Tile(stringResource(R.string.a_to_collect), money(st.dues), stringResource(R.string.a_from_these_bills), Modifier.weight(1f))
        }

        if (st.sales == 0L && st.expenses == 0L) {
            Text(stringResource(R.string.a_no_data), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@FormPage
        }

        SectionTitle(stringResource(R.string.a_sales_trend))
        AppCard(Modifier.fillMaxWidth()) {
            Bars(st.trend.map { it.amount }, st.trend.map { it.label }, MaterialTheme.colorScheme.primary, st.trend.map { it.amount }.let { v -> v.maxOrNull() ?: 0 }, cur,
                second = st.expenseTrend.map { it.amount }, secondColor = MaterialTheme.colorScheme.tertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                Legend(MaterialTheme.colorScheme.primary, stringResource(R.string.a_sales))
                Legend(MaterialTheme.colorScheme.tertiary, stringResource(R.string.a_expenses))
            }
        }

        if (st.products.isNotEmpty()) {
            SectionTitle(stringResource(R.string.a_top_products))
            AppCard(Modifier.fillMaxWidth()) { Ranked(st.products.map { it.productName to it.total }, money) }
        }
        if (st.customers.isNotEmpty()) {
            SectionTitle(stringResource(R.string.a_top_customers))
            AppCard(Modifier.fillMaxWidth()) { Ranked(st.customers.map { it.category to it.total }, money) }
        }
        if (st.expenseKinds.isNotEmpty()) {
            SectionTitle(stringResource(R.string.a_expense_kinds))
            AppCard(Modifier.fillMaxWidth()) { Ranked(st.expenseKinds.map { it.category to it.total }, money, MaterialTheme.colorScheme.tertiary) }
        }
        SectionTitle(stringResource(R.string.a_busy_days))
        AppCard(Modifier.fillMaxWidth()) {
            val names = listOf(R.string.d_mon, R.string.d_tue, R.string.d_wed, R.string.d_thu, R.string.d_fri, R.string.d_sat, R.string.d_sun).map { stringResource(it) }
            Bars(st.weekdays.toList(), names, MaterialTheme.colorScheme.secondary, st.weekdays.maxOrNull() ?: 0, cur)
            val best = st.weekdays.indices.maxByOrNull { st.weekdays[it] }
            if (best != null && st.weekdays[best] > 0) Text(stringResource(R.string.a_best_day, names[best]), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        Text(stringResource(R.string.a_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Tile(title: String, value: String, sub: String?, modifier: Modifier, negative: Boolean = false) {
    AppCard(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** Vertical bars with the highest value written at the top; an optional second series is drawn beside each bar. */
@Composable
private fun Bars(values: List<Long>, labels: List<String>, color: Color, max: Long, cur: String, second: List<Long>? = null, secondColor: Color = Color.Gray) {
    val top = maxOf(max, second?.maxOrNull() ?: 0L, 1L)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column {
        Text(AnalysisMath.short(top, cur), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(150.dp).padding(top = 4.dp)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val gap = (slot * 0.2f).coerceAtMost(8f)
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), 1f)
            for (i in values.indices) {
                val w = if (second != null) (slot - gap) / 2f else slot - gap
                val x = i * slot + gap / 2f
                val h = size.height * (values[i].toFloat() / top)
                if (h > 0f) drawRoundRect(color, Offset(x, size.height - h), Size(w, h), CornerRadius(3f, 3f))
                if (second != null) {
                    val h2 = size.height * ((second.getOrNull(i) ?: 0L).toFloat() / top)
                    if (h2 > 0f) drawRoundRect(secondColor, Offset(x + w, size.height - h2), Size(w, h2), CornerRadius(3f, 3f))
                }
            }
        }
        // Only some labels fit under many bars.
        val every = when { labels.size > 20 -> 5; labels.size > 10 -> 2; else -> 1 }
        // Each shown label gets the width of the bars it stands for, so "30" or "Nov" is not cut to one letter.
        Row(Modifier.fillMaxWidth()) {
            for (i in labels.indices step every) {
                val span = minOf(every, labels.size - i)
                Text(labels[i], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, softWrap = false, modifier = Modifier.weight(span.toFloat()))
            }
        }
    }
}

/** Names with a bar showing each one's share of the biggest, and the amount. */
@Composable
private fun Ranked(rows: List<Pair<String, Long>>, money: (Long) -> String, color: Color = MaterialTheme.colorScheme.primary) {
    val max = rows.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((name, v) in rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(money(v), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(v.toFloat() / max).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
            }
        }
    }
}
