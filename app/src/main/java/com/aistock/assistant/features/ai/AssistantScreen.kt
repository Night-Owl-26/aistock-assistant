package com.aistock.assistant.features.ai

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.aistock.assistant.R
import com.aistock.assistant.ai.ReplyKind
import com.aistock.assistant.data.Prefs
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.components.AppTopBar
import com.aistock.assistant.ui.components.ConfirmDialog
import com.aistock.assistant.ui.components.EmptyState
import com.aistock.assistant.ui.components.LabelValue
import com.aistock.assistant.ui.components.ListRow
import com.aistock.assistant.ui.components.Tag
import com.aistock.assistant.ui.fmtDateTime
import com.aistock.assistant.voice.Speaker
import com.aistock.assistant.voice.VoiceRecognizer
import com.aistock.assistant.voice.VoiceState

@Composable
fun AssistantScreen(nav: AppNav, prefs: Prefs, conversationId: String?, startListening: Boolean, vm: AssistantViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val partial by vm.partial.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val liveState by vm.liveState.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // True while the microphone was reopened automatically to continue a spoken conversation.
    var autoListening by remember { mutableStateOf(false) }

    val recognizer = remember { VoiceRecognizer(ctx) }
    val speaker = remember { Speaker(ctx) }
    DisposableEffect(Unit) { onDispose { recognizer.stop(); speaker.shutdown() } }

    val msgNoSpeech = stringResource(R.string.voice_not_heard)
    val msgNoService = stringResource(R.string.voice_unavailable)
    val msgNoPermission = stringResource(R.string.voice_permission_needed)
    val msgNetwork = stringResource(R.string.voice_network)
    // Which language the microphone listens for. It follows the app language until the owner taps the chip.
    var voiceTamilChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val voiceTamil = voiceTamilChoice ?: prefs.tamil
    val languageTag = if (voiceTamil) "ta-IN" else "en-IN"
    // True when the owner pressed the microphone, false when it reopened by itself.
    var manualMic by remember { mutableStateOf(false) }

    fun startVoice() {
        val quiet = autoListening
        if (!recognizer.isAvailable()) { vm.onVoiceError(msgNoService); return }
        speaker.stop()
        recognizer.start(languageTag, object : VoiceRecognizer.Callback {
            override fun onListening() = vm.onListening()
            override fun onPartial(text: String) = vm.onPartial(text)
            override fun onResult(text: String) {
                autoListening = false
                vm.onVoiceStopped()
                // "Stop" or "Bye Stocky" ends the voice conversation instead of being sent as a command.
                val said = text.trim().lowercase().trim('.', '!', ' ')
                if (said in setOf("stop", "stop listening", "bye", "bye stocky", "goodbye", "thats all", "that's all", "podhum", "poi varen", "போதும்", "நிறுத்து")) { speaker.stop(); return }
                vm.send(text, fromVoice = true)
            }
            override fun onError(code: Int) {
                vm.onVoiceStopped()
                // Silence after an automatic re-listen just ends the spoken conversation quietly.
                val wasManual = manualMic && !autoListening
                autoListening = false
                manualMic = false
                // The phone often reports an error twice, or after the microphone reopened by itself. None of that belongs in the chat.
                if (!wasManual) return
                val serious = code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || code == SpeechRecognizer.ERROR_NETWORK ||
                    code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || code == SpeechRecognizer.ERROR_SERVER
                if (!serious) {
                    if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) android.widget.Toast.makeText(ctx, msgNoSpeech, android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                vm.onVoiceError(
                    when (code) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> msgNoPermission
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> msgNetwork
                        SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> msgNoService
                        else -> msgNoSpeech
                    }
                )
            }
        }, quiet)
        vm.onListening()
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.onVoiceError(msgNoPermission) else if (vm.liveAvailable()) vm.startLive() else startVoice()
    }
    // While the chat has the microphone, background "Hey Stocky" listening steps aside.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var paused = false
        fun set(on: Boolean) { if (on != paused) { paused = on; com.aistock.assistant.voice.HandsFree.pause(on) } }
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> set(true)
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> { set(false); recognizer.stop(); speaker.stop() }
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) set(true)
        onDispose { lifecycle.removeObserver(obs); set(false) }
    }

    fun micPressed() {
        autoListening = false
        manualMic = true
        // Real-time conversation: one tap starts it, the next tap ends it.
        if (vm.liveState.value != null) { vm.stopLive(); return }
        if (vm.liveAvailable()) {
            speaker.stop(); recognizer.stop()
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startLive()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (voice == VoiceState.LISTENING) { recognizer.stop(); vm.onVoiceStopped(); return }
        if (busy) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(conversationId) { if (conversationId != null) vm.load(conversationId) }
    LaunchedEffect(startListening) {
        if (startListening && !autoStarted) { autoStarted = true; micPressed() }
    }
    LaunchedEffect(Unit) {
        vm.speak.collect { text ->
            if (prefs.ttsEnabled && text.isNotBlank()) {
                vm.onSpeaking(true)
                speaker.speak(text, prefs.tamil)
                launch { speaker.awaitDone(); vm.onSpeaking(false) }
            }
        }
    }
    LaunchedEffect(Unit) {
        vm.relisten.collect { _ ->
            // Wait until the reply has really been read out, then listen again so the user does not have to press the button each time.
            if (prefs.ttsEnabled) speaker.awaitDone() else delay(600L)
            val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (granted && !vm.busy.value && vm.voice.value != VoiceState.LISTENING && recognizer.isAvailable()) {
                autoListening = true
                startVoice()
            }
        }
    }
    LaunchedEffect(items.size, busy) { if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1 + if (busy) 1 else 0) }

    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.assistant_title), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.tell_me_what_happened), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { nav.go("conversations") }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.conversation_history)) }
                    IconButton(onClick = { recognizer.stop(); vm.newConversation() }) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_conversation)) }
                }
                Spacer(Modifier.height(6.dp))
                val online = remember(items.size) { vm.isOnline() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag(
                    when {
                        prefs.aiMode == "cloud" && prefs.cloudReady && online -> stringResource(if (source == "cloud") R.string.ai_cloud_active else R.string.ai_cloud_ready)
                        prefs.aiMode == "cloud" && prefs.cloudReady -> stringResource(R.string.ai_offline_local)
                        !online -> stringResource(R.string.ai_offline_local)
                        else -> stringResource(R.string.ai_local)
                    }
                )
                androidx.compose.material3.AssistChip(
                    onClick = { voiceTamilChoice = !voiceTamil },
                    label = { Text(stringResource(if (voiceTamil) R.string.voice_lang_ta else R.string.voice_lang_en)) },
                )
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 8.dp, modifier = Modifier.imePadding()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val status = if (liveState != null) when (liveState) {
                        com.aistock.assistant.voice.live.LiveState.CONNECTING -> stringResource(R.string.live_connecting)
                        com.aistock.assistant.voice.live.LiveState.RECONNECTING -> stringResource(R.string.live_reconnecting)
                        com.aistock.assistant.voice.live.LiveState.LISTENING -> stringResource(R.string.live_listening)
                        com.aistock.assistant.voice.live.LiveState.THINKING -> stringResource(R.string.understanding)
                        com.aistock.assistant.voice.live.LiveState.SPEAKING -> stringResource(R.string.live_speaking)
                        else -> stringResource(R.string.try_again)
                    } else when (voice) {
                        VoiceState.LISTENING -> partial.ifBlank { stringResource(R.string.listening) }
                        VoiceState.PROCESSING -> stringResource(R.string.understanding)
                        VoiceState.SPEAKING -> stringResource(R.string.speaking)
                        VoiceState.EXECUTING -> stringResource(R.string.saving)
                        VoiceState.CONFIRMATION -> stringResource(R.string.waiting_for_answer)
                        VoiceState.SUCCESS -> stringResource(R.string.done)
                        VoiceState.ERROR -> stringResource(R.string.try_again)
                        VoiceState.IDLE -> stringResource(R.string.tap_mic_or_type)
                    }
                    Text(
                        status, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (voice == VoiceState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it.take(500) }, placeholder = { Text(stringResource(R.string.type_here)) },
                            maxLines = 3, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank() && !busy) { vm.send(input); input = "" } }),
                        )
                        Spacer(Modifier.width(8.dp))
                        if (input.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = CircleShape,
                                modifier = Modifier.size(60.dp).clickable(enabled = !busy, role = Role.Button) { vm.send(input); input = "" },
                            ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send)) } }
                        } else {
                            val listening = voice == VoiceState.LISTENING || liveState != null
                            Surface(
                                color = if (listening) MaterialTheme.colorScheme.error else Color(0xFFFFC857),
                                contentColor = if (listening) MaterialTheme.colorScheme.onError else Color(0xFF2C1A00),
                                shape = CircleShape, shadowElevation = 6.dp,
                                modifier = Modifier.size(68.dp).clickable(role = Role.Button, onClickLabel = stringResource(if (listening) R.string.stop_listening else R.string.speak)) { micPressed() },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(painterResource(if (listening) R.drawable.ic_stop else R.drawable.ic_mic), contentDescription = stringResource(if (listening) R.string.stop_listening else R.string.speak), modifier = Modifier.size(32.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (items.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.try_saying), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val examples = listOf(R.string.example_1, R.string.example_2, R.string.example_3, R.string.example_4, R.string.example_5, R.string.example_6)
                for (e in examples) {
                    val text = stringResource(e)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp), shadowElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { vm.send(text) },
                    ) { Text("“$text”", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) }
                }
            }
        } else {
            LazyColumn(
                state = listState, modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { item -> Bubble(item, busy, vm) }
                if (busy) item(key = "typing") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(if (voice == VoiceState.EXECUTING) R.string.saving else R.string.understanding), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(item: ChatItem, busy: Boolean, vm: AssistantViewModel) {
    val ownerCheck = com.aistock.assistant.ui.LocalOwnerCheck.current
    val reason = stringResource(R.string.owner_check_confirm_change)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (item.fromUser) Arrangement.End else Arrangement.Start) {
        val shape = if (item.fromUser) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
        val error = item.kind == ReplyKind.ERROR
        Surface(
            color = when {
                item.fromUser -> MaterialTheme.colorScheme.primary
                error -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            },
            contentColor = when {
                item.fromUser -> MaterialTheme.colorScheme.onPrimary
                error -> MaterialTheme.colorScheme.onErrorContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
            shape = shape, shadowElevation = if (item.fromUser) 0.dp else 1.dp, modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (item.kind == ReplyKind.DONE) { Tag(stringResource(R.string.done)); Spacer(Modifier.height(6.dp)) }
                if (item.text.isNotBlank()) Text(item.text, style = MaterialTheme.typography.bodyLarge, fontStyle = if (item.partial) androidx.compose.ui.text.font.FontStyle.Italic else null)
                if (item.actions.isNotEmpty()) {
                    val c = LocalContext.current
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        for (a in item.actions) {
                            if (a.text.isNotBlank() && a.kind != "CALL" && a === item.actions.first()) Text(a.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                            Button(onClick = { com.aistock.assistant.features.work.handOff(c, a.kind, a.phone, a.text); vm.handedOff(a) }, modifier = Modifier.fillMaxWidth()) { Text(a.label) }
                        }
                        Text(stringResource(R.string.not_sent_note), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(item.time)),
                    style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = 0.6f),
                    modifier = Modifier.align(if (item.fromUser) Alignment.End else Alignment.Start).padding(top = 4.dp),
                )
                if (item.lines.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    for (l in item.lines) LabelValue(l.label, l.value)
                }
                if (item.kind == ReplyKind.CONFIRM && item.live) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy, onClick = { if (item.sensitive || item.dangerous) ownerCheck(reason) { vm.confirm() } else vm.confirm() }, modifier = Modifier.weight(1f).height(48.dp),
                            colors = if (item.dangerous) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                        ) { Text(stringResource(R.string.confirm)) }
                        OutlinedButton(enabled = !busy, onClick = { vm.cancel() }, modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.cancel)) }
                    }
                }
                if (item.kind == ReplyKind.CLARIFY && item.live && item.choices.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in item.choices) OutlinedButton(enabled = !busy, onClick = { vm.send(c) }, modifier = Modifier.fillMaxWidth()) { Text(c, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (item.undoToken != null) {
                    TextButton(enabled = !busy, onClick = { ownerCheck(reason) { vm.undo(item.undoToken) } }) { Text(stringResource(R.string.undo)) }
                }
            }
        }
    }
}

