package com.boxleits.vikunjaandroid.core.repository

import com.boxleits.vikunjaandroid.core.api.VikunjaApi
import com.boxleits.vikunjaandroid.core.api.dto.LabelDto
import com.boxleits.vikunjaandroid.core.api.dto.RELATION_KIND_PARENT_TASK
import com.boxleits.vikunjaandroid.core.api.dto.TaskDto
import com.boxleits.vikunjaandroid.core.mapper.formatVikunjaInstant
import com.boxleits.vikunjaandroid.core.mapper.toDomain
import com.boxleits.vikunjaandroid.core.model.Label
import com.boxleits.vikunjaandroid.core.model.Priority
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

/** Outcome of a write that is conditional on the task not having moved on. */
sealed class TaskWriteResult {
    /** The write went through; [task] is the task as the server left it. */
    data class Applied(val task: Task) : TaskWriteResult()

    /**
     * Somebody changed the task after this edit was made, so nothing was
     * written. [serverTask] is the version that won.
     */
    data class Conflict(val serverTask: Task) : TaskWriteResult()
}

/**
 * The parts of a task the edit form covers, as the user left them.
 *
 * Every field is stated rather than only the changed ones. That reads like it
 * would clobber concurrent changes, but it can't: the write is conditional on
 * the task not having moved on, and the fields this doesn't name are carried
 * over from the server's own copy of the task rather than dropped.
 */
data class TaskEdits(
    val title: String,
    val priority: Priority,
    /** Null clears the due date. */
    val dueDate: Instant?,
)

interface VikunjaRepository {
    suspend fun fetchSnapshot(): SyncSnapshot

    /**
     * Marks a task done/not-done, unless it changed on the server first.
     *
     * [expectedUpdatedAt] is the task's `updated` stamp as of when the edit was
     * made. If the server's differs, the edit is abandoned rather than written
     * over the newer version. Passing null skips the check — used for edits
     * queued before a base version was recorded.
     */
    suspend fun setTaskDone(
        taskId: Long,
        done: Boolean,
        expectedUpdatedAt: Instant? = null,
    ): TaskWriteResult

    /**
     * Creates a task, optionally hung under [parentTaskId].
     *
     * Returns the task as the server created it, which is the only place its
     * real id comes from — a locally created task has none until this returns.
     */
    /**
     * The optional fields exist for a conflict copy, which has to arrive
     * complete: it stands in for a version of a task that already had a
     * priority, a due date, or a tick, and creating it blank would lose
     * exactly what it is there to preserve.
     */
    suspend fun createTask(
        projectId: Long,
        title: String,
        parentTaskId: Long? = null,
        done: Boolean = false,
        priority: Priority = Priority.UNSET,
        dueDate: Instant? = null,
    ): Task

    /** Relates one task to another; Vikunja adds the inverse itself. */
    suspend fun relateTask(taskId: Long, otherTaskId: Long, kind: String)

    /** The user's labels, for finding one by name before minting it. */
    suspend fun fetchLabels(): List<Label>

    suspend fun createLabel(title: String, hexColor: String? = null): Label

    suspend fun addLabelToTask(taskId: Long, labelId: Long)

    /**
     * Applies [edits] to a task, unless it changed on the server first.
     *
     * [expectedUpdatedAt] works exactly as it does for [setTaskDone].
     */
    suspend fun updateTask(
        taskId: Long,
        edits: TaskEdits,
        expectedUpdatedAt: Instant? = null,
    ): TaskWriteResult
}

private const val FIELD_DONE = "done"
private const val FIELD_TITLE = "title"
private const val FIELD_PRIORITY = "priority"
private const val FIELD_DUE_DATE = "due_date"
private const val FIELD_OTHER_TASK_ID = "other_task_id"
private const val FIELD_RELATION_KIND = "relation_kind"
private const val FIELD_LABEL_ID = "label_id"
private const val FIELD_HEX_COLOR = "hex_color"

