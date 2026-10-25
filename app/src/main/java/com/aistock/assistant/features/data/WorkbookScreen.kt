package com.aistock.assistant.features.data

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aistock.assistant.R
import com.aistock.assistant.excel.ColumnAiStatus
import com.aistock.assistant.excel.HeaderMapper
import com.aistock.assistant.excel.ImportKind
import com.aistock.assistant.excel.ImportSpec
import com.aistock.assistant.excel.LinkCandidate
import com.aistock.assistant.excel.SyncOutcome
import com.aistock.assistant.excel.SheetProfile
import com.aistock.assistant.excel.SheetPurpose
import com.aistock.assistant.excel.WorkbookPlanItem
import com.aistock.assistant.excel.WorkbookResult
import com.aistock.assistant.features.reports.XLSX_MIME
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.DropdownField
import com.aistock.assistant.ui.components.FormPage
import com.aistock.assistant.ui.components.LabelValue
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.SectionTitle
import com.aistock.assistant.ui.components.Tag
import com.aistock.assistant.ui.components.WarnTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun purposeRes(p: SheetPurpose): Int = when (p) {
    SheetPurpose.PRODUCTS -> R.string.purpose_products
    SheetPurpose.STOCK -> R.string.purpose_stock
    SheetPurpose.CUSTOMERS -> R.string.purpose_customers
    SheetPurpose.SUPPLIERS -> R.string.purpose_suppliers
    SheetPurpose.SALES -> R.string.purpose_sales
    SheetPurpose.PURCHASES -> R.string.purpose_purchases
    SheetPurpose.EXPENSES -> R.string.purpose_expenses
    SheetPurpose.PAYMENTS -> R.string.purpose_payments
    SheetPurpose.OTHER -> R.string.purpose_other
}

/** Label for one import field, e.g. "Size" or "Selling price". */
fun fieldRes(key: String): Int = when (key) {
    "name" -> R.string.field_name
    "size" -> R.string.field_size
    "code" -> R.string.field_code
    "category" -> R.string.field_category
    "unit" -> R.string.field_unit
    "barcode" -> R.string.field_barcode
    "purchasePrice", "unitCost" -> R.string.field_purchase_price
    "sellingPrice" -> R.string.field_selling_price
    "minStock" -> R.string.field_min_stock
    "openingStock", "quantity", "stock" -> R.string.field_stock
    "description" -> R.string.field_description
    "phone" -> R.string.field_phone
    "email" -> R.string.field_email
    "address" -> R.string.field_address
    "openingBalance" -> R.string.field_opening_balance
    else -> R.string.field_notes
}

private fun sourceRes(s: String?): Int = when (s) {
    "ai" -> R.string.col_by_ai
    "saved" -> R.string.col_by_saved
    "you" -> R.string.col_by_you
    else -> R.string.col_by_rules
}

/** One line about the last update of a linked file. */
@Composable
fun syncText(o: SyncOutcome?): String = when {
    o == null -> stringResource(R.string.sync_never)
    o.ok && o.code == "UP_TO_DATE" -> stringResource(R.string.sync_up_to_date) + (if (o.formula > 0) " " + stringResource(R.string.sync_formula_skipped, o.formula) else "")
    o.ok -> stringResource(R.string.sync_updated, o.changed, o.added) + (if (o.formula > 0) " " + stringResource(R.string.sync_formula_skipped, o.formula) else "")
    else -> stringResource(when (o.code) {
        "PERMISSION", "OPEN" -> R.string.sync_err_open
        "NO_SHEET" -> R.string.sync_err_sheet
        "COLUMNS" -> R.string.sync_err_columns
        "READ_ONLY" -> R.string.sync_err_read_only
        "LAYOUT", "NOT_XLSX", "TOO_LARGE" -> R.string.sync_err_layout
        "VERIFY" -> R.string.sync_err_verify
        else -> R.string.sync_err_failed
    })
}

private fun kindRes(k: ImportKind): Int = when (k) {
    ImportKind.PRODUCTS -> R.string.products
    ImportKind.CUSTOMERS -> R.string.customers
    ImportKind.SUPPLIERS -> R.string.suppliers
    ImportKind.OPENING_STOCK -> R.string.opening_stock
}

/**
 * Upload a whole workbook: the AI (when set up) and the header rules read every sheet, the owner sees what each sheet
 * and column is taken to be and can change any of it, and only then is anything imported. Afterwards the owner may
 * turn on keeping that same Excel file updated with the app's stock.
 */
