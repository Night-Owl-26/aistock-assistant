package com.aistock.assistant.features.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aistock.assistant.R
import com.aistock.assistant.ai.PartyKind
import com.aistock.assistant.data.LineInput
import com.aistock.assistant.data.Op
import com.aistock.assistant.data.PartyRow
import com.aistock.assistant.database.ProductEntity
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.LocalTamil
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.BottomAction
import com.aistock.assistant.ui.components.DateField
import com.aistock.assistant.ui.components.FormPage
import com.aistock.assistant.ui.components.LabelValue
import com.aistock.assistant.ui.components.LocalCurrency
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.NumberInput
import com.aistock.assistant.ui.components.PickerField
import com.aistock.assistant.ui.components.SectionTitle
import com.aistock.assistant.ui.components.TextInput
import com.aistock.assistant.ui.components.errorRes
import com.aistock.assistant.ui.money
import com.aistock.assistant.ui.qtyPlain
import com.aistock.assistant.utils.Money
import com.aistock.assistant.utils.Qty
import kotlinx.coroutines.launch

private class Line(val product: ProductEntity, qty: String, price: String) {
    var qty by mutableStateOf(qty)
    var price by mutableStateOf(price)
    val total: Long get() = Money.lineTotal(Qty.parse(qty) ?: 0L, Money.parse(price) ?: 0L)
}

/** @param kind "sale" or "purchase" */
@Composable
fun TradeFormScreen(nav: AppNav, kind: String, vm: TradeViewModel = hiltViewModel()) {
    val isSale = kind == "sale"
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val tamil = LocalTamil.current
    val currency = LocalCurrency.current

    var products by remember { mutableStateOf<List<ProductEntity>>(emptyList()) }
    var parties by remember { mutableStateOf<List<PartyRow>>(emptyList()) }
    var partyId by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    val lines = remember { mutableStateListOf<Line>() }
    var discount by remember { mutableStateOf("") }
    var tax by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf("") }
    var payMode by remember { mutableStateOf("full") } // full, credit, part
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        products = vm.products()
        parties = vm.partyList(if (isSale) PartyKind.CUSTOMER else PartyKind.SUPPLIER)
    }

    val subtotal = lines.sumOf { it.total }
    val total = subtotal - (Money.parse(discount) ?: 0L) + (Money.parse(tax) ?: 0L)
    val paidNow = when (payMode) { "full" -> total; "credit" -> 0L; else -> Money.parse(paid) ?: 0L }
    val party = parties.firstOrNull { it.id == partyId }

    FormPage(
        title = stringResource(if (isSale) R.string.new_sale else R.string.new_purchase), onBack = nav.back,
        bottom = {
            BottomAction(stringResource(R.string.save_amount, money(total.coerceAtLeast(0))), enabled = !saving && lines.isNotEmpty()) {
                val inputs = ArrayList<LineInput>()
                for (l in lines) {
                    val q = Qty.parse(l.qty)
                    if (q == null || q <= 0) { toast(ctx.getString(R.string.enter_quantity_for, l.product.name)); return@BottomAction }
                    if (isSale && q > l.product.currentStock) {
                        // The business rule is enforced again when saving; this just explains it early.
                        toast(ctx.getString(R.string.only_in_stock, l.product.name, qtyPlain(l.product.currentStock, l.product.unit, tamil)))
                    }
                    inputs.add(LineInput(l.product.id, q, Money.parse(l.price) ?: 0L))
                }
                if (total < 0) { toast(ctx.getString(R.string.err_invalid)); return@BottomAction }
                if (paidNow > total) { toast(ctx.getString(R.string.paid_more_than_total)); return@BottomAction }
                if (paidNow < total && partyId == null) {
                    toast(ctx.getString(if (isSale) R.string.choose_customer_for_credit else R.string.choose_supplier_for_credit)); return@BottomAction
                }
                saving = true
                scope.launch {
                    val d = Money.parse(discount) ?: 0L
                    val t = Money.parse(tax) ?: 0L
                    val r: Op<Any> = if (isSale) vm.createSale(partyId, inputs, d, t, paidNow, notes, date) else vm.createPurchase(partyId, inputs, d, t, paidNow, notes, date)
                    saving = false
                    when (r) {
                        is Op.Ok -> { toast(ctx.getString(if (isSale) R.string.sale_saved else R.string.purchase_saved)); nav.back() }
                        is Op.Err -> toast(ctx.getString(errorRes(r.code)))
                    }
                }
            }
        },
    ) {
        PickerField(
            stringResource(if (isSale) R.string.customer_optional else R.string.supplier_optional), party?.name.orEmpty(),
            parties.map { it.id to it.name }, onPick = { partyId = it }, onClear = { partyId = null },
            emptyText = stringResource(if (isSale) R.string.no_customers else R.string.no_suppliers),
        )
        DateField(stringResource(R.string.date), date, { date = it })

        SectionTitle(stringResource(R.string.items))
        for (l in lines) {
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.product.name, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.in_stock_n, qtyPlain(l.product.currentStock, l.product.unit, tamil)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { lines.remove(l) }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_item)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberInput(stringResource(R.string.quantity), l.qty, { l.qty = it }, Modifier.weight(1f), suffix = l.product.unit)
                    NumberInput(stringResource(R.string.rate), l.price, { l.price = it }, Modifier.weight(1f), decimals = 2, suffix = currency)
                }
                LabelValue(stringResource(R.string.amount), money(l.total), strong = true)
            }
        }
        PickerField(
            stringResource(R.string.add_item), "",
            products.filter { p -> lines.none { it.product.id == p.id } }.map { it.id to (it.name + "  ·  " + qtyPlain(it.currentStock, it.unit, tamil)) },
            onPick = { id ->
                products.firstOrNull { it.id == id }?.let { p ->
                    val price = if (isSale) p.sellingPrice else p.purchasePrice
                    lines.add(Line(p, "1", if (price == 0L) "" else Money.plain(price)))
                }
            },
            emptyText = stringResource(R.string.no_products),
        )

        if (lines.isNotEmpty()) {
            SectionTitle(stringResource(R.string.bill))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberInput(stringResource(R.string.discount), discount, { discount = it }, Modifier.weight(1f), decimals = 2, suffix = currency)
                NumberInput(stringResource(R.string.tax), tax, { tax = it }, Modifier.weight(1f), decimals = 2, suffix = currency)
            }
            AppCard(Modifier.fillMaxWidth()) {
                LabelValue(stringResource(R.string.subtotal), money(subtotal))
                LabelValue(stringResource(R.string.total), money(total), strong = true)
                LabelValue(stringResource(R.string.balance), money((total - paidNow).coerceAtLeast(0)))
            }
            SectionTitle(stringResource(R.string.payment))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = payMode == "full", onClick = { payMode = "full" }, label = { Text(stringResource(R.string.paid_in_full)) })
                FilterChip(selected = payMode == "credit", onClick = { payMode = "credit" }, label = { Text(stringResource(R.string.credit)) })
                FilterChip(selected = payMode == "part", onClick = { payMode = "part" }, label = { Text(stringResource(R.string.part_paid)) })
            }
            if (payMode == "part") NumberInput(stringResource(R.string.amount_paid_now), paid, { paid = it }, decimals = 2, suffix = currency)
            TextInput(stringResource(R.string.notes), notes, { notes = it }, singleLine = false, capitalize = false)
        }
        Spacer(Modifier.height(8.dp))
    }
}