class RemoteVikunjaRepository(private val api: VikunjaApi) : VikunjaRepository {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }


    override suspend fun fetchSnapshot(): SyncSnapshot = wrapErrors {
        val projects = api.getProjects().map { it.toDomain() }
        val labels = api.getLabels().map { it.toDomain() }
        val tasks = fetchAllTasks()
        SyncSnapshot(projects = projects, tasks = tasks, labels = labels, syncedAt = Clock.System.now())
    }

    override suspend fun setTaskDone(
        taskId: Long,
        done: Boolean,
        expectedUpdatedAt: Instant?,
    ): TaskWriteResult = conditionalWrite(taskId, expectedUpdatedAt) { current ->
        JsonObject(current + (FIELD_DONE to JsonPrimitive(done)))
    }

    override suspend fun updateTask(
        taskId: Long,
        edits: TaskEdits,
        expectedUpdatedAt: Instant?,
    ): TaskWriteResult = conditionalWrite(taskId, expectedUpdatedAt) { current ->
        JsonObject(
            current + mapOf(
                FIELD_TITLE to JsonPrimitive(edits.title),
                FIELD_PRIORITY to JsonPrimitive(edits.priority.value),
                // Always written, even when unset: leaving the field out would
                // keep the old date, so clearing one has to be said out loud.
                FIELD_DUE_DATE to JsonPrimitive(formatVikunjaInstant(edits.dueDate)),
            ),
        )
    }

    /**
     * Read-modify-write: fetches the task's own JSON, lets [mutate] change the
     * fields it cares about, and posts the whole object back.
     *
     * Sending only the changed fields would be one request instead of two, but
     * Vikunja's update writes an explicit column list — title and description
     * among them — so a field missing from the payload risks being written as
     * empty. Round-tripping the server's own object keeps every field this
     * client doesn't model intact, and is why an edit form that states all of
     * its fields still can't clobber the ones it doesn't show.
     */
    private suspend fun conditionalWrite(
        taskId: Long,
        expectedUpdatedAt: Instant?,
        mutate: (JsonObject) -> JsonObject,
    ): TaskWriteResult = wrapErrors {
        val current = requireBody(api.getTaskJson(taskId))
        val currentTask = json.decodeFromJsonElement(TaskDto.serializer(), current).toDomain()

        // The read this write is built on doubles as the conflict check, so
        // detecting one costs no extra request — and crucially the POST is
        // never sent, rather than sent and regretted.
        if (expectedUpdatedAt != null &&
            currentTask.updatedAt?.toServerPrecision() != expectedUpdatedAt.toServerPrecision()
        ) {
            // Any difference counts, not only a newer stamp: either way this is
            // no longer the task the edit was made against. A task that has
            // stopped reporting `updated` at all counts too — unknown is not
            // the same as unchanged.
            return@wrapErrors TaskWriteResult.Conflict(currentTask)
        }

        val saved = requireBody(api.updateTaskJson(taskId, mutate(current)))
        TaskWriteResult.Applied(json.decodeFromJsonElement(TaskDto.serializer(), saved).toDomain())
    }

    override suspend fun createTask(
        projectId: Long,
        title: String,
        parentTaskId: Long?,
        done: Boolean,
        priority: Priority,
        dueDate: Instant?,
    ): Task = wrapErrors {
        // Only what differs from the server's own defaults is sent. A quick
        // capture stays a one-field payload; a conflict copy carries whatever
        // the version it stands for had.
        val payload = buildMap {
            put(FIELD_TITLE, JsonPrimitive(title))
            if (done) put(FIELD_DONE, JsonPrimitive(true))
            if (priority != Priority.UNSET) put(FIELD_PRIORITY, JsonPrimitive(priority.value))
            if (dueDate != null) put(FIELD_DUE_DATE, JsonPrimitive(formatVikunjaInstant(dueDate)))
        }
        val created = requireBody(api.createTask(projectId, JsonObject(payload)))
        val task = json.decodeFromJsonElement(TaskDto.serializer(), created).toDomain()

        if (parentTaskId == null) return@wrapErrors task

        // A second request, because Vikunja has no parent field on the task
        // itself — the hierarchy lives in relations. The task exists either
        // way by this point; a failure here leaves it correctly created but at
        // the top level, which is recoverable, unlike losing it.
        relateTask(taskId = task.id, otherTaskId = parentTaskId, kind = RELATION_KIND_PARENT_TASK)
        task.copy(parentTaskId = parentTaskId)
    }

    override suspend fun relateTask(taskId: Long, otherTaskId: Long, kind: String) {
        wrapErrors {
            requireBody(
                api.createRelation(
                    taskId = taskId,
                    relation = JsonObject(
                        mapOf(
                            FIELD_OTHER_TASK_ID to JsonPrimitive(otherTaskId),
                            FIELD_RELATION_KIND to JsonPrimitive(kind),
                        ),
                    ),
                ),
            )
        }
    }

    override suspend fun fetchLabels(): List<Label> = wrapErrors {
        api.getLabels().map { it.toDomain() }
    }

    override suspend fun createLabel(title: String, hexColor: String?): Label = wrapErrors {
        val payload = buildMap {
            put(FIELD_TITLE, JsonPrimitive(title))
            if (hexColor != null) put(FIELD_HEX_COLOR, JsonPrimitive(hexColor))
        }
        requireBody(api.createLabel(JsonObject(payload))).toDomain()
    }

    override suspend fun addLabelToTask(taskId: Long, labelId: Long) {
        wrapErrors {
            requireBody(
                api.addLabelToTask(taskId, JsonObject(mapOf(FIELD_LABEL_ID to JsonPrimitive(labelId)))),
            )
        }
    }

    /**
     * Drops sub-second digits before comparing two `updated` stamps.
     *
     * Vikunja stores the column as a DATETIME through xorm, which writes it
     * formatted to whole seconds — but the task in a create or update
     * *response* is serialised from the in-memory struct, where xorm left the
     * full-precision `time.Now()` it generated. So the same write reports
     * `…:12.345678901Z` in its response and reads back as `…:12Z` on the next
     * fetch. Comparing exactly makes this device's own successful write look
     * like somebody else's change, which is how a task edited offline and then
     * synced came back as a conflict with itself.
     *
     * Whole seconds is the precision the server actually keeps, so it is the
     * precision worth comparing. Nothing is lost that the database ever held.
     */
    private fun Instant.toServerPrecision(): Instant = Instant.fromEpochSeconds(epochSeconds)

    private fun <T> requireBody(response: Response<T>): T {
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
