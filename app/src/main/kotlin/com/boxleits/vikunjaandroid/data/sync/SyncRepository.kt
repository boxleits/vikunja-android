package com.boxleits.vikunjaandroid.data.sync

import androidx.room.withTransaction
import com.boxleits.vikunjaandroid.core.repository.RemoteVikunjaRepository
import com.boxleits.vikunjaandroid.core.repository.VikunjaSyncException
import com.boxleits.vikunjaandroid.data.local.AppDatabase
import com.boxleits.vikunjaandroid.data.local.entity.TaskLabelCrossRef
import com.boxleits.vikunjaandroid.data.local.entity.toEntity
import com.boxleits.vikunjaandroid.data.settings.SettingsRepository
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
    }
}
