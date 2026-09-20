package com.boxleits.vikunjaandroid.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.boxleits.vikunjaandroid.data.local.entity.LabelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LabelDao {

    @Query("SELECT * FROM labels ORDER BY title")
    fun observeAll(): Flow<List<LabelEntity>>

    /**
     * Looks a label up by name, for finding the conflict label before minting
     * one. Case-insensitive because `LIKE` is, and a user who already has a
     * "Sync-Conflict" label should not end up with a second one.
     */
    @Query("SELECT * FROM labels WHERE title LIKE :title LIMIT 1")
    suspend fun findByTitle(title: String): LabelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(label: LabelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(labels: List<LabelEntity>)

    @Query("DELETE FROM labels")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(labels: List<LabelEntity>) {
        deleteAll()
        insertAll(labels)
    }
}
