package com.boxleits.vikunjaandroid.data.sync

import com.boxleits.vikunjaandroid.core.api.VikunjaApi
import com.boxleits.vikunjaandroid.core.api.VikunjaApiClient
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
    @Volatile private var cachedApi: VikunjaApi? = null

    suspend fun getApi(): VikunjaApi? {
        val settings = settingsRepository.settingsFlow.first() ?: return null
        val current = cachedApi
        if (current != null && settings == cachedSettings) return current

        val api = VikunjaApiClient.create(baseUrl = settings.baseUrl, tokenProvider = { settings.apiToken })
        cachedSettings = settings
        cachedApi = api
        return api
    }
}