@Composable
fun WorkbookScreen(nav: AppNav, vm: DataViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current
    var busy by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var fileName by remember { mutableStateOf("") }
    var fileUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var writable by remember { mutableStateOf(false) }
    var isXlsx by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf<List<SheetProfile>>(emptyList()) }
    var aiStatus by remember { mutableStateOf<ColumnAiStatus?>(null) }
    var changes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var plan by remember { mutableStateOf<List<WorkbookPlanItem>>(emptyList()) }
    var result by remember { mutableStateOf<WorkbookResult?>(null) }
    var linkSheet by remember { mutableStateOf<String?>(null) }
    var addRows by remember { mutableStateOf(true) }
    var salesSheet by remember { mutableStateOf(false) }
    var linkOutcome by remember { mutableStateOf<SyncOutcome?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; result = null; aiStatus = null; linkOutcome = null; linkSheet = null
            try {
                val name = displayName(ctx, uri)
                val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: ByteArray(0)
                val found = vm.exchange.analyzeWorkbook(name, bytes.inputStream())
                if (found.isEmpty()) toast(ctx.getString(R.string.import_empty))
                fileName = name
                fileUri = uri
                isXlsx = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
                writable = isXlsx && withContext(Dispatchers.IO) { vm.excelSync.keepPermission(uri) && vm.excelSync.canWrite(uri) }
                changes = vm.exchange.workbookChanges(found)
                profiles = found
                busy = false
                if (found.isNotEmpty()) {
                    reading = true
                    val (smart, status) = vm.exchange.smartColumns(found)
                    profiles = smart; aiStatus = status
                    reading = false
                }
            } catch (e: Exception) {
                profiles = emptyList()
                toast(ctx.getString(R.string.import_unreadable))
            }
            busy = false; reading = false
        }
    }
    LaunchedEffect(profiles) { plan = if (profiles.isEmpty()) emptyList() else try { vm.exchange.workbookPlan(profiles) } catch (e: Exception) { emptyList() } }

    /** The owner picks a column for one field; that column is taken away from any other field. */
    fun setColumn(sheet: String, kind: ImportKind, key: String, col: Int) {
        val field = HeaderMapper.sharedField(kind, key)
        profiles = profiles.map { p ->
            if (p.name != sheet) return@map p
            val fields = LinkedHashMap(p.fields)
            val source = LinkedHashMap(p.fieldSource)
            if (col >= 0) {
                // Any other field that was using the chosen column loses it (marked "not used").
                val current = HeaderMapper.choose(p, kind).mapping
                for ((k, c) in current) if (c == col && k != key) { val f = HeaderMapper.sharedField(kind, k); fields[f] = -1; source[f] = "you" }
            }
            fields[field] = col; source[field] = "you"
            p.copy(fields = fields, fieldSource = source)
        }
    }

    FormPage(title = stringResource(R.string.workbook_title), onBack = nav.back) {
        AppCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.workbook_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Button(enabled = !busy && !reading, onClick = {
                picker.launch(arrayOf(XLSX_MIME, "text/csv", "text/comma-separated-values", "application/csv", "text/plain", "application/vnd.ms-excel", "application/octet-stream"))
            }) { Text(stringResource(if (profiles.isEmpty()) R.string.choose_file else R.string.choose_another_file)) }
            if (busy) { Spacer(Modifier.height(10.dp)); CircularProgressIndicator() }
        }

        val done = result
        if (done != null) {
            SectionTitle(stringResource(R.string.import_finished))
            AppCard(Modifier.fillMaxWidth()) {
                for ((kind, n) in done.imported) LabelValue(stringResource(kindRes(kind)), n.toString(), strong = true)
                LabelValue(stringResource(R.string.workbook_sheets_remembered), done.sheetsRemembered.toString())
                Text(stringResource(R.string.workbook_done_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (i in done.issues.take(15)) Text(i, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { nav.go("ai") }) { Text(stringResource(R.string.workbook_ask_stocky)) }
                    OutlinedButton(onClick = nav.back) { Text(stringResource(R.string.close)) }
                }
            }
            val uri = fileUri
            if (isXlsx && uri != null && done.linkable.isNotEmpty()) {
                SectionTitle(stringResource(R.string.link_title))
                AppCard(Modifier.fillMaxWidth()) {
                    val chosen: LinkCandidate = done.linkable.firstOrNull { it.sheet == linkSheet } ?: done.linkable.first()
                    val outcome = linkOutcome
                    if (outcome != null) {
                        Text(syncText(outcome), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { nav.go("excel_links") }) { Text(stringResource(R.string.link_manage)) }
                    } else if (!writable) {
                        Text(stringResource(R.string.link_cannot_write), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val stockTitle = HeaderMapper.title(chosen.headers, chosen.stockCol)
                        Text(stringResource(R.string.link_body, fileName, chosen.sheet, stockTitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (done.linkable.size > 1) {
                            Spacer(Modifier.height(8.dp))
                            DropdownField(stringResource(R.string.link_sheet), done.linkable.map { it.sheet to it.sheet }, chosen.sheet, { linkSheet = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = addRows, onCheckedChange = { addRows = it })
                            Text(stringResource(R.string.link_add_rows), style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = salesSheet, onCheckedChange = { salesSheet = it })
                            Text(stringResource(R.string.link_sales_sheet), style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                linkOutcome = try { vm.excelSync.link(uri, fileName, chosen, addRows, salesSheet) } catch (e: Exception) { SyncOutcome(false, "FAILED") }
                                busy = false
                            }
                        }) { Text(stringResource(R.string.link_turn_on)) }
                    }
                }
            }
        } else if (profiles.isNotEmpty()) {
            SectionTitle(stringResource(R.string.workbook_found, profiles.size, fileName))
            AppCard(Modifier.fillMaxWidth()) {
                if (reading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(stringResource(R.string.cols_reading), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(stringResource(when (aiStatus) {
                        ColumnAiStatus.USED -> R.string.cols_ai_used
                        ColumnAiStatus.SAVED -> R.string.cols_saved
                        ColumnAiStatus.FAILED -> R.string.cols_ai_failed
                        ColumnAiStatus.OFF -> R.string.cols_ai_off
                        else -> R.string.cols_rules
                    }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            for ((index, p) in profiles.withIndex()) {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.workbook_rows_cols, p.rowCount, p.headers.filter { it.isNotBlank() }.take(6).joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (p.purpose != SheetPurpose.OTHER && p.confidence < 60) WarnTag(stringResource(R.string.workbook_please_check))
                        if (p.purposeSource == "ai") Tag(stringResource(R.string.col_by_ai))
                        if (p.hidden) Tag(stringResource(R.string.workbook_hidden))
                        when (val c = changes[p.name]) {
                            null -> {}
                            "NEW_SHEET" -> Tag(stringResource(R.string.workbook_new_sheet))
                            else -> Tag(stringResource(R.string.workbook_new_columns, c))
                        }
                    }
                    if (p.links.isNotEmpty()) Text(stringResource(R.string.workbook_linked, p.links.take(3).joinToString("; ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    DropdownField(
                        stringResource(R.string.workbook_this_is), SheetPurpose.entries.map { it.name to stringResource(purposeRes(it)) }, p.purpose.name,
                        { chosen -> profiles = profiles.toMutableList().also { it[index] = p.copy(purpose = SheetPurpose.valueOf(chosen), confidence = 100, purposeSource = "you") } },
                    )
                }
            }
            SectionTitle(stringResource(R.string.preview))
            AppCard(Modifier.fillMaxWidth()) {
                if (plan.isEmpty()) Text(stringResource(R.string.workbook_nothing_to_import), style = MaterialTheme.typography.bodyMedium)
                for (item in plan) {
                    LabelValue(stringResource(kindRes(item.kind)) + " · " + item.sheet, stringResource(R.string.workbook_found_n, item.preview.ready.size), strong = true)
                    if (item.editable) {
                        val notUsed = stringResource(R.string.col_not_used)
                        val options = listOf("-1" to notUsed) + item.headers.indices.map { it.toString() to HeaderMapper.title(item.headers, it) }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (f in ImportSpec.fields(item.kind)) {
                                val col = item.choice.mapping[f.key]
                                // Only fields that are matched, plus the important ones, so the list stays short.
                                if (col == null && f.key !in setOf("name", "size", "sellingPrice", "openingStock", "quantity", "phone")) continue
                                val label = stringResource(fieldRes(f.key)) + (if (col != null) " · " + stringResource(sourceRes(item.choice.source[f.key])) else "")
                                DropdownField(label, options, (col ?: -1).toString(), { picked -> setColumn(item.sheet, item.kind, f.key, picked.toInt()) })
                            }
                        }
                    }
                    if (item.preview.skipped.isNotEmpty() || item.preview.errors.isNotEmpty()) {
                        Text(stringResource(R.string.workbook_skip_err, item.preview.skipped.size, item.preview.errors.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (i in (item.preview.errors + item.preview.skipped).take(3)) Text(stringResource(R.string.row_issue, i.rowNumber, i.message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item.preview.ready.firstOrNull()?.let { first ->
                        Text(stringResource(R.string.cols_first_row, first.values.entries.take(5).joinToString(", ") { it.value }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(stringResource(R.string.workbook_other_sheets), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Button(enabled = !busy && !reading, onClick = {
                    scope.launch {
                        busy = true
                        try { result = vm.exchange.importWorkbook(profiles) } catch (e: Exception) { toast(ctx.getString(R.string.import_failed)) }
                        busy = false
                    }
                }) { Text(stringResource(R.string.workbook_import)) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
