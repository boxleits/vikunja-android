package com.boxleits.vikunjaandroid.core.model

import kotlinx.datetime.Instant

/**
 * [parentTaskId] is derived client-side from Vikunja's `related_tasks`
 * "parenttask" relation so the outline can render org-mode-style nested
 * headings even though Vikunja itself has no native subtask hierarchy.
 */
data class Task(
    val id: Long,
    val projectId: Long,
    val parentTaskId: Long?,
    val title: String,
    val description: String?,
    val done: Boolean,
    val doneAt: Instant?,
    val priority: Priority,
    val dueDate: Instant?,
    val startDate: Instant?,
    val endDate: Instant?,
    val labels: List<Label>,
    val position: Double,
)
