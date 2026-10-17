package com.aistock.assistant.features.work

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aistock.assistant.R
import com.aistock.assistant.ai.PartyRef
import com.aistock.assistant.business.BusinessAction
import com.aistock.assistant.business.BusinessConfig
import com.aistock.assistant.business.FollowupEntity
import com.aistock.assistant.business.FollowupRef
import com.aistock.assistant.business.Module
import com.aistock.assistant.business.Templates
import com.aistock.assistant.business.Workflow
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.LocalTamil
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.AppTopBar
import com.aistock.assistant.ui.components.BottomAction
import com.aistock.assistant.ui.components.DateTimeField
import com.aistock.assistant.ui.components.DropdownField
import com.aistock.assistant.ui.components.EmptyState
import com.aistock.assistant.ui.components.FormPage
import com.aistock.assistant.ui.components.ListRow
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.PickerField
import com.aistock.assistant.ui.components.SectionTitle
import com.aistock.assistant.ui.components.Tag
import com.aistock.assistant.ui.components.TextInput
import com.aistock.assistant.ui.components.WarnTag
import com.aistock.assistant.ui.components.errorRes
import com.aistock.assistant.ui.fmtDateTime
import kotlinx.coroutines.launch

/** Opens the phone's dialler, WhatsApp, SMS or the share sheet. Nothing is sent or dialled without the owner pressing it there. */
fun handOff(context: android.content.Context, kind: String, phone: String, text: String) {
    val digits = phone.filter { it.isDigit() || it == '+' }
    val intent = when (kind) {
        "CALL" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
        "WHATSAPP" -> {
            val num = digits.removePrefix("+").let { if (it.length == 10) "91$it" else it }
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num?text=" + Uri.encode(text)))
        }
        "SMS" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$digits")).putExtra("sms_body", text)
        else -> Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
    }
    try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {
        android.widget.Toast.makeText(context, context.getString(R.string.no_app_for_this), android.widget.Toast.LENGTH_SHORT).show()
    }
}

private val KINDS = listOf("CALL", "MESSAGE", "VISIT", "PAYMENT", "OTHER")
@Composable
private fun kindLabel(k: String) = stringResource(when (k) { "CALL" -> R.string.k_call; "MESSAGE" -> R.string.k_message; "VISIT" -> R.string.k_visit; "PAYMENT" -> R.string.k_payment; else -> R.string.k_other })

// ------------------------------------------------------------------ follow-ups

@Composable
fun FollowupsScreen(nav: AppNav, vm: WorkViewModel = hiltViewModel()) {
    val list by vm.followups.collectAsState()
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf("PENDING") }
    var adding by remember { mutableStateOf(false) }
    var completing by remember { mutableStateOf<FollowupEntity?>(null) }
    val addLabel = stringResource(R.string.add_followup)
    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.followups), nav.back) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { adding = true }, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = addLabel }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.add_followup)) }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tab == "PENDING", { tab = "PENDING" }, label = { Text(stringResource(R.string.f_pending)) })
                FilterChip(tab == "DONE", { tab = "DONE" }, label = { Text(stringResource(R.string.f_done)) })
            }
            val shown = list.filter { if (tab == "PENDING") it.status == "PENDING" else it.status != "PENDING" }
            if (shown.isEmpty()) EmptyState(stringResource(R.string.nothing_here), stringResource(R.string.followups_hint))
            else LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { f ->
                    AppCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.personName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Tag(kindLabel(f.kind))
                            if (f.status == "PENDING" && f.dueAt < System.currentTimeMillis()) { Spacer(Modifier.padding(2.dp)); WarnTag(stringResource(R.string.f_overdue)) }
                        }
                        if (f.note.isNotBlank()) Text(f.note, style = MaterialTheme.typography.bodyMedium)
                        Text(fmtDateTime(f.dueAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (f.result.isNotBlank()) Text("✓ " + f.result, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        if (f.status == "PENDING") Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            if (f.phone.isNotBlank()) OutlinedButton(onClick = { handOff(ctx, "CALL", f.phone, "") }) { Text(stringResource(R.string.call)) }
                            if (f.phone.isNotBlank()) OutlinedButton(onClick = { handOff(ctx, "WHATSAPP", f.phone, "") }) { Text("WhatsApp") }
                            Button(onClick = { completing = f }) { Text(stringResource(R.string.mark_done)) }
                        }
                    }
                }
            }
        }
    }
    if (adding) FollowupDialog(vm) { adding = false }
    completing?.let { f -> CompleteDialog(vm, f) { completing = null } }
}

