package com.aistock.assistant.features.settings

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistock.assistant.BuildConfig
import com.aistock.assistant.R
import com.aistock.assistant.data.Op
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.AppCard
import com.aistock.assistant.ui.components.ConfirmDialog
import com.aistock.assistant.ui.components.DropdownField
import com.aistock.assistant.ui.components.FormPage
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.SectionTitle
import com.aistock.assistant.ui.components.TextInput
import com.aistock.assistant.ui.components.errorRes
import kotlinx.coroutines.launch

/** The "More" tab: everything that is not day-to-day stock and money entry. */
@Composable
fun MoreScreen(nav: AppNav, vm: MainViewModel, cfg: com.aistock.assistant.business.BusinessConfig, inNav: Set<com.aistock.assistant.business.Module>, tabRoutes: Set<String> = emptySet()) {
    val business by vm.businessProfile.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.nav_more), style = MaterialTheme.typography.headlineSmall)
        business?.let { Text(it.name + " · " + it.ownerName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(4.dp))
        // Everyday things first; work modules that are not in the bottom bar next; setup and account last.
        for (m in cfg.workModules.filter { it !in inNav }) MenuRow(R.drawable.ic_work, com.aistock.assistant.features.work.moduleTitle(cfg, m), stringResource(R.string.work_sub)) { nav.go("worklist/${m.key}") }
        if (cfg.has(com.aistock.assistant.business.Module.FOLLOWUPS)) MenuRow(R.drawable.ic_people, stringResource(R.string.followups), stringResource(R.string.followups_sub)) { nav.go("followups") }
        MenuRow(R.drawable.ic_people, stringResource(R.string.customers), stringResource(R.string.customers_sub)) { nav.go("parties/customer") }
        MenuRow(R.drawable.ic_people, stringResource(R.string.suppliers), stringResource(R.string.suppliers_sub)) { nav.go("parties/supplier") }
        MenuRow(R.drawable.ic_report, stringResource(R.string.reports), stringResource(R.string.reports_sub)) { nav.go("reports") }
        if ("inventory" !in tabRoutes && cfg.has(com.aistock.assistant.business.Module.INVENTORY)) MenuRow(R.drawable.ic_inventory, stringResource(R.string.nav_inventory), stringResource(R.string.inventory_sub)) { nav.go("inventory") }
        if ("transactions" !in tabRoutes) MenuRow(R.drawable.ic_receipt, stringResource(R.string.nav_transactions), stringResource(R.string.transactions_sub)) { nav.go("transactions") }
        MenuRow(R.drawable.ic_settings, stringResource(R.string.reminders), stringResource(R.string.reminders_sub)) { nav.go("reminders") }
        if (cfg.has(com.aistock.assistant.business.Module.STAFF) || cfg.has(com.aistock.assistant.business.Module.TASKS)) MenuRow(R.drawable.ic_people, stringResource(R.string.staff), stringResource(R.string.staff_sub)) { nav.go("staff") }
        MenuRow(R.drawable.ic_stock_in, stringResource(R.string.import_export), stringResource(R.string.import_export_sub)) { nav.go("data") }
        MenuRow(R.drawable.ic_backup, stringResource(R.string.backup_restore), stringResource(R.string.backup_restore_sub)) { nav.go("backup") }
        MenuRow(R.drawable.ic_work, stringResource(R.string.business_setup), stringResource(R.string.business_setup_sub)) { nav.go("setup") }
        MenuRow(R.drawable.ic_lock, stringResource(R.string.aistock_account), stringResource(R.string.account_sub)) { nav.go("account") }
        MenuRow(R.drawable.ic_settings, stringResource(R.string.settings), stringResource(R.string.settings_sub)) { nav.go("settings") }
    }
}