@Composable
fun ConversationsScreen(nav: AppNav, vm: AssistantViewModel = hiltViewModel()) {
    val all by vm.conversations.collectAsStateWithLifecycle()
    var delete by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var rename by remember { mutableStateOf<Pair<String, String>?>(null) }
    val list = if (query.isBlank()) all else all.filter { c -> c.conversation.title.contains(query.trim(), true) || c.lastText.orEmpty().contains(query.trim(), true) }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.conversation_history), nav.back) }) { padding ->
        if (all.isEmpty()) {
            EmptyState(stringResource(R.string.no_conversations), stringResource(R.string.no_conversations_body), painterResource(R.drawable.ic_ai), modifier = Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "search") {
                    OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.search_conversations)) })
                }
                items(list, key = { it.conversation.id }) { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            ListRow(
                                title = c.conversation.title.ifBlank { stringResource(R.string.conversation) },
                                subtitle = fmtDateTime(c.conversation.updatedAt) + " · " + stringResource(R.string.n_messages, c.messageCount),
                                onClick = { nav.go("ai?conv=${c.conversation.id}") },
                            )
                        }
                        IconButton(onClick = { rename = c.conversation.id to c.conversation.title }) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.rename)) }
                        IconButton(onClick = { delete = c.conversation.id }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
    rename?.let { (id, old) ->
        var title by remember(id) { mutableStateOf(old) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(title, { title = it.take(60) }, singleLine = true) },
            confirmButton = { Button(onClick = { vm.renameConversation(id, title); rename = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    delete?.let { id ->
        ConfirmDialog(
            stringResource(R.string.delete_conversation_title), stringResource(R.string.delete_conversation_body), stringResource(R.string.delete), danger = true,
            onDismiss = { delete = null }, onConfirm = { vm.deleteConversation(id); delete = null },
        )
    }
}
