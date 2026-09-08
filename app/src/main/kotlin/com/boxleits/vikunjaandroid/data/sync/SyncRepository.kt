package com.boxleits.vikunjaandroid.data.sync

import androidx.room.withTransaction
import com.boxleits.vikunjaandroid.core.repository.RemoteVikunjaRepository
import com.boxleits.vikunjaandroid.core.repository.VikunjaSyncException
import com.boxleits.vikunjaandroid.data.local.AppDatabase
import com.boxleits.vikunjaandroid.data.local.entity.EDIT_TYPE_CREATE_TASK
import com.boxleits.vikunjaandroid.data.local.entity.TaskLabelCrossRef
import com.boxleits.vikunjaandroid.data.local.entity.toPlaceholderTask
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
    private val taskEditRepository: TaskEditRepository,
    private val widgetRefresher: WidgetRefresher,
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

                // The snapshot is the server's view, which by definition
                // predates anything still sitting in the queue. Without
                // re-applying those edits, this replace would silently undo
                // a change the user made and is still waiting to send.
                database.pendingEditDao().getAll().forEach { edit ->
                    when (edit.type) {
                        // A task the server has never seen is not in the
                        // snapshot, so the replace above just deleted it.
                        EDIT_TYPE_CREATE_TASK -> database.taskDao().upsert(edit.toPlaceholderTask())

                        else -> database.taskDao().updateDone(
                            id = edit.taskId,
                            done = edit.done,
                            doneAtEpochMs = if (edit.done) edit.createdAtEpochMs else null,
                        )
                    }
                }
            }

            settingsRepository.recordSyncTimestamp(snapshot.syncedAt)

            // Now that the server has been read, try to hand it the backlog.
            taskEditRepository.flushPending()

            // Glance snapshots its content when the widget is composed, so it
            // only changes when something asks it to. Every sync path funnels
            // through here — pull-to-refresh and onboarding call this directly
            // rather than going via SyncWorker — so this is the one place that
            // reliably keeps the widget from showing stale data.
            widgetRefresher.refresh()

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
            // Queued edits belong to the account being signed out of; keeping
            // them would push one user's changes with the next user's token.
            database.pendingEditDao().deleteAll()
            database.conflictNoticeDao().deleteAll()
        }
        settingsRepository.clear()
        // Otherwise the widget keeps displaying the logged-out user's tasks.
        widgetRefresher.refresh()
    }
}
