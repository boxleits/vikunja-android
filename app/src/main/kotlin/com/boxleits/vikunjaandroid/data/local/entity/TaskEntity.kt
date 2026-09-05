package com.boxleits.vikunjaandroid.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.boxleits.vikunjaandroid.core.model.Label
import com.boxleits.vikunjaandroid.core.model.Priority
import com.boxleits.vikunjaandroid.core.model.Task
import kotlinx.datetime.Instant

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: Long,
    val projectId: Long,
    val parentTaskId: Long?,
    val title: String,
    val description: String?,
    val done: Boolean,
    val doneAtEpochMs: Long?,
    val priority: Int,
    val dueDateEpochMs: Long?,
    val startDateEpochMs: Long?,
    val endDateEpochMs: Long?,
    val position: Double,
)

fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id,
    projectId = projectId,
    parentTaskId = parentTaskId,
    title = title,
    description = description,
    done = done,
    doneAtEpochMs = doneAt?.toEpochMilliseconds(),
    priority = priority.value,
    dueDateEpochMs = dueDate?.toEpochMilliseconds(),
    startDateEpochMs = startDate?.toEpochMilliseconds(),
    endDateEpochMs = endDate?.toEpochMilliseconds(),
    position = position,
)

fun TaskEntity.toDomain(labels: List<Label>): Task = Task(
    id = id,
    projectId = projectId,
    parentTaskId = parentTaskId,
    title = title,
    description = description,
    done = done,
    doneAt = doneAtEpochMs?.let { Instant.fromEpochMilliseconds(it) },
    priority = Priority.fromValue(priority),
    dueDate = dueDateEpochMs?.let { Instant.fromEpochMilliseconds(it) },
    startDate = startDateEpochMs?.let { Instant.fromEpochMilliseconds(it) },
    endDate = endDateEpochMs?.let { Instant.fromEpochMilliseconds(it) },
    labels = labels,
    position = position,
)
