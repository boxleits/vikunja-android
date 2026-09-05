package com.boxleits.vikunjaandroid.widget

import com.boxleits.vikunjaandroid.data.sync.TaskQueryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Glance widgets aren't part of the Hilt graph, so they reach it through this entry point. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun taskQueryRepository(): TaskQueryRepository
}
