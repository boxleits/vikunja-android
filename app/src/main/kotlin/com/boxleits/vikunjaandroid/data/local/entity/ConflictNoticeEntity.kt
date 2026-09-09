package com.boxleits.vikunjaandroid.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A task that changed on the server while this device was editing it, whose
 * local version was kept as a separate `[conflict]` task.
 *
 * Stored rather than raised in the moment: the flush that discovers a conflict
 * usually runs in a background worker with no UI attached, and the user has to
 * be told where their version went even if that happens hours later. Keyed by
 * task, so repeated conflicts on one task don't stack up into a list of
 * duplicates. The title is copied in because it's what the message needs to
 * name, and the task may be gone by the time it's read.
 */
@Entity(tableName = "conflict_notices")
data class ConflictNoticeEntity(
    @PrimaryKey val taskId: Long,
    val taskTitle: String,
    val detectedAtEpochMs: Long,
)
