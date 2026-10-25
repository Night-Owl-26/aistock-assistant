package com.aistock.assistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Google Gemini through its chat-completions compatible address. Both can be changed in Settings. */
const val GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
const val GEMINI_MODEL = "gemini-flash-latest"

data class Prefs(
    val loaded: Boolean = false,
    val onboardingDone: Boolean = false,
    val language: String = "en",
    val theme: String = "system",
    val ttsEnabled: Boolean = true,
    /** local = on-device rules only, cloud = use the configured AI service when online */
    val aiMode: String = "local",
    val cloudEndpoint: String = "",
    val cloudModel: String = "",
    val cloudKeySaved: Boolean = false,
    val allowNegativeStock: Boolean = false,
    val lowStockAlerts: Boolean = true,
    val currencyCode: String = "INR",
    val currencySymbol: String = "₹",
    /** The "import your Excel file" offer has been shown after setup. */
    val importOffered: Boolean = false,
    /** Ask for the phone lock (fingerprint / PIN) or the app PIN before payments, big corrections, restore, reset and export. */
    val ownerCheck: Boolean = true,
    /** "Hey Stocky" listening in the background is switched on. */
    val handsFree: Boolean = false,
    /** Hour of the daily business briefing notification, -1 = off. */
    val briefingHour: Int = -1,
    /** AIStock server address; empty = not connected. */
    val serverUrl: String = "",
    val serverEmail: String = "",
    val serverBusinessId: String = "",
    /** "server" = Stocky AI through the AIStock server; "key" = own Gemini key on this phone (advanced). */
    val aiSource: String = "server",
    /** Real-time spoken conversation (Gemini Live) when signed in. */
    val liveVoice: Boolean = true,
    /** Google Cloud Speech-to-Text and Text-to-Speech through the server, turn by turn (instead of Gemini Live). */
    val cloudSpeech: Boolean = false,
) {
    val tamil: Boolean get() = language == "ta"
    val cloudReady: Boolean get() = cloudEndpoint.startsWith("https://") && cloudModel.isNotBlank() && cloudKeySaved
}

