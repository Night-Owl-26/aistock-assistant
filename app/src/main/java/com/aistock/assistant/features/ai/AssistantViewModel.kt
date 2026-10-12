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
)

@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val backend: RoomAssistantBackend,
    factory: StockyFactory,
    private val business: BusinessRepository,
    private val connectivity: Connectivity,
) : ViewModel() {
    /** The conversation manager: a real language model with safe tools when configured, on-device rules otherwise. */
    private val stocky = factory.create()

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
        run(voiceExchange, userText = null, state = VoiceState.EXECUTING) { stocky.confirm() }
    }

    fun cancel() {
        if (_busy.value) return
        run(false, userText = null) { stocky.cancel() }
    }

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
