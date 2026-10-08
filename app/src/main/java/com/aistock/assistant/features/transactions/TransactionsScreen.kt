package com.aistock.assistant.features.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistock.assistant.R
import com.aistock.assistant.data.Op
import com.aistock.assistant.data.Status
import com.aistock.assistant.database.ItemWithProduct
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.ConfirmDialog
import com.aistock.assistant.ui.components.EmptyState
import com.aistock.assistant.ui.components.LabelValue
import com.aistock.assistant.ui.components.ListRow
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.SearchField
import com.aistock.assistant.ui.components.Tag
import com.aistock.assistant.ui.components.WarnTag
import com.aistock.assistant.ui.components.errorRes
import com.aistock.assistant.ui.fmtDate
import com.aistock.assistant.ui.methodRes
import com.aistock.assistant.ui.money
import com.aistock.assistant.ui.qtyText
import com.aistock.assistant.ui.statusRes
import kotlinx.coroutines.launch

/** What the detail dialog is showing. */
private data class Detail(
    val kind: String, val id: String, val title: String, val party: String, val date: Long, val status: String,
    val subtotal: Long, val discount: Long, val tax: Long, val total: Long, val paid: Long, val balance: Long, val notes: String,
)

@Composable
fun TransactionsScreen(nav: AppNav, initialTab: Int, vm: TradeViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    val query by vm.query.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<Detail?>(null) }
    var cancelSimple by remember { mutableStateOf<Pair<String, String>?>(null) } // kind to id
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val titles = listOf(R.string.sales, R.string.purchases, R.string.expenses, R.string.payments)
    Scaffold(
        floatingActionButton = {
            val (label, route) = when (tab) {
                0 -> R.string.new_sale to "trade/sale"
                1 -> R.string.new_purchase to "trade/purchase"
                2 -> R.string.new_expense to "expense"
                else -> R.string.new_payment to "payment"
            }
            ExtendedFloatingActionButton(onClick = { nav.go(route) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(label)) })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(stringResource(R.string.nav_transactions), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t), maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
            }
            if (tab != 3) SearchField(query, { vm.query.value = it }, stringResource(R.string.search), Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            val listPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (tab == 3) 12.dp else 0.dp, bottom = 96.dp)
            when (tab) {
                0 -> {
                    val sales by vm.sales.collectAsStateWithLifecycle()
                    if (sales.isEmpty()) EmptyState(stringResource(R.string.no_sales), stringResource(R.string.no_sales_body), painterResource(R.drawable.ic_sale))
                    else LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sales, key = { it.sale.id }) { r ->
                            val s = r.sale
                            ListRow(
                                title = r.partyName ?: stringResource(R.string.cash_sale),
                                subtitle = s.invoiceNumber + " · " + fmtDate(s.date) + (r.itemSummary?.let { "\n$it" } ?: ""),
                                trailing = money(s.total),
                                trailingSub = if (s.balanceAmount > 0 && s.status != Status.CANCELLED) stringResource(R.string.due_amount, money(s.balanceAmount)) else "",
                                onClick = { detail = Detail("sale", s.id, s.invoiceNumber, r.partyName.orEmpty(), s.date, s.status, s.subtotal, s.discount, s.tax, s.total, s.paidAmount, s.balanceAmount, s.notes) },
                                badge = { StatusTag(s.status) },
                            )
                        }
                    }
                }
                1 -> {
                    val purchases by vm.purchases.collectAsStateWithLifecycle()
                    if (purchases.isEmpty()) EmptyState(stringResource(R.string.no_purchases), stringResource(R.string.no_purchases_body), painterResource(R.drawable.ic_purchase))
                    else LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(purchases, key = { it.purchase.id }) { r ->
                            val s = r.purchase
                            ListRow(
                                title = r.partyName ?: stringResource(R.string.cash_purchase),
                                subtitle = s.purchaseNumber + " · " + fmtDate(s.date) + (r.itemSummary?.let { "\n$it" } ?: ""),
                                trailing = money(s.total),
                                trailingSub = if (s.balanceAmount > 0 && s.status != Status.CANCELLED) stringResource(R.string.due_amount, money(s.balanceAmount)) else "",
                                onClick = { detail = Detail("purchase", s.id, s.purchaseNumber, r.partyName.orEmpty(), s.date, s.status, s.subtotal, s.discount, s.tax, s.total, s.paidAmount, s.balanceAmount, s.notes) },
                                badge = { StatusTag(s.status) },
                            )
                        }
                    }
                }
                2 -> {
                    val expenses by vm.expenses.collectAsStateWithLifecycle()
                    if (expenses.isEmpty()) EmptyState(stringResource(R.string.no_expenses), stringResource(R.string.no_expenses_body), painterResource(R.drawable.ic_expense))
                    else LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(expenses, key = { it.id }) { e ->
                            ListRow(
                                title = e.category,
                                subtitle = listOf(fmtDate(e.date), stringResource(methodRes(e.paymentMethod)), e.description).filter { it.isNotBlank() }.joinToString(" · "),
                                trailing = money(e.amount),
                                onClick = if (e.status == Status.ACTIVE) ({ cancelSimple = "expense" to e.id }) else null,
                                badge = if (e.status == Status.CANCELLED) ({ StatusTag(e.status) }) else null,
                            )
                        }
                    }
                }
                else -> {
                    val payments by vm.payments.collectAsStateWithLifecycle()
                    if (payments.isEmpty()) EmptyState(stringResource(R.string.no_payments), stringResource(R.string.no_payments_body), painterResource(R.drawable.ic_expense))
                    else LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(payments, key = { it.payment.id }) { r ->
                            val p = r.payment
                            ListRow(
                                title = r.partyName ?: "—",
                                subtitle = stringResource(if (p.partyType == "CUSTOMER") R.string.received_from_customer else R.string.paid_to_supplier) + " · " + fmtDate(p.date) + " · " + stringResource(methodRes(p.paymentMethod)),
                                trailing = money(p.amount),
                                onClick = if (p.status == Status.ACTIVE) ({ cancelSimple = "payment" to p.id }) else null,
                                badge = if (p.status == Status.CANCELLED) ({ StatusTag(p.status) }) else null,
                            )
                        }
                    }
                }
            }
        }
    }

    detail?.let { d -> DetailDialog(d, vm) { detail = null } }
    cancelSimple?.let { (kind, id) ->
        ConfirmDialog(
            stringResource(if (kind == "expense") R.string.cancel_expense_title else R.string.cancel_payment_title),
            stringResource(R.string.cancel_record_body), stringResource(R.string.cancel_record), danger = true,
            onDismiss = { cancelSimple = null },
            onConfirm = {
                cancelSimple = null
                scope.launch {
                    val r = if (kind == "expense") vm.cancelExpense(id) else vm.cancelPayment(id)
                    toast(ctx.getString(if (r is Op.Err) errorRes(r.code) else R.string.cancelled_done))
                }
            },
        )
    }
}

