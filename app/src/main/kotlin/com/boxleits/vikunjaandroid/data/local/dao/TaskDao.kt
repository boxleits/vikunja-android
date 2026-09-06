package com.boxleits.vikunjaandroid.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.boxleits.vikunjaandroid.data.local.entity.TaskEntity
import com.boxleits.vikunjaandroid.data.local.entity.TaskLabelCrossRef
import com.boxleits.vikunjaandroid.data.local.entity.TaskWithLabels
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Transaction
    @Query("SELECT * FROM tasks")
    fun observeAllWithLabels(): Flow<List<TaskWithLabels>>

    @Transaction
    @Query("SELECT * FROM tasks")
    suspend fun getAllWithLabels(): List<TaskWithLabels>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun findById(id: Long): TaskEntity?

    @Query("UPDATE tasks SET done = :done, doneAtEpochMs = :doneAtEpochMs WHERE id = :id")
    suspend fun updateDone(id: Long, done: Boolean, doneAtEpochMs: Long?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<TaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRefs(crossRefs: List<TaskLabelCrossRef>)

    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()

    @Query("DELETE FROM task_label_cross_refs")
    suspend fun deleteAllCrossRefs()

    @Transaction
    suspend fun replaceAll(tasks: List<TaskEntity>, crossRefs: List<TaskLabelCrossRef>) {
        deleteAllCrossRefs()
        deleteAllTasks()
        insertTasks(tasks)
        insertCrossRefs(crossRefs)
    }
}
