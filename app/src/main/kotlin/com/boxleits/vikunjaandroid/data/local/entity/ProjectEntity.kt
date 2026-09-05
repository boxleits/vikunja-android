package com.boxleits.vikunjaandroid.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.boxleits.vikunjaandroid.core.model.Project

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val parentProjectId: Long?,
    val hexColor: String?,
    val isArchived: Boolean,
)

fun ProjectEntity.toDomain(): Project = Project(
    id = id,
    title = title,
    parentProjectId = parentProjectId,
    hexColor = hexColor,
    isArchived = isArchived,
)

fun Project.toEntity(): ProjectEntity = ProjectEntity(
    id = id,
    title = title,
    parentProjectId = parentProjectId,
    hexColor = hexColor,
    isArchived = isArchived,
)
