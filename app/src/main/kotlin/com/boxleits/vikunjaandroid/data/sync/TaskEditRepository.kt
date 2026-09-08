package com.boxleits.vikunjaandroid.data.sync

import com.boxleits.vikunjaandroid.core.model.Priority
import com.boxleits.vikunjaandroid.core.model.Task
import com.boxleits.vikunjaandroid.core.repository.RemoteVikunjaRepository
import com.boxleits.vikunjaandroid.core.repository.TaskEdits
import com.boxleits.vikunjaandroid.core.repository.TaskWriteResult
import com.boxleits.vikunjaandroid.core.repository.VikunjaSyncException
import com.boxleits.vikunjaandroid.core.repository.isRetryable
import com.boxleits.vikunjaandroid.data.local.AppDatabase
import com.boxleits.vikunjaandroid.data.local.entity.ConflictNoticeEntity
import com.boxleits.vikunjaandroid.data.local.entity.EDIT_TYPE_CREATE_TASK
import com.boxleits.vikunjaandroid.data.local.entity.EDIT_TYPE_SET_DONE
import com.boxleits.vikunjaandroid.data.local.entity.EDIT_TYPE_UPDATE_TASK
import com.boxleits.vikunjaandroid.data.local.entity.PendingEditEntity
import com.boxleits.vikunjaandroid.data.local.entity.toEntity
import com.boxleits.vikunjaandroid.data.local.entity.toPlaceholderTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
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

    /**
     * Creates a task, immediately and locally, then queues it for the server.
     *
     * The row appears at once under a placeholder id so the outline reacts the
     * way it does for every other edit. That placeholder is the whole
     * difficulty of creating offline, as opposed to editing offline: an edit
     * names a task the server already knows, while a creation has to invent an
     * identity and later reconcile it with the one the server assigns.
     */
    suspend fun createTask(projectId: Long, title: String): EditResult {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return EditResult.Rejected("A task needs a title.")

        val taskDao = database.taskDao()
        val now = Clock.System.now()

        val placeholderId = database.withTransaction {
            // Negative, and below every id already present, so it collides
            // neither with Vikunja's ids nor with another placeholder.
            val id = minOf(taskDao.lowestId() ?: 0L, 0L) - 1
            val edit = PendingEditEntity(
                taskId = id,
                type = EDIT_TYPE_CREATE_TASK,
                done = false,
                previousDone = false,
                previousDoneAtEpochMs = null,
                baseUpdatedAtEpochMs = null,
                title = trimmed,
                projectId = projectId,
                createdAtEpochMs = now.toEpochMilliseconds(),
            )
            taskDao.upsert(edit.toPlaceholderTask())
            database.pendingEditDao().upsert(edit)
            id
        }
        check(placeholderId < 0)
        widgetRefresher.refresh()

        val result = flushPending().resultForCaller()
        if (result is EditResult.Queued) syncScheduler.requestImmediateSync()
        return result
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
     * Changes a task's title, priority and due date, locally first.
     *
     * The whole form is written, not only the fields the user touched. That is
     * safe because the push round-trips the server's own copy of the task: the
     * fields this app doesn't show are carried over from it rather than
     * overwritten with blanks.
     */
    suspend fun updateTask(taskId: Long, edits: TaskEdits): EditResult {
        val trimmed = edits.title.trim()
        if (trimmed.isEmpty()) return EditResult.Rejected("A task needs a title.")

        val taskDao = database.taskDao()
        val pendingDao = database.pendingEditDao()

        val before = taskDao.findById(taskId)
            ?: return EditResult.Rejected("That task is no longer in the local copy — try syncing.")

        // As with a tick: if an edit of this kind is already queued, the row on
        // screen has already diverged, so the state to return to on a rejection
        // is the one from before that first edit, not the intermediate one.
        val queued = pendingDao.find(taskId, EDIT_TYPE_UPDATE_TASK)
        val now = Clock.System.now()

        taskDao.updateFields(
            id = taskId,
            title = trimmed,
            priority = edits.priority.value,
            dueDateEpochMs = edits.dueDate?.toEpochMilliseconds(),
        )
        pendingDao.upsert(
            PendingEditEntity(
                taskId = taskId,
                type = EDIT_TYPE_UPDATE_TASK,
                done = before.done,
                previousDone = before.done,
                previousDoneAtEpochMs = before.doneAtEpochMs,
                title = trimmed,
                priority = edits.priority.value,
                dueDateEpochMs = edits.dueDate?.toEpochMilliseconds(),
                previousTitle = queued?.previousTitle ?: before.title,
                previousPriority = queued?.previousPriority ?: before.priority,
                previousDueDateEpochMs = queued?.previousDueDateEpochMs ?: before.dueDateEpochMs,
                baseUpdatedAtEpochMs = if (queued != null) queued.baseUpdatedAtEpochMs else before.updatedAtEpochMs,
                createdAtEpochMs = now.toEpochMilliseconds(),
            ),
        )
        widgetRefresher.refresh()

        val result = flushPending().resultForCaller()
        if (result is EditResult.Queued) syncScheduler.requestImmediateSync()
        return result
    }

    /**
     * Pushes every queued edit. Safe to call repeatedly and from anywhere —
     * after a sync, from the worker, or straight after an edit.
     */
    suspend fun flushPending(): FlushOutcome {
        val pendingDao = database.pendingEditDao()
        val taskDao = database.taskDao()
        val queuedIds = pendingDao.getAll().map { it.id }
        if (queuedIds.isEmpty()) return FlushOutcome(pushed = 0, stillQueued = 0, rejected = 0, conflicted = 0)

        val api = apiProvider.getApi()
            ?: return FlushOutcome(
                pushed = 0,
                stillQueued = queuedIds.size,
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

        for (editId in queuedIds) {
            // Re-read rather than trusting the snapshot: pushing an earlier
            // edit can have rewritten this one — a create gives its task a real
            // id, and a successful write moves the base version the next edit
            // for that task has to be checked against.
            val edit = pendingDao.findById(editId) ?: continue

            try {
                when (edit.type) {
                    EDIT_TYPE_CREATE_TASK -> {
                        val created = remote.createTask(
                            projectId = requireNotNull(edit.projectId) { "a queued create must know its project" },
                            title = requireNotNull(edit.title) { "a queued create must know its title" },
                        )
                        database.withTransaction {
                            // The placeholder is replaced rather than updated:
                            // its id is the primary key, and the server's id is
                            // a different row as far as the database is
                            // concerned.
                            taskDao.deleteById(edit.taskId)
                            taskDao.upsert(created.toEntity())
                            pendingDao.deleteById(edit.id)
                            // Anything else queued against the placeholder — an
                            // edit made before the create had been sent — now
                            // has a real task to name.
                            pendingDao.remapTaskId(
                                oldTaskId = edit.taskId,
                                newTaskId = created.id,
                                updatedAtEpochMs = created.updatedAt?.toEpochMilliseconds(),
                            )
                        }
                        pushed++
                    }

                    EDIT_TYPE_UPDATE_TASK -> {
                        val outcome = remote.updateTask(
                            taskId = edit.taskId,
                            edits = TaskEdits(
                                title = requireNotNull(edit.title) { "a queued edit must know its title" },
                                priority = Priority.fromValue(edit.priority ?: 0),
                                dueDate = edit.dueDateEpochMs?.let { Instant.fromEpochMilliseconds(it) },
                            ),
                            expectedUpdatedAt = edit.baseUpdatedAtEpochMs?.let { Instant.fromEpochMilliseconds(it) },
                        )
                        when (outcome) {
                            is TaskWriteResult.Applied -> {
                                val saved = outcome.task
                                database.withTransaction {
                                    taskDao.updateFieldsAndVersion(
                                        id = saved.id,
                                        title = saved.title,
                                        priority = saved.priority.value,
                                        dueDateEpochMs = saved.dueDate?.toEpochMilliseconds(),
                                        updatedAtEpochMs = saved.updatedAt?.toEpochMilliseconds(),
                                    )
                                    pendingDao.deleteById(edit.id)
                                    pendingDao.rebaseOtherEdits(
                                        taskId = saved.id,
                                        exceptId = edit.id,
                                        updatedAtEpochMs = saved.updatedAt?.toEpochMilliseconds(),
                                    )
                                }
                                pushed++
                            }

                            is TaskWriteResult.Conflict -> {
                                recordConflict(outcome.serverTask, edit.id)
                                conflicted++
                            }
                        }
                    }

                    else -> {
                        val outcome = remote.setTaskDone(
                            taskId = edit.taskId,
                            done = edit.done,
                            expectedUpdatedAt = edit.baseUpdatedAtEpochMs?.let { Instant.fromEpochMilliseconds(it) },
                        )
                        when (outcome) {
                            is TaskWriteResult.Applied -> {
                                val saved = outcome.task
                                database.withTransaction {
                                    taskDao.updateDoneAndVersion(
                                        id = saved.id,
                                        done = saved.done,
                                        doneAtEpochMs = saved.doneAt?.toEpochMilliseconds(),
                                        updatedAtEpochMs = saved.updatedAt?.toEpochMilliseconds(),
                                    )
                                    pendingDao.deleteById(edit.id)
                                    pendingDao.rebaseOtherEdits(
                                        taskId = saved.id,
                                        exceptId = edit.id,
                                        updatedAtEpochMs = saved.updatedAt?.toEpochMilliseconds(),
                                    )
                                }
                                pushed++
                            }

                            is TaskWriteResult.Conflict -> {
                                recordConflict(outcome.serverTask, edit.id)
                                conflicted++
                            }
                        }
                    }
                }
            } catch (e: VikunjaSyncException) {
                message = message ?: e.message
                if (e.isRetryable()) {
                    pendingDao.recordFailedAttempt(edit.id, e.message)
                    stillQueued++
                    continue
                }

                when (edit.type) {
                    // A create the server refuses has no earlier state to
                    // return to — the placeholder was never anything else, so
                    // it goes with the edit rather than lingering as a task
                    // that will never exist, and takes any edit made against it
                    // along with it.
                    EDIT_TYPE_CREATE_TASK -> database.withTransaction {
                        taskDao.deleteById(edit.taskId)
                        pendingDao.deleteByTaskId(edit.taskId)
                    }

                    EDIT_TYPE_UPDATE_TASK -> database.withTransaction {
                        taskDao.updateFields(
                            id = edit.taskId,
                            title = edit.previousTitle.orEmpty(),
                            priority = edit.previousPriority ?: 0,
                            dueDateEpochMs = edit.previousDueDateEpochMs,
                        )
                        pendingDao.deleteById(edit.id)
                    }

                    // Never going to succeed: undo it locally so the user
                    // isn't left looking at a change that will never stick.
                    else -> database.withTransaction {
                        taskDao.updateDone(
                            id = edit.taskId,
                            done = edit.previousDone,
                            doneAtEpochMs = edit.previousDoneAtEpochMs,
                        )
                        pendingDao.deleteById(edit.id)
                    }
                }
                rejected++
            }
        }

        widgetRefresher.refresh()

        // Deliberately schedules nothing: SyncRepository.sync() calls this,
        // and asking for a sync from here would mean a failing flush kept
        // re-triggering the sync that triggered it. Retries are arranged by
        // the caller that started the edit, plus the periodic sync.
        return FlushOutcome(pushed, stillQueued, rejected, conflicted, message)
    }

    /**
     * Keeps the server's version whole and leaves a note saying so.
     *
     * The server wins outright rather than being merged with: the point of the
     * version check is to stop a silent overwrite, and quietly keeping the
     * local value would be exactly that.
     */
    private suspend fun recordConflict(serverTask: Task, editId: Long) {
        database.withTransaction {
            database.taskDao().upsert(serverTask.toEntity())
            database.conflictNoticeDao().upsert(
                ConflictNoticeEntity(
                    taskId = serverTask.id,
                    taskTitle = serverTask.title,
                    detectedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                ),
            )
            database.pendingEditDao().deleteById(editId)
        }
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