@Composable
private fun MenuRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun SettingsScreen(nav: AppNav, vm: MainViewModel) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val business by vm.businessProfile.collectAsStateWithLifecycle()
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val toast = LocalToast.current

    var editBusiness by remember { mutableStateOf(false) }
    val ownerCheck = com.aistock.assistant.ui.LocalOwnerCheck.current
    val handsFreePerms = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[android.Manifest.permission.RECORD_AUDIO] == true) {
            scope.launch { vm.setHandsFree(true) }
            com.aistock.assistant.voice.HandsFree.start(ctx)
        } else toast(ctx.getString(R.string.handsfree_needs_mic))
    }
    var pinDialog by remember { mutableStateOf(false) }
    var confirmSample by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var endpoint by remember(prefs.cloudEndpoint) { mutableStateOf(prefs.cloudEndpoint) }
    var model by remember(prefs.cloudModel) { mutableStateOf(prefs.cloudModel) }
    var apiKey by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    var testOk by remember { mutableStateOf(false) }
    var checks by remember { mutableStateOf<List<com.aistock.assistant.di.Check>>(emptyList()) }

    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { vm.setLowStockAlerts(granted) }
        if (!granted) toast(ctx.getString(R.string.notifications_denied))
    }

    FormPage(title = stringResource(R.string.settings), onBack = nav.back) {
        SectionTitle(stringResource(R.string.business_settings))
        AppCard(Modifier.fillMaxWidth(), onClick = { editBusiness = true }) {
            Text(business?.name.orEmpty().ifBlank { "—" }, style = MaterialTheme.typography.titleMedium)
            Text(business?.ownerName.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.currency) + ": " + prefs.currencyCode + " " + prefs.currencySymbol.trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.tap_to_edit), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }

        SectionTitle(stringResource(R.string.language_and_look))
        AppCard(Modifier.fillMaxWidth()) {
            DropdownField(stringResource(R.string.language), listOf("en" to "English", "ta" to "தமிழ்"), prefs.language, { lang ->
                if (lang != prefs.language) scope.launch { vm.setLanguage(lang); (ctx as? Activity)?.recreate() }
            })
            Spacer(Modifier.height(10.dp))
            DropdownField(
                stringResource(R.string.appearance),
                listOf("system" to stringResource(R.string.theme_system), "light" to stringResource(R.string.theme_light), "dark" to stringResource(R.string.theme_dark)),
                prefs.theme, { scope.launch { vm.setTheme(it) } },
            )
        }

        SectionTitle(stringResource(R.string.voice))
        AppCard(Modifier.fillMaxWidth()) {
            Text(stringResource(if (prefs.tamil) R.string.voice_language_ta else R.string.voice_language_en), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow(stringResource(R.string.spoken_replies), stringResource(R.string.spoken_replies_sub), prefs.ttsEnabled) { scope.launch { vm.setTts(it) } }
            SwitchRow(stringResource(R.string.handsfree_setting), stringResource(R.string.handsfree_setting_sub), prefs.handsFree) { on ->
                if (!on) { scope.launch { vm.setHandsFree(false) }; com.aistock.assistant.voice.HandsFree.stop(ctx) }
                else {
                    val perms = buildList {
                        add(android.Manifest.permission.RECORD_AUDIO)
                        if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    handsFreePerms.launch(perms.toTypedArray())
                }
            }
            Text(stringResource(R.string.handsfree_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionTitle(stringResource(R.string.security))
        AppCard(Modifier.fillMaxWidth()) {
            SwitchRow(stringResource(R.string.owner_check_setting), stringResource(R.string.owner_check_setting_sub), prefs.ownerCheck) { on ->
                if (on) scope.launch { vm.setOwnerCheck(true) }
                // Turning the protection off is itself protected.
                else ownerCheck(ctx.getString(R.string.owner_check_setting)) { scope.launch { vm.setOwnerCheck(false) } }
            }
        }

        SectionTitle(stringResource(R.string.ai_provider))
        AppCard(Modifier.fillMaxWidth()) {
            DropdownField(
                stringResource(R.string.ai_source), listOf("server" to stringResource(R.string.ai_source_server), "key" to stringResource(R.string.ai_source_key)),
                prefs.aiSource, { scope.launch { vm.setAiSource(it) } },
            )
            Spacer(Modifier.height(8.dp))
            if (prefs.aiSource == "server") {
                Text(stringResource(if (prefs.serverBusinessId.isNotBlank()) R.string.ai_server_signed_in else R.string.ai_server_help, prefs.serverEmail), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { nav.go("account") }) { Text(stringResource(R.string.aistock_account)) }
                SwitchRow(stringResource(R.string.cloud_speech), stringResource(R.string.cloud_speech_sub), prefs.cloudSpeech) { scope.launch { vm.setCloudSpeech(it) } }
                if (!prefs.cloudSpeech) SwitchRow(stringResource(R.string.live_voice), stringResource(R.string.live_voice_sub), prefs.liveVoice) { scope.launch { vm.setLiveVoice(it) } }
            }
            if (prefs.aiSource == "key") {
            Text(
                stringResource(
                    when {
                        prefs.aiMode == "cloud" && prefs.cloudReady -> R.string.ai_status_cloud
                        prefs.aiMode == "cloud" -> R.string.ai_status_cloud_missing
                        else -> R.string.ai_status_local
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            DropdownField(
                stringResource(R.string.ai_mode), listOf("local" to stringResource(R.string.ai_mode_local), "cloud" to stringResource(R.string.ai_mode_cloud)),
                prefs.aiMode, { scope.launch { vm.setAiMode(it) } },
            )
            if (prefs.aiMode == "cloud") {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.ai_cloud_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(if (prefs.cloudKeySaved) R.string.ai_key_saved else R.string.ai_key)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.ai_advanced)) }
                if (advanced) {
                    TextInput(stringResource(R.string.ai_endpoint), endpoint, { endpoint = it.trim() }, keyboard = KeyboardType.Uri, capitalize = false)
                    Spacer(Modifier.height(8.dp))
                    TextInput(stringResource(R.string.ai_model), model, { model = it.trim() }, capitalize = false)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (!endpoint.startsWith("https://")) { toast(ctx.getString(R.string.ai_endpoint_https)); return@Button }
                        scope.launch {
                            val ok = vm.saveCloud(endpoint, model, apiKey)
                            apiKey = ""
                            toast(ctx.getString(if (ok) R.string.saved else R.string.ai_key_not_saved))
                        }
                    }) { Text(stringResource(R.string.save)) }
                    if (prefs.cloudKeySaved) OutlinedButton(onClick = { ownerCheck(ctx.getString(R.string.owner_check_key)) { scope.launch { vm.clearCloudKey(); toast(ctx.getString(R.string.ai_key_removed)) } } }) { Text(stringResource(R.string.remove_key)) }
                }
            }
                run {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(enabled = !testing, onClick = {
                        testing = true; testResult = ""; checks = emptyList()
                        scope.launch {
                            checks = try { vm.diagnoseAi() } catch (e: Exception) { listOf(com.aistock.assistant.di.Check("Check", false, e.toString().take(200))) }
                            testOk = checks.isNotEmpty() && checks.none { it.ok == false }
                            testResult = if (testOk) ctx.getString(R.string.ai_test_ok) else ""
                            testing = false
                        }
                    }) { Text(stringResource(if (testing) R.string.ai_testing else R.string.ai_test)) }
                    for (c in checks) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            (when (c.ok) { true -> "✓ "; false -> "✗ "; null -> "• " }) + c.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = when (c.ok) { true -> MaterialTheme.colorScheme.primary; false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.onSurfaceVariant },
                        )
                        if (c.detail.isNotBlank()) Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (testResult.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(testResult, style = MaterialTheme.typography.bodyMedium, color = if (testOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        SectionTitle(stringResource(R.string.inventory_rules))
        AppCard(Modifier.fillMaxWidth()) {
            SwitchRow(stringResource(R.string.allow_negative_stock), stringResource(R.string.allow_negative_stock_sub), prefs.allowNegativeStock) { scope.launch { vm.setAllowNegative(it) } }
        }

        SectionTitle(stringResource(R.string.notifications))
        AppCard(Modifier.fillMaxWidth()) {
            SwitchRow(stringResource(R.string.low_stock_alerts), stringResource(R.string.low_stock_alerts_sub), prefs.lowStockAlerts) { on ->
                if (on && Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else scope.launch { vm.setLowStockAlerts(on) }
            }
            DropdownField(
                stringResource(R.string.daily_briefing),
                listOf("-1" to stringResource(R.string.off)) + listOf(7, 8, 9, 10, 20, 21).map { it.toString() to (if (it < 12) "$it AM" else "${it - 12} PM") },
                prefs.briefingHour.toString(), { h -> scope.launch { vm.setBriefingHour(h.toInt()) } },
            )
        }

        SectionTitle(stringResource(R.string.security))
        AppCard(Modifier.fillMaxWidth(), onClick = { pinDialog = true }) {
            Text(stringResource(if (hasPin == true) R.string.change_pin else R.string.set_pin), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (hasPin == true) R.string.pin_is_on else R.string.pin_is_off), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionTitle(stringResource(R.string.data))
        AppCard(Modifier.fillMaxWidth()) {
            SettingsLink(stringResource(R.string.import_export)) { nav.go("data") }
            SettingsLink(stringResource(R.string.backup_restore)) { nav.go("backup") }
            SettingsLink(stringResource(R.string.load_sample_data)) { confirmSample = true }
            SettingsLink(stringResource(R.string.reset_all_data), danger = true) { ownerCheck(ctx.getString(R.string.owner_check_reset)) { confirmReset = true } }
        }

        SectionTitle(stringResource(R.string.about))
        AppCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.version_n, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }

    if (editBusiness) {
        var name by remember { mutableStateOf(business?.name.orEmpty()) }
        var owner by remember { mutableStateOf(business?.ownerName.orEmpty()) }
        var currency by remember { mutableStateOf(prefs.currencyCode) }
        AlertDialog(
            onDismissRequest = { editBusiness = false },
            title = { Text(stringResource(R.string.business_settings)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput(stringResource(R.string.business_name), name, { name = it })
                    TextInput(stringResource(R.string.owner_name), owner, { owner = it })
                    DropdownField(stringResource(R.string.currency), CURRENCIES.map { it.first to (it.first + "  " + it.second.trim()) }, currency, { currency = it })
                }
            },
            confirmButton = {
                Button(enabled = name.isNotBlank() && owner.isNotBlank(), onClick = {
                    scope.launch { vm.saveBusiness(name, owner, currency); toast(ctx.getString(R.string.saved)); editBusiness = false }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editBusiness = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (pinDialog) {
        var current by remember { mutableStateOf("") }
        var fresh by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        val pinOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        AlertDialog(
            onDismissRequest = { pinDialog = false },
            title = { Text(stringResource(if (hasPin == true) R.string.change_pin else R.string.set_pin)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasPin == true) {
                        OutlinedTextField(
                            value = current, onValueChange = { current = it.filter { c -> c.isDigit() }.take(8); error = null }, singleLine = true,
                            label = { Text(stringResource(R.string.current_pin)) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = pinOptions,
                        )
                    }
                    OutlinedTextField(
                        value = fresh, onValueChange = { fresh = it.filter { c -> c.isDigit() }.take(8); error = null }, singleLine = true,
                        label = { Text(stringResource(R.string.new_pin)) }, isError = error != null,
                        supportingText = { Text(error ?: stringResource(if (hasPin == true) R.string.pin_help_remove else R.string.pin_help)) },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = pinOptions,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        if (hasPin == true && !vm.checkPin(current)) { error = ctx.getString(R.string.wrong_pin); return@launch }
                        if (hasPin != true && fresh.isEmpty()) { error = ctx.getString(R.string.pin_help); return@launch }
                        if (vm.setPin(fresh)) {
                            toast(ctx.getString(if (fresh.isEmpty()) R.string.pin_removed else R.string.pin_saved)); pinDialog = false
                        } else error = ctx.getString(R.string.pin_help)
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { pinDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmSample) {
        ConfirmDialog(
            stringResource(R.string.load_sample_data), stringResource(R.string.sample_confirm_body), stringResource(R.string.load),
            onDismiss = { confirmSample = false },
            onConfirm = {
                confirmSample = false
                scope.launch {
                    val r = vm.loadSample()
                    toast(ctx.getString(if (r is Op.Err) errorRes(r.code) else R.string.sample_loaded))
                }
            },
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            stringResource(R.string.reset_all_data), stringResource(R.string.reset_confirm_body), stringResource(R.string.erase_everything), danger = true,
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                scope.launch { vm.resetAll(); (ctx as? Activity)?.recreate() }
            },
        )
    }
}

@Composable
private fun SettingsLink(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}
