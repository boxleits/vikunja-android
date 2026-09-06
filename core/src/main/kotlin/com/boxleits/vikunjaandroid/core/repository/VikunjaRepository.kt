package com.boxleits.vikunjaandroid.core.repository

import com.boxleits.vikunjaandroid.core.api.VikunjaApi
import com.boxleits.vikunjaandroid.core.api.dto.TaskDto
import com.boxleits.vikunjaandroid.core.mapper.toDomain
import com.boxleits.vikunjaandroid.core.model.Label
import com.boxleits.vikunjaandroid.core.model.Project
import com.boxleits.vikunjaandroid.core.model.Task
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import retrofit2.HttpException
import java.io.IOException

data class SyncSnapshot(
    val projects: List<Project>,
    val tasks: List<Task>,
    val labels: List<Label>,
    val syncedAt: Instant,
)

interface VikunjaRepository {
    suspend fun fetchSnapshot(): SyncSnapshot
}

/** Pulls the full task/project/label state from a Vikunja instance. Read-only: no writes. */
class RemoteVikunjaRepository(private val api: VikunjaApi) : VikunjaRepository {

    override suspend fun fetchSnapshot(): SyncSnapshot = wrapErrors {
        val projects = api.getProjects().map { it.toDomain() }
        val labels = api.getLabels().map { it.toDomain() }
        val tasks = fetchAllTasks()
        SyncSnapshot(projects = projects, tasks = tasks, labels = labels, syncedAt = Clock.System.now())
    }

    private suspend fun fetchAllTasks(): List<Task> {
        val dtos = mutableListOf<TaskDto>()
        var page = 1
        while (true) {
            val response = api.getAllTasks(page = page)
            if (!response.isSuccessful) {
                if (response.code() == 401) throw VikunjaSyncException.Unauthorized()
                throw VikunjaSyncException.Server(response.code(), response.errorBody()?.string())
            }
            val pageBody = response.body().orEmpty()
            dtos += pageBody
            val totalPages = response.headers()[VikunjaApi.PAGINATION_TOTAL_PAGES_HEADER]?.toIntOrNull() ?: 1
            if (pageBody.isEmpty() || page >= totalPages) break
            page++
        }
        return dtos.map { it.toDomain() }
    }

    private suspend fun <T> wrapErrors(block: suspend () -> T): T = try {
        block()
    } catch (e: VikunjaSyncException) {
        throw e
    } catch (e: HttpException) {
        when (e.code()) {
            401 -> throw VikunjaSyncException.Unauthorized(e)
            else -> throw VikunjaSyncException.Server(e.code(), e.response()?.errorBody()?.string())
        }
    } catch (e: IOException) {
        throw VikunjaSyncException.Network(e)
    } catch (e: Exception) {
        throw VikunjaSyncException.Unexpected(e)
    }
}
