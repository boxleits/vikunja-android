package com.boxleits.vikunjaandroid.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.boxleits.vikunjaandroid.data.local.dao.LabelDao
import com.boxleits.vikunjaandroid.data.local.dao.ProjectDao
import com.boxleits.vikunjaandroid.data.local.dao.TaskDao
import com.boxleits.vikunjaandroid.data.local.entity.LabelEntity
import com.boxleits.vikunjaandroid.data.local.entity.ProjectEntity
import com.boxleits.vikunjaandroid.data.local.entity.TaskEntity
import com.boxleits.vikunjaandroid.data.local.entity.TaskLabelCrossRef

@Database(
    entities = [ProjectEntity::class, TaskEntity::class, LabelEntity::class, TaskLabelCrossRef::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun taskDao(): TaskDao
    abstract fun labelDao(): LabelDao
}
