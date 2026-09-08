package com.boxleits.vikunjaandroid.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.boxleits.vikunjaandroid.data.local.dao.ConflictNoticeDao
import com.boxleits.vikunjaandroid.data.local.dao.LabelDao
import com.boxleits.vikunjaandroid.data.local.dao.PendingEditDao
import com.boxleits.vikunjaandroid.data.local.dao.ProjectDao
import com.boxleits.vikunjaandroid.data.local.dao.TaskDao
import com.boxleits.vikunjaandroid.data.local.entity.ConflictNoticeEntity
import com.boxleits.vikunjaandroid.data.local.entity.LabelEntity
import com.boxleits.vikunjaandroid.data.local.entity.PendingEditEntity
import com.boxleits.vikunjaandroid.data.local.entity.ProjectEntity
import com.boxleits.vikunjaandroid.data.local.entity.TaskEntity
import com.boxleits.vikunjaandroid.data.local.entity.TaskLabelCrossRef

@Database(
    entities = [
        ProjectEntity::class,
        TaskEntity::class,
        LabelEntity::class,
        TaskLabelCrossRef::class,
        PendingEditEntity::class,
        ConflictNoticeEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun taskDao(): TaskDao
    abstract fun labelDao(): LabelDao
    abstract fun pendingEditDao(): PendingEditDao
    abstract fun conflictNoticeDao(): ConflictNoticeDao
}

/**
 * Columns for an edit that changes a task's fields: the new values, and the
 * ones to put back if the server refuses the write outright.
 *
 * Nullable throughout, so the ALTERs need no default and rows queued before
 * this version — which are all SET_DONE or CREATE_TASK, neither of which reads
 * these — stay valid exactly as they are.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pending_edits ADD COLUMN priority INTEGER")
        db.execSQL("ALTER TABLE pending_edits ADD COLUMN dueDateEpochMs INTEGER")
        db.execSQL("ALTER TABLE pending_edits ADD COLUMN previousTitle TEXT")
        db.execSQL("ALTER TABLE pending_edits ADD COLUMN previousPriority INTEGER")
        db.execSQL("ALTER TABLE pending_edits ADD COLUMN previousDueDateEpochMs INTEGER")
    }
}

/**
 * Written by hand rather than falling back to a destructive migration, because
 * pending_edits is no longer necessarily empty: it can hold edits the user made
 * and the server hasn't taken yet, and dropping the table would throw those
 * away without telling anyone. Both new columns are nullable, so the ALTERs
 * need no default and existing rows simply have no recorded base version —
 * which the flush treats as "can't tell", not as a conflict.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pending_edits ADD COLUMN title TEXT")
        db.execSQL("ALTER TABLE pending_edits ADD COLUMN projectId INTEGER")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN updatedAtEpochMs INTEGER")
        db.execSQL("ALTER TABLE pending_edits ADD COLUMN baseUpdatedAtEpochMs INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `conflict_notices` (" +
                "`taskId` INTEGER NOT NULL, " +
                "`taskTitle` TEXT NOT NULL, " +
                "`detectedAtEpochMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`taskId`))",
        )
    }
}
