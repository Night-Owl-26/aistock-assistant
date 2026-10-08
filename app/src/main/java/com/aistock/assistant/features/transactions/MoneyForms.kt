package com.aistock.assistant.features.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import com.aistock.assistant.ai.PartyKind
import com.aistock.assistant.data.Op
import com.aistock.assistant.data.PartyRow
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.BottomAction
import com.aistock.assistant.ui.components.DateField
import com.aistock.assistant.ui.components.DropdownField
import com.aistock.assistant.ui.components.FormPage
import com.aistock.assistant.ui.components.LocalCurrency
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.NumberInput
import com.aistock.assistant.ui.components.PickerField
import com.aistock.assistant.ui.components.TextInput
import com.aistock.assistant.ui.components.errorRes
import com.aistock.assistant.ui.methodOptions
import com.aistock.assistant.ui.money
import com.aistock.assistant.utils.Money
import kotlinx.coroutines.launch

@Composable
fun ExpenseFormScreen(nav: AppNav, vm: TradeViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current
    var category by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf("CASH") }
    var description by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    var used by remember { mutableStateOf<List<String>>(emptyList()) }
    var categoryError by remember { mutableStateOf<String?>(null) }
    var amountError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { used = vm.expenseCategories() }
    val suggestions = (used + listOf("Transport", "Rent", "Salary", "Electricity", "Food", "Fuel", "Maintenance", "Packing")).distinct().take(12)

    FormPage(
        title = stringResource(R.string.new_expense), onBack = nav.back,
        bottom = {
            BottomAction(stringResource(R.string.save), enabled = !saving) {
                val a = Money.parse(amount)
                if (category.isBlank()) { categoryError = ctx.getString(R.string.category_required); return@BottomAction }
                if (a == null || a <= 0) { amountError = ctx.getString(R.string.enter_amount); return@BottomAction }
                saving = true
                scope.launch {
                    val r = vm.addExpense(category, description, a, method, date)
                    saving = false
                    when (r) {
                        is Op.Ok -> { toast(ctx.getString(R.string.expense_saved)); nav.back() }
                        is Op.Err -> toast(ctx.getString(errorRes(r.code)))
                    }
                }
            }
        },
    ) {
        NumberInput(stringResource(R.string.amount), amount, { amount = it; amountError = null }, decimals = 2, suffix = LocalCurrency.current, error = amountError)
        TextInput(stringResource(R.string.category), category, { category = it; categoryError = null }, error = categoryError)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in suggestions) FilterChip(selected = category == s, onClick = { category = s; categoryError = null }, label = { Text(s) })
        }
        DropdownField(stringResource(R.string.payment_method), methodOptions(), method, { method = it })
        DateField(stringResource(R.string.date), date, { date = it })
        TextInput(stringResource(R.string.description), description, { description = it }, singleLine = false, capitalize = false)
        Spacer(Modifier.height(8.dp))
    }
}

/** @param initialKind "customer" or "supplier" */
@Composable
fun PaymentFormScreen(nav: AppNav, initialKind: String, initialPartyId: String?, vm: TradeViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current
    var kind by rememberSaveable { mutableStateOf(if (initialKind == "supplier") "SUPPLIER" else "CUSTOMER") }
    var parties by remember { mutableStateOf<List<PartyRow>>(emptyList()) }
    var partyId by rememberSaveable { mutableStateOf(initialPartyId) }
    var amount by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf("CASH") }
    var reference by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    var amountError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(kind) { parties = vm.partyList(if (kind == "CUSTOMER") PartyKind.CUSTOMER else PartyKind.SUPPLIER) }
    val party = parties.firstOrNull { it.id == partyId }

    FormPage(
        title = stringResource(R.string.new_payment), onBack = nav.back,
        bottom = {
            BottomAction(stringResource(R.string.save), enabled = !saving) {
                val a = Money.parse(amount)
                val id = partyId
                if (id == null || party == null) { toast(ctx.getString(if (kind == "CUSTOMER") R.string.choose_customer else R.string.choose_supplier)); return@BottomAction }
                if (a == null || a <= 0) { amountError = ctx.getString(R.string.enter_amount); return@BottomAction }
                saving = true
                scope.launch {
                    val r = vm.addPayment(kind, id, a, method, reference, date, notes)
                    saving = false
                    when (r) {
                        is Op.Ok -> { toast(ctx.getString(R.string.payment_saved)); nav.back() }
                        is Op.Err -> toast(ctx.getString(errorRes(r.code)))
                    }
                }
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == "CUSTOMER", onClick = { if (kind != "CUSTOMER") { kind = "CUSTOMER"; partyId = null } }, label = { Text(stringResource(R.string.received_from_customer)) })
            FilterChip(selected = kind == "SUPPLIER", onClick = { if (kind != "SUPPLIER") { kind = "SUPPLIER"; partyId = null } }, label = { Text(stringResource(R.string.paid_to_supplier)) })
        }
        PickerField(
            stringResource(if (kind == "CUSTOMER") R.string.customer else R.string.supplier), party?.name.orEmpty(),
            parties.map { it.id to it.name }, onPick = { partyId = it },
            emptyText = stringResource(if (kind == "CUSTOMER") R.string.no_customers else R.string.no_suppliers),
        )
        if (party != null) {
            AppCard(Modifier.fillMaxWidth()) {
                Text(stringResource(if (kind == "CUSTOMER") R.string.to_receive else R.string.to_pay), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(party.balance), style = MaterialTheme.typography.headlineSmall)
            }
        }
        NumberInput(stringResource(R.string.amount), amount, { amount = it; amountError = null }, decimals = 2, suffix = LocalCurrency.current, error = amountError)
        DropdownField(stringResource(R.string.payment_method), methodOptions(), method, { method = it })
        DateField(stringResource(R.string.date), date, { date = it })
        TextInput(stringResource(R.string.reference_optional), reference, { reference = it }, capitalize = false)
        TextInput(stringResource(R.string.notes), notes, { notes = it }, singleLine = false, capitalize = false)
        Spacer(Modifier.height(8.dp))
    }
}
