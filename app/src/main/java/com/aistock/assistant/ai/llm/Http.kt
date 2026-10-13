package com.aistock.assistant.ai.llm

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * The app's one HTTP client for the AI service.
 *
 * Some phones cannot look up Google's address (a Private DNS or ad-blocking DNS, a broken router, an ISP hiccup)
 * even though the internet works. When the phone's own lookup fails, the address is asked from Google's and
 * Cloudflare's public resolvers over HTTPS instead. The secure connection is still checked against the real
 * Google name, so this cannot be used to send the key anywhere else.
 */
object Http {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Talks only to fixed IP addresses, so it never needs a name lookup itself. */
    private val resolverClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
        .build()

    /** How the last address was found: "phone" or the backup resolver used. Shown in the connection check. */
    @Volatile var lastLookup: String = ""
        private set

    private val RESOLVERS = listOf(
        "https://8.8.8.8/resolve?type=A&name=",
        "https://1.1.1.1/dns-query?type=A&name=",
        "https://8.8.4.4/resolve?type=A&name=",
    )

    fun backupLookup(host: String): List<InetAddress> {
        for (base in RESOLVERS) {
            try {
                val req = Request.Builder().url(base + host).header("accept", "application/dns-json").build()
                resolverClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val answers = JSONObject(resp.body?.string().orEmpty()).optJSONArray("Answer") ?: return@use
                    val out = ArrayList<InetAddress>()
                    for (i in 0 until answers.length()) {
                        val a = answers.optJSONObject(i) ?: continue
                        val ip = a.optString("data")
                        // Only plain IPv4 addresses: getByName then makes no lookup of its own.
                        if (a.optInt("type") == 1 && Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip)) out.add(InetAddress.getByName(ip))
                    }
                    if (out.isNotEmpty()) { lastLookup = "backup (" + base.substringAfter("//").substringBefore('/') + ")"; return out }
                }
            } catch (e: Exception) {
                // try the next resolver
            }
        }
        return emptyList()
    }

    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = try {
            Dns.SYSTEM.lookup(hostname).also { lastLookup = "phone" }
        } catch (e: UnknownHostException) {
            backupLookup(hostname).ifEmpty { throw e }
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    class Response(val code: Int, val body: String)

    /** Blocking; call from a background thread. */
    fun post(url: String, headers: Map<String, String>, json: String): Response {
        val b = Request.Builder().url(url).post(json.toRequestBody(JSON))
        for ((k, v) in headers) b.header(k, v)
        client.newCall(b.build()).execute().use { return Response(it.code, it.body?.string().orEmpty()) }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response {
        val b = Request.Builder().url(url).get()
        for ((k, v) in headers) b.header(k, v)
        client.newCall(b.build()).execute().use { return Response(it.code, it.body?.string().orEmpty()) }
    }
}
