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
)

@Serializable
data class RelatedTaskRefDto(
    val id: Long,
)

/** Vikunja relation-kind key used for a task's parent within [TaskDto.relatedTasks]. */
const val RELATION_KIND_PARENT_TASK = "parenttask"
