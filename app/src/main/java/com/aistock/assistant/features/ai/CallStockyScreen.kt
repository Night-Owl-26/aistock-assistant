package com.aistock.assistant.features.ai

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistock.assistant.R
import com.aistock.assistant.ai.ReplyKind
import com.aistock.assistant.ui.AppNav
import com.aistock.assistant.ui.LocalOwnerCheck
import com.aistock.assistant.voice.Speaker
import com.aistock.assistant.voice.SpeechInput
import com.aistock.assistant.voice.SpeechOutput
import com.aistock.assistant.voice.VoiceRecognizer
import com.aistock.assistant.voice.VoiceState
import kotlinx.coroutines.delay

/** Words that end the call instead of being sent to Stocky. */
private val HANG_UP = setOf("bye", "bye stocky", "goodbye", "stop", "that's all", "thats all", "podhum", "poi varen", "vechudu", "போதும்", "வெச்சுடு", "சரி பை")

/**
 * "Call Stocky": a hands-free conversation laid out like a phone call. Stocky listens, answers aloud, and listens again
 * until the owner hangs up (or stays silent a few times). Changes still need the owner's yes, and money changes a tap
 * on Confirm, exactly as in the chat.
 */
@Composable
fun CallStockyScreen(nav: AppNav, vm: AssistantViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val ownerCheck = LocalOwnerCheck.current
    val prefs by vm.prefsFlow.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val partial by vm.partial.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    val cloudVoice = prefs.cloudSpeech && vm.cloudSpeechReady()
    val recognizer: SpeechInput = remember(cloudVoice) { if (cloudVoice) vm.cloudRecognizer() else VoiceRecognizer(ctx) }
    val speaker: SpeechOutput = remember(cloudVoice) { if (cloudVoice) vm.cloudSpeaker(Speaker(ctx)) else Speaker(ctx) }
    var tamil by remember { mutableStateOf(prefs.tamil) }
    var muted by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var silent by remember { mutableIntStateOf(0) }
    var denied by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }

    fun hangUp() {
        if (ended) return
        ended = true
        recognizer.stop(); speaker.stop()
        nav.back()
    }

    fun listen() {
        if (ended || muted || busy || !recognizer.isAvailable()) return
        speaker.stop()
        recognizer.start(if (tamil) "ta-IN" else "en-IN", object : VoiceRecognizer.Callback {
            override fun onListening() = vm.onListening()
            override fun onPartial(text: String) = vm.onPartial(text)
            override fun onResult(text: String) {
                vm.onVoiceStopped()
                silent = 0
                val said = text.trim().lowercase().trim('.', '!', '?', ' ')
                if (said in HANG_UP) { hangUp(); return }
                if (said.isBlank()) return
                vm.send(text, fromVoice = true)
            }
            override fun onError(code: Int) {
                vm.onVoiceStopped()
                // Silence: keep the line open a few times, then end the call.
                silent++
                if (silent >= 4) hangUp()
            }
        }, quiet = true)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) { started = true; listen() } else denied = true }

    // The screen stays on during the call; background "Hey Stocky" steps aside; leaving the app ends the call.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        com.aistock.assistant.voice.HandsFree.pause(true)
        onDispose { view.keepScreenOn = false; com.aistock.assistant.voice.HandsFree.pause(false); recognizer.stop(); speaker.shutdown() }
    }
    LaunchedEffect(Unit) {
        vm.newConversation()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val hello = ctx.getString(R.string.call_hello)
            speaker.speak(hello, tamil)
            speaker.awaitDone(8_000)
            started = true
            listen()
        } else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) { while (true) { delay(1_000); if (!ended) seconds++ } }
    LaunchedEffect(Unit) {
        vm.speak.collect { text -> if (text.isNotBlank() && !ended) { vm.onSpeaking(true); speaker.speak(text, tamil); speaker.awaitDone(); vm.onSpeaking(false) } }
    }
    LaunchedEffect(Unit) {
        vm.relisten.collect { _ -> delay(400); speaker.awaitDone(); delay(250); listen() }
    }
    // Errors are not re-listened by the chat logic; on a call the line stays open.
    LaunchedEffect(voice) { if (voice == VoiceState.ERROR && !busy) { delay(1_200); listen() } }
    LaunchedEffect(muted) { if (!started) return@LaunchedEffect; if (muted) { recognizer.stop(); vm.onVoiceStopped() } else listen() }

    val lastUser = items.lastOrNull { it.fromUser }?.text.orEmpty()
    val lastReply = items.lastOrNull { !it.fromUser }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.12f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "scale")
    val green = Color(0xFF1B8A5A)

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.inverseSurface).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.call_title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.inverseOnSurface, fontWeight = FontWeight.SemiBold)
        Text("%02d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f))
        Spacer(Modifier.height(28.dp))
        val listening = voice == VoiceState.LISTENING
        Box(
            Modifier.size(132.dp).scale(if (listening || voice == VoiceState.SPEAKING) pulse else 1f).background(if (listening) green else MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_ai), contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                denied -> stringResource(R.string.voice_permission_needed)
                muted -> stringResource(R.string.call_muted)
                voice == VoiceState.LISTENING -> partial.ifBlank { stringResource(R.string.listening) }
                voice == VoiceState.PROCESSING || busy -> stringResource(R.string.understanding)
                voice == VoiceState.SPEAKING -> stringResource(R.string.speaking)
                voice == VoiceState.CONFIRMATION -> stringResource(R.string.waiting_for_answer)
                else -> stringResource(R.string.call_connected)
            },
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.inverseOnSurface, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (lastUser.isNotBlank()) Text("“$lastUser”", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.75f))
            if (lastReply != null && lastReply.text.isNotBlank()) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(lastReply.text, style = MaterialTheme.typography.bodyLarge)
                        for (l in lastReply.lines) Text("${l.label}: ${l.value}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (lastReply.live && lastReply.kind == ReplyKind.CONFIRM) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { if (lastReply.sensitive) ownerCheck(ctx.getString(R.string.confirm)) { vm.confirm() } else vm.confirm() }) { Text(stringResource(R.string.confirm)) }
                            OutlinedButton(onClick = { vm.cancel() }) { Text(stringResource(R.string.cancel)) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundButton(stringResource(if (muted) R.string.call_unmute else R.string.call_mute), if (muted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, R.drawable.ic_mic) { muted = !muted }
            RoundButton(stringResource(R.string.call_hang_up), Color(0xFFD93025), R.drawable.ic_call_end, big = true) { hangUp() }
            RoundButton(if (tamil) "தமிழ்" else "English", MaterialTheme.colorScheme.surfaceVariant, null) { tamil = !tamil }
        }
    }
}

@Composable
private fun RoundButton(label: String, color: Color, icon: Int?, big: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(if (big) 76.dp else 60.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)) {
            Box(contentAlignment = Alignment.Center) {
                if (icon != null) Icon(painterResource(icon), contentDescription = label, tint = if (big) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(if (big) 36.dp else 26.dp))
                else Text(label.take(2), style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.inverseOnSurface)
    }
}
