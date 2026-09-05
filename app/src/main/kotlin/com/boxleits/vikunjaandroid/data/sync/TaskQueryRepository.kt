package com.boxleits.vikunjaandroid.data.sync

import com.boxleits.vikunjaandroid.core.agenda.AgendaSections
import com.boxleits.vikunjaandroid.core.agenda.buildAgenda
import com.boxleits.vikunjaandroid.core.model.Project
import com.boxleits.vikunjaandroid.core.outline.OutlineNode
import com.boxleits.vikunjaandroid.core.outline.buildOutlineByProject
import com.boxleits.vikunjaandroid.data.local.AppDatabase
import com.boxleits.vikunjaandroid.data.local.entity.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import javax.inject.Inject
import javax.inject.Singleton

data class ProjectOutline(
    val project: Project,
    val nodes: List<OutlineNode>,
)

@Singleton
class TaskQueryRepository @Inject constructor(private val database: AppDatabase) {

    fun observeOutline(): Flow<List<ProjectOutline>> =
        combine(database.projectDao().observeAll(), database.taskDao().observeAllWithLabels()) { projects, tasksWithLabels ->
            val outlinesByProjectId = buildOutlineByProject(tasksWithLabels.map { it.toDomain() })
            projects
                .filter { !it.isArchived }
                .map { entity -> ProjectOutline(entity.toDomain(), outlinesByProjectId[entity.id].orEmpty()) }
        }

    fun observeAgenda(timeZone: TimeZone = TimeZone.currentSystemDefault()): Flow<AgendaSections> =
        database.taskDao().observeAllWithLabels().map { tasksWithLabels ->
            val today = Clock.System.todayIn(timeZone)
            buildAgenda(tasksWithLabels.map { it.toDomain() }, today, timeZone)
        }
}
