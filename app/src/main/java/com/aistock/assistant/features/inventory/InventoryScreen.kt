package com.aistock.assistant.features.inventory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.aistock.assistant.database.CategoryWithCount
import com.aistock.assistant.database.MovementWithProduct
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.ConfirmDialog
import com.aistock.assistant.ui.components.EmptyState
import com.aistock.assistant.ui.components.LabelValue
import com.aistock.assistant.ui.components.ListRow
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.SearchField
import com.aistock.assistant.ui.components.Tag
import com.aistock.assistant.ui.components.TextInput
import com.aistock.assistant.ui.components.WarnTag
import com.aistock.assistant.ui.components.errorRes
import com.aistock.assistant.ui.fmtDateTime
import com.aistock.assistant.ui.money
import com.aistock.assistant.ui.qtyText
import com.aistock.assistant.ui.txnTypeRes
import com.aistock.assistant.utils.Qty
import kotlinx.coroutines.launch

@Composable
fun InventoryScreen(nav: AppNav, initialTab: Int, vm: InventoryViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    var editCategory by remember { mutableStateOf<CategoryWithCount?>(null) }
    var newCategory by remember { mutableStateOf(false) }
    val titles = listOf(R.string.products, R.string.categories, R.string.stock, R.string.history)
    Scaffold(
        floatingActionButton = {
            when (tab) {
                0 -> ExtendedFloatingActionButton(onClick = { nav.go("product") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.add_product)) })
                1 -> ExtendedFloatingActionButton(onClick = { newCategory = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.add_category)) })
                2 -> ExtendedFloatingActionButton(onClick = { nav.go("stockmove/in") }, icon = { Icon(painterResource(R.drawable.ic_stock_in), null) }, text = { Text(stringResource(R.string.stock_in)) })
                else -> {}
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().statusBarsPadding()) {
            Text(stringResource(R.string.nav_inventory), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                titles.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
            when (tab) {
                0 -> ProductsTab(nav, vm)
                1 -> CategoriesTab(vm) { editCategory = it }
                2 -> StockTab(nav, vm)
                else -> HistoryTab(vm)
            }
        }
    }
    if (newCategory) CategoryDialog(null, vm) { newCategory = false }
    editCategory?.let { c -> CategoryDialog(c, vm) { editCategory = null } }
}

