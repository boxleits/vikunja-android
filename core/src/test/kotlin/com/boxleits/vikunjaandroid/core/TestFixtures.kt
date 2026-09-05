package com.boxleits.vikunjaandroid.core

import com.boxleits.vikunjaandroid.core.model.Priority
import com.boxleits.vikunjaandroid.core.model.Task
import kotlinx.datetime.Instant

fun sampleTask(
    id: Long,
    projectId: Long = 1L,
    parentTaskId: Long? = null,
    title: String = "Task $id",
    done: Boolean = false,
    priority: Priority = Priority.UNSET,
    dueDate: Instant? = null,
    startDate: Instant? = null,
    position: Double = id.toDouble(),
): Task = Task(
    id = id,
    projectId = projectId,
    parentTaskId = parentTaskId,
    title = title,
    description = null,
    done = done,
    doneAt = null,
    priority = priority,
    dueDate = dueDate,
    startDate = startDate,
    endDate = null,
    labels = emptyList(),
    position = position,
)
