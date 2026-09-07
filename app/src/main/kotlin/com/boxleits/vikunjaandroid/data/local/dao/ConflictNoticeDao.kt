package com.boxleits.vikunjaandroid.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.boxleits.vikunjaandroid.data.local.entity.ConflictNoticeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConflictNoticeDao {

    @Query("SELECT * FROM conflict_notices ORDER BY detectedAtEpochMs")
    fun observeAll(): Flow<List<ConflictNoticeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notice: ConflictNoticeEntity)

    @Query("DELETE FROM conflict_notices")
    suspend fun deleteAll()
}