@Composable
private fun StatusTag(status: String) {
    if (status == Status.CANCELLED || status == Status.UNPAID) WarnTag(stringResource(statusRes(status))) else Tag(stringResource(statusRes(status)))
}

@Composable
private fun DetailDialog(d: Detail, vm: TradeViewModel, onClose: () -> Unit) {
    var lines by remember { mutableStateOf<List<ItemWithProduct>>(emptyList()) }
    var confirmCancel by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current
    LaunchedEffect(d.id) { lines = if (d.kind == "sale") vm.saleLines(d.id) else vm.purchaseLines(d.id) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(d.title) },
        text = {
            Column {
                StatusTag(d.status)
                Spacer(Modifier.height(8.dp))
                if (d.party.isNotBlank()) LabelValue(stringResource(if (d.kind == "sale") R.string.customer else R.string.supplier), d.party)
                LabelValue(stringResource(R.string.date), fmtDate(d.date))
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                for (l in lines) {
                    LabelValue(l.productName + "\n" + qtyText(l.quantity, l.unit) + " × " + money(l.unitPrice), money(l.total))
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                if (d.discount > 0 || d.tax > 0) LabelValue(stringResource(R.string.subtotal), money(d.subtotal))
                if (d.discount > 0) LabelValue(stringResource(R.string.discount), "−" + money(d.discount))
                if (d.tax > 0) LabelValue(stringResource(R.string.tax), money(d.tax))
                LabelValue(stringResource(R.string.total), money(d.total), strong = true)
                LabelValue(stringResource(R.string.paid), money(d.paid))
                if (d.balance > 0) LabelValue(stringResource(R.string.balance), money(d.balance))
                if (d.notes.isNotBlank()) LabelValue(stringResource(R.string.notes), d.notes)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
        dismissButton = if (d.status != Status.CANCELLED) ({
            TextButton(onClick = { confirmCancel = true }) { Text(stringResource(if (d.kind == "sale") R.string.cancel_sale else R.string.cancel_purchase), color = MaterialTheme.colorScheme.error) }
        }) else null,
    )
    if (confirmCancel) {
        ConfirmDialog(
            stringResource(if (d.kind == "sale") R.string.cancel_sale else R.string.cancel_purchase),
            stringResource(if (d.kind == "sale") R.string.cancel_sale_body else R.string.cancel_purchase_body),
            stringResource(R.string.cancel_record), danger = true, onDismiss = { confirmCancel = false },
            onConfirm = {
                confirmCancel = false
                scope.launch {
                    val r = if (d.kind == "sale") vm.cancelSale(d.id) else vm.cancelPurchase(d.id)
                    toast(ctx.getString(if (r is Op.Err) errorRes(r.code) else R.string.cancelled_done))
                    onClose()
                }
            },
        )
    }
}
