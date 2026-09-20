package com.boxleits.vikunjaandroid.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.json.Json

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
 * A change to a task's title, priority or due date.
 *
 * Kept apart from SET_DONE rather than folded into one "edit" row, because
 * ticking a task off and rewriting it are different gestures with different
 * undo: a rejected tick should put the checkbox back without also reverting a
 * title the user retyped in between.
 */
const val EDIT_TYPE_UPDATE_TASK = "UPDATE_TASK"

/**
 * A task removed locally that the server hasn't removed yet.
 *
 * The odd one out: every other edit changes a row that stays, so rolling one
 * back means writing the old values into it. A deletion takes the row with it,
 * so the row itself has to be carried along in [PendingEditEntity.taskSnapshotJson]
 * until the server agrees.
 */
const val EDIT_TYPE_DELETE_TASK = "DELETE_TASK"

/**
 * An edit made locally that the server hasn't accepted yet.
 *
 * The `previous*` columns are the values the row held before the edit, so a
 * write the server permanently rejects can be undone exactly rather than
 * guessed at.
 *
 * The unique index on (taskId, type) makes a repeated edit of the same kind to
 * the same task collapse into one queued edit — what matters is the state the
 * user last asked for, not how many times they tapped. Two edits of *different*
 * kinds to one task do coexist, which is why the flush has to keep their base
 * versions in step as it goes.
 */
@Entity(
    tableName = "pending_edits",
    indices = [Index(value = ["taskId", "type"], unique = true)],
)
data class PendingEditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val type: String,
    /** SET_DONE only; meaningless for a create or a field edit. */
    val done: Boolean,
    val previousDone: Boolean,
    val previousDoneAtEpochMs: Long?,
    /** CREATE_TASK: the title to create with. UPDATE_TASK: the new title. */
    val title: String? = null,
    /** CREATE_TASK only: where to create it. */
    val projectId: Long? = null,
    /**
     * CREATE_TASK only: set when this creation is a conflict copy, naming the
     * task it was copied from. Drives the label and the `copiedfrom` relation
     * once the server has given the copy an id of its own.
     */
    val conflictOfTaskId: Long? = null,
    /** UPDATE_TASK only: the new values, and what to put back if the write is refused. */
    val priority: Int? = null,
    val dueDateEpochMs: Long? = null,
    val previousTitle: String? = null,
    val previousPriority: Int? = null,
    val previousDueDateEpochMs: Long? = null,
    /**
     * DELETE_TASK only: the whole row as it stood, so a deletion the server
     * refuses can be put back rather than being lost.
     *
     * A serialised snapshot rather than a column per field, because the fields
     * a task has keep growing and a column each would mean a migration each
     * time. The row lives for seconds — it is a snapshot, not a model.
     */
    val taskSnapshotJson: String? = null,
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
 * otherwise delete a task the server has never heard of. It carries the edit's
 * own fields rather than blanks, because a conflict copy is a creation whose
 * whole point is the values it holds.
 */
fun PendingEditEntity.toPlaceholderTask(): TaskEntity = TaskEntity(
    id = taskId,
    projectId = requireNotNull(projectId) { "a queued create must know its project" },
    parentTaskId = null,
    title = title.orEmpty(),
    description = null,
    done = done,
    doneAtEpochMs = if (done) createdAtEpochMs else null,
    priority = priority ?: 0,
    dueDateEpochMs = dueDateEpochMs,
    startDateEpochMs = null,
    endDateEpochMs = null,
    position = 0.0,
    // No server version: it has never been to the server.
    updatedAtEpochMs = null,
)

/** The task as it stood on this device, for a conflict copy to preserve. */
data class LosingVersion(
    val title: String,
    val done: Boolean,
    val priority: Int,
    val dueDateEpochMs: Long?,
)

/**
 * Reconstructs what this edit would have produced: the state the task was in
 * when the edit was made, with the edit's own change on top.
 *
 * Deliberately built from the recorded base rather than from the local row.
 * The two are usually the same, but not after a sync has run since the edit
 * was made: the wholesale replace writes the server's values and the re-apply
 * only puts back what the edit itself asserts, so the row can end up carrying
 * the *server's* title under this device's tick. A copy built from that would
 * claim someone ticked off a task by a name they never saw — which is the
 * whole failure a conflict copy exists to avoid.
 */
fun PendingEditEntity.losingVersion(): LosingVersion = when (type) {
    EDIT_TYPE_UPDATE_TASK -> LosingVersion(
        title = title.orEmpty(),
        // An edit of the fields says nothing about done, so the base stands.
        done = previousDone,
        priority = priority ?: 0,
        dueDateEpochMs = dueDateEpochMs,
    )

    else -> LosingVersion(
        title = previousTitle.orEmpty(),
        done = done,
        priority = previousPriority ?: 0,
        dueDateEpochMs = previousDueDateEpochMs,
    )
}

/**
 * The row a queued deletion removed, for putting back if the server refuses.
 *
 * Returns null rather than throwing when the snapshot is missing or no longer
 * parses: an edit queued by an older build has none, and failing to restore a
 * row is bad but recoverable — the next sync brings the task back, because a
 * refused deletion means the server still has it.
 */
fun PendingEditEntity.restoredTask(json: Json): TaskEntity? = taskSnapshotJson?.let { raw ->
    try {
        json.decodeFromString(TaskEntity.serializer(), raw)
    } catch (e: IllegalArgumentException) {
        null
    }
}
