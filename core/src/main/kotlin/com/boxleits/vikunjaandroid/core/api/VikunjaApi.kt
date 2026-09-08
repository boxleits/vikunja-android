package com.boxleits.vikunjaandroid.core.api

import com.boxleits.vikunjaandroid.core.api.dto.LabelDto
import com.boxleits.vikunjaandroid.core.api.dto.ProjectDto
import com.boxleits.vikunjaandroid.core.api.dto.TaskDto
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Vikunja's stable `/api/v1` REST surface, limited to what a read-only client needs. */
interface VikunjaApi {

    @GET("api/v1/projects")
    suspend fun getProjects(): List<ProjectDto>

    @GET("api/v1/labels")
    suspend fun getLabels(): List<LabelDto>

    /**
     * Returns one page of every task across all projects. Callers should
     * keep requesting increasing [page] values until the response's
     * `x-pagination-total-pages` header is reached (see [PAGINATION_TOTAL_PAGES_HEADER]).
     *
     * The path is `tasks`, not `tasks/all`: Vikunja registers
     * `GET /tasks` for the collection (pkg/routes/routes.go), and there is
     * no `/tasks/all` route — that path falls through to a parameterised
     * route which fails to bind "all" and answers
     * `400 Invalid model provided`.
     *
     * Sends no filter parameters either: `filter_include_nulls` only has
     * meaning alongside a `filter` expression.
     */
    @GET("api/v1/tasks")
    suspend fun getAllTasks(
        @Query("page") page: Int,
        @Query("per_page") perPage: Int = DEFAULT_PAGE_SIZE,
    ): Response<List<TaskDto>>

    /**
     * One task as its raw JSON. Deliberately not a [TaskDto]: this is half of
     * a read-modify-write, and decoding into our own subset would silently
     * drop every field this client doesn't model when the object is sent back.
     */
    @GET("api/v1/tasks/{id}")
    suspend fun getTaskJson(@Path("id") id: Long): Response<JsonObject>

    /** Updates one task. Vikunja uses POST (not PUT) for this. */
    @POST("api/v1/tasks/{id}")
    suspend fun updateTaskJson(@Path("id") id: Long, @Body task: JsonObject): Response<JsonObject>

    /**
     * Creates a task in a project. Vikunja uses PUT to create and POST to
     * update — the opposite of the usual convention, and verified against
     * pkg/routes/routes.go rather than assumed.
     */
    @PUT("api/v1/projects/{project}/tasks")
    suspend fun createTask(@Path("project") projectId: Long, @Body task: JsonObject): Response<JsonObject>

    /**
     * Relates one task to another — used to hang a new task under a parent,
     * since Vikunja has no parent field on the task itself.
     *
     * Only the forward direction needs sending: Vikunja creates the inverse
     * relation itself.
     */
    @PUT("api/v1/tasks/{task}/relations")
    suspend fun createRelation(@Path("task") taskId: Long, @Body relation: JsonObject): Response<JsonObject>

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val PAGINATION_TOTAL_PAGES_HEADER = "x-pagination-total-pages"
    }
}
