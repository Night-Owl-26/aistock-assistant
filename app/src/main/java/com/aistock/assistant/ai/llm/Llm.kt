package com.aistock.assistant.ai.llm

/** One argument a tool accepts. type is "string", "number" or "boolean". */
data class ToolParam(val name: String, val type: String, val description: String, val required: Boolean = false, val options: List<String> = emptyList())

/** A safe application function the model may ask the app to run. The model never touches the database itself. */
data class ToolSpec(val name: String, val description: String, val params: List<ToolParam> = emptyList())

/** The model's request to run a tool. Argument values arrive as text, whatever their JSON type was. */
data class ToolCall(val id: String, val name: String, val args: Map<String, String>)

sealed class LlmMessage {
    data class System(val text: String) : LlmMessage()
    data class User(val text: String) : LlmMessage()
    data class Assistant(val text: String, val toolCalls: List<ToolCall> = emptyList()) : LlmMessage()
    data class ToolResult(val callId: String, val name: String, val text: String) : LlmMessage()
}

data class LlmReply(val text: String, val toolCalls: List<ToolCall> = emptyList())

/**
 * A conversational language model with tool calling. Implementations talk to a real service;
 * the rest of the app only knows this interface, so the vendor can be swapped.
 */
interface LlmClient {
    val name: String
    /** @throws Exception when the service cannot be reached or answers with an error */
    suspend fun chat(messages: List<LlmMessage>, tools: List<ToolSpec>): LlmReply
}
