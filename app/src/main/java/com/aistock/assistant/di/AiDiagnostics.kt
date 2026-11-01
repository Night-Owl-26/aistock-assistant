package com.aistock.assistant.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.aistock.assistant.ai.llm.Http
import com.aistock.assistant.ai.llm.LlmException
import com.aistock.assistant.ai.llm.LlmMessage
import com.aistock.assistant.ai.llm.LlmProblem
import com.aistock.assistant.ai.llm.OpenAiCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/** One line of the step-by-step AI connection check. ok = null means "skipped". */
data class Check(val title: String, val ok: Boolean?, val detail: String)

/**
 * Finds exactly where the road to Google's AI breaks: Android's network permission for this app,
 * the phone's name lookup, the secure connection, the key, and finally a real answer from the model.
 */
class AiDiagnostics(private val context: Context) {

    /** The same check for the Follower server: internet, the server itself, the account and plan, and a real answer. */
    suspend fun runServer(api: com.aistock.assistant.server.ServerApi, url: String, tamil: Boolean, learnModel: (String) -> Unit): List<Check> = withContext(Dispatchers.IO) {
        fun t(en: String, ta: String) = if (tamil) ta else en
        val out = ArrayList<Check>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = try { cm?.getNetworkCapabilities(cm.activeNetwork) } catch (e: Exception) { null }
        out.add(Check(t("Internet for this app", "இந்த ஆப்புக்கு இணையம்"), caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            if (caps == null) t("Android is not giving this app any network. Allow Wi-Fi and mobile data for Follower in phone Settings > Apps.", "Android இந்த ஆப்புக்கு இணையம் தரவில்லை.") else ""))
        if (url.isBlank()) { out.add(Check(t("Follower server", "Follower சர்வர்"), false, t("No server address yet. Enter your server address and sign in above.", "சர்வர் முகவரி இல்லை. மேலே உள்ளிட்டு sign in செய்யுங்கள்."))); return@withContext out }
        try {
            val start = System.currentTimeMillis()
            val h = api.health(url)
            out.add(Check(t("Follower server answers", "Follower சர்வர் பதில் தருகிறது"), true, "${System.currentTimeMillis() - start} ms · " + t("address from ", "முகவரி: ") + Http.lastLookup))
            if (!h.optBoolean("gemini")) { out.add(Check(t("Gemini key on the server", "சர்வரில் Gemini key"), false, t("The Follower server is not set up for AI yet. Please tell Follower support.", "Follower server-ல AI இன்னும் set up ஆகல. Follower support-க்கு சொல்லுங்க."))); return@withContext out }
            learnModel(h.optString("textModel").ifBlank { "gemini-flash-latest" })
        } catch (e: Exception) {
            out.add(Check(t("Follower server answers", "Follower சர்வர் பதில் தருகிறது"), false, (e.message ?: e.javaClass.simpleName).take(200) + " [" + e.javaClass.simpleName + "]"))
            return@withContext out
        }
        if (!api.signedIn) { out.add(Check(t("Signed in", "Sign in"), false, t("Sign in or create your account above.", "மேலே sign in செய்யுங்கள்."))); return@withContext out }
        try {
            val a = api.account()
            val u = a.usage
            out.add(Check(t("Account and plan", "கணக்கு மற்றும் திட்டம்"), u?.active == true,
                "${a.email} · ${a.businessName} · " + (u?.let { "${it.planName} (${it.status}) · AI ${it.aiUsed}/${it.aiLimit} · voice ${it.liveUsedSec / 60}/${it.liveLimitSec / 60} min" } ?: "")))
        } catch (e: Exception) {
            out.add(Check(t("Account and plan", "கணக்கு மற்றும் திட்டம்"), false, e.message ?: e.toString())); return@withContext out
        }
        try {
            val start = System.currentTimeMillis()
            val reply = com.aistock.assistant.ai.llm.ServerLlmClient(api, "gemini-flash-latest").chat(listOf(LlmMessage.User("Reply with just the word OK")), emptyList())
            out.add(Check(t("Stocky AI answers", "Stocky AI பதில் தருகிறது"), true, "${System.currentTimeMillis() - start} ms · \"${reply.text.take(30)}\""))
        } catch (e: Exception) {
            out.add(Check(t("Stocky AI answers", "Stocky AI பதில் தருகிறது"), false, LlmProblem.explain(e, true, tamil)))
        }
        out
    }
}
