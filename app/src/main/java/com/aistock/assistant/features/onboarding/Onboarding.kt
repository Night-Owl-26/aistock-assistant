package com.aistock.assistant.features.onboarding

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aistock.assistant.R
import com.aistock.assistant.features.settings.CURRENCIES
import com.aistock.assistant.features.settings.MainViewModel
import com.aistock.assistant.ui.components.DropdownField
import com.aistock.assistant.ui.components.TextInput
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(vm: MainViewModel) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var business by rememberSaveable { mutableStateOf("") }
    var owner by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("INR") }
    var language by rememberSaveable { mutableStateOf("en") }
    var pin by rememberSaveable { mutableStateOf("") }
    var sample by rememberSaveable { mutableStateOf(false) }
    var businessError by remember { mutableStateOf<String?>(null) }
    var ownerError by remember { mutableStateOf<String?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            for (i in 0..3) {
                Box(
                    Modifier.padding(horizontal = 4.dp).height(6.dp).width(if (i == page) 28.dp else 10.dp)
                        .background(if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (page < 3) {
                val (icon, title, body) = when (page) {
                    0 -> Triple(R.drawable.ic_inventory, R.string.onb_title_1, R.string.onb_body_1)
                    1 -> Triple(R.drawable.ic_mic, R.string.onb_title_2, R.string.onb_body_2)
                    else -> Triple(R.drawable.ic_report, R.string.onb_title_3, R.string.onb_body_3)
                }
                Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = CircleShape, modifier = Modifier.size(120.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(56.dp)) }
                }
                Spacer(Modifier.height(32.dp))
                Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.business_setup), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.business_setup_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextInput(stringResource(R.string.business_name), business, { business = it; businessError = null }, error = businessError)
                    TextInput(stringResource(R.string.owner_name), owner, { owner = it; ownerError = null }, error = ownerError)
                    DropdownField(stringResource(R.string.currency), CURRENCIES.map { it.first to (it.first + "  " + it.second.trim()) }, currency, { currency = it })
                    DropdownField(stringResource(R.string.language), listOf("en" to "English", "ta" to "தமிழ்"), language, { language = it })
                    OutlinedTextField(
                        value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8); pinError = null },
                        label = { Text(stringResource(R.string.pin_optional)) }, singleLine = true, isError = pinError != null,
                        supportingText = { Text(pinError ?: stringResource(R.string.pin_help)) },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().toggleable(value = sample, role = Role.Checkbox, onValueChange = { sample = it }).padding(vertical = 8.dp),
                    ) {
                        Checkbox(checked = sample, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.load_sample_data), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.load_sample_data_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (page > 0) TextButton(onClick = { page-- }) { Text(stringResource(R.string.back)) }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = !saving, modifier = Modifier.height(52.dp),
                onClick = {
                    if (page < 3) { page++; return@Button }
                    if (business.isBlank()) { businessError = ctx.getString(R.string.name_required); return@Button }
                    if (owner.isBlank()) { ownerError = ctx.getString(R.string.name_required); return@Button }
                    saving = true
                    scope.launch {
                        val ok = vm.finishOnboarding(business, owner, currency, language, pin, sample)
                        saving = false
                        if (!ok) pinError = ctx.getString(R.string.pin_help)
                        else if (language != "en") (ctx as? Activity)?.recreate()
                    }
                },
            ) { Text(stringResource(if (page < 3) R.string.next else R.string.get_started_button)) }
        }
    }
}

@Composable
fun LockScreen(vm: MainViewModel) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = CircleShape, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(40.dp)) }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.enter_pin), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8); error = false },
            label = { Text(stringResource(R.string.pin)) }, singleLine = true, isError = error,
            supportingText = if (error) ({ Text(stringResource(R.string.wrong_pin)) }) else null,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = pin.length >= 4, modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = { scope.launch { if (!vm.unlock(pin)) { error = true; pin = "" } } },
        ) { Text(stringResource(R.string.unlock)) }
    }
}
