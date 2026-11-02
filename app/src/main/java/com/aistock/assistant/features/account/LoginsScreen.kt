package com.aistock.assistant.features.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aistock.assistant.R
import com.aistock.assistant.server.ServerApi
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.ConfirmDialog
import com.aistock.assistant.ui.components.DropdownField
import com.aistock.assistant.ui.components.FormPage
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.SectionTitle
import kotlinx.coroutines.launch

/**
 * The owner's list of people who sign in to this business (staff sync): make a login for a staff member or manager,
 * remove one, and choose whether staff may also record purchases, payments and expenses.
 */
@Composable
fun LoginsScreen(nav: AppNav, vm: AccountViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var team by remember { mutableStateOf<ServerApi.Team?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ServerApi.Member?>(null) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("staff") }
    var existing by remember { mutableStateOf(false) }

    suspend fun reload() {
        loadError = try { team = vm.api.team(); null } catch (e: Exception) { e.message ?: e.toString() }
    }
    LaunchedEffect(Unit) { reload() }

    FormPage(title = stringResource(R.string.logins_title), onBack = nav.back) {
        AppCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.logins_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        val t = team
        if (t != null) {
            SectionTitle(stringResource(R.string.logins_people, t.members.size, t.maxStaff))
            for (m in t.members) {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name.ifBlank { m.email }, style = MaterialTheme.typography.titleMedium)
                            val r = stringResource(when (m.role) { "staff" -> R.string.role_staff; "manager" -> R.string.role_manager; else -> R.string.role_owner })
                            Text(listOf(m.email, r, m.code).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (m.role != "owner") TextButton(onClick = { removing = m }) { Text(stringResource(R.string.logins_remove), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            SectionTitle(stringResource(R.string.logins_rules))
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.logins_money), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.logins_money_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = t.staffMoney, enabled = !busy, onCheckedChange = { on ->
                        busy = true
                        scope.launch {
                            try { vm.api.setStaffMoney(on); reload() } catch (e: Exception) { toast(e.message ?: e.toString()) }
                            busy = false
                        }
                    })
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.logins_rules_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionTitle(stringResource(R.string.logins_add))
            AppCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownField(stringResource(R.string.logins_kind), listOf("new" to stringResource(R.string.logins_kind_new), "existing" to stringResource(R.string.logins_kind_existing)),
                        if (existing) "existing" else "new", { existing = it == "existing" })
                    if (!existing) OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.logins_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(email, { email = it.trim().take(120) }, label = { Text(stringResource(R.string.email)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    if (!existing) OutlinedTextField(password, { password = it.take(100) }, label = { Text(stringResource(R.string.logins_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    DropdownField(stringResource(R.string.logins_role), listOf("staff" to stringResource(R.string.role_staff), "manager" to stringResource(R.string.role_manager)), role, { role = it })
                    Text(stringResource(if (role == "manager") R.string.logins_role_manager else R.string.logins_role_staff), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = !busy && email.contains('@') && (existing || (name.isNotBlank() && password.length >= 8)), onClick = {
                        busy = true
                        scope.launch {
                            try {
                                if (existing) vm.api.addExistingMember(email, role) else vm.api.createMember(name.trim(), email, password, role)
                                toast(ctx.getString(R.string.logins_added, if (existing) email else name.trim()))
                                name = ""; email = ""; password = ""
                                reload()
                            } catch (e: Exception) { toast(e.message ?: e.toString()) }
                            busy = false
                        }
                    }) { Text(stringResource(R.string.logins_add_button)) }
                    Text(stringResource(R.string.logins_how), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (loadError == null) {
            Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
        }
        if (loadError != null) OutlinedButton(onClick = { scope.launch { reload() } }) { Text(stringResource(R.string.retry)) }
    }

    removing?.let { m ->
        ConfirmDialog(
            stringResource(R.string.logins_remove_title, m.name.ifBlank { m.email }), stringResource(R.string.logins_remove_body), stringResource(R.string.logins_remove),
            onConfirm = {
                removing = null
                scope.launch {
                    try { vm.api.removeMember(m.email); reload() } catch (e: Exception) { toast(e.message ?: e.toString()) }
                }
            },
            onDismiss = { removing = null }, danger = true,
        )
    }
}
