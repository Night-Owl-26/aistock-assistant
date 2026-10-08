package com.aistock.assistant.features.data

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.ViewModel
import com.aistock.assistant.R
import com.aistock.assistant.backup.BackupException
import com.aistock.assistant.backup.BackupInfo
import com.aistock.assistant.backup.BackupManager
import com.aistock.assistant.data.BusinessRepository
import com.aistock.assistant.excel.DataExchange
import com.aistock.assistant.excel.ExportKind
import com.aistock.assistant.excel.ImportKind
import com.aistock.assistant.excel.ImportPreview
import com.aistock.assistant.excel.ImportResult
import com.aistock.assistant.excel.ImportSpec
import com.aistock.assistant.features.reports.XLSX_MIME
import com.aistock.assistant.features.reports.shareFile
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.ConfirmDialog
import com.aistock.assistant.ui.components.DropdownField
import com.aistock.assistant.ui.components.FormPage
import com.aistock.assistant.ui.components.LabelValue
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.SectionTitle
import com.aistock.assistant.ui.fmtDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DataViewModel @Inject constructor(
    val exchange: DataExchange,
    val backups: BackupManager,
    private val business: BusinessRepository,
) : ViewModel() {
    suspend fun exportFile(context: Context, kind: ExportKind, name: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, name)
        val sheets = exchange.exportSheets(kind)
        file.outputStream().use { exchange.writeXlsx(it, sheets) }
        file
    }

    suspend fun afterRestore() = business.syncSettingsFromBusiness()
}

fun displayName(context: Context, uri: Uri): String {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: ""
        }
    } catch (e: Exception) { /* fall through */ }
    return uri.lastPathSegment.orEmpty()
}

private fun exportKindRes(kind: ExportKind): Int = when (kind) {
    ExportKind.EVERYTHING -> R.string.export_everything
    ExportKind.PRODUCTS -> R.string.products
    ExportKind.STOCK -> R.string.report_stock
    ExportKind.STOCK_MOVEMENT -> R.string.report_movement
    ExportKind.SALES -> R.string.sales
    ExportKind.PURCHASES -> R.string.purchases
    ExportKind.EXPENSES -> R.string.expenses
    ExportKind.CUSTOMERS -> R.string.customers
    ExportKind.SUPPLIERS -> R.string.suppliers
}

private fun importKindRes(kind: ImportKind): Int = when (kind) {
    ImportKind.PRODUCTS -> R.string.products
    ImportKind.CUSTOMERS -> R.string.customers
    ImportKind.SUPPLIERS -> R.string.suppliers
    ImportKind.OPENING_STOCK -> R.string.opening_stock
}

private fun fieldRes(key: String): Int = when (key) {
    "name" -> R.string.name
    "code" -> R.string.product_code
    "category" -> R.string.category
    "unit" -> R.string.unit
    "barcode" -> R.string.barcode
    "purchasePrice" -> R.string.purchase_price
    "sellingPrice" -> R.string.selling_price
    "minStock" -> R.string.minimum_stock
    "openingStock" -> R.string.opening_stock
    "description" -> R.string.description
    "phone" -> R.string.phone
    "email" -> R.string.email
    "address" -> R.string.address
    "openingBalance" -> R.string.opening_balance
    "notes" -> R.string.notes
    "quantity" -> R.string.quantity
    "unitCost" -> R.string.unit_cost
    else -> R.string.name
}

