package com.boxleits.vikunjaandroid.core.repository

import com.boxleits.vikunjaandroid.core.api.VikunjaApi
import com.boxleits.vikunjaandroid.core.api.dto.TaskDto
import com.boxleits.vikunjaandroid.core.mapper.toDomain
import com.boxleits.vikunjaandroid.core.model.Label
import com.boxleits.vikunjaandroid.core.model.Project
import com.boxleits.vikunjaandroid.core.model.Task
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

data class SyncSnapshot(
    val projects: List<Project>,
    val tasks: List<Task>,
    val labels: List<Label>,
    val syncedAt: Instant,
)

interface VikunjaRepository {
    suspend fun fetchSnapshot(): SyncSnapshot

    /** Marks a task done/not-done, returning the task as the server left it. */
    suspend fun setTaskDone(taskId: Long, done: Boolean): Task
}

private const val FIELD_DONE = "done"

class RemoteVikunjaRepository(private val api: VikunjaApi) : VikunjaRepository {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }


    override suspend fun fetchSnapshot(): SyncSnapshot = wrapErrors {
        val projects = api.getProjects().map { it.toDomain() }
        val labels = api.getLabels().map { it.toDomain() }
        val tasks = fetchAllTasks()
        SyncSnapshot(projects = projects, tasks = tasks, labels = labels, syncedAt = Clock.System.now())
    }

    /**
     * Read-modify-write: fetches the task's own JSON, flips `done` in it, and
     * posts the whole object back.
     *
     * Sending a bare `{"done": …}` would be one request instead of two, but
     * Vikunja's update writes an explicit column list — title and description
     * among them — so a field missing from the payload risks being written as
     * empty. Round-tripping the server's own object keeps every field this
     * client doesn't model intact.
     */
    override suspend fun setTaskDone(taskId: Long, done: Boolean): Task = wrapErrors {
        val current = requireBody(api.getTaskJson(taskId))
        val updated = JsonObject(current + (FIELD_DONE to JsonPrimitive(done)))
        val saved = requireBody(api.updateTaskJson(taskId, updated))
        json.decodeFromJsonElement(TaskDto.serializer(), saved).toDomain()
    }

    private fun requireBody(response: Response<JsonObject>): JsonObject {
        if (!response.isSuccessful) {
            if (response.code() == 401) throw VikunjaSyncException.Unauthorized()
            throw VikunjaSyncException.Server(response.code(), response.errorBody()?.string())
        }
        return response.body()
            ?: throw VikunjaSyncException.Server(response.code(), "Empty response body")
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
