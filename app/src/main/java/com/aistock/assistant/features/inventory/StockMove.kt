package com.aistock.assistant.features.inventory

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aistock.assistant.R
import com.aistock.assistant.data.Op
import com.aistock.assistant.data.TxnType
import com.aistock.assistant.database.ProductEntity
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.LocalTamil
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.BottomAction
import com.aistock.assistant.ui.components.ConfirmDialog
import com.aistock.assistant.ui.components.DropdownField
import com.aistock.assistant.ui.components.FormPage
import com.aistock.assistant.ui.components.LocalCurrency
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.NumberInput
import com.aistock.assistant.ui.components.PickerField
import com.aistock.assistant.ui.components.TextInput
import com.aistock.assistant.ui.components.errorRes
import com.aistock.assistant.ui.qtyPlain
import com.aistock.assistant.ui.qtyText
import com.aistock.assistant.ui.txnTypeRes
import com.aistock.assistant.utils.Money
import com.aistock.assistant.utils.Qty
import kotlinx.coroutines.launch

/** @param mode "in", "out" or "adjust" */
@Composable
fun StockMoveScreen(nav: AppNav, mode: String, initialProductId: String?, vm: InventoryViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val tamil = LocalTamil.current
    var products by remember { mutableStateOf<List<ProductEntity>>(emptyList()) }
    var productId by rememberSaveable { mutableStateOf(initialProductId) }
    var qty by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(if (mode == "out") TxnType.DAMAGE else TxnType.ADJUSTMENT_IN) }
    var qtyError by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { products = vm.activeProducts() }
    val product = products.firstOrNull { it.id == productId }
    val amount = Qty.parse(qty)
    val after: Long? = if (product != null && amount != null) when (mode) {
        "in" -> product.currentStock + amount
        "out" -> product.currentStock - amount
        else -> amount
    } else null

    val title = stringResource(when (mode) { "in" -> R.string.stock_in; "out" -> R.string.stock_out; else -> R.string.stock_adjustment })
    FormPage(
        title = title, onBack = nav.back,
        bottom = {
            BottomAction(stringResource(R.string.review), enabled = !saving) {
                when {
                    product == null -> toast(ctx.getString(R.string.choose_product))
                    amount == null || (amount <= 0 && mode != "adjust") -> qtyError = ctx.getString(R.string.enter_quantity)
                    mode == "adjust" && amount == product.currentStock -> qtyError = ctx.getString(R.string.same_as_current)
                    else -> review = true
                }
            }
        },
    ) {
        PickerField(
            stringResource(R.string.product), product?.name.orEmpty(),
            products.map { it.id to (it.name + "  ·  " + qtyPlain(it.currentStock, it.unit, tamil)) },
            onPick = { productId = it }, emptyText = stringResource(R.string.no_products),
        )
        if (product != null) {
            AppCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.current_stock), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(qtyText(product.currentStock, product.unit), style = MaterialTheme.typography.headlineSmall)
            }
        }
        NumberInput(
            stringResource(if (mode == "adjust") R.string.counted_quantity else R.string.quantity), qty, { qty = it; qtyError = null },
            suffix = product?.unit, error = qtyError,
        )
        when (mode) {
            "in" -> {
                DropdownField(stringResource(R.string.reason), TxnType.INCOMING.map { it to stringResource(txnTypeRes(it)) }, type, { type = it })
                NumberInput(stringResource(R.string.unit_cost_optional), cost, { cost = it }, decimals = 2, suffix = LocalCurrency.current)
            }
            "out" -> DropdownField(stringResource(R.string.reason), TxnType.OUTGOING.map { it to stringResource(txnTypeRes(it)) }, type, { type = it })
        }
        TextInput(stringResource(R.string.notes), notes, { notes = it }, singleLine = false, capitalize = false)
        Spacer(Modifier.height(8.dp))
    }

    if (review && product != null && amount != null && after != null) {
        val body = buildString {
            append(product.name).append("\n\n")
            append(ctx.getString(R.string.current_stock)).append(": ").append(qtyPlain(product.currentStock, product.unit, tamil)).append("\n")
            append(ctx.getString(R.string.new_stock)).append(": ").append(qtyPlain(after, product.unit, tamil))
            val diff = after - product.currentStock
            append("\n").append(ctx.getString(R.string.change)).append(": ").append(if (diff > 0) "+" else "−").append(qtyPlain(Math.abs(diff), product.unit, tamil))
        }
        ConfirmDialog(
            title, body, stringResource(R.string.confirm), danger = mode == "adjust" && after < product.currentStock,
            onDismiss = { review = false },
            onConfirm = {
                review = false
                saving = true
                scope.launch {
                    val r = when (mode) {
                        "in" -> vm.stockIn(product.id, amount, type, Money.parse(cost)?.takeIf { it > 0 }, notes)
                        "out" -> vm.stockOut(product.id, amount, type, notes)
                        else -> vm.adjustTo(product.id, amount, notes)
                    }
                    saving = false
                    when (r) {
                        is Op.Ok -> { toast(ctx.getString(R.string.stock_updated, product.name, qtyPlain(r.value.afterQuantity, product.unit, tamil))); nav.back() }
                        is Op.Err -> toast(ctx.getString(errorRes(r.code)))
                    }
                }
            },
        )
    }
}