@Composable
private fun FollowupDialog(vm: WorkViewModel, close: () -> Unit) {
    val toast = LocalToast.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var customers by remember { mutableStateOf<List<PartyRef>>(emptyList()) }
    LaunchedEffect(Unit) { customers = vm.customers() }
    var who by remember { mutableStateOf<PartyRef?>(null) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("CALL") }
    var note by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<Long?>(System.currentTimeMillis() + 86_400_000) }
    var remind by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.add_followup)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerField(stringResource(R.string.customer), who?.name.orEmpty(), customers.map { it.id to it.name }, { id -> who = customers.firstOrNull { it.id == id } }, onClear = { who = null })
                if (who == null) { TextInput(stringResource(R.string.or_new_customer), name, { name = it }); TextInput(stringResource(R.string.phone), phone, { phone = it }, keyboard = KeyboardType.Phone) }
                DropdownField(stringResource(R.string.what), KINDS.map { it to kindLabel(it) }, kind, { kind = it })
                TextInput(stringResource(R.string.about), note, { note = it }, capitalize = false)
                DateTimeField(stringResource(R.string.when_label), due, { due = it }, defaultHour = 10)
                Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.remind_me), Modifier.weight(1f)); Switch(remind, { remind = it }) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val person = who?.name ?: name.trim()
                val at = due
                if (person.isEmpty() || at == null) { toast(ctx.getString(R.string.need_person_time)); return@Button }
                scope.launch {
                    val r = vm.run(BusinessAction.NewFollowup(who, person, who?.phone ?: phone.trim(), kind, note.trim(), at, remind, null, null))
                    toast(if (r.ok) ctx.getString(R.string.saved) else ctx.getString(errorRes(r.error)))
                    if (r.ok) close()
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CompleteDialog(vm: WorkViewModel, f: FollowupEntity, close: () -> Unit) {
    val toast = LocalToast.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("") }
    var next by remember { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(f.personName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput(stringResource(R.string.what_happened), result, { result = it }, capitalize = false, singleLine = false)
                DateTimeField(stringResource(R.string.next_followup_optional), next, { next = it }, defaultHour = 10)
            }
        },
        confirmButton = {
            Button(enabled = result.isNotBlank(), onClick = {
                scope.launch {
                    val r = vm.run(BusinessAction.CompleteFollowup(FollowupRef(f.id, f.customerId, f.personName, f.phone, f.kind, f.note, f.dueAt, f.status, f.result), result.trim(), next, null))
                    toast(if (r.ok) ctx.getString(R.string.saved) else ctx.getString(errorRes(r.error)))
                    if (r.ok) close()
                }
            }) { Text(stringResource(R.string.mark_done)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------------ staff

@Composable
fun StaffScreen(nav: AppNav, vm: WorkViewModel = hiltViewModel()) {
    val staff by vm.staff.collectAsState()
    val toast = LocalToast.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    FormPage(stringResource(R.string.staff), nav.back) {
        Text(stringResource(R.string.staff_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (s in staff) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ListRow(s.name, listOf(s.role, s.phone).filter { it.isNotBlank() }.joinToString(" · ")) }
            if (s.phone.isNotBlank()) TextButton(onClick = { handOff(ctx, "CALL", s.phone, "") }) { Text(stringResource(R.string.call)) }
            TextButton(onClick = { vm.archiveStaff(s.id) }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
        }
        SectionTitle(stringResource(R.string.add_staff))
        TextInput(stringResource(R.string.name), name, { name = it })
        TextInput(stringResource(R.string.role), role, { role = it })
        TextInput(stringResource(R.string.phone), phone, { phone = it }, keyboard = KeyboardType.Phone)
        Button(enabled = name.isNotBlank(), onClick = {
            scope.launch {
                val r = vm.run(BusinessAction.AddStaff(name.trim(), phone.filter { it.isDigit() || it == '+' }, role.trim()))
                toast(if (r.ok) ctx.getString(R.string.saved) else ctx.getString(errorRes(r.error)))
                if (r.ok) { name = ""; role = ""; phone = "" }
            }
        }) { Text(stringResource(R.string.add)) }
    }
}

// ------------------------------------------------------------------ reminders

@Composable
fun RemindersScreen(nav: AppNav, vm: WorkViewModel = hiltViewModel()) {
    val list by vm.reminders.collectAsState()
    val toast = LocalToast.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var at by remember { mutableStateOf<Long?>(null) }
    FormPage(stringResource(R.string.reminders), nav.back) {
        Text(stringResource(R.string.reminders_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextInput(stringResource(R.string.remind_about), text, { text = it }, capitalize = false)
        DateTimeField(stringResource(R.string.when_label), at, { at = it }, defaultHour = 9)
        Button(enabled = text.isNotBlank() && at != null, onClick = {
            val t = at ?: return@Button
            if (t < System.currentTimeMillis()) { toast(ctx.getString(R.string.time_passed)); return@Button }
            scope.launch {
                val r = vm.run(BusinessAction.NewReminder(text.trim(), t))
                toast(if (r.ok) ctx.getString(R.string.saved) else ctx.getString(errorRes(r.error)))
                if (r.ok) { text = ""; at = null }
            }
        }) { Text(stringResource(R.string.add)) }
        SectionTitle(stringResource(R.string.reminders))
        if (list.isEmpty()) Text(stringResource(R.string.nothing_here), color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (r in list) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { ListRow(r.text, fmtDateTime(r.dueAt) + " · " + when (r.status) { "SCHEDULED" -> stringResource(R.string.r_scheduled); "SHOWN" -> stringResource(R.string.r_shown); else -> stringResource(R.string.r_cancelled) }) }
            if (r.status == "SCHEDULED") TextButton(onClick = { vm.cancelReminder(r.id) }) { Text(stringResource(R.string.cancel)) }
        }
    }
}

// ------------------------------------------------------------------ business setup

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BusinessSetupScreen(nav: AppNav, vm: WorkViewModel = hiltViewModel()) {
    val current by vm.config.collectAsState()
    val configured by vm.configured.collectAsState()
    val tamil = LocalTamil.current
    val toast = LocalToast.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf<String?>(null) }
    var modules by remember { mutableStateOf<Set<Module>>(emptySet()) }
    val stages = remember { mutableStateOf<Map<Module, String>>(emptyMap()) }
    LaunchedEffect(configured) {
        if (type == null && configured != null) {
            type = current.businessType; modules = current.modules
            stages.value = current.workflows.mapValues { it.value.stages.joinToString(", ") }
        }
    }
    val t = type ?: return
    fun choose(tt: String) {
        val c = Templates.of(tt).config
        type = tt; modules = c.modules; stages.value = c.workflows.mapValues { it.value.stages.joinToString(", ") }
    }
    FormPage(stringResource(R.string.business_setup), nav.back, bottom = {
        BottomAction(stringResource(R.string.save)) {
            val base = Templates.of(t).config
            val flows = LinkedHashMap<Module, Workflow>()
            for (m in modules.filter { it.isWork }) {
                val wf = (if (t == current.businessType) current.workflows[m] else null) ?: base.workflows[m] ?: Templates.defaultWorkflow(m)
                val st = stages.value[m]?.split(',', '>', '›')?.map { it.trim() }?.filter { it.isNotEmpty() }
                flows[m] = if (st != null) wf.copy(stages = st) else wf
            }
            val terms = if (t == current.businessType) current.terminology else base.terminology
            val fields = (if (t == current.businessType) current.customFields else base.customFields).filter { it.module in modules }
            val cfg = BusinessConfig(t, modules, terms, base.units, flows, fields, current.language)
            val errors = cfg.validate()
            if (errors.isNotEmpty()) { toast(errors.first()); return@BottomAction }
            scope.launch {
                val r = vm.saveSetup(cfg)
                toast(if (r.ok) ctx.getString(R.string.saved) else ctx.getString(errorRes(r.error)))
                if (r.ok) nav.back()
            }
        }
    }) {
        Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle(stringResource(R.string.business_type))
        for (tp in Templates.ALL) {
            AppCard(Modifier.fillMaxWidth(), onClick = { choose(tp.type) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (tamil) tp.nameTa else tp.nameEn, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (tp.type == t) Tag(stringResource(R.string.chosen))
                }
                Text(if (tamil) tp.descriptionTa else tp.descriptionEn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tp.type == t && tp.limitsEn.isNotBlank()) Text(stringResource(R.string.not_built_yet) + " " + tp.limitsEn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        SectionTitle(stringResource(R.string.modules))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (m in Module.entries) FilterChip(m in modules, { modules = if (m in modules) modules - m else modules + m }, label = { Text(moduleLabel(m)) })
        }
        for (m in modules.filter { it.isWork && it != Module.TASKS && it != Module.APPOINTMENTS && it != Module.ORDERS }) {
            TextInput(stringResource(R.string.steps_for, moduleLabel(m)), stages.value[m].orEmpty(), { stages.value = stages.value + (m to it) }, capitalize = false, singleLine = false)
        }
        Text(stringResource(R.string.steps_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
fun moduleLabel(m: Module): String = stringResource(
    when (m) {
        Module.INVENTORY -> R.string.nav_inventory; Module.SALES -> R.string.sales; Module.PURCHASES -> R.string.purchases
        Module.EXPENSES -> R.string.expenses; Module.CUSTOMERS -> R.string.customers; Module.SUPPLIERS -> R.string.suppliers
        Module.ORDERS -> R.string.m_orders; Module.PRODUCTION -> R.string.m_production; Module.SERVICE_TICKETS -> R.string.m_service
        Module.APPOINTMENTS -> R.string.m_appointments; Module.PROJECTS -> R.string.m_projects; Module.TASKS -> R.string.m_tasks
        Module.FOLLOWUPS -> R.string.followups; Module.STAFF -> R.string.staff
    }
)