/** App preferences (DataStore). Nothing here is secret except the AI key, which is encrypted with the Android Keystore. */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) : StockRules {
    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val language = stringPreferencesKey("language")
        val theme = stringPreferencesKey("theme")
        val tts = booleanPreferencesKey("tts_enabled")
        val aiMode = stringPreferencesKey("ai_mode")
        val endpoint = stringPreferencesKey("cloud_endpoint")
        val model = stringPreferencesKey("cloud_model")
        val key = stringPreferencesKey("cloud_key_enc")
        val negative = booleanPreferencesKey("allow_negative_stock")
        val alerts = booleanPreferencesKey("low_stock_alerts")
        val currencyCode = stringPreferencesKey("currency_code")
        val currencySymbol = stringPreferencesKey("currency_symbol")
        val importOffered = booleanPreferencesKey("import_offered")
        val ownerCheck = booleanPreferencesKey("owner_check")
        val handsFree = booleanPreferencesKey("hands_free")
        val briefing = intPreferencesKey("briefing_hour")
        val serverUrl = stringPreferencesKey("server_url")
        val serverEmail = stringPreferencesKey("server_email")
        val serverBusiness = stringPreferencesKey("server_business")
        val aiSource = stringPreferencesKey("ai_source")
        val liveVoice = booleanPreferencesKey("live_voice")
        val cloudSpeech = booleanPreferencesKey("cloud_speech")
        val session = stringPreferencesKey("server_session_enc")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val flow: Flow<Prefs> = context.dataStore.data.catch { emit(emptyPreferences()) }.map { p ->
        Prefs(
            loaded = true,
            onboardingDone = p[K.onboarding] ?: false,
            language = p[K.language] ?: "en",
            theme = p[K.theme] ?: "system",
            ttsEnabled = p[K.tts] ?: true,
            aiMode = p[K.aiMode] ?: "local",
            cloudEndpoint = p[K.endpoint]?.ifBlank { null } ?: GEMINI_ENDPOINT,
            cloudModel = p[K.model]?.ifBlank { null } ?: GEMINI_MODEL,
            cloudKeySaved = !p[K.key].isNullOrEmpty(),
            allowNegativeStock = p[K.negative] ?: false,
            lowStockAlerts = p[K.alerts] ?: true,
            currencyCode = p[K.currencyCode] ?: "INR",
            currencySymbol = p[K.currencySymbol] ?: "₹",
            importOffered = p[K.importOffered] ?: false,
            ownerCheck = p[K.ownerCheck] ?: true,
            handsFree = p[K.handsFree] ?: false,
            briefingHour = p[K.briefing] ?: -1,
            serverUrl = p[K.serverUrl].orEmpty(),
            serverEmail = p[K.serverEmail].orEmpty(),
            serverBusinessId = p[K.serverBusiness].orEmpty(),
            // Phones that already used their own key keep doing so until they sign in.
            aiSource = p[K.aiSource] ?: (if (!p[K.key].isNullOrEmpty()) "key" else "server"),
            liveVoice = p[K.liveVoice] ?: true,
            cloudSpeech = p[K.cloudSpeech] ?: false,
        )
    }

    /** Latest preferences for code that cannot suspend. */
    val current: StateFlow<Prefs> = flow.stateIn(scope, SharingStarted.Eagerly, Prefs())

    override fun allowNegativeStock(): Boolean = current.value.allowNegativeStock

    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[K.onboarding] = done }.let { }

    suspend fun setLanguage(language: String) {
        context.dataStore.edit { it[K.language] = language }
        LocaleHelper.saveLanguage(context, language)
    }

    suspend fun setTheme(theme: String) = context.dataStore.edit { it[K.theme] = theme }.let { }
    suspend fun setTts(enabled: Boolean) = context.dataStore.edit { it[K.tts] = enabled }.let { }
    suspend fun setAllowNegative(allow: Boolean) = context.dataStore.edit { it[K.negative] = allow }.let { }
    suspend fun setLowStockAlerts(on: Boolean) = context.dataStore.edit { it[K.alerts] = on }.let { }

    suspend fun setCurrency(code: String, symbol: String) = context.dataStore.edit {
        it[K.currencyCode] = code; it[K.currencySymbol] = symbol
    }.let { }

    suspend fun setImportOffered() = context.dataStore.edit { it[K.importOffered] = true }.let { }

    suspend fun setOwnerCheck(on: Boolean) = context.dataStore.edit { it[K.ownerCheck] = on }.let { }
    suspend fun setBriefingHour(h: Int) = context.dataStore.edit { it[K.briefing] = h }.let { }
    suspend fun setAiSource(s: String) = context.dataStore.edit { it[K.aiSource] = s }.let { }
    suspend fun setLiveVoice(on: Boolean) = context.dataStore.edit { it[K.liveVoice] = on }.let { }
    suspend fun setCloudSpeech(on: Boolean) = context.dataStore.edit { it[K.cloudSpeech] = on }.let { }

    /** Saves the signed-in session. The refresh token is encrypted with the Android Keystore. */
    suspend fun setServerSession(url: String, email: String, businessId: String, refreshToken: String): Boolean {
        val enc = SecretBox.encrypt(refreshToken) ?: return false
        context.dataStore.edit {
            it[K.serverUrl] = url.trimEnd('/'); it[K.serverEmail] = email; it[K.serverBusiness] = businessId; it[K.session] = enc; it[K.aiSource] = "server"
        }
        return true
    }

    suspend fun updateRefreshToken(refreshToken: String) {
        val enc = SecretBox.encrypt(refreshToken) ?: return
        context.dataStore.edit { it[K.session] = enc }
    }

    suspend fun setServerBusiness(id: String) = context.dataStore.edit { it[K.serverBusiness] = id }.let { }
    suspend fun setServerUrl(url: String) = context.dataStore.edit { it[K.serverUrl] = url.trim().trimEnd('/') }.let { }

    suspend fun refreshToken(): String? = context.dataStore.data.first()[K.session]?.let { SecretBox.decrypt(it) }

    suspend fun clearServerSession() = context.dataStore.edit { it.remove(K.session); it.remove(K.serverBusiness); it.remove(K.serverEmail) }.let { }

    suspend fun setHandsFree(on: Boolean) = context.dataStore.edit { it[K.handsFree] = on }.let { }

    suspend fun setAiMode(mode: String) = context.dataStore.edit { it[K.aiMode] = mode }.let { }

    /** Saves the cloud AI settings. A blank key keeps the key that is already stored. Returns false if the key could not be protected. */
    suspend fun setCloud(endpoint: String, model: String, apiKey: String): Boolean {
        val enc = if (apiKey.isBlank()) null else (SecretBox.encrypt(apiKey.trim()) ?: return false)
        context.dataStore.edit {
            it[K.endpoint] = endpoint.trim()
            it[K.model] = model.trim()
            if (enc != null) { it[K.key] = enc; it[K.aiMode] = "cloud" }
        }
        return true
    }

    suspend fun clearCloudKey() = context.dataStore.edit { it.remove(K.key) }.let { }

    suspend fun cloudKey(): String? {
        val enc = context.dataStore.data.catch { emit(emptyPreferences()) }.first()[K.key] ?: return null
        return SecretBox.decrypt(enc)
    }

    suspend fun reset() {
        context.dataStore.edit { it.clear() }
        LocaleHelper.saveLanguage(context, "en")
    }
}

/** AES-GCM with a key that never leaves the Android Keystore. */
object SecretBox {
    private const val ALIAS = "aistock_secret_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(cipher.iv) + ":" + Base64.getEncoder().encodeToString(ct)
    } catch (e: Exception) {
        null
    }

    fun decrypt(stored: String): String? = try {
        val (iv, ct) = stored.split(":").let { Base64.getDecoder().decode(it[0]) to Base64.getDecoder().decode(it[1]) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}

/** PINs are never stored or logged: only a salted PBKDF2 hash is kept. */
object PinHasher {
    private const val ITERATIONS = 60_000

    fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.let { Base64.getEncoder().encodeToString(it) }

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun verify(pin: String, salt: String, expected: String): Boolean {
        if (salt.isEmpty() || expected.isEmpty()) return false
        val a = hash(pin, salt).toByteArray()
        val b = expected.toByteArray()
        return java.security.MessageDigest.isEqual(a, b)
    }

    fun isValidPin(pin: String): Boolean = pin.length in 4..8 && pin.all { it.isDigit() }
}

/** Per-app language. The choice is mirrored in plain SharedPreferences so it can be read before the UI starts. */
object LocaleHelper {
    private const val FILE = "boot"

    fun saveLanguage(context: Context, language: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("language", language).apply()
    }

    fun language(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("language", "en") ?: "en"

    fun wrap(base: Context): Context {
        val locale = java.util.Locale.forLanguageTag(if (language(base) == "ta") "ta-IN" else "en-IN")
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
