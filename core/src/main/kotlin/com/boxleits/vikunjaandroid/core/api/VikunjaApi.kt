package com.boxleits.vikunjaandroid.core.api

import com.boxleits.vikunjaandroid.core.api.dto.LabelDto
import com.boxleits.vikunjaandroid.core.api.dto.ProjectDto
import com.boxleits.vikunjaandroid.core.api.dto.TaskDto
import retrofit2.Response
import retrofit2.http.GET
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
     */
    @GET("api/v1/tasks/all")
    suspend fun getAllTasks(
        @Query("page") page: Int,
        @Query("per_page") perPage: Int = DEFAULT_PAGE_SIZE,
        @Query("filter_include_nulls") filterIncludeNulls: Boolean = true,
    ): Response<List<TaskDto>>

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val PAGINATION_TOTAL_PAGES_HEADER = "x-pagination-total-pages"
    }
}
