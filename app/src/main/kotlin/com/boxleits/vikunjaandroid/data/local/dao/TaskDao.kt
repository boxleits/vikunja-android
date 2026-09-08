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

    /**
     * The lowest id in the table, used to mint the next placeholder id.
     *
     * Locally created tasks get negative ids: Vikunja's are positive, so the
     * two can never collide, and the sign alone says whether a row has ever
     * reached the server.
     */
    @Query("SELECT MIN(id) FROM tasks")
    suspend fun lowestId(): Long?

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE tasks SET done = :done, doneAtEpochMs = :doneAtEpochMs WHERE id = :id")
    suspend fun updateDone(id: Long, done: Boolean, doneAtEpochMs: Long?)

    /**
     * Writes back what the server returned, version included, so the next edit
     * to this task is compared against the right base.
     */
    @Query(
        "UPDATE tasks SET done = :done, doneAtEpochMs = :doneAtEpochMs, " +
            "updatedAtEpochMs = :updatedAtEpochMs WHERE id = :id",
    )
    suspend fun updateDoneAndVersion(id: Long, done: Boolean, doneAtEpochMs: Long?, updatedAtEpochMs: Long?)

    /**
     * Replaces one task with the server's version — used when a conflict means
     * the server's copy wins outright. Label cross-refs are left alone and are
     * reconciled by the next full sync.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

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
