package com.aistock.assistant.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.aistock.assistant.R
import kotlinx.coroutines.launch

/**
 * Asks the owner to prove it is them before a sensitive action, then runs it.
 * Usage: LocalOwnerCheck.current(reason) { doTheThing() }
 */
val LocalOwnerCheck = staticCompositionLocalOf<(String, () -> Unit) -> Unit> { { _, action -> action() } }

/**
 * Uses the phone's own lock screen (fingerprint, face, PIN, pattern) when the phone has one;
 * otherwise the app PIN when one is set; otherwise lets the action through.
 */
@Composable
fun OwnerCheckHost(enabled: Boolean, hasAppPin: Boolean, checkPin: suspend (String) -> Boolean, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pinAsk by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val title = stringResource(R.string.owner_check_title)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = waiting
        waiting = null
        if (result.resultCode == Activity.RESULT_OK) action?.invoke()
    }

    val check: (String, () -> Unit) -> Unit = remember(enabled, hasAppPin) {
        { reason, action ->
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            @Suppress("DEPRECATION")
            val intent = if (km != null && km.isDeviceSecure) km.createConfirmDeviceCredentialIntent(title, reason) else null
            when {
                !enabled -> action()
                intent != null -> { waiting = action; try { launcher.launch(intent) } catch (e: Exception) { waiting = null; if (hasAppPin) { waiting = action; pinAsk = reason } else action() } }
                hasAppPin -> { waiting = action; pin = ""; wrong = false; pinAsk = reason }
                else -> action()
            }
        }
    }

    CompositionLocalProvider(LocalOwnerCheck provides check) { content() }

    pinAsk?.let { reason ->
        AlertDialog(
            onDismissRequest = { pinAsk = null; waiting = null },
            title = { Text(title) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(reason, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = pin, onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8); wrong = false }, singleLine = true,
                        label = { Text(stringResource(if (wrong) R.string.wrong_pin else R.string.enter_pin)) }, isError = wrong,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        if (checkPin(pin)) { val a = waiting; waiting = null; pinAsk = null; a?.invoke() } else wrong = true
                    }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { pinAsk = null; waiting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
