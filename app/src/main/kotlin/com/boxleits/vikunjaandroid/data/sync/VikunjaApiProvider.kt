package com.boxleits.vikunjaandroid.data.sync

import com.boxleits.vikunjaandroid.core.api.VikunjaApi
import com.boxleits.vikunjaandroid.core.api.VikunjaApiClient
import com.boxleits.vikunjaandroid.core.api.VikunjaConnection
import com.boxleits.vikunjaandroid.data.settings.SettingsRepository
import com.boxleits.vikunjaandroid.data.settings.VikunjaSettings
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds (and caches) a [VikunjaApi] for the currently saved connection
 * settings, rebuilding it whenever those settings change.
 */
@Singleton
class VikunjaApiProvider @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    @Volatile private var cachedSettings: VikunjaSettings? = null
    @Volatile private var cachedConnection: VikunjaConnection? = null

    suspend fun getApi(): VikunjaApi? = connection()?.api

    private suspend fun connection(): VikunjaConnection? {
        val settings = settingsRepository.settingsFlow.first() ?: return null
        val current = cachedConnection
        if (current != null && settings == cachedSettings) return current

        val fresh = VikunjaApiClient.connect(baseUrl = settings.baseUrl, tokenProvider = { settings.apiToken })
        cachedSettings = settings
        cachedConnection = fresh
        return fresh
    }

    /**
     * Drops pooled sockets, for when the device changes network.
     *
     * The cached connection is the point of this class, and the reason this
     * method has to exist: the client — and its pool — lives as long as the
     * process, so it happily outlives the network it was talking over.
     */
    fun evictPooledConnections() {
        cachedConnection?.evictPooledConnections()
    }
}
