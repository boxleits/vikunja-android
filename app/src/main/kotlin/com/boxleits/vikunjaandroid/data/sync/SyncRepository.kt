package com.boxleits.vikunjaandroid.data.sync

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.room.withTransaction
import com.boxleits.vikunjaandroid.core.repository.RemoteVikunjaRepository
import com.boxleits.vikunjaandroid.core.repository.VikunjaSyncException
import com.boxleits.vikunjaandroid.data.local.AppDatabase
import com.boxleits.vikunjaandroid.data.local.entity.TaskLabelCrossRef
import com.boxleits.vikunjaandroid.data.local.entity.toEntity
import com.boxleits.vikunjaandroid.data.settings.SettingsRepository
import com.boxleits.vikunjaandroid.widget.AgendaWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed class SyncResult {
    data object NotConfigured : SyncResult()
    data object Success : SyncResult()
    data class Error(val exception: VikunjaSyncException) : SyncResult()
}

@Singleton
class SyncRepository @Inject constructor(
    private val apiProvider: VikunjaApiProvider,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) {
    suspend fun sync(): SyncResult {
        val api = apiProvider.getApi() ?: return SyncResult.NotConfigured

        return try {
            val snapshot = RemoteVikunjaRepository(api).fetchSnapshot()

            val crossRefs = snapshot.tasks.flatMap { task ->
                task.labels.map { label -> TaskLabelCrossRef(taskId = task.id, labelId = label.id) }
            }

            database.withTransaction {
                database.projectDao().replaceAll(snapshot.projects.map { it.toEntity() })
                database.labelDao().replaceAll(snapshot.labels.map { it.toEntity() })
                database.taskDao().replaceAll(snapshot.tasks.map { it.toEntity() }, crossRefs)
            }

            settingsRepository.recordSyncTimestamp(snapshot.syncedAt)

            // Glance snapshots its content when the widget is composed, so it
            // only changes when something asks it to. Every sync path funnels
            // through here — pull-to-refresh and onboarding call this directly
            // rather than going via SyncWorker — so this is the one place that
            // reliably keeps the widget from showing stale data.
            AgendaWidget().updateAll(context)

            SyncResult.Success
        } catch (e: VikunjaSyncException) {
            SyncResult.Error(e)
        }
    }

    suspend fun logOut() {
        database.withTransaction {
            database.taskDao().replaceAll(emptyList(), emptyList())
            database.projectDao().replaceAll(emptyList())
            database.labelDao().replaceAll(emptyList())
        }
        settingsRepository.clear()
        // Otherwise the widget keeps displaying the logged-out user's tasks.
        AgendaWidget().updateAll(context)
    }
}
