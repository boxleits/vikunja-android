package com.boxleits.vikunjaandroid.core.repository

import com.boxleits.vikunjaandroid.core.api.VikunjaApiClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `the tasks request uses the collection route with only pagination parameters`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))

        repository.fetchSnapshot()

        server.takeRequest() // projects
        server.takeRequest() // labels
        val tasksRequest = server.takeRequest()

        // Both halves of this matter against a real server: Vikunja has no
        // /tasks/all route (that path answers 400 "Invalid model provided"),
        // and filter_include_nulls is only valid alongside a `filter`
        // expression.
        assertThat(tasksRequest.path).isEqualTo("/api/v1/tasks?page=1&per_page=50")
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
    fun `setTaskDone round-trips the server's own object, preserving unmodelled fields`() = runTest {
        // Fields this client knows nothing about must survive the write, or a
        // toggle would quietly clear them server-side.
        val serverTask = """
            {
              "id": 7,
              "project_id": 1,
              "title": "Buy milk",
              "description": "semi-skimmed",
              "done": false,
              "percent_done": 40,
              "hex_color": "aabbcc",
              "assignees": [{"id": 3}],
              "some_future_field": {"nested": true}
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(serverTask))
        server.enqueue(MockResponse().setBody(serverTask.replace("\"done\": false", "\"done\": true")))

        repository.setTaskDone(taskId = 7, done = true)

        val getRequest = server.takeRequest()
        assertThat(getRequest.method).isEqualTo("GET")
        assertThat(getRequest.path).isEqualTo("/api/v1/tasks/7")

        val postRequest = server.takeRequest()
        assertThat(postRequest.method).isEqualTo("POST")
        assertThat(postRequest.path).isEqualTo("/api/v1/tasks/7")

        val sent = Json.parseToJsonElement(postRequest.body.readUtf8()).jsonObject
        assertThat(sent["done"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(sent["title"]!!.jsonPrimitive.content).isEqualTo("Buy milk")
        assertThat(sent["description"]!!.jsonPrimitive.content).isEqualTo("semi-skimmed")
        assertThat(sent["percent_done"]!!.jsonPrimitive.int).isEqualTo(40)
        assertThat(sent["hex_color"]!!.jsonPrimitive.content).isEqualTo("aabbcc")
        // Not modelled by TaskDto at all — the point of the round-trip.
        assertThat(sent).containsKey("assignees")
        assertThat(sent).containsKey("some_future_field")
    }

    @Test
    fun `setTaskDone returns the task as the server left it`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":7,"project_id":1,"title":"T","done":false}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"id":7,"project_id":1,"title":"T","done":true,"done_at":"2024-01-15T10:30:00Z"}""",
            ),
        )

        val result = repository.setTaskDone(taskId = 7, done = true)

        assertThat(result).isInstanceOf(TaskWriteResult.Applied::class.java)
        val task = (result as TaskWriteResult.Applied).task
        assertThat(task.done).isTrue()
        assertThat(task.doneAt).isEqualTo(Instant.parse("2024-01-15T10:30:00Z"))
    }

    @Test
    fun `setTaskDone applies the write when the task has not changed since the edit`() = runTest {
        val base = Instant.parse("2024-01-15T10:00:00Z")
        server.enqueue(
            MockResponse().setBody(
                """{"id":7,"project_id":1,"title":"T","done":false,"updated":"2024-01-15T10:00:00Z"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"id":7,"project_id":1,"title":"T","done":true,"updated":"2024-01-15T11:00:00Z"}""",
            ),
        )

        val result = repository.setTaskDone(taskId = 7, done = true, expectedUpdatedAt = base)

        assertThat(result).isInstanceOf(TaskWriteResult.Applied::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `setTaskDone reports a conflict and writes nothing when the task moved on`() = runTest {
        val base = Instant.parse("2024-01-15T10:00:00Z")
        // Someone else saved the task after this edit was made.
        server.enqueue(
            MockResponse().setBody(
                """{"id":7,"project_id":1,"title":"Renamed elsewhere","done":true,"updated":"2024-01-15T12:00:00Z"}""",
            ),
        )

        val result = repository.setTaskDone(taskId = 7, done = false, expectedUpdatedAt = base)

        assertThat(result).isInstanceOf(TaskWriteResult.Conflict::class.java)
        val serverTask = (result as TaskWriteResult.Conflict).serverTask
        assertThat(serverTask.title).isEqualTo("Renamed elsewhere")
        assertThat(serverTask.done).isTrue()
        // The whole point: the POST is never sent, so the newer version stands.
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `setTaskDone skips the conflict check when no base version is known`() = runTest {
        // Edits queued before the base version was recorded must still flush
        // rather than being stuck as permanent conflicts.
        server.enqueue(
            MockResponse().setBody(
                """{"id":7,"project_id":1,"title":"T","done":false,"updated":"2024-01-15T12:00:00Z"}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"id":7,"project_id":1,"title":"T","done":true}"""))

        val result = repository.setTaskDone(taskId = 7, done = true, expectedUpdatedAt = null)

        assertThat(result).isInstanceOf(TaskWriteResult.Applied::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `setTaskDone treats a task with no server timestamp as a conflict against a known base`() = runTest {
        // A server that stops sending `updated` must not be read as "unchanged".
        server.enqueue(MockResponse().setBody("""{"id":7,"project_id":1,"title":"T","done":false}"""))

        val result = repository.setTaskDone(
            taskId = 7,
            done = true,
            expectedUpdatedAt = Instant.parse("2024-01-15T10:00:00Z"),
        )

        assertThat(result).isInstanceOf(TaskWriteResult.Conflict::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `setTaskDone does not write when the read fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val exception = try {
            repository.setTaskDone(taskId = 7, done = true)
            null
        } catch (e: VikunjaSyncException) {
            e
        }

        assertThat(exception).isInstanceOf(VikunjaSyncException.Server::class.java)
        assertThat(server.requestCount).isEqualTo(1)
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