@Composable
private fun ProductsTab(nav: AppNav, vm: InventoryViewModel) {
    val query by vm.query.collectAsStateWithLifecycle()
    val products by vm.products.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        SearchField(query, { vm.query.value = it }, stringResource(R.string.search_products), Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        if (products.isEmpty()) {
            EmptyState(
                if (query.isBlank()) stringResource(R.string.no_products) else stringResource(R.string.nothing_found),
                if (query.isBlank()) stringResource(R.string.no_products_body) else "",
                painterResource(R.drawable.ic_inventory),
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(products, key = { it.product.id }) { row ->
                    val p = row.product
                    val low = p.currentStock <= p.minimumStock
                    ListRow(
                        title = p.name,
                        subtitle = listOfNotNull(p.productCode.ifBlank { null }, row.categoryName).joinToString(" · "),
                        trailing = qtyText(p.currentStock, p.unit), trailingSub = money(p.sellingPrice),
                        onClick = { nav.go("product?id=${p.id}") },
                        badge = if (low) ({ WarnTag(stringResource(R.string.low_stock)) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoriesTab(vm: InventoryViewModel, onEdit: (CategoryWithCount) -> Unit) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    if (categories.isEmpty()) {
        EmptyState(stringResource(R.string.no_categories), stringResource(R.string.no_categories_body), painterResource(R.drawable.ic_inventory))
    } else {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories, key = { it.category.id }) { c ->
                ListRow(title = c.category.name, subtitle = c.category.description, trailing = stringResource(R.string.n_products, c.productCount), onClick = { onEdit(c) })
            }
        }
    }
}

@Composable
private fun CategoryDialog(existing: CategoryWithCount?, vm: InventoryViewModel, onClose: () -> Unit) {
    var name by remember { mutableStateOf(existing?.category?.name.orEmpty()) }
    var description by remember { mutableStateOf(existing?.category?.description.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(if (existing == null) R.string.add_category else R.string.edit_category)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput(stringResource(R.string.name), name, { name = it; error = null }, error = error)
                TextInput(stringResource(R.string.description), description, { description = it })
                if (existing != null) {
                    TextButton(onClick = {
                        scope.launch {
                            vm.removeCategory(existing.category.id)
                            toast(ctx.getString(R.string.deleted)); onClose()
                        }
                    }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    when (val r = vm.saveCategory(existing?.category?.id, name, description)) {
                        is Op.Ok -> { toast(ctx.getString(R.string.saved)); onClose() }
                        is Op.Err -> error = ctx.getString(errorRes(r.code))
                    }
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun StockTab(nav: AppNav, vm: InventoryViewModel) {
    val products by vm.products.collectAsStateWithLifecycle()
    var lowOnly by rememberSaveable { mutableStateOf(false) }
    val shown = if (lowOnly) products.filter { it.product.currentStock <= it.product.minimumStock } else products
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !lowOnly, onClick = { lowOnly = false }, label = { Text(stringResource(R.string.all)) })
            FilterChip(selected = lowOnly, onClick = { lowOnly = true }, label = { Text(stringResource(R.string.low_stock)) })
        }
        if (shown.isEmpty()) {
            EmptyState(if (lowOnly) stringResource(R.string.no_low_stock) else stringResource(R.string.no_products), "", painterResource(R.drawable.ic_inventory))
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.product.id }) { row ->
                    val p = row.product
                    val low = p.currentStock <= p.minimumStock
                    com.aistock.assistant.ui.components.AppCard(Modifier.fillMaxWidth()) {
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.minimum_is, qtyText(p.minimumStock, p.unit)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(qtyText(p.currentStock, p.unit), style = MaterialTheme.typography.titleLarge)
                                if (low) WarnTag(stringResource(R.string.low_stock))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { nav.go("stockmove/in?productId=${p.id}") }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(stringResource(R.string.add_short)) }
                            OutlinedButton(onClick = { nav.go("stockmove/out?productId=${p.id}") }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(stringResource(R.string.remove_short)) }
                            OutlinedButton(onClick = { nav.go("stockmove/adjust?productId=${p.id}") }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(stringResource(R.string.count_short)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryTab(vm: InventoryViewModel) {
    val query by vm.historyQuery.collectAsStateWithLifecycle()
    val movements by vm.movements.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<MovementWithProduct?>(null) }
    Column(Modifier.fillMaxSize()) {
        SearchField(query, { vm.historyQuery.value = it }, stringResource(R.string.search_history), Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        if (movements.isEmpty()) {
            EmptyState(stringResource(R.string.no_history), stringResource(R.string.no_history_body), painterResource(R.drawable.ic_inventory))
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(movements, key = { it.txn.id }) { m ->
                    val delta = m.txn.afterQuantity - m.txn.beforeQuantity
                    ListRow(
                        title = m.productName,
                        subtitle = stringResource(txnTypeRes(m.txn.type)) + " · " + fmtDateTime(m.txn.createdAt),
                        trailing = (if (delta > 0) "+" else "−") + qtyText(Math.abs(delta), m.txn.unit),
                        trailingSub = Qty.format(m.txn.beforeQuantity) + " → " + Qty.format(m.txn.afterQuantity),
                        onClick = { open = m },
                    )
                }
            }
        }
    }
    open?.let { m -> MovementDialog(m, vm) { open = null } }
}

@Composable
private fun MovementDialog(m: MovementWithProduct, vm: InventoryViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current
    var confirmUndo by remember { mutableStateOf(false) }
    val delta = m.txn.afterQuantity - m.txn.beforeQuantity
    val canUndo = m.txn.referenceType in setOf("MANUAL", "ASSISTANT", "ADJUSTMENT")
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(m.productName) },
        text = {
            Column {
                Tag(stringResource(txnTypeRes(m.txn.type)))
                Spacer(Modifier.height(8.dp))
                LabelValue(stringResource(R.string.date), fmtDateTime(m.txn.createdAt))
                LabelValue(stringResource(R.string.change), (if (delta > 0) "+" else "−") + qtyText(Math.abs(delta), m.txn.unit))
                LabelValue(stringResource(R.string.before), qtyText(m.txn.beforeQuantity, m.txn.unit))
                LabelValue(stringResource(R.string.after), qtyText(m.txn.afterQuantity, m.txn.unit))
                m.txn.unitCost?.let { LabelValue(stringResource(R.string.unit_cost), money(it)) }
                if (m.txn.notes.isNotBlank()) LabelValue(stringResource(R.string.notes), m.txn.notes)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
        dismissButton = if (canUndo) ({ TextButton(onClick = { confirmUndo = true }) { Text(stringResource(R.string.undo)) } }) else null,
    )
    if (confirmUndo) {
        ConfirmDialog(
            stringResource(R.string.undo_title), stringResource(R.string.undo_body), stringResource(R.string.undo),
            onConfirm = {
                confirmUndo = false
                scope.launch {
                    when (val r = vm.undo(m.txn.id)) {
                        is Op.Ok -> toast(ctx.getString(R.string.undone))
                        is Op.Err -> toast(ctx.getString(errorRes(r.code)))
                    }
                    onClose()
                }
            },
            onDismiss = { confirmUndo = false },
        )
    }
}

@Composable
internal fun Placeholder() { Box(Modifier.fillMaxSize()) }
