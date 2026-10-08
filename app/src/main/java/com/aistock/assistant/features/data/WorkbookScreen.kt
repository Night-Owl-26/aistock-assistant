package com.aistock.assistant.features.data

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aistock.assistant.R
import com.aistock.assistant.excel.ImportKind
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

private fun kindRes(k: ImportKind): Int = when (k) {
    ImportKind.PRODUCTS -> R.string.products
    ImportKind.CUSTOMERS -> R.string.customers
    ImportKind.SUPPLIERS -> R.string.suppliers
    ImportKind.OPENING_STOCK -> R.string.opening_stock
}

/** Upload a whole workbook: Stocky reads every sheet, says what it thinks each one is, and imports after the owner agrees. */
@Composable
fun WorkbookScreen(nav: AppNav, vm: DataViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current
    var busy by remember { mutableStateOf(false) }
    var fileName by remember { mutableStateOf("") }
    var profiles by remember { mutableStateOf<List<SheetProfile>>(emptyList()) }
    var changes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var plan by remember { mutableStateOf<List<WorkbookPlanItem>>(emptyList()) }
    var result by remember { mutableStateOf<WorkbookResult?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; result = null
            try {
                val name = displayName(ctx, uri)
                val found = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { vm.exchange.analyzeWorkbook(name, it) } } ?: emptyList()
                if (found.isEmpty()) toast(ctx.getString(R.string.import_empty))
                fileName = name
                changes = vm.exchange.workbookChanges(found)
                profiles = found
            } catch (e: Exception) {
                profiles = emptyList()
                toast(ctx.getString(R.string.import_unreadable))
            }
            busy = false
        }
    }
    LaunchedEffect(profiles) { plan = if (profiles.isEmpty()) emptyList() else try { vm.exchange.workbookPlan(profiles) } catch (e: Exception) { emptyList() } }

    FormPage(title = stringResource(R.string.workbook_title), onBack = nav.back) {
        AppCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.workbook_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Button(enabled = !busy, onClick = {
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
        } else if (profiles.isNotEmpty()) {
            SectionTitle(stringResource(R.string.workbook_found, profiles.size, fileName))
            for ((index, p) in profiles.withIndex()) {
                AppCard(Modifier.fillMaxWidth()) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.workbook_rows_cols, p.rowCount, p.headers.filter { it.isNotBlank() }.take(6).joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (p.purpose != SheetPurpose.OTHER && p.confidence < 60) WarnTag(stringResource(R.string.workbook_please_check))
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
                        { chosen -> profiles = profiles.toMutableList().also { it[index] = p.copy(purpose = SheetPurpose.valueOf(chosen), confidence = 100) } },
                    )
                }
            }
            SectionTitle(stringResource(R.string.preview))
            AppCard(Modifier.fillMaxWidth()) {
                if (plan.isEmpty()) Text(stringResource(R.string.workbook_nothing_to_import), style = MaterialTheme.typography.bodyMedium)
                for (item in plan) {
                    LabelValue(stringResource(kindRes(item.kind)) + " · " + item.sheet, stringResource(R.string.workbook_found_n, item.preview.ready.size), strong = true)
                    if (item.preview.skipped.isNotEmpty() || item.preview.errors.isNotEmpty()) {
                        Text(stringResource(R.string.workbook_skip_err, item.preview.skipped.size, item.preview.errors.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (i in (item.preview.errors + item.preview.skipped).take(3)) Text(stringResource(R.string.row_issue, i.rowNumber, i.message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.workbook_other_sheets), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Button(enabled = !busy, onClick = {
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
