package com.boxleits.vikunjaandroid.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val EDIT_TYPE_SET_DONE = "SET_DONE"

/**
 * A task that exists only on this device until the server accepts it.
 *
 * Unlike SET_DONE this edit has no server-side subject yet, which is what
 * makes creating offline harder than editing offline: the row it refers to is
 * a placeholder whose id this app invented.
 */
const val EDIT_TYPE_CREATE_TASK = "CREATE_TASK"

/**
 * An edit made locally that the server hasn't accepted yet.
 *
 * [previousDone]/[previousDoneAtEpochMs] are the values the row held before
 * the edit, so a write the server permanently rejects can be undone exactly
 * rather than guessed at.
 *
 * The unique index on (taskId, type) makes a repeated toggle of the same task
 * collapse into one queued edit — what matters is the state the user last
 * asked for, not how many times they tapped.
 */
@Entity(
    tableName = "pending_edits",
    indices = [Index(value = ["taskId", "type"], unique = true)],
)
data class PendingEditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val type: String,
    /** SET_DONE only; meaningless for a create, which has nothing to toggle. */
    val done: Boolean,
    val previousDone: Boolean,
    val previousDoneAtEpochMs: Long?,
    /** CREATE_TASK only: what to create, and where. */
    val title: String? = null,
    val projectId: Long? = null,
    /**
     * The task's server `updated` stamp when the edit was made. If the server's
     * differs at flush time, somebody else got there first. Null for an edit
     * queued before the row carried a version, which skips the check rather
     * than treating it as a permanent conflict.
     */
    val baseUpdatedAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val attempts: Int = 0,
    val lastErrorMessage: String? = null,
)

/**
 * The local stand-in row for a queued creation.
 *
 * Both the create path and a sync need it: the create path to show the task at
 * once, and a sync to put it back after the wholesale replace, which would
 * otherwise delete a task the server has never heard of.
 */
fun PendingEditEntity.toPlaceholderTask(): TaskEntity = TaskEntity(
    id = taskId,
    projectId = requireNotNull(projectId) { "a queued create must know its project" },
    parentTaskId = null,
    title = title.orEmpty(),
    description = null,
    done = false,
    doneAtEpochMs = null,
    priority = 0,
    dueDateEpochMs = null,
    startDateEpochMs = null,
    endDateEpochMs = null,
    position = 0.0,
    // No server version: it has never been to the server.
    updatedAtEpochMs = null,
)
