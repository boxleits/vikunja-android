package com.boxleits.vikunjaandroid.core.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskDto(
    val id: Long,
    @SerialName("project_id") val projectId: Long,
    val title: String = "",
    val description: String? = null,
    val done: Boolean = false,
    @SerialName("done_at") val doneAt: String? = null,
    val priority: Int = 0,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val labels: List<LabelDto>? = null,
    @SerialName("related_tasks") val relatedTasks: Map<String, List<RelatedTaskRefDto>>? = null,
    val position: Double = 0.0,
    /**
     * Server-set, read-only in Vikunja. Used as the version to detect a task
     * that changed underneath a queued edit.
     */
    val updated: String? = null,
)

@Serializable
data class RelatedTaskRefDto(
    val id: Long,
)

/** Vikunja relation-kind key used for a task's parent within [TaskDto.relatedTasks]. */
const val RELATION_KIND_PARENT_TASK = "parenttask"

/**
 * Points a conflict copy at the task it was copied from.
 *
 * Chosen over `related` because it carries a direction: from the relation
 * alone you can tell which of the two is the copy. Vikunja creates the
 * `copiedto` inverse on the original itself, so both sides show the link.
 */
const val RELATION_KIND_COPIED_FROM = "copiedfrom"
