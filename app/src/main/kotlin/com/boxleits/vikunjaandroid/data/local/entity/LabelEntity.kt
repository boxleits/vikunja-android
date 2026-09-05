package com.boxleits.vikunjaandroid.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.boxleits.vikunjaandroid.core.model.Label

@Entity(tableName = "labels")
data class LabelEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val hexColor: String?,
)

fun LabelEntity.toDomain(): Label = Label(id = id, title = title, hexColor = hexColor)

fun Label.toEntity(): LabelEntity = LabelEntity(id = id, title = title, hexColor = hexColor)
