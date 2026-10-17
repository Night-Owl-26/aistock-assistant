package com.aistock.assistant.ai.llm

/** One argument a tool accepts. type is "string", "number" or "boolean". */
data class ToolParam(val name: String, val type: String, val description: String, val required: Boolean = false, val options: List<String> = emptyList())

/** A safe application function the model may ask the app to run. The model never touches the database itself. */
data class ToolSpec(val name: String, val description: String, val params: List<ToolParam> = emptyList())

/** The model's request to run a tool. Argument values arrive as text, whatever their JSON type was. */
data class ToolCall(val id: String, val name: String, val args: Map<String, String>, val signature: String = "")

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

/** Turns a failed model call into a sentence the owner can act on. */
object LlmProblem {
    fun explain(e: Throwable, online: Boolean = true, tamil: Boolean = false): String {
        fun t(en: String, ta: String) = if (tamil) ta else en
        if (e is LlmException) {
            val d = e.detail.replace("\n", " ").trim().take(160)
            val tail = if (d.isNotBlank()) " ($d)" else ""
            return when (e.status) {
                400, 401, 403 -> if (d.contains("key", true) || e.status != 400) t("Google did not accept the AI key. Please check the key in More > Settings > AI Provider.", "AI key-ஐ Google ஏற்கவில்லை. மேலும் > அமைப்புகள் > AI வழங்குநர்-ல் key-ஐ சரிபார்க்கவும்.") + tail
                    else t("The AI service rejected the request.", "AI சேவை கோரிக்கையை நிராகரித்தது.") + tail
                402 -> d.ifBlank { t("Your AIStock plan has ended.", "உங்கள் AIStock திட்டம் முடிந்தது.") }
                404 -> t("The AI model name was not found.", "AI model பெயர் கிடைக்கவில்லை.") + tail
                429 -> if (d.contains("month", true) || d.contains("minutes", true)) d else t("The AI key has reached its usage limit for now. Try again in a minute.", "AI key-யின் பயன்பாட்டு வரம்பு முடிந்தது. ஒரு நிமிடம் கழித்து முயற்சிக்கவும்.") + tail
                else -> t("The AI service had a problem (code ${e.status}).", "AI சேவையில் சிக்கல் (code ${e.status}).") + tail
            }
        }
        if (!online) return t("There is no internet connection, so the AI could not be reached.", "இணைய இணைப்பு இல்லை; AI-ஐ அணுக முடியவில்லை.")
        if (e is java.net.UnknownHostException) return t(
            "The phone is online but could not find Google's AI server (generativelanguage.googleapis.com). This is usually Private DNS, an ad-blocker or a VPN blocking Google. Set Settings > Network > Private DNS to Automatic or Off, turn off ad-blocker/VPN apps, or switch between Wi-Fi and mobile data, then try again.",
            "போன் இணையத்தில் உள்ளது, ஆனால் Google AI சர்வரை (generativelanguage.googleapis.com) கண்டுபிடிக்க முடியவில்லை. பொதுவாக Private DNS, ad-blocker அல்லது VPN Google-ஐ தடுக்கிறது. Settings > Network > Private DNS-ஐ Automatic அல்லது Off ஆக்குங்கள், ad-blocker/VPN-ஐ நிறுத்துங்கள், அல்லது Wi-Fi/மொபைல் டேட்டா மாற்றி மீண்டும் முயற்சிக்கவும்.")
        if (e is java.net.ConnectException || e is javax.net.ssl.SSLException) return t("Google's AI server could not be connected", "Google AI சர்வருடன் இணைக்க முடியவில்லை") + " (" + (e.message ?: e.javaClass.simpleName).take(120) + "). " + t("A Wi-Fi login page, firewall or VPN may be blocking it.", "Wi-Fi login பக்கம், firewall அல்லது VPN தடுக்கலாம்.")
        if (e is java.net.SocketTimeoutException) return t("The AI took too long to answer. Please try again.", "AI பதில் தர தாமதமானது. மீண்டும் முயற்சிக்கவும்.")
        return t("The AI could not be reached", "AI-ஐ அணுக முடியவில்லை") + " (" + (e.message ?: e.javaClass.simpleName).take(120) + ")."
    }
}

/** The service answered with an error (wrong key, quota used up, unknown model ...). */
class LlmException(val status: Int, val detail: String) : Exception("AI service answered $status: $detail")
