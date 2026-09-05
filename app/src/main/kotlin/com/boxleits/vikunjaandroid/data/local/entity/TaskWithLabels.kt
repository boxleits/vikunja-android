package com.boxleits.vikunjaandroid.data.local.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.boxleits.vikunjaandroid.core.model.Task

data class TaskWithLabels(
    @Embedded val task: TaskEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = TaskLabelCrossRef::class,
            parentColumn = "taskId",
            entityColumn = "labelId",
        ),
    )
    val labels: List<LabelEntity>,
)

fun TaskWithLabels.toDomain(): Task = task.toDomain(labels.map { it.toDomain() })
