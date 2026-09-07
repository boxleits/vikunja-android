package com.boxleits.vikunjaandroid.data.sync

import com.boxleits.vikunjaandroid.core.repository.RemoteVikunjaRepository
import com.boxleits.vikunjaandroid.core.repository.VikunjaSyncException
import com.boxleits.vikunjaandroid.core.repository.isRetryable
import com.boxleits.vikunjaandroid.data.local.AppDatabase
import com.boxleits.vikunjaandroid.data.local.entity.EDIT_TYPE_SET_DONE
import com.boxleits.vikunjaandroid.data.local.entity.PendingEditEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import javax.inject.Inject
import javax.inject.Singleton

sealed class EditResult {
    /** Accepted by the server. */
    data object Synced : EditResult()

    /** Applied locally and queued; the server hasn't taken it yet. */
    data class Queued(val reason: String) : EditResult()

    /** Rejected for good; the local row has been put back as it was. */
    data class Rejected(val message: String) : EditResult()
}

/**
 * Edits are written locally, queued, and then pushed.
 *
 * The queue is what makes an edit survive being offline: a write that fails
 * for a reason that might pass later stays queued and keeps its local effect,
 * rather than being undone under the user. Only a failure that can never
 * succeed — the server rejecting this particular request — rolls the row back.
 */
@Singleton
class TaskEditRepository @Inject constructor(
    private val apiProvider: VikunjaApiProvider,
    private val database: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val widgetRefresher: WidgetRefresher,
) {
    /** How many edits are waiting for the server. */
    fun observePendingCount(): Flow<Int> = database.pendingEditDao().observeCount()

    suspend fun setDone(taskId: Long, done: Boolean): EditResult {
        val taskDao = database.taskDao()
        val pendingDao = database.pendingEditDao()

        val before = taskDao.findById(taskId)
            ?: return EditResult.Rejected("That task is no longer in the local copy — try syncing.")

        // If an edit for this task is already queued, the row on screen has
        // already diverged from the server. The state to roll back to is the
        // one from before that first edit, not the intermediate one.
        val queued = pendingDao.find(taskId, EDIT_TYPE_SET_DONE)
        val previousDone = queued?.previousDone ?: before.done
        val previousDoneAt = queued?.previousDoneAtEpochMs ?: before.doneAtEpochMs

        val now = Clock.System.now()
        taskDao.updateDone(
            id = taskId,
            done = done,
            doneAtEpochMs = if (done) now.toEpochMilliseconds() else null,
        )
        pendingDao.upsert(
            PendingEditEntity(
                taskId = taskId,
                type = EDIT_TYPE_SET_DONE,
                done = done,
                previousDone = previousDone,
                previousDoneAtEpochMs = previousDoneAt,
                createdAtEpochMs = now.toEpochMilliseconds(),
            ),
        )
        widgetRefresher.refresh()

        val result = flushPending().resultForCaller()
        if (result is EditResult.Queued) {
            // Constrained on connectivity, so this waits for a network rather
            // than spinning. Scheduling here, at the user's action, keeps the
            // retry out of flushPending() itself.
            syncScheduler.requestImmediateSync()
        }
        return result
    }

    /**
     * Pushes every queued edit. Safe to call repeatedly and from anywhere —
     * after a sync, from the worker, or straight after an edit.
     */
    suspend fun flushPending(): FlushOutcome {
        val pendingDao = database.pendingEditDao()
        val taskDao = database.taskDao()
        val queued = pendingDao.getAll()
        if (queued.isEmpty()) return FlushOutcome(pushed = 0, stillQueued = 0, rejected = 0)

        val api = apiProvider.getApi()
            ?: return FlushOutcome(
                pushed = 0,
                stillQueued = queued.size,
                rejected = 0,
                message = "Not connected to a Vikunja instance.",
            )
        val remote = RemoteVikunjaRepository(api)

        var pushed = 0
        var stillQueued = 0
        var rejected = 0
        var message: String? = null

        for (edit in queued) {
            try {
                val saved = remote.setTaskDone(edit.taskId, edit.done)
                taskDao.updateDone(
                    id = saved.id,
                    done = saved.done,
                    doneAtEpochMs = saved.doneAt?.toEpochMilliseconds(),
                )
                pendingDao.deleteById(edit.id)
                pushed++
            } catch (e: VikunjaSyncException) {
                message = message ?: e.message
                if (e.isRetryable()) {
                    pendingDao.recordFailedAttempt(edit.id, e.message)
                    stillQueued++
                } else {
                    // Never going to succeed: undo it locally so the user
                    // isn't left looking at a change that will never stick.
                    taskDao.updateDone(
                        id = edit.taskId,
                        done = edit.previousDone,
                        doneAtEpochMs = edit.previousDoneAtEpochMs,
                    )
                    pendingDao.deleteById(edit.id)
                    rejected++
                }
            }
        }

        widgetRefresher.refresh()

        // Deliberately schedules nothing: SyncRepository.sync() calls this,
        // and asking for a sync from here would mean a failing flush kept
        // re-triggering the sync that triggered it. Retries are arranged by
        // the caller that started the edit, plus the periodic sync.
        return FlushOutcome(pushed, stillQueued, rejected, message)
    }
}

data class FlushOutcome(
    val pushed: Int,
    val stillQueued: Int,
    val rejected: Int,
    val message: String? = null,
) {
    fun resultForCaller(): EditResult = when {
        rejected > 0 -> EditResult.Rejected(message ?: "Vikunja rejected the change.")
        stillQueued > 0 -> EditResult.Queued(message ?: "No connection.")
        else -> EditResult.Synced
    }
}
