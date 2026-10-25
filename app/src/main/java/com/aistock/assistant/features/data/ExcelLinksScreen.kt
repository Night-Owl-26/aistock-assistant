package com.aistock.assistant.features.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aistock.assistant.R
import com.aistock.assistant.excel.ExcelLinkEntity
import com.aistock.assistant.excel.SyncOutcome
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.ConfirmDialog
import com.aistock.assistant.ui.components.EmptyState
import com.aistock.assistant.ui.components.FormPage
import com.aistock.assistant.ui.components.LabelValue
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The owner's Excel files that AIStock keeps updated: when each was last updated, update now, pause or stop. */
@Composable
fun ExcelLinksScreen(nav: AppNav, vm: DataViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    val links by vm.excelSync.links.collectAsState(initial = emptyList())
    var busy by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<ExcelLinkEntity?>(null) }
    val fmt = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    FormPage(title = stringResource(R.string.links_title), onBack = nav.back) {
        AppCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.links_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (links.isEmpty()) {
            EmptyState(stringResource(R.string.links_empty), stringResource(R.string.links_empty_body), actionText = stringResource(R.string.workbook_card_title), onAction = { nav.go("workbook") })
        }
        for (l in links) {
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l.fileName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = l.enabled, onCheckedChange = { on -> scope.launch { vm.excelSync.setEnabled(l.id, on) } })
                }
                LabelValue(stringResource(R.string.link_sheet), l.sheetName)
                LabelValue(stringResource(R.string.links_columns), listOf(l.nameHeader, l.sizeHeader, l.stockHeader, l.priceHeader).filter { it.isNotBlank() }.joinToString(", "))
                LabelValue(stringResource(R.string.links_last), l.lastSyncAt?.let { fmt.format(Date(it)) } ?: "-")
                Text(syncText(SyncOutcome.decode(l.lastResult)), style = MaterialTheme.typography.bodyMedium)
                val copies = remember(l.lastSyncAt) { vm.excelSync.backups(l.id).size }
                if (copies > 0) Text(stringResource(R.string.links_backups, copies), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = busy == null && l.enabled, onClick = {
                        scope.launch { busy = l.id; try { vm.excelSync.sync(l.id) } finally { busy = null } }
                    }) { Text(stringResource(if (busy == l.id) R.string.links_updating else R.string.links_update_now)) }
                    OutlinedButton(enabled = busy == null, onClick = { removing = l }) { Text(stringResource(R.string.links_stop)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    removing?.let { l ->
        ConfirmDialog(
            stringResource(R.string.links_stop), stringResource(R.string.links_stop_body, l.fileName), stringResource(R.string.links_stop),
            onConfirm = { scope.launch { vm.excelSync.unlink(l.id) }; removing = null }, onDismiss = { removing = null },
        )
    }
}
