package com.boxleits.vikunjaandroid.data.local.entity

import androidx.room.Entity

@Entity(tableName = "task_label_cross_refs", primaryKeys = ["taskId", "labelId"])
data class TaskLabelCrossRef(
    val taskId: Long,
    val labelId: Long,
)
