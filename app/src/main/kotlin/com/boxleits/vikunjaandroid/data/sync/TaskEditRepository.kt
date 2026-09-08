package com.boxleits.vikunjaandroid.data.sync

import com.boxleits.vikunjaandroid.core.repository.RemoteVikunjaRepository
import com.boxleits.vikunjaandroid.core.repository.TaskWriteResult
import com.boxleits.vikunjaandroid.core.repository.VikunjaSyncException
import com.boxleits.vikunjaandroid.core.repository.isRetryable
import com.boxleits.vikunjaandroid.data.local.AppDatabase
import com.boxleits.vikunjaandroid.data.local.entity.ConflictNoticeEntity
import com.boxleits.vikunjaandroid.data.local.entity.EDIT_TYPE_SET_DONE
import com.boxleits.vikunjaandroid.data.local.entity.PendingEditEntity
import com.boxleits.vikunjaandroid.data.local.entity.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed class EditResult {
    /** Accepted by the server. */
    data object Synced : EditResult()

    /** Applied locally and queued; the server hasn't taken it yet. */
    data class Queued(val reason: String) : EditResult()

    /** Rejected for good; the local row has been put back as it was. */
    data class Rejected(val message: String) : EditResult()

    /**
     * The task had already changed on the server, so the edit was dropped and
     * the server's version kept. Reported separately because the user is told
     * about it through the conflict notices, not through an error.
     */
    data object Conflicted : EditResult()
}

/** A local change that lost to a newer version on the server. */
data class TaskConflict(val taskId: Long, val taskTitle: String)

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

    /** Edits dropped because the server had a newer version, until acknowledged. */
    fun observeConflicts(): Flow<List<TaskConflict>> = database.conflictNoticeDao().observeAll()
        .map { notices -> notices.map { TaskConflict(it.taskId, it.taskTitle) } }

    suspend fun acknowledgeConflicts() {
        database.conflictNoticeDao().deleteAll()
    }

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
        // Same reasoning as the previous value: the base version is the one
        // this run of edits started from. Taking `before` here instead would
        // adopt a version the user never actually saw a conflict against.
        val baseUpdatedAt = if (queued != null) queued.baseUpdatedAtEpochMs else before.updatedAtEpochMs

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
                baseUpdatedAtEpochMs = baseUpdatedAt,
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
        if (queued.isEmpty()) return FlushOutcome(pushed = 0, stillQueued = 0, rejected = 0, conflicted = 0)

        val api = apiProvider.getApi()
            ?: return FlushOutcome(
                pushed = 0,
                stillQueued = queued.size,
                rejected = 0,
                conflicted = 0,
                message = "Not connected to a Vikunja instance.",
            )
        val remote = RemoteVikunjaRepository(api)

        var pushed = 0
        var stillQueued = 0
        var rejected = 0
        var conflicted = 0
        var message: String? = null

        for (edit in queued) {
            try {
                val outcome = remote.setTaskDone(
                    taskId = edit.taskId,
                    done = edit.done,
                    expectedUpdatedAt = edit.baseUpdatedAtEpochMs?.let { Instant.fromEpochMilliseconds(it) },
                )
                when (outcome) {
                    is TaskWriteResult.Applied -> {
                        val saved = outcome.task
                        taskDao.updateDoneAndVersion(
                            id = saved.id,
                            done = saved.done,
                            doneAtEpochMs = saved.doneAt?.toEpochMilliseconds(),
                            updatedAtEpochMs = saved.updatedAt?.toEpochMilliseconds(),
                        )
                        pendingDao.deleteById(edit.id)
                        pushed++
                    }

                    is TaskWriteResult.Conflict -> {
                        // The server's version wins whole, rather than the local
                        // edit being merged into it: with one boolean at stake
                        // there is nothing to merge, and quietly keeping the
                        // local value would be the silent overwrite this whole
                        // check exists to prevent.
                        val server = outcome.serverTask
                        taskDao.upsert(server.toEntity())
                        database.conflictNoticeDao().upsert(
                            ConflictNoticeEntity(
                                taskId = server.id,
                                taskTitle = server.title,
                                detectedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                            ),
                        )
                        pendingDao.deleteById(edit.id)
                        conflicted++
                    }
                }
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
        return FlushOutcome(pushed, stillQueued, rejected, conflicted, message)
    }
}

data class FlushOutcome(
    val pushed: Int,
    val stillQueued: Int,
    val rejected: Int,
    val conflicted: Int = 0,
    val message: String? = null,
) {
    fun resultForCaller(): EditResult = when {
        rejected > 0 -> EditResult.Rejected(message ?: "Vikunja rejected the change.")
        conflicted > 0 -> EditResult.Conflicted
        stillQueued > 0 -> EditResult.Queued(message ?: "No connection.")
        else -> EditResult.Synced
    }
}
