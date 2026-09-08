package com.boxleits.vikunjaandroid.di

import android.content.Context
import androidx.room.Room
import com.boxleits.vikunjaandroid.data.local.AppDatabase
import com.boxleits.vikunjaandroid.data.local.MIGRATION_2_3
import com.boxleits.vikunjaandroid.data.local.MIGRATION_3_4
import com.boxleits.vikunjaandroid.data.local.dao.ConflictNoticeDao
import com.boxleits.vikunjaandroid.data.local.dao.LabelDao
import com.boxleits.vikunjaandroid.data.local.dao.PendingEditDao
import com.boxleits.vikunjaandroid.data.local.dao.ProjectDao
import com.boxleits.vikunjaandroid.data.local.dao.TaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "vikunja.db")
            // pending_edits is not cache — it holds edits the server hasn't
            // accepted — so schema changes from here on get a real migration
            // rather than dropping the user's queued work on the floor.
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            // Still here for a downgrade or a version with no path, where the
            // alternative is refusing to open the database at all. Everything
            // except pending_edits is a cache the next sync rebuilds.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideProjectDao(database: AppDatabase): ProjectDao = database.projectDao()

    @Provides
    fun provideTaskDao(database: AppDatabase): TaskDao = database.taskDao()

    @Provides
    fun provideLabelDao(database: AppDatabase): LabelDao = database.labelDao()

    @Provides
    fun providePendingEditDao(database: AppDatabase): PendingEditDao = database.pendingEditDao()

    @Provides
    fun provideConflictNoticeDao(database: AppDatabase): ConflictNoticeDao = database.conflictNoticeDao()
}
