package com.boxleits.vikunjaandroid.core.model

data class Project(
    val id: Long,
    val title: String,
    val parentProjectId: Long?,
    val hexColor: String?,
    val isArchived: Boolean,
)
