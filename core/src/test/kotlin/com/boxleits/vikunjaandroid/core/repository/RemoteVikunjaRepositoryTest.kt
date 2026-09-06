package com.boxleits.vikunjaandroid.core.repository

import com.boxleits.vikunjaandroid.core.api.VikunjaApiClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class RemoteVikunjaRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: RemoteVikunjaRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val api = VikunjaApiClient.create(baseUrl = server.url("/").toString(), tokenProvider = { "test-token" })
        repository = RemoteVikunjaRepository(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetchSnapshot aggregates projects, labels and every page of tasks`() = runTest {
        server.enqueue(MockResponse().setBody("""[{"id":1,"title":"Inbox"}]"""))
        server.enqueue(MockResponse().setBody("""[{"id":1,"title":"urgent","hex_color":"ff0000"}]"""))
        server.enqueue(
            MockResponse()
                .setHeader("x-pagination-total-pages", "2")
                .setBody("""[{"id":1,"project_id":1,"title":"First page task"}]"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("x-pagination-total-pages", "2")
                .setBody("""[{"id":2,"project_id":1,"title":"Second page task"}]"""),
        )

        val snapshot = repository.fetchSnapshot()

        assertThat(snapshot.projects.map { it.title }).containsExactly("Inbox")
        assertThat(snapshot.labels.map { it.title }).containsExactly("urgent")
        assertThat(snapshot.tasks.map { it.id }).containsExactly(1L, 2L)

        val firstRequest = server.takeRequest()
        assertThat(firstRequest.getHeader("Authorization")).isEqualTo("Bearer test-token")
    }

    @Test
    fun `an empty page stops pagination even if total pages says otherwise`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(
            MockResponse()
                .setHeader("x-pagination-total-pages", "5")
                .setBody("[]"),
        )

        val snapshot = repository.fetchSnapshot()

        assertThat(snapshot.tasks).isEmpty()
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `the tasks request sends only pagination parameters`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))

        repository.fetchSnapshot()

        server.takeRequest() // projects
        server.takeRequest() // labels
        val tasksRequest = server.takeRequest()

        // filter_include_nulls only has meaning alongside a `filter`
        // expression; on its own a real Vikunja server answers 400
        // "Invalid model provided".
        assertThat(tasksRequest.path).isEqualTo("/api/v1/tasks/all?page=1&per_page=50")
    }

    @Test
    fun `a non-401 error surfaces the status and the server's message`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"message":"Invalid model provided: Bad Request"}"""),
        )

        val exception = try {
            repository.fetchSnapshot()
            null
        } catch (e: VikunjaSyncException) {
            e
        }

        assertThat(exception).isInstanceOf(VikunjaSyncException.Server::class.java)
        val serverError = exception as VikunjaSyncException.Server
        assertThat(serverError.statusCode).isEqualTo(400)
        assertThat(serverError.message).contains("400")
        assertThat(serverError.message).contains("Invalid model provided")
    }

    @Test
    fun `a 401 response is surfaced as Unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val exception = try {
            repository.fetchSnapshot()
            null
        } catch (e: VikunjaSyncException) {
            e
        }

        assertThat(exception).isInstanceOf(VikunjaSyncException.Unauthorized::class.java)
    }
}
