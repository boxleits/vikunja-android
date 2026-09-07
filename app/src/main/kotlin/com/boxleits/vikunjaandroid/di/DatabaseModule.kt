package com.boxleits.vikunjaandroid.di

import android.content.Context
import androidx.room.Room
import com.boxleits.vikunjaandroid.data.local.AppDatabase
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
            // Everything here except pending_edits is a cache that the next
            // sync rebuilds, so dropping it on a schema change is cheap.
            //
            // Caveat worth keeping in view: pending_edits is NOT cache — it
            // holds edits the server hasn't accepted. It is empty at the
            // upgrade that introduces it, so nothing is lost this time, but
            // once users have queued work a destructive migration would
            // discard it silently. The next schema change needs a real
            // migration, not this.
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
}
