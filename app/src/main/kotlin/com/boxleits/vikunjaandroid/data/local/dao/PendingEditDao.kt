package com.boxleits.vikunjaandroid.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.boxleits.vikunjaandroid.data.local.entity.PendingEditEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingEditDao {

    /**
     * Oldest first, with the autoincrement id breaking ties.
     *
     * The order is load-bearing rather than cosmetic: an edit made against a
     * task this device created has to be pushed after the create that gives
     * that task its real id, or it names an id the server never had.
     */
    @Query("SELECT * FROM pending_edits ORDER BY createdAtEpochMs, id")
    suspend fun getAll(): List<PendingEditEntity>

    @Query("SELECT COUNT(*) FROM pending_edits")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_edits WHERE taskId = :taskId AND type = :type LIMIT 1")
    suspend fun find(taskId: Long, type: String): PendingEditEntity?

    /**
     * Re-reads one edit at flush time.
     *
     * The flush works from a snapshot of the queue, but pushing one edit can
     * change another — a create hands its task a real id, which the edits
     * queued against the placeholder have to follow. Re-reading each row just
     * before pushing it is what makes those updates visible.
     */
    @Query("SELECT * FROM pending_edits WHERE id = :id")
    suspend fun findById(id: Long): PendingEditEntity?

    /**
     * Moves every edit still queued against a placeholder onto the id the
     * server gave the task, so they push against the real task rather than
     * being rejected for naming one that never existed.
     */
    @Query("UPDATE pending_edits SET taskId = :newTaskId, baseUpdatedAtEpochMs = :updatedAtEpochMs WHERE taskId = :oldTaskId")
    suspend fun remapTaskId(oldTaskId: Long, newTaskId: Long, updatedAtEpochMs: Long?)

    /**
     * Brings the other edits queued for a task onto the version this write
     * just produced.
     *
     * Without it, a task with both a tick and a field edit queued would report
     * a conflict for whichever went second: the first write moves the server's
     * `updated` stamp, and the second is still holding the one from before it.
     * That is a conflict with this device's own edit, which is not what the
     * check is for — a genuine one still shows up, because a third party's
     * change lands on a stamp nobody here has seen.
     */
    @Query("UPDATE pending_edits SET baseUpdatedAtEpochMs = :updatedAtEpochMs WHERE taskId = :taskId AND id != :exceptId")
    suspend fun rebaseOtherEdits(taskId: Long, exceptId: Long, updatedAtEpochMs: Long?)

    /** Replaces any queued edit of the same kind for the same task (see the unique index). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(edit: PendingEditEntity)

    @Query("DELETE FROM pending_edits WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Drops every edit for a task, for when the task itself goes away — a
     * create the server refused takes the edits made against its placeholder
     * with it, rather than leaving them to name a task that never existed.
     */
    @Query("DELETE FROM pending_edits WHERE taskId = :taskId")
    suspend fun deleteByTaskId(taskId: Long)

    @Query("DELETE FROM pending_edits")
    suspend fun deleteAll()

    @Query("UPDATE pending_edits SET attempts = attempts + 1, lastErrorMessage = :message WHERE id = :id")
    suspend fun recordFailedAttempt(id: Long, message: String?)
}
