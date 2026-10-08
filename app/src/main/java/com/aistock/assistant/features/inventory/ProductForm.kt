package com.aistock.assistant.features.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.aistock.assistant.data.ProductInput
import com.aistock.assistant.database.CategoryEntity
import com.aistock.assistant.database.ProductEntity
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.BottomAction
import com.aistock.assistant.ui.components.ConfirmDialog
import com.aistock.assistant.ui.components.DropdownField
import com.aistock.assistant.ui.components.FormPage
import com.aistock.assistant.ui.components.LocalCurrency
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.NumberInput
import com.aistock.assistant.ui.components.SectionTitle
import com.aistock.assistant.ui.components.TextInput
import com.aistock.assistant.ui.components.WarnTag
import com.aistock.assistant.ui.components.errorRes
import com.aistock.assistant.ui.qtyText
import com.aistock.assistant.utils.Money
import com.aistock.assistant.utils.Qty
import com.aistock.assistant.utils.Units
import kotlinx.coroutines.launch

@Composable
fun ProductFormScreen(nav: AppNav, productId: String?, vm: InventoryViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val currency = LocalCurrency.current

    var loaded by remember { mutableStateOf(productId == null) }
    var existing by remember { mutableStateOf<ProductEntity?>(null) }
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var unit by rememberSaveable { mutableStateOf("PCS") }
    var customUnit by rememberSaveable { mutableStateOf("") }
    var barcode by rememberSaveable { mutableStateOf("") }
    var buy by rememberSaveable { mutableStateOf("") }
    var sell by rememberSaveable { mutableStateOf("") }
    var min by rememberSaveable { mutableStateOf("") }
    var opening by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(productId) {
        categories = vm.activeCategories()
        if (productId != null && !loaded) {
            val p = vm.product(productId)
            if (p == null) { nav.back(); return@LaunchedEffect }
            existing = p
            name = p.name; code = p.productCode; categoryId = p.categoryId; barcode = p.barcode
            if (p.unit in Units.ALL) unit = p.unit else { unit = "OTHER"; customUnit = p.unit }
            buy = if (p.purchasePrice == 0L) "" else Money.plain(p.purchasePrice)
            sell = if (p.sellingPrice == 0L) "" else Money.plain(p.sellingPrice)
            min = if (p.minimumStock == 0L) "" else Qty.format(p.minimumStock)
            description = p.description
            loaded = true
        }
    }

    val unitOptions = Units.ALL.map { it to (if (it == "OTHER") stringResource(R.string.unit_other) else it) }
    val categoryOptions = listOf("" to stringResource(R.string.no_category)) + categories.map { it.id to it.name }

    FormPage(
        title = stringResource(if (productId == null) R.string.add_product else R.string.edit_product),
        onBack = nav.back,
        actions = {
            if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete)) }
        },
        bottom = {
            BottomAction(stringResource(R.string.save), enabled = loaded && !saving) {
                if (name.isBlank()) { nameError = ctx.getString(R.string.name_required); return@BottomAction }
                val finalUnit = if (unit == "OTHER" && customUnit.isNotBlank()) Units.custom(customUnit) else unit
                val input = ProductInput(
                    name = name, productCode = code, description = description, categoryId = categoryId?.ifEmpty { null }, unit = finalUnit, barcode = barcode,
                    purchasePrice = Money.parse(buy) ?: 0L, sellingPrice = Money.parse(sell) ?: 0L,
                    minimumStock = Qty.parse(min) ?: 0L, openingStock = Qty.parse(opening) ?: 0L,
                )
                saving = true
                scope.launch {
                    val r = if (productId == null) vm.create(input) else vm.update(productId, input)
                    saving = false
                    when (r) {
                        is Op.Ok -> { toast(ctx.getString(R.string.saved)); nav.back() }
                        is Op.Err -> if (r.code == "DUPLICATE") nameError = ctx.getString(R.string.product_exists) else toast(ctx.getString(errorRes(r.code)))
                    }
                }
            }
        },
    ) {
        existing?.let { p ->
            AppCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.current_stock), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(qtyText(p.currentStock, p.unit), style = MaterialTheme.typography.headlineMedium)
                if (p.currentStock <= p.minimumStock) WarnTag(stringResource(R.string.low_stock))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.go("stockmove/in?productId=${p.id}") }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(stringResource(R.string.add_short)) }
                    OutlinedButton(onClick = { nav.go("stockmove/out?productId=${p.id}") }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(stringResource(R.string.remove_short)) }
                    OutlinedButton(onClick = { nav.go("stockmove/adjust?productId=${p.id}") }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(stringResource(R.string.count_short)) }
                }
            }
        }
        TextInput(stringResource(R.string.product_name), name, { name = it; nameError = null }, error = nameError)
        DropdownField(stringResource(R.string.unit), unitOptions, unit, { unit = it })
        if (unit == "OTHER") TextInput(stringResource(R.string.custom_unit), customUnit, { customUnit = it.take(12) })
        DropdownField(stringResource(R.string.category), categoryOptions, categoryId ?: "", { categoryId = it.ifEmpty { null } })
        SectionTitle(stringResource(R.string.prices))
        NumberInput(stringResource(R.string.selling_price), sell, { sell = it }, decimals = 2, suffix = currency)
        NumberInput(stringResource(R.string.purchase_price), buy, { buy = it }, decimals = 2, suffix = currency)
        SectionTitle(stringResource(R.string.stock))
        if (productId == null) NumberInput(stringResource(R.string.opening_stock), opening, { opening = it })
        NumberInput(stringResource(R.string.minimum_stock), min, { min = it })
        SectionTitle(stringResource(R.string.more_details))
        TextInput(stringResource(R.string.product_code), code, { code = it }, capitalize = false)
        TextInput(stringResource(R.string.barcode), barcode, { barcode = it }, capitalize = false)
        TextInput(stringResource(R.string.description), description, { description = it }, singleLine = false)
        Spacer(Modifier.height(8.dp))
    }

    if (confirmDelete) {
        ConfirmDialog(
            stringResource(R.string.delete_product_title), stringResource(R.string.delete_product_body), stringResource(R.string.delete),
            danger = true, onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    when (val r = vm.remove(productId ?: return@launch)) {
                        is Op.Ok -> { toast(ctx.getString(if (r.value) R.string.deleted else R.string.hidden_kept_history)); nav.back() }
                        is Op.Err -> toast(ctx.getString(errorRes(r.code)))
                    }
                }
            },
        )
    }
}
