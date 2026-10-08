package com.aistock.assistant.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aistock.assistant.R
import com.aistock.assistant.data.ErrCode

/** Shows a short message at the bottom of the screen. Provided by the app root. */
val LocalToast = compositionLocalOf<(String) -> Unit> { {} }

/** Currency symbol of the business, e.g. ₹. */
val LocalCurrency = compositionLocalOf { "₹" }

fun errorRes(code: String?): Int = when (code) {
    ErrCode.INSUFFICIENT_STOCK -> R.string.err_insufficient_stock
    ErrCode.NOT_FOUND -> R.string.err_not_found
    ErrCode.DUPLICATE -> R.string.err_duplicate
    ErrCode.INVALID -> R.string.err_invalid
    ErrCode.NEEDS_PARTY -> R.string.err_needs_party
    ErrCode.ALREADY_DONE -> R.string.err_already_done
    else -> R.string.err_failed
}

@Composable
fun AppTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** Standard page: top bar, optional floating button, scrolling content that stays above the keyboard. */
@Composable
fun FormPage(
    title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {},
    bottom: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(topBar = { AppTopBar(title, onBack, actions) }, bottomBar = { Box(Modifier.imePadding().navigationBarsPadding()) { bottom() } }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
        )
    }
}

@Composable
fun BottomAction(text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 6.dp) {
        Button(
            onClick = onClick, enabled = enabled,
            colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(52.dp),
        ) { Text(text) }
    }
}

@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, colors = colors, elevation = elevation) { Column(Modifier.padding(padding), content = content) }
    } else {
        Card(modifier = modifier, colors = colors, elevation = elevation) { Column(Modifier.padding(padding), content = content) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
fun TextInput(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, error: String? = null,
    enabled: Boolean = true, suffix: String? = null, capitalize: Boolean = true,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, enabled = enabled,
        isError = error != null, supportingText = if (error != null) ({ Text(error) }) else null,
        suffix = if (suffix != null) ({ Text(suffix) }) else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            capitalization = if (capitalize && keyboard == KeyboardType.Text) KeyboardCapitalization.Words else KeyboardCapitalization.None,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Keeps only digits and one decimal point so money and quantity fields cannot hold junk. */
fun cleanDecimal(input: String, maxDecimals: Int = 3): String {
    val sb = StringBuilder()
    var dot = false
    var decimals = 0
    for (c in input) {
        if (c.isDigit()) {
            if (dot) { if (decimals >= maxDecimals) continue; decimals++ }
            if (sb.length < 14) sb.append(c)
        } else if (c == '.' && !dot) { dot = true; sb.append(if (sb.isEmpty()) "0." else ".") }
    }
    return sb.toString()
}

@Composable
fun NumberInput(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, decimals: Int = 3, suffix: String? = null, error: String? = null) {
    TextInput(label, value, { onChange(cleanDecimal(it, decimals)) }, modifier, KeyboardType.Decimal, suffix = suffix, error = error)
}

/** Makes the tap area of a chooser field readable by TalkBack: "Label: current value", announced as a drop-down. */
fun Modifier.chooser(label: String, value: String): Modifier = this.semantics {
    contentDescription = if (value.isBlank()) label else "$label: $value"
    role = Role.DropdownList
}

/** A read-only field that opens a menu of choices. */
@Composable
fun DropdownField(label: String, options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == selected }?.second ?: ""
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = shown, onValueChange = {}, readOnly = true,
            label = { Text(label) }, singleLine = true,
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        )
        Box(Modifier.matchParentSize().chooser(label, shown).clickable(onClickLabel = label) { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((key, text) in options) {
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(key) })
            }
        }
    }
}

/** A read-only field that opens a searchable list, for picking a product, customer or supplier. */
@Composable
fun PickerField(
    label: String, valueText: String, options: List<Pair<String, String>>, onPick: (String) -> Unit,
    modifier: Modifier = Modifier, onClear: (() -> Unit)? = null, emptyText: String = "",
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = valueText, onValueChange = {}, readOnly = true, label = { Text(label) }, singleLine = true,
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        )
        Box(Modifier.matchParentSize().chooser(label, valueText).clickable(onClickLabel = label) { open = true })
    }
    if (open) {
        var query by remember { mutableStateOf("") }
        val filtered = remember(query, options) { if (query.isBlank()) options else options.filter { it.second.contains(query.trim(), ignoreCase = true) } }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column {
                    SearchField(query, { query = it }, stringResource(R.string.search))
                    Spacer(Modifier.height(8.dp))
                    if (filtered.isEmpty()) {
                        Text(emptyText.ifEmpty { stringResource(R.string.nothing_found) }, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { it.first }) { (id, text) ->
                            Text(
                                text, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clickable { open = false; onPick(id) }.padding(vertical = 14.dp, horizontal = 4.dp),
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.close)) } },
            dismissButton = if (onClear != null) ({ TextButton(onClick = { open = false; onClear() }) { Text(stringResource(R.string.clear)) } }) else null,
        )
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear)) } },
        shape = RoundedCornerShape(28.dp), modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun EmptyState(title: String, subtitle: String = "", icon: Painter? = null, actionText: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionText) }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Small coloured label. Always carries text, never colour alone. */
@Composable
fun Tag(text: String, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(8.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
fun WarnTag(text: String) = Tag(text, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)

@Composable
fun LabelValue(label: String, value: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

/** List row used across the app: title, a line of detail, and something on the right. */
@Composable
fun ListRow(title: String, subtitle: String = "", trailing: String = "", trailingSub: String = "", onClick: (() -> Unit)? = null, badge: @Composable (() -> Unit)? = null) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (badge != null) { Spacer(Modifier.height(6.dp)); badge() }
            }
            if (trailing.isNotEmpty() || trailingSub.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (trailing.isNotEmpty()) Text(trailing, style = MaterialTheme.typography.titleMedium)
                    if (trailingSub.isNotEmpty()) Text(trailingSub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
