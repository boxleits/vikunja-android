package com.boxleits.vikunjaandroid.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.boxleits.vikunjaandroid.core.agenda.AgendaHorizon
import com.boxleits.vikunjaandroid.core.agenda.AgendaSort
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

    val agendaSettingsFlow: Flow<AgendaSettings> = dataStore.data.map { prefs ->
        AgendaSettings(
            // An unknown stored name (a downgrade, a renamed constant) falls
            // back to the default rather than crashing the widget.
            horizon = prefs[KEY_WIDGET_HORIZON]
                ?.let { name -> AgendaHorizon.entries.firstOrNull { it.name == name } }
                ?: AgendaSettings.DEFAULT.horizon,
            sort = prefs[KEY_WIDGET_SORT]
                ?.let { name -> AgendaSort.entries.firstOrNull { it.name == name } }
                ?: AgendaSettings.DEFAULT.sort,
        )
    }

    /** The project a task was last created in — the useful default for the next one. */
    val lastProjectIdFlow: Flow<Long?> = dataStore.data.map { prefs -> prefs[KEY_LAST_PROJECT_ID] }

    suspend fun setLastProjectId(projectId: Long) {
        dataStore.edit { prefs -> prefs[KEY_LAST_PROJECT_ID] = projectId }
    }

    suspend fun setAgendaHorizon(horizon: AgendaHorizon) {
        dataStore.edit { prefs -> prefs[KEY_WIDGET_HORIZON] = horizon.name }
    }

    suspend fun setAgendaSort(sort: AgendaSort) {
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
        // Key names keep saying "widget" so settings already on a device
        // survive: they now govern the Agenda screen too, but renaming the
        // key would silently reset everyone to the defaults.
        val KEY_WIDGET_HORIZON = stringPreferencesKey("widget_horizon")
        val KEY_WIDGET_SORT = stringPreferencesKey("widget_sort")
        val KEY_LAST_PROJECT_ID = longPreferencesKey("last_project_id")
    }
}
