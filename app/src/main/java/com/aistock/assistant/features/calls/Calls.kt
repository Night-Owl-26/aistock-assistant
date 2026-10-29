package com.aistock.assistant.features.calls

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aistock.assistant.R
import com.aistock.assistant.ai.PartyKind
import com.aistock.assistant.ai.PartyRef
import com.aistock.assistant.business.BusinessAction
import com.aistock.assistant.business.ContactRef
import com.aistock.assistant.business.RoomBusinessBackend
import com.aistock.assistant.features.work.handOff
import com.aistock.assistant.ui.components.LocalToast
import com.aistock.assistant.ui.components.errorRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Calls to customers and suppliers. The app only opens the phone's dialler (no call permission is needed and nothing
 * is dialled without the owner pressing call). When the owner comes back to Follower, it asks how the call went and
 * keeps that note in the person's history.
 */
object CallTracker {
    data class Pending(val id: String?, val kind: PartyKind?, val name: String, val phone: String, val at: Long)

    val pending = MutableStateFlow<Pending?>(null)

    fun call(context: Context, name: String, phone: String, id: String? = null, kind: PartyKind? = null) {
        if (phone.none { it.isDigit() }) return
        pending.value = Pending(id, kind, name, phone, System.currentTimeMillis())
        handOff(context, "CALL", phone, "")
    }

    /** Back in the app after at least a few seconds in the dialler: time to ask how it went. */
    fun due(now: Long = System.currentTimeMillis()): Pending? = pending.value?.takeIf { now - it.at >= 4_000L }

    fun done() { pending.value = null }
}

@HiltViewModel
class CallViewModel @Inject constructor(private val backend: RoomBusinessBackend) : ViewModel() {
    suspend fun saveNote(p: CallTracker.Pending, note: String): String? {
        val ref = if (p.kind == PartyKind.CUSTOMER && p.id != null) PartyRef(p.id, p.name, p.phone, 0L, PartyKind.CUSTOMER) else null
        val r = backend.execute(BusinessAction.LogContact(ref, p.name, "CALL", note.trim().ifBlank { "Called" }))
        return if (r.ok) null else r.error
    }

    suspend fun history(id: String?, name: String): List<ContactRef> = try { backend.contactsFor(id, name) } catch (e: Exception) { emptyList() }
}

/** A phone button for a list row or a detail screen. */
@Composable
fun CallButton(name: String, phone: String, id: String? = null, kind: PartyKind? = null) {
    if (phone.none { it.isDigit() }) return
    val ctx = LocalContext.current
    IconButton(onClick = { CallTracker.call(ctx, name, phone, id, kind) }) {
        Icon(painterResource(R.drawable.ic_call), contentDescription = stringResource(R.string.call_person, name), tint = MaterialTheme.colorScheme.primary)
    }
}

/** Asks for a short note after a call; placed once at the app root. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallNoteHost(vm: CallViewModel = hiltViewModel()) {
    val owner = LocalLifecycleOwner.current
    val toast = LocalToast.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<CallTracker.Pending?>(null) }
    var note by remember { mutableStateOf("") }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) CallTracker.due()?.let { asking = it; note = "" } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val p = asking ?: return
    val quick = listOf(R.string.cn_will_pay, R.string.cn_ordered, R.string.cn_no_answer, R.string.cn_call_back).map { stringResource(it) }
    AlertDialog(
        onDismissRequest = { asking = null; CallTracker.done() },
        title = { Text(stringResource(R.string.call_note_title, p.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (q in quick) AssistChip(onClick = { note = if (note.isBlank()) q else "$note. $q" }, label = { Text(q) })
                }
                OutlinedTextField(note, { note = it.take(300) }, label = { Text(stringResource(R.string.call_note_label)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    val err = vm.saveNote(p, note)
                    toast(if (err == null) ctx.getString(R.string.call_note_saved) else ctx.getString(errorRes(err)))
                    asking = null; CallTracker.done()
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = { asking = null; CallTracker.done() }) { Text(stringResource(R.string.skip)) } },
    )
}
