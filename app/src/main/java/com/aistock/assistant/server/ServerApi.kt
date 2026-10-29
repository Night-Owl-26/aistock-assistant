package com.aistock.assistant.server

import com.aistock.assistant.BuildConfig
import com.aistock.assistant.ai.llm.Http
import com.aistock.assistant.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** The server answered with an error, in words meant for the owner. */
class ServerException(val status: Int, val code: String, message: String) : Exception(message)

data class Usage(val active: Boolean, val status: String, val planName: String, val periodEnd: String, val aiUsed: Int, val aiLimit: Int, val liveUsedSec: Int, val liveLimitSec: Int, val priceMinor: Int, val planId: String = "", val pendingPayments: Int = 0) {
    val endMillis: Long get() = try { java.time.Instant.parse(periodEnd).toEpochMilli() } catch (e: Exception) { 0L }
}
/** A plan the owner can pay for, and where to pay (UPI). */
data class PaidPlan(val id: String, val name: String, val priceMinor: Int, val periodDays: Int, val aiRequests: Int, val voiceMinutes: Int, val maxStaff: Int)
data class PayInfo(val plans: List<PaidPlan>, val upiId: String, val payeeName: String, val supportPhone: String, val trialDays: Int)
/** A UPI payment the owner reported; "pending" until the Follower team checks it. */
data class PaymentClaim(val id: Long, val planId: String, val amountMinor: Int, val utr: String, val status: String, val note: String, val createdAt: String)
data class Account(val email: String, val businessId: String, val businessName: String, val role: String, val usage: Usage?, val aiConfigured: Boolean, val textModel: String, val liveModel: String, val speechConfigured: Boolean = false)
/** What Google Cloud Speech-to-Text heard. */
data class Heard(val text: String, val language: String, val confidence: Double?)
/** mode "relay": voice goes through the Follower server (signed in with the bearer token); "token": straight to Google with a one-use token. */
data class LiveTicket(val mode: String, val sessionId: String, val token: String, val wsUrl: String, val model: String, val maxSeconds: Int)

/**
 * Talks to the Follower server (your own deployment). The app keeps only a refresh token, encrypted with the Android
 * Keystore; access tokens live in memory and are renewed automatically. No AI key is ever on the phone in this mode.
 */
@Singleton
class ServerApi @Inject constructor(private val settings: SettingsStore) {
    private val json = "application/json; charset=utf-8".toMediaType()
    private var access: String? = null
    private var accessUntil = 0L
    private val lock = Mutex()

    /** False when the server has no Gemini key: real-time voice is then not offered (typed chat may use another model). */
    @Volatile var liveReady: Boolean = true

    val signedIn: Boolean get() = settings.current.value.serverUrl.isNotBlank() && settings.current.value.serverBusinessId.isNotBlank()
    private val base: String get() = settings.current.value.serverUrl.trimEnd('/')
    val businessId: String get() = settings.current.value.serverBusinessId

    private fun url(path: String): String {
        val b = server
        if (!b.startsWith("https://") && !b.startsWith("http://10.0.2.2")) throw ServerException(0, "bad_url", "The server address must start with https://")
        return b + path
    }

    private fun send(method: String, path: String, body: JSONObject?, token: String?, raw: ByteArray? = null, extra: Map<String, String> = emptyMap()): Pair<Int, ByteArray> {
        val b = Request.Builder().url(url(path))
        token?.let { b.header("Authorization", "Bearer $it") }
        for ((k, v) in extra) b.header(k, v)
        val rb = when {
            raw != null -> raw.toRequestBody("application/octet-stream".toMediaType())
            body != null -> body.toString().toRequestBody(json)
            method == "GET" -> null
            else -> "{}".toRequestBody(json)
        }
        b.method(method, rb)
        Http.client.newCall(b.build()).execute().use { r -> return r.code to (r.body?.bytes() ?: ByteArray(0)) }
    }

    private fun parse(code: Int, bytes: ByteArray): JSONObject {
        val o = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { JSONObject() }
        if (code !in 200..299) throw ServerException(code, o.optString("error", "http_$code"), o.optString("message").ifBlank { o.optJSONObject("error")?.optString("message").orEmpty() }.ifBlank { "The server answered $code." })
        return o
    }

    private suspend fun accessToken(): String = lock.withLock {
        access?.takeIf { System.currentTimeMillis() < accessUntil }?.let { return it }
        val refresh = settings.refreshToken() ?: throw ServerException(401, "unauthorized", "Sign in to your Follower account.")
        val o = withContext(Dispatchers.IO) { send("POST", "/v1/auth/refresh", JSONObject().put("refreshToken", refresh), null).let { (c, b) -> parse(c, b) } }
        remember(o)
        settings.updateRefreshToken(o.getString("refreshToken"))
        access!!
    }

    private fun remember(o: JSONObject) {
        access = o.getString("accessToken")
        accessUntil = System.currentTimeMillis() + (o.optLong("expiresIn", 3600) - 60) * 1000
    }

