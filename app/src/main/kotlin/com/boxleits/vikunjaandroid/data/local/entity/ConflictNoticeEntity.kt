package com.boxleits.vikunjaandroid.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A local edit that was dropped because the task had already changed on the
 * server.
 *
 * Stored rather than raised in the moment: the flush that discovers a conflict
 * usually runs in a background worker with no UI attached, and the user has to
 * be told their change was undone even if that happens hours later. Keyed by
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
