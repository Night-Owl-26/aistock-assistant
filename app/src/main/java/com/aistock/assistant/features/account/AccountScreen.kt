package com.aistock.assistant.features.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.aistock.assistant.data.Access
import com.aistock.assistant.data.Prefs
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistock.assistant.R
import com.aistock.assistant.backup.BackupManager
import com.aistock.assistant.data.SettingsStore
import com.aistock.assistant.server.Account
import com.aistock.assistant.server.ServerApi
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.LocalOwnerCheck
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.FormPage
import com.aistock.assistant.ui.components.LabelValue
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.SectionTitle
import com.aistock.assistant.ui.components.TextInput
import com.aistock.assistant.utils.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(val api: ServerApi, val settings: SettingsStore, val backups: BackupManager) : ViewModel() {
    val prefs = settings.flow
}

/**
 * Email and password only: the Follower server is built into the app. The address can still be changed under
 * "Change server" (for a test server or your own deployment).
 */
@Composable
fun SignInForm(vm: AccountViewModel, p: Prefs, startMode: String, defaultBusiness: String = "") {
    val toast = LocalToast.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember(p.serverUrl) { mutableStateOf(p.serverUrl.ifBlank { com.aistock.assistant.BuildConfig.DEFAULT_SERVER }) }
    var showServer by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(startMode) }
    var email by remember { mutableStateOf(p.serverEmail) }
    var password by remember { mutableStateOf("") }
    var business by remember(defaultBusiness) { mutableStateOf(defaultBusiness) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(mode == "register", { mode = "register" }, label = { Text(stringResource(R.string.create_account)) })
        FilterChip(mode == "login", { mode = "login" }, label = { Text(stringResource(R.string.sign_in)) })
    }
    TextInput(stringResource(R.string.email), email, { email = it.trim() }, keyboard = KeyboardType.Email, capitalize = false)
    OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    if (mode == "register") TextInput(stringResource(R.string.business_name), business, { business = it })
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && url.length > 10 && email.contains('@') && password.length >= 8 && (mode == "login" || business.isNotBlank()), onClick = {
        busy = true; error = ""
        scope.launch {
            try {
                vm.api.health(url)
                if (mode == "login") vm.api.login(url, email, password) else vm.api.register(url, email, password, "", business)
                password = ""
                toast(ctx.getString(R.string.signed_in))
            } catch (e: java.io.IOException) {
                error = ctx.getString(R.string.signin_no_internet)
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            busy = false
        }
    }) { Text(stringResource(if (busy) R.string.please_wait else if (mode == "login") R.string.sign_in else R.string.create_account)) }
    TextButton(onClick = { showServer = !showServer }) { Text(stringResource(R.string.change_server), style = MaterialTheme.typography.bodySmall) }
    if (showServer) {
        TextInput(stringResource(R.string.server_address), url, { url = it.trim() }, keyboard = KeyboardType.Uri, capitalize = false)
        Text(stringResource(R.string.server_address_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Shown after setup until the owner has a Follower account: the 14-day free trial starts with it. */
@Composable
fun SignInGate(businessName: String, vm: AccountViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle(initialValue = null)
    val p = prefs ?: return
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.gate_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.gate_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SignInForm(vm, p, "register", businessName)
    }
}

/**
 * The owner's Follower account. Once signed in, Stocky's AI and voice go through the Follower server,
 * which holds the AI keys, counts usage and checks the plan. Business records stay on this phone.
 */
@Composable
fun AccountScreen(nav: AppNav, vm: AccountViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle(initialValue = null)
    val p = prefs ?: return
    val toast = LocalToast.current
    val ctx = LocalContext.current
    val ownerCheck = LocalOwnerCheck.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf<Account?>(null) }
    var error by remember { mutableStateOf("") }
    var deletePw by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }
    val signedIn = p.serverBusinessId.isNotBlank() && p.serverBusinessId != "pending"

    suspend fun load() { account = try { vm.api.account() } catch (e: Exception) { error = e.message ?: e.toString(); null } }
    LaunchedEffect(signedIn) { if (signedIn) load() }

    FormPage(stringResource(R.string.aistock_account), nav.back) {
        Text(stringResource(R.string.account_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!signedIn) {
            SignInForm(vm, p, "login")
        } else {
            AppCard(Modifier.fillMaxWidth()) {
                LabelValue(stringResource(R.string.email), p.serverEmail)
                account?.let { a ->
                    LabelValue(stringResource(R.string.business_name), a.businessName)
                    a.usage?.let { u ->
                        LabelValue(stringResource(R.string.plan), "${u.planName}" + if (u.priceMinor > 0) " · ${Money.format(u.priceMinor.toLong(), "₹")}/month" else "")
                        LabelValue(stringResource(if (u.active) R.string.plan_until else R.string.plan_ended_on), u.periodEnd.take(10))
                        LabelValue(stringResource(R.string.ai_requests_used), "${u.aiUsed} / ${u.aiLimit}")
                        LabelValue(stringResource(R.string.voice_minutes_used), "${u.liveUsedSec / 60} / ${u.liveLimitSec / 60}")
                        if (!u.active) Text(stringResource(R.string.plan_inactive), color = MaterialTheme.colorScheme.error)
                    }
                    if (!a.aiConfigured) Text(stringResource(R.string.server_no_key), color = MaterialTheme.colorScheme.error)
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { nav.go("plan") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.plan_and_payment)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { error = ""; load() } }) { Text(stringResource(R.string.refresh)) }
                OutlinedButton(enabled = !busy, onClick = {
                    ownerCheck(ctx.getString(R.string.owner_check_export)) {
                        busy = true
                        scope.launch {
                            try { vm.api.uploadBackup(vm.backups.snapshot(), "from phone"); toast(ctx.getString(R.string.backup_uploaded)) } catch (e: Exception) { toast(e.message ?: e.toString()) }
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.backup_to_server)) }
            }
            Text(stringResource(R.string.backup_to_server_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionTitle(stringResource(R.string.account))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { vm.api.logout(); account = null } }) { Text(stringResource(R.string.sign_out)) }
                TextButton(onClick = { showDelete = !showDelete }) { Text(stringResource(R.string.delete_account), color = MaterialTheme.colorScheme.error) }
            }
            if (showDelete) {
                Text(stringResource(R.string.delete_account_body), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(deletePw, { deletePw = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Button(enabled = deletePw.length >= 8, onClick = {
                    ownerCheck(ctx.getString(R.string.delete_account)) {
                        scope.launch {
                            try { vm.api.deleteAccount(deletePw); toast(ctx.getString(R.string.account_deleted)) } catch (e: Exception) { toast(e.message ?: e.toString()) }
                        }
                    }
                }) { Text(stringResource(R.string.delete_account)) }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
