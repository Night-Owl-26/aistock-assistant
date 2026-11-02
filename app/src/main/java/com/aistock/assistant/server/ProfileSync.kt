package com.aistock.assistant.server

import com.aistock.assistant.business.BusinessConfig
import com.aistock.assistant.business.BusinessConfigJson
import com.aistock.assistant.business.RoomBusinessBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies the business setup to the owner's server whenever it is saved on this phone (from the setup screen or by
 * Stocky), using the server version it was based on, so a change made on another phone is never silently overwritten.
 */
@Singleton
class ProfileSync @Inject constructor(private val server: ServerApi, private val platform: RoomBusinessBackend) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The last result, for the setup screen: null = fine, otherwise a sentence. */
    @Volatile var lastProblem: String? = null
        private set

    fun start() {
        platform.onSetupSaved = { _: BusinessConfig -> scope.launch { push() } }
    }

    suspend fun push() {
        if (!server.signedIn) return
        val local = platform.latestProfile() ?: return
        lastProblem = try {
            val v = server.saveProfile(local.config, local.serverVersion)
            platform.markSynced(local.version, v)
            null
        } catch (e: ServerException) {
            if (e.status == 409) "The business setup was changed on another phone; this phone's change was kept here only." else e.message
        } catch (e: Exception) {
            e.message
        }
    }

    @Volatile private var lastPull = 0L

    /**
     * Brings the business setup made on another phone (the owner's) to this one: a staff phone gets the owner's
     * kind of business, steps and words. Runs at most every two minutes; a setup this phone has not sent yet is kept.
     */
    suspend fun pull() {
        if (!server.signedIn || System.currentTimeMillis() - lastPull < 120_000L) return
        lastPull = System.currentTimeMillis()
        try {
            val (version, json) = server.profile()
            if (json == null || version <= 0) return
            val local = platform.latestProfile()
            if (local != null && local.serverVersion >= version) return
            if (local != null && local.serverVersion == 0 && server.businessId.isNotBlank() && com.aistock.assistant.data.Access.can(com.aistock.assistant.data.Access.Perm.MANAGE)) return
            val cfg = BusinessConfigJson.read(json) ?: return
            platform.saveConfig(cfg, serverVersion = version)
        } catch (e: Exception) { }
    }

    @Suppress("unused") private fun json(c: BusinessConfig) = BusinessConfigJson.write(c)
}
