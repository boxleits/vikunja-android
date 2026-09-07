package com.boxleits.vikunjaandroid.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.boxleits.vikunjaandroid.data.local.entity.PendingEditEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingEditDao {

    @Query("SELECT * FROM pending_edits ORDER BY createdAtEpochMs")
    suspend fun getAll(): List<PendingEditEntity>

    @Query("SELECT COUNT(*) FROM pending_edits")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_edits WHERE taskId = :taskId AND type = :type LIMIT 1")
    suspend fun find(taskId: Long, type: String): PendingEditEntity?

    /** Replaces any queued edit of the same kind for the same task (see the unique index). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(edit: PendingEditEntity)

    @Query("DELETE FROM pending_edits WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_edits")
    suspend fun deleteAll()

    @Query("UPDATE pending_edits SET attempts = attempts + 1, lastErrorMessage = :message WHERE id = :id")
    suspend fun recordFailedAttempt(id: Long, message: String?)
}
