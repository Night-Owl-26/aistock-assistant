package com.aistock.assistant.features.parties

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aistock.assistant.R
import com.aistock.assistant.ai.PartyKind
import com.aistock.assistant.data.Op
import com.aistock.assistant.data.PartyInput
import com.aistock.assistant.data.PartyRepository
import com.aistock.assistant.data.PartyRow
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.AppTopBar
import com.aistock.assistant.ui.components.BottomAction
import com.aistock.assistant.ui.components.ConfirmDialog
import com.aistock.assistant.ui.components.EmptyState
import com.aistock.assistant.ui.components.FormPage
import com.aistock.assistant.ui.components.ListRow
import com.aistock.assistant.ui.components.LocalCurrency
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.NumberInput
import com.aistock.assistant.ui.components.SearchField
import com.aistock.assistant.ui.components.TextInput
import com.aistock.assistant.ui.components.errorRes
import com.aistock.assistant.ui.money
import com.aistock.assistant.utils.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PartyViewModel @Inject constructor(private val repo: PartyRepository) : ViewModel() {
    val query = MutableStateFlow("")
    private val kind = MutableStateFlow(PartyKind.CUSTOMER)
    fun setKind(k: PartyKind) { kind.value = k }

    val list: StateFlow<List<PartyRow>> = combine(kind, query.debounce(150)) { k, q -> k to q }
        .flatMapLatest { (k, q) -> repo.observe(k, q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun get(kind: PartyKind, id: String): PartyRow? = repo.get(kind, id)
    suspend fun create(kind: PartyKind, input: PartyInput): Op<String> = repo.create(kind, input)
    suspend fun update(kind: PartyKind, id: String, input: PartyInput): Op<Unit> = repo.update(kind, id, input)
    suspend fun remove(kind: PartyKind, id: String): Op<Boolean> = repo.remove(kind, id)
}

fun partyKind(route: String): PartyKind = if (route == "supplier") PartyKind.SUPPLIER else PartyKind.CUSTOMER

@Composable
fun PartyListScreen(nav: AppNav, kindRoute: String, vm: PartyViewModel = hiltViewModel()) {
    val kind = partyKind(kindRoute)
    LaunchedEffect(kind) { vm.setKind(kind) }
    val query by vm.query.collectAsStateWithLifecycle()
    val list by vm.list.collectAsStateWithLifecycle()
    val isCustomer = kind == PartyKind.CUSTOMER
    Scaffold(
        topBar = { AppTopBar(stringResource(if (isCustomer) R.string.customers else R.string.suppliers), nav.back) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.go("party/$kindRoute") }, icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(if (isCustomer) R.string.add_customer else R.string.add_supplier)) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(query, { vm.query.value = it }, stringResource(R.string.search_name_phone), Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            if (list.isEmpty()) {
                EmptyState(
                    if (query.isNotBlank()) stringResource(R.string.nothing_found) else stringResource(if (isCustomer) R.string.no_customers else R.string.no_suppliers),
                    if (query.isNotBlank()) "" else stringResource(if (isCustomer) R.string.no_customers_body else R.string.no_suppliers_body),
                    painterResource(R.drawable.ic_people),
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { p ->
                        ListRow(
                            title = p.name, subtitle = listOf(p.phone, p.address).filter { it.isNotBlank() }.joinToString(" · "),
                            trailing = if (p.balance != 0L) money(Math.abs(p.balance)) else "",
                            trailingSub = when {
                                p.balance > 0 -> stringResource(if (isCustomer) R.string.to_receive else R.string.to_pay)
                                p.balance < 0 -> stringResource(R.string.advance)
                                else -> stringResource(R.string.settled)
                            },
                            onClick = { nav.go("party/$kindRoute?id=${p.id}") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PartyFormScreen(nav: AppNav, kindRoute: String, partyId: String?, vm: PartyViewModel = hiltViewModel()) {
    val kind = partyKind(kindRoute)
    val isCustomer = kind == PartyKind.CUSTOMER
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current
    var existing by remember { mutableStateOf<PartyRow?>(null) }
    var loaded by remember { mutableStateOf(partyId == null) }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var opening by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(partyId) {
        if (partyId != null && !loaded) {
            val p = vm.get(kind, partyId)
            if (p == null) { nav.back(); return@LaunchedEffect }
            existing = p
            name = p.name; phone = p.phone; email = p.email; address = p.address; notes = p.notes
            opening = if (p.openingBalance == 0L) "" else Money.plain(p.openingBalance)
            loaded = true
        }
    }

    FormPage(
        title = stringResource(
            when {
                partyId == null && isCustomer -> R.string.add_customer
                partyId == null -> R.string.add_supplier
                isCustomer -> R.string.edit_customer
                else -> R.string.edit_supplier
            }
        ),
        onBack = nav.back,
        actions = { if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete)) } },
        bottom = {
            BottomAction(stringResource(R.string.save), enabled = loaded && !saving) {
                if (name.isBlank()) { nameError = ctx.getString(R.string.name_required); return@BottomAction }
                val input = PartyInput(name, phone, email, address, Money.parse(opening) ?: 0L, notes)
                saving = true
                scope.launch {
                    val r: Op<Any> = if (partyId == null) vm.create(kind, input) else vm.update(kind, partyId, input)
                    saving = false
                    when (r) {
                        is Op.Ok -> { toast(ctx.getString(R.string.saved)); nav.back() }
                        is Op.Err -> if (r.code == "DUPLICATE") nameError = ctx.getString(R.string.name_exists) else toast(ctx.getString(errorRes(r.code)))
                    }
                }
            }
        },
    ) {
        existing?.let { p ->
            AppCard(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(when { p.balance < 0 -> R.string.advance; isCustomer -> R.string.to_receive; else -> R.string.to_pay }),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(money(Math.abs(p.balance)), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { nav.go("payment?kind=$kindRoute&partyId=${p.id}") }) {
                    Text(stringResource(if (isCustomer) R.string.record_payment_received else R.string.record_payment_made))
                }
            }
        }
        TextInput(stringResource(R.string.name), name, { name = it; nameError = null }, error = nameError)
        TextInput(stringResource(R.string.phone), phone, { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(16) }, keyboard = KeyboardType.Phone)
        TextInput(stringResource(R.string.address), address, { address = it }, singleLine = false)
        TextInput(stringResource(R.string.email), email, { email = it.trim() }, keyboard = KeyboardType.Email)
        NumberInput(stringResource(if (isCustomer) R.string.opening_balance_customer else R.string.opening_balance_supplier), opening, { opening = it }, decimals = 2, suffix = LocalCurrency.current)
        TextInput(stringResource(R.string.notes), notes, { notes = it }, singleLine = false, capitalize = false)
        Spacer(Modifier.height(8.dp))
    }

    if (confirmDelete) {
        ConfirmDialog(
            stringResource(R.string.delete_party_title), stringResource(R.string.delete_party_body), stringResource(R.string.delete), danger = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    when (val r = vm.remove(kind, partyId ?: return@launch)) {
                        is Op.Ok -> { toast(ctx.getString(if (r.value) R.string.deleted else R.string.hidden_kept_history)); nav.back() }
                        is Op.Err -> toast(ctx.getString(errorRes(r.code)))
                    }
                }
            },
        )
    }
}
