package com.boxleits.vikunjaandroid.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** The only edit kind so far; stored explicitly so more can be added without ambiguity. */
const val EDIT_TYPE_SET_DONE = "SET_DONE"

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
    val done: Boolean,
    val previousDone: Boolean,
    val previousDoneAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val attempts: Int = 0,
    val lastErrorMessage: String? = null,
)
