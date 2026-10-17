package com.aistock.assistant.features.work

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aistock.assistant.R
import com.aistock.assistant.ai.PartyRef
import com.aistock.assistant.ai.ProductRef
import com.aistock.assistant.business.BusinessAction
import com.aistock.assistant.business.BusinessConfig
import com.aistock.assistant.business.FieldType
import com.aistock.assistant.business.Module
import com.aistock.assistant.business.StaffRef
import com.aistock.assistant.business.WorkRow
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.AppTopBar
import com.aistock.assistant.ui.components.BottomAction
import com.aistock.assistant.ui.components.DateTimeField
import com.aistock.assistant.ui.components.DropdownField
import com.aistock.assistant.ui.components.EmptyState
import com.aistock.assistant.ui.components.FormPage
import com.aistock.assistant.ui.components.LabelValue
import com.aistock.assistant.ui.components.ListRow
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.NumberInput
import com.aistock.assistant.ui.components.PickerField
import com.aistock.assistant.ui.components.SectionTitle
import com.aistock.assistant.ui.components.Tag
import com.aistock.assistant.ui.components.TextInput
import com.aistock.assistant.ui.components.WarnTag
import com.aistock.assistant.ui.components.errorRes
import com.aistock.assistant.ui.fmtDateTime
import com.aistock.assistant.ui.money
import com.aistock.assistant.utils.Money
import com.aistock.assistant.utils.Qty
import kotlinx.coroutines.launch

/** What the owner calls a module: their own word from the setup, otherwise the app's. */
@Composable
fun moduleTitle(cfg: BusinessConfig, m: Module, plural: Boolean = true): String {
    val own = cfg.terminology[if (plural) m.key + "_tab" else m.key] ?: if (!plural) null else cfg.terminology[m.key]
    if (!own.isNullOrBlank()) return own
    return stringResource(
        when (m) {
            Module.PRODUCTION -> if (plural) R.string.m_production else R.string.m_production_one
            Module.SERVICE_TICKETS -> if (plural) R.string.m_service else R.string.m_service_one
            Module.APPOINTMENTS -> if (plural) R.string.m_appointments else R.string.m_appointments_one
            Module.PROJECTS -> if (plural) R.string.m_projects else R.string.m_projects_one
            Module.ORDERS -> if (plural) R.string.m_orders else R.string.m_orders_one
            else -> if (plural) R.string.m_tasks else R.string.m_tasks_one
        }
    )
}

private fun now() = System.currentTimeMillis()

// ------------------------------------------------------------------ list

@Composable
fun WorkListScreen(nav: AppNav, moduleKey: String, showBack: Boolean, vm: WorkViewModel = hiltViewModel()) {
    val cfg by vm.config.collectAsState()
    val m = Module.of(moduleKey) ?: Module.TASKS
    val rows by vm.rows(m).collectAsState(initial = null)
    var filter by rememberSaveable { mutableStateOf("open") }
    val flow = cfg.workflow(m)
    val newLabel = stringResource(R.string.new_item, moduleTitle(cfg, m, false))
    Scaffold(
        topBar = { AppTopBar(moduleTitle(cfg, m), if (showBack) nav.back else null) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.go("workform/${m.key}") }, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = newLabel }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.new_item, moduleTitle(cfg, m, false))) })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, label) in listOf("open" to R.string.f_open, "overdue" to R.string.f_overdue, "done" to R.string.f_done)) FilterChip(filter == k, { filter = k }, label = { Text(stringResource(label)) })
            }
            val list = rows
            if (list == null) return@Column
            val shown = list.filter { r ->
                val closed = r.closedAt != null || flow.isDone(r.status)
                when (filter) { "open" -> !closed; "overdue" -> !closed && r.dueAt != null && r.dueAt < now(); else -> closed }
            }
            if (shown.isEmpty()) {
                EmptyState(stringResource(R.string.nothing_here), stringResource(R.string.work_empty_hint, moduleTitle(cfg, m, false).lowercase()))
            } else LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { r -> WorkCard(r, cfg) { nav.go("work/${r.id}") } }
            }
        }
    }
}

