package com.aistock.assistant.features.reports

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aistock.assistant.R
import com.aistock.assistant.excel.DataExchange
import com.aistock.assistant.excel.ReportData
import com.aistock.assistant.excel.ReportType
import com.aistock.assistant.excel.Sheet
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.AppTopBar
import com.aistock.assistant.ui.components.DateField
import com.aistock.assistant.ui.components.DropdownField
import com.aistock.assistant.ui.components.EmptyState
import com.aistock.assistant.ui.components.LabelValue
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.utils.DateKey
import com.aistock.assistant.utils.DateRanges
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class ReportsState(
    val type: ReportType = ReportType.SALES,
    /** A [DateKey] name, or CUSTOM */
    val range: String = DateKey.THIS_MONTH.name,
    val from: Long = DateRanges.range(DateKey.THIS_MONTH).start,
    /** Last included day (any time on that day) */
    val to: Long = System.currentTimeMillis(),
    val loading: Boolean = true,
    val data: ReportData? = null,
)

@HiltViewModel
class ReportsViewModel @Inject constructor(private val exchange: DataExchange) : ViewModel() {
    private val _state = MutableStateFlow(ReportsState())
    val state: StateFlow<ReportsState> = _state

    init { load() }

    fun setType(type: ReportType) { _state.value = _state.value.copy(type = type); load() }

    fun setRange(key: DateKey) {
        val w = DateRanges.range(key)
        _state.value = _state.value.copy(range = key.name, from = w.start, to = if (key == DateKey.ALL) System.currentTimeMillis() else w.end - 1)
        load()
    }

    fun setCustom(from: Long, to: Long) {
        _state.value = _state.value.copy(range = "CUSTOM", from = minOf(from, to), to = maxOf(from, to))
        load()
    }

    private fun window(s: ReportsState): Pair<Long, Long> {
        if (s.range == DateKey.ALL.name) return 0L to Long.MAX_VALUE
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(s.from).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = Instant.ofEpochMilli(s.to).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    private fun load() {
        val s = _state.value
        _state.value = s.copy(loading = true)
        viewModelScope.launch {
            val (from, to) = window(s)
            val data = try { exchange.report(s.type, from, to) } catch (e: Exception) { null }
            if (_state.value.type == s.type && _state.value.range == s.range && _state.value.from == s.from && _state.value.to == s.to) {
                _state.value = _state.value.copy(loading = false, data = data)
            }
        }
    }

    suspend fun exportTo(context: Context, sheets: List<Sheet>, name: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L) it.delete() }
        val file = File(dir, name)
        file.outputStream().use { exchange.writeXlsx(it, sheets) }
        file
    }

    fun fileName(base: String) = exchange.fileName(base)
}

const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

fun shareFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

fun reportTitleRes(type: ReportType): Int = when (type) {
    ReportType.SALES -> R.string.report_sales
    ReportType.PURCHASES -> R.string.report_purchases
    ReportType.STOCK -> R.string.report_stock
    ReportType.STOCK_MOVEMENT -> R.string.report_movement
    ReportType.EXPENSES -> R.string.report_expenses
    ReportType.CUSTOMER_OUTSTANDING -> R.string.report_customer_outstanding
    ReportType.SUPPLIER_OUTSTANDING -> R.string.report_supplier_outstanding
}

fun dateKeyRes(key: DateKey): Int = when (key) {
    DateKey.TODAY -> R.string.today
    DateKey.YESTERDAY -> R.string.yesterday
    DateKey.THIS_WEEK -> R.string.this_week
    DateKey.LAST_WEEK -> R.string.last_week
    DateKey.THIS_MONTH -> R.string.this_month
    DateKey.LAST_MONTH -> R.string.last_month
    DateKey.ALL -> R.string.all_time
}

private fun cell(v: Any?): String = when (v) {
    null -> ""
    is BigDecimal -> v.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
    else -> v.toString()
}

@Composable
fun ReportsScreen(nav: AppNav, vm: ReportsViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val data = st.data
    val usesDates = st.type !in setOf(ReportType.STOCK, ReportType.CUSTOMER_OUTSTANDING, ReportType.SUPPLIER_OUTSTANDING)

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        val d = st.data
        if (uri != null && d != null) scope.launch {
            try {
                val file = vm.exportTo(ctx, listOf(d.sheet()), "report.xlsx")
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } }
                toast(ctx.getString(R.string.export_saved))
            } catch (e: Exception) {
                toast(ctx.getString(R.string.export_failed))
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(stringResource(R.string.reports), nav.back) {
                IconButton(enabled = data != null && data.rows.isNotEmpty(), onClick = {
                    val d = data ?: return@IconButton
                    scope.launch {
                        try { shareFile(ctx, vm.exportTo(ctx, listOf(d.sheet()), vm.fileName(d.title)), XLSX_MIME) } catch (e: Exception) { toast(ctx.getString(R.string.export_failed)) }
                    }
                }) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_excel)) }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                DropdownField(stringResource(R.string.report), ReportType.entries.map { it.name to stringResource(reportTitleRes(it)) }, st.type.name, { vm.setType(ReportType.valueOf(it)) })
            }
            if (usesDates) {
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in DateKey.entries) FilterChip(selected = st.range == k.name, onClick = { vm.setRange(k) }, label = { Text(stringResource(dateKeyRes(k))) })
                        FilterChip(selected = st.range == "CUSTOM", onClick = { vm.setCustom(st.from, st.to) }, label = { Text(stringResource(R.string.custom_dates)) })
                    }
                }
                if (st.range == "CUSTOM") item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateField(stringResource(R.string.from_date), st.from, { vm.setCustom(it, st.to) }, Modifier.weight(1f))
                        DateField(stringResource(R.string.to_date), st.to, { vm.setCustom(st.from, it) }, Modifier.weight(1f))
                    }
                }
            }
            if (data != null) {
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        for ((label, value) in data.summary) LabelValue(label, value, strong = true)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(enabled = data.rows.isNotEmpty(), onClick = { saver.launch(vm.fileName(data.title)) }) { Text(stringResource(R.string.save_excel)) }
                    }
                }
            }
            when {
                st.loading && data == null -> item { Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
                data == null || data.rows.isEmpty() -> item { EmptyState(stringResource(R.string.no_report_rows), stringResource(R.string.no_report_rows_body), painterResource(R.drawable.ic_report)) }
                else -> itemsIndexed(data.rows.take(300)) { _, row ->
                    AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(cell(row.getOrNull(0)), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        for (i in 1 until row.size) {
                            val v = cell(row[i])
                            if (v.isNotBlank()) LabelValue(data.headers.getOrElse(i) { "" }, v)
                        }
                    }
                }
            }
            if (data != null && data.rows.size > 300) item { Text(stringResource(R.string.more_rows_in_excel, data.rows.size - 300), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
