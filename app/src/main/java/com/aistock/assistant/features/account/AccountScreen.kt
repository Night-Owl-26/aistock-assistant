package com.aistock.assistant.features.account

import androidx.compose.foundation.layout.Arrangement
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
 * Sign in to the owner's own AIStock server. Once signed in, Stocky's AI and real-time voice go through the server,
 * which holds the Gemini key, counts usage and checks the plan. Business records stay on this phone.
 */
@Composable
fun AccountScreen(nav: AppNav, vm: AccountViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle(initialValue = null)
    val p = prefs ?: return
    val toast = LocalToast.current
    val ctx = LocalContext.current
    val ownerCheck = LocalOwnerCheck.current
    val scope = rememberCoroutineScope()
    var url by remember(p.serverUrl) { mutableStateOf(p.serverUrl.ifBlank { "https://" }) }
    var mode by remember { mutableStateOf("login") }
    var email by remember { mutableStateOf(p.serverEmail) }
    var password by remember { mutableStateOf("") }
    var business by remember { mutableStateOf("") }
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
            TextInput(stringResource(R.string.server_address), url, { url = it.trim() }, keyboard = KeyboardType.Uri, capitalize = false)
            Text(stringResource(R.string.server_address_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == "login", { mode = "login" }, label = { Text(stringResource(R.string.sign_in)) })
                FilterChip(mode == "register", { mode = "register" }, label = { Text(stringResource(R.string.create_account)) })
            }
            TextInput(stringResource(R.string.email), email, { email = it.trim() }, keyboard = KeyboardType.Email, capitalize = false)
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (mode == "register") TextInput(stringResource(R.string.business_name), business, { business = it })
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Button(enabled = !busy && url.length > 10 && email.contains('@') && password.length >= 8 && (mode == "login" || business.isNotBlank()), onClick = {
                busy = true; error = ""
                scope.launch {
                    try {
                        vm.api.health(url)
                        if (mode == "login") vm.api.login(url, email, password) else vm.api.register(url, email, password, "", business)
                        password = ""
                        toast(ctx.getString(R.string.signed_in))
                    } catch (e: Exception) {
                        error = e.message ?: e.toString()
                    }
                    busy = false
                }
            }) { Text(stringResource(if (busy) R.string.please_wait else if (mode == "login") R.string.sign_in else R.string.create_account)) }
        } else {
            AppCard(Modifier.fillMaxWidth()) {
                LabelValue(stringResource(R.string.email), p.serverEmail)
                LabelValue(stringResource(R.string.server_address), p.serverUrl)
                account?.let { a ->
                    LabelValue(stringResource(R.string.business_name), a.businessName)
                    a.usage?.let { u ->
                        LabelValue(stringResource(R.string.plan), "${u.planName} · ${u.status}" + if (u.priceMinor > 0) " · ${Money.format(u.priceMinor.toLong(), "₹")}/month" else "")
                        LabelValue(stringResource(R.string.renews), u.periodEnd.take(10))
                        LabelValue(stringResource(R.string.ai_requests_used), "${u.aiUsed} / ${u.aiLimit}")
                        LabelValue(stringResource(R.string.voice_minutes_used), "${u.liveUsedSec / 60} / ${u.liveLimitSec / 60}")
                        if (!u.active) Text(stringResource(R.string.plan_inactive), color = MaterialTheme.colorScheme.error)
                    }
                    if (!a.aiConfigured) Text(stringResource(R.string.server_no_key), color = MaterialTheme.colorScheme.error)
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.plan_how_to_pay), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
