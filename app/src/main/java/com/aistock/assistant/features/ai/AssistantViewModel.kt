package com.aistock.assistant.features.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistock.assistant.ai.AssistantEngine
import com.aistock.assistant.ai.AssistantReply
import com.aistock.assistant.ai.ChatTurn
import com.aistock.assistant.ai.ConfirmLine
import com.aistock.assistant.ai.ReplyKind
import com.aistock.assistant.ai.LocalFallbackAIProvider
import com.aistock.assistant.conversation.StockyFactory
import com.aistock.assistant.di.LlmSource
import com.aistock.assistant.data.BusinessRepository
import com.aistock.assistant.data.RoomAssistantBackend
import com.aistock.assistant.data.SettingsStore
import com.aistock.assistant.data.newId
import com.aistock.assistant.database.ConversationSummary
import com.aistock.assistant.di.Connectivity
import com.aistock.assistant.voice.VoiceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

data class ChatItem(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val kind: ReplyKind? = null,
    val lines: List<ConfirmLine> = emptyList(),
    val choices: List<String> = emptyList(),
    val undoToken: String? = null,
    val dangerous: Boolean = false,
    /** Buttons on this message can still be used (it is the latest question). */
    val live: Boolean = false,
    /** Confirming needs the owner's fingerprint / phone lock / PIN. */
    val sensitive: Boolean = false,
    val time: Long = System.currentTimeMillis(),
    /** Buttons that open the dialler, WhatsApp or SMS. */
    val actions: List<com.aistock.assistant.ai.ReplyAction> = emptyList(),
    /** Still being spoken in a live voice session. */
    val partial: Boolean = false,
)

