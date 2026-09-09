package com.boxleits.vikunjaandroid.data.sync

import com.boxleits.vikunjaandroid.core.model.Priority
import com.boxleits.vikunjaandroid.core.model.Task
import com.boxleits.vikunjaandroid.core.repository.RemoteVikunjaRepository
import com.boxleits.vikunjaandroid.core.api.dto.RELATION_KIND_COPIED_FROM
import com.boxleits.vikunjaandroid.core.repository.TaskEdits
import com.boxleits.vikunjaandroid.core.repository.TaskWriteResult
import com.boxleits.vikunjaandroid.core.repository.VikunjaSyncException
import com.boxleits.vikunjaandroid.core.repository.isRetryable
import com.boxleits.vikunjaandroid.data.local.AppDatabase
import com.boxleits.vikunjaandroid.data.local.entity.ConflictNoticeEntity
import com.boxleits.vikunjaandroid.data.local.entity.EDIT_TYPE_CREATE_TASK
import com.boxleits.vikunjaandroid.data.local.entity.EDIT_TYPE_SET_DONE
import com.boxleits.vikunjaandroid.data.local.entity.EDIT_TYPE_UPDATE_TASK
import com.boxleits.vikunjaandroid.data.local.entity.LabelEntity
import com.boxleits.vikunjaandroid.data.local.entity.PendingEditEntity
import com.boxleits.vikunjaandroid.data.local.entity.losingVersion
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
     * The task had already changed on the server, so its version was kept and
     * this device's was turned into a separate `[conflict]` task. Reported
     * separately because the user is told about it through the conflict
     * notices, not through an error — and because nothing was lost, so it is
     * not a failure.
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
 *
 * Losing a conflict is not such a failure. The server's version takes the task
 * back, and this device's version becomes a task of its own, titled
 * `[conflict] …` and labelled, so no edit is ever silently discarded and the
 * choice between the two is left to the person who can actually make it.
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
                // The rest of the base is recorded even though a tick doesn't
                // change it: if this edit loses a conflict, the copy that
                // preserves it needs a title, and the title it needs is the one
                // the task had when the tick was made — not whatever the server
                // has renamed it to since.
                previousTitle = queued?.previousTitle ?: before.title,
                previousPriority = queued?.previousPriority ?: before.priority,
                previousDueDateEpochMs = queued?.previousDueDateEpochMs ?: before.dueDateEpochMs,
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

        // A conflict queues a copy behind the edit that lost it, so the queue
        // grows while it is being drained. Working from a deque rather than the
        // snapshot lets those copies be pushed in this same flush instead of
        // waiting for the next sync; `handled` keeps that from looping, however
        // the queue behaves.
        val remaining = ArrayDeque(queuedIds)
        val handled = mutableSetOf<Long>()

        while (remaining.isNotEmpty()) {
            val editId = remaining.removeFirst()
            if (!handled.add(editId)) continue
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
                            done = edit.done,
                            priority = Priority.fromValue(edit.priority ?: 0),
                            dueDate = edit.dueDateEpochMs?.let { Instant.fromEpochMilliseconds(it) },
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
                            //
                            // With no base version: an edit made against a
                            // placeholder was never made against a server
                            // version, so there is nothing for it to conflict
                            // with. Adopting the stamp from this create instead
                            // would claim the user edited a version they never
                            // saw, and the task is seconds old and known only
                            // to this device — nobody else can have touched it.
                            pendingDao.remapTaskId(
                                oldTaskId = edit.taskId,
                                newTaskId = created.id,
                                updatedAtEpochMs = null,
                            )
                        }
                        // Only now does the copy have a real id to label and to
                        // point at its original.
                        edit.conflictOfTaskId?.let { originalId ->
                            markAsConflictCopy(remote, copyId = created.id, originalId = originalId)
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
                                remaining += recordConflict(outcome.serverTask, edit)
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
                                remaining += recordConflict(outcome.serverTask, edit)
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
    private suspend fun recordConflict(serverTask: Task, edit: PendingEditEntity): Long {
        val losing = edit.losingVersion()
        val now = Clock.System.now()

        return database.withTransaction {
            val taskDao = database.taskDao()
            val pendingDao = database.pendingEditDao()

            // The server's version takes the task back, whole.
            taskDao.upsert(serverTask.toEntity())

            // And this device's version becomes a task of its own rather than
            // being thrown away. Same placeholder machinery as an ordinary
            // capture: it shows up at once and reaches the server on the next
            // push, so a copy is never lost to the network dropping here.
            val placeholderId = minOf(taskDao.lowestId() ?: 0L, 0L) - 1
            val copy = PendingEditEntity(
                taskId = placeholderId,
                type = EDIT_TYPE_CREATE_TASK,
                done = losing.done,
                previousDone = losing.done,
                previousDoneAtEpochMs = null,
                title = CONFLICT_TITLE_PREFIX + losing.title,
                projectId = serverTask.projectId,
                priority = losing.priority,
                dueDateEpochMs = losing.dueDateEpochMs,
                conflictOfTaskId = serverTask.id,
                baseUpdatedAtEpochMs = null,
                createdAtEpochMs = now.toEpochMilliseconds(),
            )
            taskDao.upsert(copy.toPlaceholderTask())
            pendingDao.upsert(copy)

            database.conflictNoticeDao().upsert(
                ConflictNoticeEntity(
                    taskId = serverTask.id,
                    taskTitle = serverTask.title,
                    detectedAtEpochMs = now.toEpochMilliseconds(),
                ),
            )
            // Dropped only now that the copy holding it is queued, and in the
            // same transaction, so there is no moment where this device's
            // version exists nowhere.
            pendingDao.deleteById(edit.id)
            requireNotNull(pendingDao.find(placeholderId, EDIT_TYPE_CREATE_TASK)) {
                "the conflict copy must be queued"
            }.id
        }
    }

    /**
     * Labels a conflict copy and points it at the task it was copied from.
     *
     * Deliberately swallows failures. The copy already exists and already says
     * `[conflict]` in its title — the part that must not be lost is safe. The
     * label and the link are findability, and nothing retries them; risking the
     * copy for them would be the wrong way round.
     */
    private suspend fun markAsConflictCopy(
        remote: RemoteVikunjaRepository,
        copyId: Long,
        originalId: Long,
    ) {
        try {
            conflictLabelId(remote)?.let { labelId -> remote.addLabelToTask(copyId, labelId) }
            remote.relateTask(taskId = copyId, otherTaskId = originalId, kind = RELATION_KIND_COPIED_FROM)
        } catch (e: VikunjaSyncException) {
            // See above: the copy stands on its own without these.
        }
    }

    /**
     * Finds the label conflict copies carry, minting it the first time.
     *
     * Best-effort by design: a copy without its label is still a task with a
     * `[conflict]` title and a link to its original, which is recoverable. A
     * copy that failed to be created at all would not be.
     */
    private suspend fun conflictLabelId(remote: RemoteVikunjaRepository): Long? = try {
        val labelDao = database.labelDao()
        val existing = labelDao.findByTitle(CONFLICT_LABEL_TITLE)
            ?: remote.fetchLabels()
                .firstOrNull { it.title.equals(CONFLICT_LABEL_TITLE, ignoreCase = true) }
                ?.let { found -> LabelEntity(found.id, found.title, found.hexColor).also { labelDao.upsert(it) } }
            ?: remote.createLabel(CONFLICT_LABEL_TITLE, CONFLICT_LABEL_COLOUR)
                .let { made -> LabelEntity(made.id, made.title, made.hexColor).also { labelDao.upsert(it) } }
        existing.id
    } catch (e: VikunjaSyncException) {
        null
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

/** Marks a conflict copy in any list that shows only a title — the widget included. */
const val CONFLICT_TITLE_PREFIX = "[conflict] "

/** Native Vikunja label, so conflict copies are filterable in the web UI too. */
const val CONFLICT_LABEL_TITLE = "sync-conflict"

private const val CONFLICT_LABEL_COLOUR = "e8412c"
