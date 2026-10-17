package com.aistock.assistant.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.aistock.assistant.ai.llm.GeminiClient
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
    private val host = "generativelanguage.googleapis.com"

    /** The same check for the AIStock server: internet, the server itself, the account and plan, and a real answer. */
    suspend fun runServer(api: com.aistock.assistant.server.ServerApi, url: String, tamil: Boolean, learnModel: (String) -> Unit): List<Check> = withContext(Dispatchers.IO) {
        fun t(en: String, ta: String) = if (tamil) ta else en
        val out = ArrayList<Check>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = try { cm?.getNetworkCapabilities(cm.activeNetwork) } catch (e: Exception) { null }
        out.add(Check(t("Internet for this app", "இந்த ஆப்புக்கு இணையம்"), caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            if (caps == null) t("Android is not giving this app any network. Allow Wi-Fi and mobile data for AIStock in phone Settings > Apps.", "Android இந்த ஆப்புக்கு இணையம் தரவில்லை.") else ""))
        if (url.isBlank()) { out.add(Check(t("AIStock server", "AIStock சர்வர்"), false, t("No server address yet. Enter your server address and sign in above.", "சர்வர் முகவரி இல்லை. மேலே உள்ளிட்டு sign in செய்யுங்கள்."))); return@withContext out }
        try {
            val start = System.currentTimeMillis()
            val h = api.health(url)
            out.add(Check(t("AIStock server answers", "AIStock சர்வர் பதில் தருகிறது"), true, "${System.currentTimeMillis() - start} ms · " + t("address from ", "முகவரி: ") + Http.lastLookup))
            if (!h.optBoolean("gemini")) { out.add(Check(t("Gemini key on the server", "சர்வரில் Gemini key"), false, t("The server has no GEMINI_API_KEY. Add it to the server's .env file and restart it.", "சர்வரின் .env-ல் GEMINI_API_KEY சேர்த்து restart செய்யுங்கள்."))); return@withContext out }
            learnModel(h.optString("textModel").ifBlank { "gemini-flash-latest" })
        } catch (e: Exception) {
            out.add(Check(t("AIStock server answers", "AIStock சர்வர் பதில் தருகிறது"), false, (e.message ?: e.javaClass.simpleName).take(200) + " [" + e.javaClass.simpleName + "]"))
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

    suspend fun run(key: String?, model: String, tamil: Boolean): List<Check> = withContext(Dispatchers.IO) {
        fun t(en: String, ta: String) = if (tamil) ta else en
        val out = ArrayList<Check>()

        // 1. Does Android let this app use the internet at all?
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = try { cm?.activeNetwork } catch (e: Exception) { null }
        val caps = try { cm?.getNetworkCapabilities(net) } catch (e: Exception) { null }
        val saver = try { cm?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED } catch (e: Exception) { false }
        when {
            net == null || caps == null -> out.add(Check(t("Internet for this app", "இந்த ஆப்புக்கு இணையம்"), false, t(
                "Android is not giving this app any network. Open phone Settings > Apps > AIStock Assistant > Mobile data & Wi-Fi (or Data usage / Network access) and allow Wi-Fi and mobile data. Also turn off Data Saver and any firewall app for AIStock.",
                "Android இந்த ஆப்புக்கு இணையம் தரவில்லை. Settings > Apps > AIStock Assistant > Mobile data & Wi-Fi (அல்லது Data usage / Network access)-ல் Wi-Fi, mobile data-வை அனுமதியுங்கள். Data Saver, firewall ஆப்களையும் நிறுத்துங்கள்.")))
            else -> {
                val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val kind = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> t("mobile data", "mobile data")
                    else -> t("network", "network")
                }
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val notes = buildList {
                    add(kind)
                    if (vpn) add(t("VPN is on", "VPN இயக்கத்தில்"))
                    if (saver) add(t("Data Saver is on", "Data Saver இயக்கத்தில்"))
                    if (!validated) add(t("Android says this network has no working internet (Wi-Fi login page?)", "இந்த network-ல் இணையம் வேலை செய்யவில்லை என Android சொல்கிறது (Wi-Fi login பக்கம்?)"))
                }
                out.add(Check(t("Internet for this app", "இந்த ஆப்புக்கு இணையம்"), validated, notes.joinToString(" · ")))
            }
        }

        // 2. Can the phone find Google's AI server?
        var phoneDnsOk = false
        try {
            val start = System.currentTimeMillis()
            val ips = InetAddress.getAllByName(host)
            phoneDnsOk = ips.isNotEmpty()
            out.add(Check(t("Phone finds Google AI server", "Google AI சர்வரை போன் கண்டது"), true, "${ips.first().hostAddress} · ${System.currentTimeMillis() - start} ms"))
        } catch (e: Exception) {
            val privateDns = try {
                if (Build.VERSION.SDK_INT >= 28) cm?.getLinkProperties(net)?.let { lp -> if (lp.isPrivateDnsActive) (lp.privateDnsServerName ?: t("automatic", "automatic")) else null } else null
            } catch (x: Exception) { null }
            out.add(Check(t("Phone finds Google AI server", "Google AI சர்வரை போன் கண்டது"), false,
                (e.javaClass.simpleName + ": " + (e.message ?: "")).take(140) +
                    (if (privateDns != null) t(" · Private DNS: $privateDns — set it to Off or Automatic in Settings > Network.", " · Private DNS: $privateDns — Settings > Network-ல் Off அல்லது Automatic ஆக்குங்கள்.") else "")))
            // 2b. The app's own backup lookup.
            val backup = try { Http.backupLookup(host) } catch (x: Exception) { emptyList() }
            out.add(Check(t("Backup address lookup", "மாற்று முகவரி தேடல்"), backup.isNotEmpty(),
                if (backup.isNotEmpty()) t("Found ${backup.first().hostAddress} — Stocky will use this.", "${backup.first().hostAddress} கிடைத்தது — Stocky இதைப் பயன்படுத்தும்.")
                else t("The backup lookup was blocked too, so something on this phone or network blocks the internet for this app.", "மாற்று தேடலும் தடுக்கப்பட்டது; இந்த போன் அல்லது network இந்த ஆப்புக்கு இணையத்தைத் தடுக்கிறது.")))
        }

        // 3. Secure connection to Google (no key needed).
        var reachable = false
        try {
            val start = System.currentTimeMillis()
            val r = Http.get("https://$host/")
            reachable = true
            out.add(Check(t("Secure connection to Google", "Google-உடன் பாதுகாப்பான இணைப்பு"), true, "HTTP ${r.code} · ${System.currentTimeMillis() - start} ms · " + t("address from ", "முகவரி: ") + Http.lastLookup))
        } catch (e: Exception) {
            out.add(Check(t("Secure connection to Google", "Google-உடன் பாதுகாப்பான இணைப்பு"), false, LlmProblem.explain(e, true, tamil) + " [" + e.javaClass.simpleName + "]"))
        }

        // 4. The key.
        if (key.isNullOrBlank()) {
            out.add(Check(t("AI key", "AI key"), false, t("No key is saved. Paste your Gemini API key above and tap Save.", "Key சேமிக்கப்படவில்லை. மேலே Gemini API key-ஐ ஒட்டி Save தட்டுங்கள்.")))
            return@withContext out
        }
        if (!reachable) {
            out.add(Check(t("AI key", "AI key"), null, t("Not checked: Google could not be reached.", "சரிபார்க்கவில்லை: Google-ஐ அணுக முடியவில்லை.")))
            return@withContext out
        }
        try {
            val r = Http.get("https://$host/v1beta/models?pageSize=200", mapOf("x-goog-api-key" to key.trim()))
            if (r.code in 200..299) out.add(Check(t("AI key accepted", "AI key ஏற்கப்பட்டது"), true, t("Key ending …", "Key கடைசி …") + key.trim().takeLast(4)))
            else { out.add(Check(t("AI key accepted", "AI key ஏற்கப்பட்டது"), false, LlmProblem.explain(LlmException(r.code, OpenAiCompat.errorMessage(r.body)), true, tamil))); return@withContext out }
        } catch (e: Exception) {
            out.add(Check(t("AI key accepted", "AI key ஏற்கப்பட்டது"), false, LlmProblem.explain(e, true, tamil))); return@withContext out
        }

        // 5. A real answer.
        try {
            val start = System.currentTimeMillis()
            val c = GeminiClient(model.removePrefix("models/"), key.trim())
            val reply = c.chat(listOf(LlmMessage.User("Reply with just the word OK")), emptyList())
            out.add(Check(t("Gemini answers", "Gemini பதில் தருகிறது"), true, "${c.name} · ${System.currentTimeMillis() - start} ms · \"${reply.text.take(30)}\""))
        } catch (e: Exception) {
            out.add(Check(t("Gemini answers", "Gemini பதில் தருகிறது"), false, LlmProblem.explain(e, true, tamil)))
        }
        if (!phoneDnsOk) out.add(Check(t("Note", "குறிப்பு"), null, t("Your phone's own address lookup fails, but Stocky now works around it.", "போனின் முகவரி தேடல் தோல்வியடைகிறது, ஆனால் Stocky அதைச் சுற்றி வேலை செய்கிறது.")))
        out
    }
}