@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val backend: RoomAssistantBackend,
    private val factory: StockyFactory,
    private val business: BusinessRepository,
    private val connectivity: Connectivity,
    private val server: com.aistock.assistant.server.ServerApi,
    private val settings: com.aistock.assistant.data.SettingsStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {
    /** The conversation manager: a real language model with safe tools when configured, on-device rules otherwise. */
    private val stocky = factory.create()

    init {
        // Learn what the owner's server offers (for example no real-time voice when it runs on OpenRouter only).
        if (server.signedIn) viewModelScope.launch { try { server.account() } catch (e: Exception) { } }
    }

    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items
    private val _voice = MutableStateFlow(VoiceState.IDLE)
    val voice: StateFlow<VoiceState> = _voice
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _speak = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Sentences to read aloud (only after a spoken command). */
    val speak: SharedFlow<String> = _speak
    private val _relisten = MutableSharedFlow<Int>(extraBufferCapacity = 2)
    /** After a spoken exchange the microphone opens again by itself; the value is the length of the reply being read out. */
    val relisten: SharedFlow<Int> = _relisten
    private val _source = MutableStateFlow("local")
    /** Which engine understood the last command: local or cloud. */
    val source: StateFlow<String> = _source

    val conversations: StateFlow<List<ConversationSummary>> = business.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var conversationId: String? = null
    private var idleJob: Job? = null
    private var loadedConversation: String? = null
    /** The current exchange was started by voice, so its replies are read aloud. */
    private var voiceExchange = false

    fun isOnline(): Boolean = connectivity.isOnline()

    fun newConversation() {
        stocky.reset()
        conversationId = null
        loadedConversation = null
        backend.conversationId = ""
        _items.value = emptyList()
        _voice.value = VoiceState.IDLE
    }

    /** Reopens a saved conversation. Old confirmations are shown but can no longer be pressed. */
    fun load(id: String) {
        if (loadedConversation == id) return
        loadedConversation = id
        viewModelScope.launch {
            val messages = business.messages(id)
            stocky.reset()
            stocky.seed(messages.map { ChatTurn(it.role, it.text) })
            conversationId = id
            backend.conversationId = id
            _items.value = messages.map { m ->
                val meta = try { JSONObject(m.metadata.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
                val lines = ArrayList<ConfirmLine>()
                meta.optJSONArray("lines")?.let { arr -> for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> lines.add(ConfirmLine(o.optString("l"), o.optString("v"))) } }
                ChatItem(m.id, m.role == "user", m.text, meta.optString("kind").takeIf { it.isNotEmpty() }?.let { k -> ReplyKind.entries.firstOrNull { it.name == k } }, lines, time = m.createdAt)
            }
        }
    }

    fun renameConversation(id: String, title: String) {
        val t = title.trim()
        if (t.isNotEmpty()) viewModelScope.launch { business.renameConversation(id, t) }
    }

    fun onSpeaking(on: Boolean) {
        if (on) _voice.value = VoiceState.SPEAKING else if (_voice.value == VoiceState.SPEAKING) _voice.value = VoiceState.IDLE
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            business.deleteConversation(id)
            if (conversationId == id) newConversation()
        }
    }

    // ------------------------------------------------------------ voice state from the screen

    fun onListening() { idleJob?.cancel(); _partial.value = ""; _voice.value = VoiceState.LISTENING }
    fun onPartial(text: String) { _partial.value = text }
    fun onVoiceStopped() { if (_voice.value == VoiceState.LISTENING) _voice.value = VoiceState.IDLE; _partial.value = "" }

    fun onVoiceError(message: String) {
        _partial.value = ""
        _voice.value = VoiceState.ERROR
        append(ChatItem(newId(), false, message, ReplyKind.ERROR))
        settle()
    }

    // ------------------------------------------------------------ actions

    fun send(text: String, fromVoice: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() || _busy.value) return
        voiceExchange = fromVoice
        run(fromVoice, userText = t) { stocky.handle(t) }
    }

    fun confirm() {
        if (_busy.value) return
        _voice.value = VoiceState.EXECUTING
        run(voiceExchange && live == null, userText = null, state = VoiceState.EXECUTING) {
            stocky.confirm().also { r -> live?.tell("(The owner pressed Confirm on the screen. Result from the app: ${r.text}) Tell them in one short sentence.") }
        }
    }

    fun cancel() {
        if (_busy.value) return
        run(false, userText = null) { stocky.cancel().also { live?.tell("(The owner pressed Cancel on the screen. Nothing was saved.) Acknowledge briefly.") } }
    }

    /** Logs a call or message the owner started from a button (only that it was opened, never that it was delivered). */
    fun handedOff(a: com.aistock.assistant.ai.ReplyAction) {
        if (a.kind == "SHARE") return
        viewModelScope.launch {
            try {
                factory.platform.execute(com.aistock.assistant.business.BusinessAction.LogContact(null, a.person.ifBlank { a.phone }, if (a.kind == "CALL") "CALL" else a.kind,
                    if (a.kind == "CALL") "Call started from Stocky" else "Message opened in ${a.kind.lowercase()}: " + a.text.take(120)))
            } catch (e: Exception) { }
        }
    }

    // ------------------------------------------------------------ real-time voice

    private var live: com.aistock.assistant.voice.live.LiveSession? = null
    private var liveJob: Job? = null
    private val _liveState = MutableStateFlow<com.aistock.assistant.voice.live.LiveState?>(null)
    /** null when no live voice session is running. */
    val liveState: StateFlow<com.aistock.assistant.voice.live.LiveState?> = _liveState

    /** Real-time voice is used when the owner is signed in to their AIStock server and has it switched on. */
    fun liveAvailable(): Boolean {
        val p = settings.current.value
        return p.liveVoice && !p.cloudSpeech && p.aiSource == "server" && server.signedIn && server.liveReady && connectivity.isOnline()
    }

    // ------------------------------------------------------------ Google Cloud speech (turn by turn)

    /** Google Cloud Speech-to-Text and Text-to-Speech go through the owner's server, so they need a sign-in. */
    fun cloudSpeechReady(): Boolean {
        val p = settings.current.value
        return p.cloudSpeech && p.aiSource == "server" && server.signedIn
    }

    fun cloudRecognizer(): com.aistock.assistant.voice.SpeechInput = com.aistock.assistant.voice.cloud.CloudRecognizer(server) { speechHints() }

    fun cloudSpeaker(fallback: com.aistock.assistant.voice.SpeechOutput): com.aistock.assistant.voice.SpeechOutput = com.aistock.assistant.voice.cloud.CloudSpeaker(server, fallback)

    /** Names the recogniser should expect: products (with sizes), customers and suppliers. */
    private suspend fun speechHints(): List<String> =
        (backend.products().map { it.name } + backend.parties(com.aistock.assistant.ai.PartyKind.CUSTOMER).map { it.name } + backend.parties(com.aistock.assistant.ai.PartyKind.SUPPLIER).map { it.name })
            .distinct().take(500)

    fun startLive() {
        if (live != null) return
        val s = com.aistock.assistant.voice.live.LiveSession(appContext, server, stocky, viewModelScope)
        live = s
        var userId: String? = null
        var stockyId: String? = null
        liveJob = viewModelScope.launch {
            launch { s.state.collect { st -> _liveState.value = if (st == com.aistock.assistant.voice.live.LiveState.ENDED) null else st } }
            s.updates.collect { u ->
                when (u) {
                    is com.aistock.assistant.voice.live.LiveUpdate.UserSpeech -> {
                        val id = userId ?: newId().also { userId = it; append(ChatItem(it, true, u.text, partial = true)) }
                        replace(id) { it.copy(text = u.text, partial = !u.final) }
                        if (u.final) userId = null
                    }
                    is com.aistock.assistant.voice.live.LiveUpdate.StockySpeech -> {
                        val id = stockyId ?: newId().also { stockyId = it; append(ChatItem(it, false, u.text, ReplyKind.ANSWER, partial = true)) }
                        replace(id) { it.copy(text = u.text, partial = !u.final) }
                        if (u.final) {
                            stockyId = null
                            persist(_items.value.lastOrNull { it.fromUser && !it.partial }?.text, AssistantReply(u.text, ReplyKind.ANSWER))
                        }
                    }
                    is com.aistock.assistant.voice.live.LiveUpdate.Card -> {
                        val r = u.reply
                        _items.value = _items.value.map { if (it.live) it.copy(live = false) else it }
                        append(ChatItem(newId(), false, r.text, r.kind, r.lines, r.choices, r.undoToken, r.dangerous,
                            live = r.kind == ReplyKind.CONFIRM, sensitive = r.kind == ReplyKind.CONFIRM && stocky.pendingSensitive))
                        persist(null, r)
                    }
                    is com.aistock.assistant.voice.live.LiveUpdate.Actions -> append(ChatItem(newId(), false, "", ReplyKind.ANSWER, actions = u.actions))
                    is com.aistock.assistant.voice.live.LiveUpdate.Ended -> {
                        if (u.reason !in setOf("user", "idle", "time")) append(ChatItem(newId(), false, u.reason, ReplyKind.ERROR))
                        else if (u.reason == "idle" || u.reason == "time") append(ChatItem(newId(), false, appContext.getString(com.aistock.assistant.R.string.live_ended), ReplyKind.ANSWER))
                        live = null
                        _liveState.value = null
                    }
                }
            }
        }
        s.start()
    }

    fun stopLive() {
        live?.stop("user")
        live = null
        _liveState.value = null
    }

    override fun onCleared() { live?.stop("user"); super.onCleared() }

    private fun replace(id: String, f: (ChatItem) -> ChatItem) { _items.value = _items.value.map { if (it.id == id) f(it) else it } }

    fun undo(token: String) {
        if (_busy.value) return
        _items.value = _items.value.map { if (it.undoToken == token) it.copy(undoToken = null) else it }
        run(false, userText = null) { stocky.undo(token) }
    }

    private fun run(fromVoice: Boolean, userText: String?, state: VoiceState = VoiceState.PROCESSING, block: suspend () -> AssistantReply) {
        idleJob?.cancel()
        _busy.value = true
        _partial.value = ""
        _voice.value = state
        // Earlier questions can no longer be answered with their buttons.
        _items.value = _items.value.map { if (it.live) it.copy(live = false) else it }
        if (userText != null) append(ChatItem(newId(), true, userText))
        viewModelScope.launch {
            val reply = try {
                block()
            } catch (e: Exception) {
                AssistantReply("", ReplyKind.ERROR)
            }
            _source.value = stocky.lastSource
            val item = ChatItem(
                newId(), false, reply.text, reply.kind, reply.lines, reply.choices, reply.undoToken, reply.dangerous,
                live = reply.kind == ReplyKind.CONFIRM || reply.kind == ReplyKind.CLARIFY,
                sensitive = reply.kind == ReplyKind.CONFIRM && stocky.pendingSensitive,
                actions = reply.actions,
            )
            append(item)
            _busy.value = false
            _voice.value = when (reply.kind) {
                ReplyKind.CONFIRM, ReplyKind.CLARIFY -> VoiceState.CONFIRMATION
                ReplyKind.DONE -> VoiceState.SUCCESS
                ReplyKind.ERROR -> VoiceState.ERROR
                else -> VoiceState.IDLE
            }
            if (fromVoice) {
                _speak.tryEmit(reply.spoken)
                if (reply.kind != ReplyKind.ERROR) _relisten.tryEmit(reply.spoken.length)
            }
            if (_voice.value == VoiceState.SUCCESS || _voice.value == VoiceState.ERROR) settle()
            persist(userText, reply)
        }
    }

    private fun settle() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(2_500)
            if (_voice.value == VoiceState.SUCCESS || _voice.value == VoiceState.ERROR) _voice.value = VoiceState.IDLE
        }
    }

    private fun append(item: ChatItem) { _items.value = _items.value + item }

    private suspend fun persist(userText: String?, reply: AssistantReply) {
        try {
            var id = conversationId
            // The chat may have been deleted from the history list meanwhile: then start a new one.
            if (id != null && !business.conversationExists(id)) id = null
            if (id == null) {
                id = business.newConversation(userText ?: reply.text)
                conversationId = id
                loadedConversation = id
                backend.conversationId = id
            }
            if (userText != null) business.addMessage(id, "user", userText, "", "", null)
            val meta = JSONObject().put("kind", reply.kind.name)
            if (reply.lines.isNotEmpty()) {
                val arr = JSONArray()
                reply.lines.forEach { arr.put(JSONObject().put("l", it.label).put("v", it.value)) }
                meta.put("lines", arr)
            }
            business.addMessage(id, "assistant", reply.text, reply.intent.name, meta.toString(), null)
        } catch (e: Exception) {
            // Saving the chat history is best effort; the business action itself is already complete.
        }
    }
}
