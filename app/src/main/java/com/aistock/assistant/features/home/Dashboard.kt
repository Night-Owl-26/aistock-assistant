package com.aistock.assistant.features.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistock.assistant.R
import com.aistock.assistant.data.BusinessRepository
import com.aistock.assistant.data.InventoryRepository
import com.aistock.assistant.data.PartyRepository
import com.aistock.assistant.database.AppDatabase
import com.aistock.assistant.database.ProductEntity
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.ListRow
import com.aistock.assistant.ui.components.SectionTitle
import com.aistock.assistant.ui.components.WarnTag
import com.aistock.assistant.ui.fmtDate
import com.aistock.assistant.ui.money
import com.aistock.assistant.ui.qtyText
import com.aistock.assistant.utils.DateKey
import com.aistock.assistant.utils.DateRanges
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DashboardState(
    val businessName: String = "",
    val sales: Long = 0, val purchases: Long = 0, val expenses: Long = 0,
    val stockValue: Long = 0, val lowCount: Int = 0, val productCount: Int = 0,
    val customerOutstanding: Long = 0, val supplierOutstanding: Long = 0,
    val low: List<ProductEntity> = emptyList(),
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    db: AppDatabase, parties: PartyRepository, inventory: InventoryRepository, business: BusinessRepository,
) : ViewModel() {
    private val today = MutableStateFlow(DateRanges.range(DateKey.TODAY))

    /** Called whenever the dashboard is shown, so "today" is right after midnight. */
    fun refreshDay() { today.value = DateRanges.range(DateKey.TODAY) }

    private val todayTotals = today.flatMapLatest { w ->
        combine(db.tradeDao().observeSaleTotal(w.start, w.end), db.tradeDao().observePurchaseTotal(w.start, w.end), db.tradeDao().observeExpenseTotal(w.start, w.end)) { a, b, c -> Triple(a, b, c) }
    }
    private val stock = combine(db.productDao().observeStockValue(), db.productDao().observeLowCount(), db.productDao().observeCount()) { a, b, c -> Triple(a, b, c) }
    private val outstanding = combine(parties.observeCustomerOutstanding(), parties.observeSupplierOutstanding()) { a, b -> a to b }

    val state: StateFlow<DashboardState> = combine(todayTotals, stock, outstanding, inventory.observeLowStock(), business.observeBusiness()) { t, s, o, low, b ->
        DashboardState(b?.name.orEmpty(), t.first, t.second, t.third, s.first, s.second, s.third, o.first, o.second, low)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())
}

@Composable
fun DashboardScreen(nav: AppNav, vm: DashboardViewModel = hiltViewModel(), work: com.aistock.assistant.features.work.WorkViewModel = hiltViewModel()) {
    val st by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.refreshDay() }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            val hour = java.time.LocalTime.now().hour
            Text(
                stringResource(when (hour) { in 5..11 -> R.string.greet_morning; in 12..16 -> R.string.greet_afternoon; else -> R.string.greet_evening }),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
            )
            Text(st.businessName.ifBlank { stringResource(R.string.app_name) }, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fmtDate(System.currentTimeMillis()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // The business type is chosen once; until then the shop default is used.
        val configured by work.configured.collectAsState()
        if (configured == false) {
            AppCard(Modifier.fillMaxWidth(), onClick = { nav.go("setup") }) {
                Text(stringResource(R.string.setup_card_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.setup_card_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // What needs attention, worked out from the records (no AI involved).
        var insights by remember { mutableStateOf<List<com.aistock.assistant.business.Insight>>(emptyList()) }
        LaunchedEffect(st) { insights = try { work.briefing(1) } catch (e: Exception) { emptyList() } }
        val important = insights.filter { it.kind in setOf("overdue_work", "due_today", "followups", "expiry", "low_stock", "missing") }.take(3)
        if (important.isNotEmpty()) {
            AppCard(Modifier.fillMaxWidth(), onClick = { nav.go("ai") }) {
                Text(stringResource(R.string.needs_attention), style = MaterialTheme.typography.titleMedium)
                for (i in important) Text("• " + i.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }

        // Ask AI
        Surface(
            color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.ask_ai)) { nav.go("ai?listen=true") },
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ask_ai), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.tell_me_what_happened), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(4.dp))
                    val handsFree by com.aistock.assistant.voice.HandsFree.running.collectAsState()
                    Text(stringResource(if (handsFree) R.string.ask_ai_hint_handsfree else R.string.ask_ai_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f))
                }
                Spacer(Modifier.width(12.dp))
                Surface(color = Color(0xFFFFC857), contentColor = Color(0xFF2C1A00), shape = CircleShape, shadowElevation = 6.dp, modifier = Modifier.size(76.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.speak), modifier = Modifier.size(36.dp))
                    }
                }
            }
        }

        // Quick actions
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(R.drawable.ic_stock_in, stringResource(R.string.stock_in)) { nav.go("stockmove/in") }
            QuickAction(R.drawable.ic_stock_out, stringResource(R.string.stock_out)) { nav.go("stockmove/out") }
            QuickAction(R.drawable.ic_sale, stringResource(R.string.sale)) { nav.go("trade/sale") }
            QuickAction(R.drawable.ic_purchase, stringResource(R.string.purchase)) { nav.go("trade/purchase") }
            QuickAction(R.drawable.ic_expense, stringResource(R.string.expense)) { nav.go("expense") }
        }

        SectionTitle(stringResource(R.string.today))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric(stringResource(R.string.todays_sales), money(st.sales), Modifier.weight(1f)) { nav.go("transactions?tab=0") }
            Metric(stringResource(R.string.todays_purchases), money(st.purchases), Modifier.weight(1f)) { nav.go("transactions?tab=1") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric(stringResource(R.string.todays_expenses), money(st.expenses), Modifier.weight(1f)) { nav.go("transactions?tab=2") }
            Metric(stringResource(R.string.low_stock_count), st.lowCount.toString(), Modifier.weight(1f), warn = st.lowCount > 0) { nav.go("inventory?tab=2") }
        }

        SectionTitle(stringResource(R.string.business_position))
        Metric(stringResource(R.string.stock_value), money(st.stockValue), Modifier.fillMaxWidth(), sub = stringResource(R.string.n_products, st.productCount)) { nav.go("inventory?tab=0") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric(stringResource(R.string.customer_outstanding), money(st.customerOutstanding), Modifier.weight(1f), sub = stringResource(R.string.to_receive)) { nav.go("parties/customer") }
            Metric(stringResource(R.string.supplier_outstanding), money(st.supplierOutstanding), Modifier.weight(1f), sub = stringResource(R.string.to_pay)) { nav.go("parties/supplier") }
        }

        if (st.low.isNotEmpty()) {
            SectionTitle(stringResource(R.string.low_stock))
            for (p in st.low.take(5)) {
                ListRow(
                    title = p.name, subtitle = stringResource(R.string.minimum_is, qtyText(p.minimumStock, p.unit)),
                    trailing = qtyText(p.currentStock, p.unit), onClick = { nav.go("product?id=${p.id}") },
                    badge = { WarnTag(stringResource(R.string.low_stock)) },
                )
            }
        }
        if (st.productCount == 0) {
            AppCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.get_started), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.get_started_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier, sub: String = "", warn: Boolean = false, onClick: () -> Unit) {
    AppCard(modifier, onClick = onClick) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuickAction(icon: Int, label: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.width(92.dp).padding(vertical = 14.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