    /** Authenticated call; one automatic retry when the access token expired. */
    private suspend fun call(method: String, path: String, body: JSONObject? = null, raw: ByteArray? = null, extra: Map<String, String> = emptyMap()): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        var r = send(method, path, body, accessToken(), raw, extra)
        if (r.first == 401) { lock.withLock { access = null }; r = send(method, path, body, accessToken(), raw, extra) }
        r
    }

    private suspend fun obj(method: String, path: String, body: JSONObject? = null) = call(method, path, body).let { (c, b) -> parse(c, b) }

    // ---------------------------------------------------------------- account

    /** The server this phone talks to: the one signed in to, else the one built into the app. */
    val server: String get() = base.ifBlank { BuildConfig.DEFAULT_SERVER }.trimEnd('/')

    /** Asks the server for the plan (trial, paid, ended) and keeps it on the phone, so read-only also works offline. */
    suspend fun refreshPlan(): Usage? {
        val me = obj("GET", "/v1/me")
        val list = me.getJSONArray("businesses")
        var chosen: JSONObject? = null
        for (i in 0 until list.length()) if (list.getJSONObject(i).optString("id") == businessId) chosen = list.getJSONObject(i)
        val u = (chosen ?: list.optJSONObject(0))?.optJSONObject("entitlement")?.let { usage(it) } ?: return null
        settings.setPlan(u.status, u.planName, u.endMillis, u.active, u.pendingPayments)
        return u
    }

    /** Plans to pay for and the UPI ID to pay to (public, no sign-in needed). */
    suspend fun payInfo(): PayInfo = withContext(Dispatchers.IO) {
        val o = send("GET", "/v1/plans", null, null).let { (c, b) -> parse(c, b) }
        val arr = o.optJSONArray("plans") ?: JSONArray()
        val plans = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optInt("price_minor") > 0 }.map {
            PaidPlan(it.getString("id"), it.optString("name"), it.optInt("price_minor"), it.optInt("period_days", 30), it.optInt("ai_requests_month"), it.optInt("live_seconds_month") / 60, it.optInt("max_staff", 1))
        }
        val pay = o.optJSONObject("payment") ?: JSONObject()
        PayInfo(plans, pay.optString("upiId"), pay.optString("payeeName", "Follower"), pay.optString("supportPhone"), pay.optInt("trialDays", 14))
    }

    /** Reports a UPI payment with its transaction ID (UTR); the plan starts when it is approved. */
    suspend fun claimPayment(planId: String, utr: String): PaymentClaim =
        claim(obj("POST", "/v1/businesses/$businessId/payments", JSONObject().put("planId", planId).put("utr", utr)))

    suspend fun payments(): List<PaymentClaim> {
        val arr = obj("GET", "/v1/businesses/$businessId/payments").optJSONArray("payments") ?: JSONArray()
        return (0 until arr.length()).map { claim(arr.getJSONObject(it)) }
    }

    private fun claim(o: JSONObject) = PaymentClaim(o.optLong("id"), o.optString("planId"), o.optInt("amountMinor"), o.optString("utr"), o.optString("status"), o.optString("note"), o.optString("createdAt"))

    suspend fun health(url: String): JSONObject = withContext(Dispatchers.IO) {
        val r = Http.get(url.trimEnd('/') + "/healthz")
        if (r.code != 200) throw ServerException(r.code, "unhealthy", "The server did not answer correctly (${r.code}).")
        JSONObject(r.body)
    }

    suspend fun register(url: String, email: String, password: String, name: String, businessName: String) = withContext(Dispatchers.IO) {
        settings.setServerUrl(url)
        val o = send("POST", "/v1/auth/register", JSONObject().put("email", email).put("password", password).put("name", name).put("businessName", businessName), null).let { (c, b) -> parse(c, b) }
        remember(o)
        if (!settings.setServerSession(url, email.trim().lowercase(), o.getString("businessId"), o.getString("refreshToken"))) throw ServerException(0, "keystore", "This phone could not store the sign-in securely.")
        try { refreshPlan() } catch (e: Exception) { }
    }

    suspend fun login(url: String, email: String, password: String) = withContext(Dispatchers.IO) {
        settings.setServerUrl(url)
        val o = send("POST", "/v1/auth/login", JSONObject().put("email", email).put("password", password), null).let { (c, b) -> parse(c, b) }
        remember(o)
        if (!settings.setServerSession(url, email.trim().lowercase(), "pending", o.getString("refreshToken"))) throw ServerException(0, "keystore", "This phone could not store the sign-in securely.")
        val me = obj("GET", "/v1/me")
        val first = me.getJSONArray("businesses").optJSONObject(0) ?: throw ServerException(0, "no_business", "This account has no business.")
        settings.setServerBusiness(first.getString("id"))
        try { refreshPlan() } catch (e: Exception) { }
    }

    suspend fun logout() {
        val refresh = settings.refreshToken()
        try { if (refresh != null) withContext(Dispatchers.IO) { send("POST", "/v1/auth/logout", JSONObject().put("refreshToken", refresh), null) } } catch (e: Exception) { }
        lock.withLock { access = null }
        settings.clearServerSession()
    }

    suspend fun account(): Account {
        val me = obj("GET", "/v1/me")
        val list = me.getJSONArray("businesses")
        var chosen: JSONObject? = null
        for (i in 0 until list.length()) if (list.getJSONObject(i).optString("id") == businessId) chosen = list.getJSONObject(i)
        val b = chosen ?: list.optJSONObject(0) ?: throw ServerException(0, "no_business", "This account has no business.")
        val ai = me.optJSONObject("ai") ?: JSONObject()
        liveReady = ai.optBoolean("live", ai.optBoolean("configured", true))
        b.optJSONObject("entitlement")?.let { usage(it) }?.let { settings.setPlan(it.status, it.planName, it.endMillis, it.active, it.pendingPayments) }
        return Account(me.getJSONObject("user").optString("email"), b.getString("id"), b.optString("name"), b.optString("role"),
            b.optJSONObject("entitlement")?.let { usage(it) }, ai.optBoolean("configured"), ai.optString("textModel"), ai.optString("liveModel"), ai.optBoolean("speech"))
    }

    private fun usage(e: JSONObject) = Usage(
        e.optBoolean("active"), e.optString("status"), e.optString("planName"), e.optString("periodEnd"),
        e.optJSONObject("aiRequests")?.optInt("used") ?: 0, e.optJSONObject("aiRequests")?.optInt("limit") ?: 0,
        e.optJSONObject("liveSeconds")?.optInt("used") ?: 0, e.optJSONObject("liveSeconds")?.optInt("limit") ?: 0, e.optInt("priceMinor"),
        e.optString("planId"), e.optInt("pendingPayments"),
    )

    suspend fun deleteAccount(password: String) {
        obj("DELETE", "/v1/me", JSONObject().put("password", password))
        lock.withLock { access = null }
        settings.clearServerSession()
    }

    // ---------------------------------------------------------------- AI

    /** Sends a Gemini request through the server. Returns Google's status and JSON (or the server's error). */
    suspend fun generate(request: JSONObject): Pair<Int, String> =
        call("POST", "/v1/businesses/$businessId/ai/generate", JSONObject().put("request", request)).let { (c, b) -> c to String(b, Charsets.UTF_8) }

    suspend fun liveSession(): LiveTicket {
        val o = try { obj("POST", "/v1/businesses/$businessId/ai/live-session") } catch (e: ServerException) {
            if (e.code == "ai_not_configured") liveReady = false
            throw e
        }
        return LiveTicket(o.optString("mode", "token"), o.optString("sessionId"), o.optString("token"), o.getString("wsUrl"), o.getString("model"), o.optInt("maxSeconds", 600))
    }

    /**
     * Google Cloud Speech-to-Text through the server: [pcm] is 16 kHz mono 16-bit audio, [phrases] are product and
     * customer names that help recognition.
     */
    suspend fun speechToText(pcm: ByteArray, language: String, phrases: List<String>): Heard {
        val body = JSONObject().put("audio", android.util.Base64.encodeToString(pcm, android.util.Base64.NO_WRAP)).put("language", language)
            .put("phrases", org.json.JSONArray(phrases.take(500)))
        val o = obj("POST", "/v1/businesses/$businessId/ai/stt", body)
        return Heard(o.optString("text"), o.optString("language", language), if (o.isNull("confidence")) null else o.optDouble("confidence"))
    }

    /** Google Cloud Text-to-Speech through the server: a WAV file (16-bit PCM, 24 kHz). */
    suspend fun textToSpeech(text: String, language: String?): ByteArray {
        val body = JSONObject().put("text", text)
        language?.let { body.put("language", it) }
        val o = obj("POST", "/v1/businesses/$businessId/ai/tts", body)
        return android.util.Base64.decode(o.getString("audio"), android.util.Base64.DEFAULT)
    }

    /** A current access token, for the voice WebSocket through the server. */
    suspend fun bearer(): String = accessToken()

    suspend fun liveUsage(sessionId: String, seconds: Int, final: Boolean) {
        if (sessionId.isBlank()) return // relayed sessions are measured by the server itself
        try { obj("POST", "/v1/businesses/$businessId/ai/live-usage", JSONObject().put("sessionId", sessionId).put("seconds", seconds).put("final", final)) } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- business profile and backups

    suspend fun profile(): Pair<Int, String?> {
        val o = obj("GET", "/v1/businesses/$businessId/profile")
        return o.optInt("version") to o.optJSONObject("config")?.toString()
    }

    suspend fun saveProfile(configJson: String, baseVersion: Int): Int =
        obj("PUT", "/v1/businesses/$businessId/profile", JSONObject().put("config", JSONObject(configJson)).put("baseVersion", baseVersion)).getInt("version")

    suspend fun uploadBackup(bytes: ByteArray, note: String): String =
        call("POST", "/v1/businesses/$businessId/backups", raw = bytes, extra = mapOf("x-backup-note" to note.take(100))).let { (c, b) -> parse(c, b).getString("id") }
}
