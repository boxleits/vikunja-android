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
