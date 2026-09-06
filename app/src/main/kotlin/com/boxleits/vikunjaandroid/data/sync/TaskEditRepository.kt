package com.boxleits.vikunjaandroid.data.sync

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.boxleits.vikunjaandroid.core.repository.RemoteVikunjaRepository
import com.boxleits.vikunjaandroid.core.repository.VikunjaSyncException
import com.boxleits.vikunjaandroid.data.local.AppDatabase
import com.boxleits.vikunjaandroid.widget.AgendaWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.datetime.Clock
import javax.inject.Inject
import javax.inject.Singleton

sealed class EditResult {
    data object Success : EditResult()
    data class Error(val message: String) : EditResult()
}

/**
 * The one write path in the app so far.
 *
 * Writes are applied to the local database first so the checkbox responds
 * immediately, then sent to the server; a failure puts the row back exactly
 * as it was. There is no offline queue yet — an edit made with no connection
 * fails and reverts rather than being retried later.
 */
@Singleton
class TaskEditRepository @Inject constructor(
    private val apiProvider: VikunjaApiProvider,
    private val database: AppDatabase,
    @ApplicationContext private val context: Context,
) {
    suspend fun setDone(taskId: Long, done: Boolean): EditResult {
        val taskDao = database.taskDao()
        val before = taskDao.findById(taskId)
            ?: return EditResult.Error("That task is no longer in the local copy — try syncing.")
        val api = apiProvider.getApi()
            ?: return EditResult.Error("Not connected to a Vikunja instance.")

        // Optimistic: doneAt is a guess until the server answers, and the
        // response overwrites it below.
        taskDao.updateDone(
            id = taskId,
            done = done,
            doneAtEpochMs = if (done) Clock.System.now().toEpochMilliseconds() else null,
        )

        return try {
            val saved = RemoteVikunjaRepository(api).setTaskDone(taskId, done)
            taskDao.updateDone(
                id = taskId,
                done = saved.done,
                doneAtEpochMs = saved.doneAt?.toEpochMilliseconds(),
            )
            AgendaWidget().updateAll(context)
            EditResult.Success
        } catch (e: VikunjaSyncException) {
            taskDao.updateDone(id = taskId, done = before.done, doneAtEpochMs = before.doneAtEpochMs)
            EditResult.Error(e.message ?: "Could not update the task.")
        }
    }
}
