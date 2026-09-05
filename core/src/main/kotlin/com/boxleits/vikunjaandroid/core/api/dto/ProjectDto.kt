package com.boxleits.vikunjaandroid.core.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProjectDto(
    val id: Long,
    val title: String = "",
    @SerialName("parent_project_id") val parentProjectId: Long? = null,
    @SerialName("hex_color") val hexColor: String? = null,
    @SerialName("is_archived") val isArchived: Boolean = false,
)