@Composable
private fun WorkCard(r: WorkRow, cfg: BusinessConfig, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.number, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.padding(4.dp))
            Tag(r.stage ?: r.status)
            if (r.priority > 0) { Spacer(Modifier.padding(2.dp)); WarnTag(stringResource(R.string.priority_high)) }
            val overdue = r.closedAt == null && r.dueAt != null && r.dueAt < now() && !cfg.workflow(Module.of(r.module) ?: Module.TASKS).isDone(r.status)
            if (overdue) { Spacer(Modifier.padding(2.dp)); WarnTag(stringResource(R.string.f_overdue)) }
        }
        Text(r.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        val bits = listOfNotNull(r.customerName, r.staffName?.let { "→ $it" }, (r.startAt ?: r.dueAt)?.let { fmtDateTime(it) })
        if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        r.quantityMilli?.takeIf { it > 0 }?.let { q ->
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { (r.doneMilli.toFloat() / q).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("${Qty.format(r.doneMilli)} / ${Qty.format(q)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ detail

@Composable
fun WorkDetailScreen(nav: AppNav, id: String, vm: WorkViewModel = hiltViewModel()) {
    val cfg by vm.config.collectAsState()
    val row by vm.row(id).collectAsState(initial = null)
    val events by vm.events(id).collectAsState(initial = emptyList())
    val toast = LocalToast.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var fields by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var notes by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val doneText = stringResource(R.string.saved)
    LaunchedEffect(id, row?.status, row?.stage) { fields = vm.fields(id); notes = vm.notes(id) }
    val r = row ?: run { FormPage(stringResource(R.string.loading), nav.back) {}; return }
    val m = Module.of(r.module) ?: Module.TASKS
    val flow = cfg.workflow(m)
    val ref = vm.ref(r, cfg) ?: return
    fun apply(a: BusinessAction.UpdateWork) = scope.launch {
        val res = vm.run(a)
        toast(if (res.ok) doneText else ctx.getString(errorRes(res.error)))
    }
    FormPage("${r.number} · ${moduleTitle(cfg, m, false)}", nav.back) {
        AppCard(Modifier.fillMaxWidth()) {
            Text(r.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Tag(r.status); r.stage?.let { Tag(it) } }
            Spacer(Modifier.height(8.dp))
            r.customerName?.let { LabelValue(cfg.term("customer", stringResource(R.string.customer)), it) }
            r.productName?.let { LabelValue(stringResource(R.string.product), it) }
            r.staffName?.let { LabelValue(stringResource(R.string.assigned_to), it) }
            r.startAt?.let { LabelValue(stringResource(R.string.when_label), fmtDateTime(it)) }
            r.dueAt?.let { LabelValue(stringResource(R.string.due), fmtDateTime(it)) }
            r.amountPaise?.let { LabelValue(stringResource(R.string.amount), money(it)) }
            r.quantityMilli?.let { LabelValue(stringResource(R.string.done_of), "${Qty.format(r.doneMilli)} / ${Qty.format(it)}") }
            for (f in cfg.fields(m)) fields[f.key]?.takeIf { it.isNotBlank() }?.let { LabelValue(f.label, if (f.type == FieldType.BOOLEAN) (if (it == "true") "✓" else "✗") else it) }
            if (notes.isNotBlank()) Text(notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
        if (!ref.closed) {
            if (flow.stages.isNotEmpty()) {
                SectionTitle(stringResource(R.string.steps))
                AppCard(Modifier.fillMaxWidth()) {
                    Text(flow.stages.joinToString("  ›  ") { if (it == r.stage) "[$it]" else it }, style = MaterialTheme.typography.bodyMedium)
                    flow.nextStage(r.stage)?.let { next ->
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { apply(BusinessAction.UpdateWork(ref, null, next, null, null, null, null)) }) { Text(stringResource(R.string.move_to, next)) }
                    }
                }
            }
            if (r.quantityMilli != null) {
                SectionTitle(stringResource(R.string.record_progress))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberInput(stringResource(R.string.quantity_done_now), progress, { progress = it }, Modifier.weight(1f))
                    Button(enabled = (Qty.parse(progress) ?: 0) > 0, onClick = { apply(BusinessAction.UpdateWork(ref, null, null, Qty.parse(progress), null, null, null)); progress = "" }) { Text(stringResource(R.string.add)) }
                }
            }
        }
        SectionTitle(stringResource(R.string.status))
        DropdownField(stringResource(R.string.status), flow.statuses.map { it to it }, r.status, { s -> if (s != r.status) apply(BusinessAction.UpdateWork(ref, s, null, null, null, null, null)) })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(stringResource(R.string.add_note), note, { note = it }, Modifier.weight(1f), capitalize = false)
            Button(enabled = note.isNotBlank(), onClick = { apply(BusinessAction.UpdateWork(ref, null, null, null, null, null, note.trim())); note = "" }) { Text(stringResource(R.string.add)) }
        }
        SectionTitle(stringResource(R.string.history))
        for (e in events) {
            val what = when (e.kind) {
                "CREATED" -> stringResource(R.string.ev_created)
                "STAGE" -> "${e.fromValue.ifBlank { "–" }} → ${e.toValue}"
                "STATUS" -> "${e.fromValue} → ${e.toValue}"
                "PROGRESS" -> "+" + Qty.format((e.toValue.toLongOrNull() ?: 0) - (e.fromValue.toLongOrNull() ?: 0))
                "ASSIGN" -> "→ ${e.toValue}"
                "DUE" -> stringResource(R.string.due) + " " + (e.toValue.toLongOrNull()?.let { fmtDateTime(it) } ?: "")
                "NOTE" -> e.note
                else -> "${e.fromValue} → ${e.toValue}"
            }
            ListRow(what, fmtDateTime(e.createdAt))
        }
    }
}


// ------------------------------------------------------------------ new item

@Composable
fun WorkFormScreen(nav: AppNav, moduleKey: String, vm: WorkViewModel = hiltViewModel()) {
    val cfg by vm.config.collectAsState()
    val m = Module.of(moduleKey) ?: Module.TASKS
    val flow = cfg.workflow(m)
    val toast = LocalToast.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var customers by remember { mutableStateOf<List<PartyRef>>(emptyList()) }
    var products by remember { mutableStateOf<List<ProductRef>>(emptyList()) }
    var staff by remember { mutableStateOf<List<StaffRef>>(emptyList()) }
    LaunchedEffect(Unit) { customers = vm.customers(); products = vm.products(); staff = vm.staffRefs() }
    var customer by remember { mutableStateOf<PartyRef?>(null) }
    var newCustomer by rememberSaveable { mutableStateOf("") }
    var product by remember { mutableStateOf<ProductRef?>(null) }
    var who by remember { mutableStateOf<StaffRef?>(null) }
    var qty by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var due by rememberSaveable { mutableStateOf<Long?>(null) }
    var start by rememberSaveable { mutableStateOf<Long?>(null) }
    var stage by rememberSaveable { mutableStateOf(flow.stages.firstOrNull()) }
    var high by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("") }
    val values = remember { mutableStateOf(mapOf<String, String>()) }
    var busy by remember { mutableStateOf(false) }
    val saved = stringResource(R.string.saved)
    val needTitle = stringResource(R.string.need_title)
    val needTime = stringResource(R.string.need_time)
    val isAppt = m == Module.APPOINTMENTS

    FormPage(stringResource(R.string.new_item, moduleTitle(cfg, m, false)), nav.back, bottom = {
        BottomAction(stringResource(R.string.save), enabled = !busy) {
            if (title.isBlank()) { toast(needTitle); return@BottomAction }
            if (isAppt && start == null) { toast(needTime); return@BottomAction }
            busy = true
            scope.launch {
                val r = vm.run(BusinessAction.NewWork(m, title.trim(), customer, newCustomer.trim().takeIf { customer == null && it.isNotEmpty() }, product, who,
                    flow.openStatuses.first(), stage, Qty.parse(qty), Money.parse(amount), start, due, if (high) 1 else 0,
                    values.value.filterValues { it.isNotBlank() }, notes.trim(), null))
                busy = false
                if (r.ok) { toast("$saved ${r.reference.orEmpty()}"); nav.back() } else toast(ctx.getString(errorRes(r.error)))
            }
        }
    }) {
        TextInput(stringResource(R.string.title_label), title, { title = it }, capitalize = false)
        if (cfg.has(Module.CUSTOMERS) || m != Module.TASKS) {
            PickerField(cfg.term("customer", stringResource(R.string.customer)), customer?.name ?: newCustomer, customers.map { it.id to it.name }, { id -> customer = customers.firstOrNull { it.id == id } }, onClear = { customer = null })
            if (customer == null) TextInput(stringResource(R.string.or_new_customer), newCustomer, { newCustomer = it })
        }
        if (cfg.has(Module.INVENTORY) && m != Module.APPOINTMENTS) PickerField(stringResource(R.string.product), product?.name.orEmpty(), products.map { it.id to it.name }, { id -> product = products.firstOrNull { it.id == id } }, onClear = { product = null })
        if (m in setOf(Module.PRODUCTION, Module.ORDERS, Module.PROJECTS)) NumberInput(stringResource(R.string.quantity), qty, { qty = it })
        if (flow.stages.isNotEmpty()) DropdownField(stringResource(R.string.step), flow.stages.map { it to it }, stage, { stage = it })
        if (staff.isNotEmpty()) PickerField(stringResource(R.string.assigned_to), who?.name.orEmpty(), staff.map { it.id to it.name }, { id -> who = staff.firstOrNull { it.id == id } }, onClear = { who = null })
        if (isAppt) DateTimeField(stringResource(R.string.when_label), start, { start = it }, defaultHour = 11)
        else DateTimeField(stringResource(R.string.due), due, { due = it })
        if (m != Module.TASKS) NumberInput(stringResource(R.string.amount), amount, { amount = it }, decimals = 2)
        for (f in cfg.fields(m)) {
            val v = values.value[f.key].orEmpty()
            val set: (String) -> Unit = { values.value = values.value + (f.key to it) }
            when (f.type) {
                FieldType.NUMBER -> NumberInput(f.label, v, set)
                FieldType.CHOICE -> DropdownField(f.label, f.options.map { it to it }, v.ifBlank { null }, set)
                FieldType.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) { Text(f.label, Modifier.weight(1f)); Switch(v == "true", { set(it.toString()) }) }
                else -> TextInput(f.label, v, set, capitalize = false)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.priority_high), Modifier.weight(1f)); Switch(high, { high = it }) }
        TextInput(stringResource(R.string.notes), notes, { notes = it }, singleLine = false, capitalize = false)
        Spacer(Modifier.height(80.dp))
    }
}