@Composable
fun ImportExportScreen(nav: AppNav, vm: DataViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current

    // ---------------- export
    var exportKind by remember { mutableStateOf(ExportKind.EVERYTHING) }
    var busy by remember { mutableStateOf(false) }
    val exportSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val file = vm.exportFile(ctx, exportKind, "export.xlsx")
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } }
                toast(ctx.getString(R.string.export_saved))
            } catch (e: Exception) {
                toast(ctx.getString(R.string.export_failed))
            }
            busy = false
        }
    }

    // ---------------- import
    var importKind by remember { mutableStateOf(ImportKind.PRODUCTS) }
    var table by remember { mutableStateOf<List<List<String>>?>(null) }
    var fileName by remember { mutableStateOf("") }
    var mapping by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var result by remember { mutableStateOf<ImportResult?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            result = null
            try {
                val name = displayName(ctx, uri)
                val rows = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { vm.exchange.readTable(name, it) } } ?: emptyList()
                if (rows.size < 2) {
                    toast(ctx.getString(R.string.import_empty)); table = null
                } else {
                    table = rows; fileName = name
                    mapping = ImportSpec.autoMap(rows[0], importKind)
                }
            } catch (e: Exception) {
                table = null
                toast(ctx.getString(R.string.import_unreadable))
            }
            busy = false
        }
    }
    LaunchedEffect(table, mapping, importKind) {
        val t = table
        preview = if (t == null) null else vm.exchange.preview(importKind, t, mapping)
    }

    FormPage(title = stringResource(R.string.import_export), onBack = nav.back) {
        SectionTitle(stringResource(R.string.export_to_excel))
        AppCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.export_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            DropdownField(stringResource(R.string.what_to_export), ExportKind.entries.sortedBy { if (it == ExportKind.EVERYTHING) 0 else 1 }.map { it.name to stringResource(exportKindRes(it)) }, exportKind.name, { exportKind = ExportKind.valueOf(it) })
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { exportSaver.launch(vm.exchange.fileName("aistock-" + exportKind.name)) }) { Text(stringResource(R.string.save_excel)) }
                OutlinedButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try { shareFile(ctx, vm.exportFile(ctx, exportKind, vm.exchange.fileName("aistock-" + exportKind.name)), XLSX_MIME) } catch (e: Exception) { toast(ctx.getString(R.string.export_failed)) }
                        busy = false
                    }
                }) { Text(stringResource(R.string.share)) }
            }
        }

        SectionTitle(stringResource(R.string.import_from_excel))
        AppCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.import_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            DropdownField(stringResource(R.string.what_to_import), ImportKind.entries.map { it.name to stringResource(importKindRes(it)) }, importKind.name, {
                importKind = ImportKind.valueOf(it); result = null
                table?.let { t -> mapping = ImportSpec.autoMap(t[0], importKind) }
            })
            Spacer(Modifier.height(10.dp))
            Button(enabled = !busy, onClick = {
                picker.launch(arrayOf(XLSX_MIME, "text/csv", "text/comma-separated-values", "application/csv", "text/plain", "application/vnd.ms-excel", "application/octet-stream"))
            }) { Text(stringResource(if (table == null) R.string.choose_file else R.string.choose_another_file)) }
            if (busy) { Spacer(Modifier.height(10.dp)); CircularProgressIndicator() }
        }

        val t = table
        if (t != null && result == null) {
            val headers = t[0]
            SectionTitle(stringResource(R.string.match_columns))
            AppCard(Modifier.fillMaxWidth()) {
                Text(fileName, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.columns_found, headers.filter { it.isNotBlank() }.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                val options = listOf("-1" to stringResource(R.string.not_used)) + headers.mapIndexed { i, h -> i.toString() to h.ifBlank { stringResource(R.string.column_n, i + 1) } }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in ImportSpec.fields(importKind)) {
                        DropdownField(
                            stringResource(fieldRes(f.key)) + if (f.required) " *" else "", options, (mapping[f.key] ?: -1).toString(),
                            { v -> mapping = if (v == "-1") mapping - f.key else mapping + (f.key to v.toInt()) },
                        )
                    }
                }
            }
            preview?.let { p ->
                SectionTitle(stringResource(R.string.preview))
                AppCard(Modifier.fillMaxWidth()) {
                    LabelValue(stringResource(R.string.rows_ready), p.ready.size.toString(), strong = true)
                    LabelValue(stringResource(R.string.rows_skipped), p.skipped.size.toString())
                    LabelValue(stringResource(R.string.rows_with_errors), p.errors.size.toString())
                    if (p.ready.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        for (r in p.ready.take(5)) {
                            Text(r.values.entries.joinToString(" · ") { it.value }, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        }
                    }
                    IssueList(p.errors.map { it.rowNumber to it.message } + p.skipped.map { it.rowNumber to it.message })
                    Spacer(Modifier.height(10.dp))
                    Button(enabled = !busy && p.ready.isNotEmpty(), onClick = {
                        scope.launch {
                            busy = true
                            try {
                                result = vm.exchange.import(importKind, p)
                                table = null
                            } catch (e: Exception) {
                                toast(ctx.getString(R.string.import_failed))
                            }
                            busy = false
                        }
                    }) { Text(stringResource(R.string.import_n_rows, p.ready.size)) }
                }
            }
        }
        result?.let { r ->
            SectionTitle(stringResource(R.string.import_finished))
            AppCard(Modifier.fillMaxWidth()) {
                LabelValue(stringResource(R.string.rows_imported), r.imported.toString(), strong = true)
                LabelValue(stringResource(R.string.rows_skipped), r.skipped.size.toString())
                LabelValue(stringResource(R.string.rows_with_errors), r.errors.size.toString())
                IssueList(r.errors.map { it.rowNumber to it.message } + r.skipped.map { it.rowNumber to it.message })
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun IssueList(issues: List<Pair<Int, String>>) {
    if (issues.isEmpty()) return
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    for ((row, message) in issues.take(20)) {
        Text(stringResource(R.string.row_issue, row, message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (issues.size > 20) Text(stringResource(R.string.and_n_more, issues.size - 20), style = MaterialTheme.typography.bodySmall)
}

private fun backupErrorRes(e: Exception): Int = when ((e as? BackupException)?.code) {
    BackupException.NOT_A_BACKUP -> R.string.backup_not_valid
    BackupException.CORRUPT -> R.string.backup_corrupt
    BackupException.NEWER_VERSION -> R.string.backup_newer
    BackupException.TOO_LARGE -> R.string.backup_too_large
    else -> R.string.restore_failed
}

@Composable
fun BackupScreen(nav: AppNav, vm: DataViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current
    var files by remember { mutableStateOf(vm.backups.list()) }
    var busy by remember { mutableStateOf(false) }
    // The backup waiting for the user's "yes": file or picked document, plus what is inside it.
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingInfo by remember { mutableStateOf<BackupInfo?>(null) }
    var deleteFile by remember { mutableStateOf<File?>(null) }
    var saveFile by remember { mutableStateOf<File?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.MIME)) { uri ->
        val f = saveFile
        if (uri != null && f != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } }
                toast(ctx.getString(R.string.backup_copy_saved))
            } catch (e: Exception) {
                toast(ctx.getString(R.string.export_failed))
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val info = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { vm.backups.inspect(it) } }
                if (info != null) { pendingUri = uri; pendingFile = null; pendingInfo = info }
            } catch (e: Exception) {
                toast(ctx.getString(backupErrorRes(e)))
            }
            busy = false
        }
    }

    FormPage(title = stringResource(R.string.backup_restore), onBack = nav.back) {
        SectionTitle(stringResource(R.string.backup))
        AppCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.backup_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try { vm.backups.create(); files = vm.backups.list(); toast(ctx.getString(R.string.backup_created)) } catch (e: Exception) { toast(ctx.getString(R.string.backup_failed)) }
                    busy = false
                }
            }) { Text(stringResource(R.string.create_backup)) }
        }

        SectionTitle(stringResource(R.string.restore))
        AppCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.restore_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.restore_from_file)) }
            if (busy) { Spacer(Modifier.height(10.dp)); CircularProgressIndicator() }
        }

        SectionTitle(stringResource(R.string.backups_on_device))
        if (files.isEmpty()) Text(stringResource(R.string.no_backups), color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (f in files) {
            AppCard(Modifier.fillMaxWidth()) {
                Text(fmtDateTime(f.lastModified()), style = MaterialTheme.typography.titleMedium)
                Text(f.name + " · " + (f.length() / 1024 + 1) + " KB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !busy, onClick = {
                        scope.launch {
                            try { pendingInfo = f.inputStream().use { vm.backups.inspect(it) }; pendingFile = f; pendingUri = null } catch (e: Exception) { toast(ctx.getString(backupErrorRes(e))) }
                        }
                    }) { Text(stringResource(R.string.restore)) }
                    TextButton(onClick = { saveFile = f; saver.launch(f.name) }) { Text(stringResource(R.string.save_copy)) }
                    TextButton(onClick = { try { shareFile(ctx, f, BackupManager.MIME) } catch (e: Exception) { toast(ctx.getString(R.string.export_failed)) } }) { Text(stringResource(R.string.share)) }
                    TextButton(onClick = { deleteFile = f }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    pendingInfo?.let { info ->
        ConfirmDialog(
            stringResource(R.string.restore_confirm_title),
            stringResource(R.string.restore_confirm_body, fmtDateTime(info.createdAt), info.products, info.customers, info.suppliers, info.sales, info.purchases),
            stringResource(R.string.restore), danger = true,
            onDismiss = { pendingInfo = null },
            onConfirm = {
                val file = pendingFile
                val uri = pendingUri
                pendingInfo = null
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) {
                            val stream = file?.inputStream() ?: uri?.let { ctx.contentResolver.openInputStream(it) } ?: throw BackupException(BackupException.NOT_A_BACKUP)
                            stream.use { vm.backups.restore(it) }
                        }
                        vm.afterRestore()
                        toast(ctx.getString(R.string.restore_done))
                        files = vm.backups.list()
                        (ctx as? Activity)?.recreate()
                    } catch (e: Exception) {
                        toast(ctx.getString(backupErrorRes(e)))
                    }
                    busy = false
                }
            },
        )
    }
    deleteFile?.let { f ->
        ConfirmDialog(
            stringResource(R.string.delete_backup_title), stringResource(R.string.delete_backup_body), stringResource(R.string.delete), danger = true,
            onDismiss = { deleteFile = null },
            onConfirm = { f.delete(); deleteFile = null; files = vm.backups.list() },
        )
    }
}
