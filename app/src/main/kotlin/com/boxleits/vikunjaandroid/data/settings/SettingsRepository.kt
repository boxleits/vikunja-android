package com.boxleits.vikunjaandroid.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.boxleits.vikunjaandroid.core.agenda.WidgetHorizon
import com.boxleits.vikunjaandroid.core.agenda.WidgetSort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "vikunja_settings")

/**
 * Connection settings live in a plain (unencrypted) Preferences DataStore
 * private to the app; [android:allowBackup] is disabled in the manifest so
 * the API token is never swept into Android's cloud backup.
 */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {

    private val dataStore = context.dataStore

    val settingsFlow: Flow<VikunjaSettings?> = dataStore.data.map { prefs ->
        val baseUrl = prefs[KEY_BASE_URL]
        val apiToken = prefs[KEY_API_TOKEN]
        if (baseUrl.isNullOrBlank() || apiToken.isNullOrBlank()) null else VikunjaSettings(baseUrl, apiToken)
    }

    val lastSyncedAtFlow: Flow<Instant?> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_SYNCED_AT_EPOCH_MS]?.let { Instant.fromEpochMilliseconds(it) }
    }

    val widgetSettingsFlow: Flow<WidgetSettings> = dataStore.data.map { prefs ->
        WidgetSettings(
            // An unknown stored name (a downgrade, a renamed constant) falls
            // back to the default rather than crashing the widget.
            horizon = prefs[KEY_WIDGET_HORIZON]
                ?.let { name -> WidgetHorizon.entries.firstOrNull { it.name == name } }
                ?: WidgetSettings.DEFAULT.horizon,
            sort = prefs[KEY_WIDGET_SORT]
                ?.let { name -> WidgetSort.entries.firstOrNull { it.name == name } }
                ?: WidgetSettings.DEFAULT.sort,
        )
    }

    suspend fun setWidgetHorizon(horizon: WidgetHorizon) {
        dataStore.edit { prefs -> prefs[KEY_WIDGET_HORIZON] = horizon.name }
    }

    suspend fun setWidgetSort(sort: WidgetSort) {
        dataStore.edit { prefs -> prefs[KEY_WIDGET_SORT] = sort.name }
    }

    suspend fun save(settings: VikunjaSettings) {
        dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = settings.baseUrl
            prefs[KEY_API_TOKEN] = settings.apiToken
        }
    }

    suspend fun recordSyncTimestamp(instant: Instant) {
        dataStore.edit { prefs -> prefs[KEY_LAST_SYNCED_AT_EPOCH_MS] = instant.toEpochMilliseconds() }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_API_TOKEN = stringPreferencesKey("api_token")
        val KEY_LAST_SYNCED_AT_EPOCH_MS = longPreferencesKey("last_synced_at_epoch_ms")
        val KEY_WIDGET_HORIZON = stringPreferencesKey("widget_horizon")
        val KEY_WIDGET_SORT = stringPreferencesKey("widget_sort")
    }
}
