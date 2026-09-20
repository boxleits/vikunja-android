package com.boxleits.vikunjaandroid.core.repository

import com.boxleits.vikunjaandroid.core.api.VikunjaApiClient
import com.boxleits.vikunjaandroid.core.api.dto.RELATION_KIND_COPIED_FROM
import com.boxleits.vikunjaandroid.core.model.Priority
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
    fun `a base version from a write response still matches what the server reads back`() = runTest {
        // Reported from a device: a task created offline, then edited offline,
        // came back as a conflict with itself on the first sync — and the
        // conflict handling then replaced it with the server's copy, losing the
        // priority and due date the edit carried.
        //
        // Vikunja's `updated` column is a DATETIME that xorm writes formatted
        // to whole seconds, but the task in a create or update *response* is
        // serialised from the in-memory struct, where the full-precision
        // time.Now() is still sitting. So a write reports one stamp and reads
        // back as another, and an exact comparison calls that somebody else's
        // change.
        val fromWriteResponse = Instant.parse("2024-01-15T10:00:00.345678901Z")
        server.enqueue(
            MockResponse().setBody(
                """{"id":7,"project_id":1,"title":"T","updated":"2024-01-15T10:00:00Z"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"id":7,"project_id":1,"title":"Edited","updated":"2024-01-15T10:05:00Z"}""",
            ),
        )

        val result = repository.updateTask(
            taskId = 7,
            edits = TaskEdits(title = "Edited", priority = Priority.HIGH, dueDate = null),
            expectedUpdatedAt = fromWriteResponse,
        )

        assertThat(result).isInstanceOf(TaskWriteResult.Applied::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a difference of whole seconds is still a conflict`() = runTest {
        // The precision tolerance must not swallow a real change: one second
        // apart is a different version, not a rounding artefact.
        server.enqueue(
            MockResponse().setBody(
                """{"id":7,"project_id":1,"title":"Renamed elsewhere","updated":"2024-01-15T10:00:01Z"}""",
            ),
        )

        val result = repository.setTaskDone(
            taskId = 7,
            done = true,
            expectedUpdatedAt = Instant.parse("2024-01-15T10:00:00.900000000Z"),
        )

        assertThat(result).isInstanceOf(TaskWriteResult.Conflict::class.java)
        assertThat(server.requestCount).isEqualTo(1)
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
    fun `updateTask writes the edited fields and keeps the ones it does not show`() = runTest {
        // The edit form covers three fields. Everything else on the task —
        // including `done`, which has its own path — has to come back
        // untouched, or editing a title would silently un-tick a task.
        val serverTask = """
            {
              "id": 7,
              "project_id": 1,
              "title": "Buy milk",
              "description": "semi-skimmed",
              "done": true,
              "priority": 1,
              "due_date": "2024-01-15T10:00:00Z",
              "percent_done": 40,
              "assignees": [{"id": 3}]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(serverTask))
        server.enqueue(MockResponse().setBody(serverTask))

        repository.updateTask(
            taskId = 7,
            edits = TaskEdits(
                title = "Buy oat milk",
                priority = Priority.URGENT,
                dueDate = Instant.parse("2024-02-01T09:30:00Z"),
            ),
        )

        server.takeRequest() // the read the write is built on
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

        assertThat(sent["title"]!!.jsonPrimitive.content).isEqualTo("Buy oat milk")
        assertThat(sent["priority"]!!.jsonPrimitive.int).isEqualTo(4)
        assertThat(sent["due_date"]!!.jsonPrimitive.content).isEqualTo("2024-02-01T09:30:00Z")
        assertThat(sent["done"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(sent["description"]!!.jsonPrimitive.content).isEqualTo("semi-skimmed")
        assertThat(sent["percent_done"]!!.jsonPrimitive.int).isEqualTo(40)
        assertThat(sent).containsKey("assignees")
    }

    @Test
    fun `updateTask clears a due date with the zero date rather than omitting the field`() = runTest {
        // Leaving due_date out would keep the old date, and Vikunja rejects
        // null for it, so "no due date" has to be said as the zero date.
        server.enqueue(
            MockResponse().setBody(
                """{"id":7,"project_id":1,"title":"T","due_date":"2024-01-15T10:00:00Z"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"id":7,"project_id":1,"title":"T","due_date":"0001-01-01T00:00:00Z"}""",
            ),
        )

        val result = repository.updateTask(
            taskId = 7,
            edits = TaskEdits(title = "T", priority = Priority.UNSET, dueDate = null),
        )

        server.takeRequest()
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertThat(sent["due_date"]!!.jsonPrimitive.content).isEqualTo("0001-01-01T00:00:00Z")

        // And the same zero date has to read back as "no date", closing the
        // round trip rather than resurfacing as a date in the year one.
        assertThat(result).isInstanceOf(TaskWriteResult.Applied::class.java)
        assertThat((result as TaskWriteResult.Applied).task.dueDate).isNull()
    }

    @Test
    fun `updateTask reports a conflict and writes nothing when the task moved on`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":7,"project_id":1,"title":"Renamed elsewhere","updated":"2024-01-15T12:00:00Z"}""",
            ),
        )

        val result = repository.updateTask(
            taskId = 7,
            edits = TaskEdits(title = "Renamed here", priority = Priority.HIGH, dueDate = null),
            expectedUpdatedAt = Instant.parse("2024-01-15T10:00:00Z"),
        )

        assertThat(result).isInstanceOf(TaskWriteResult.Conflict::class.java)
        assertThat((result as TaskWriteResult.Conflict).serverTask.title).isEqualTo("Renamed elsewhere")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `createTask sends only a title for a plain capture`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":9,"project_id":1,"title":"Milk"}"""))

        repository.createTask(projectId = 1, title = "Milk")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.path).isEqualTo("/api/v1/projects/1/tasks")
        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        // Anything the server would default to anyway stays off the wire, so a
        // quick capture is not carrying a conflict copy's baggage.
        assertThat(sent.keys).containsExactly("title")
    }

    @Test
    fun `createTask carries done, priority and due date when a copy needs them`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":9,"project_id":1,"title":"Milk"}"""))

        repository.createTask(
            projectId = 1,
            title = "Milk",
            done = true,
            priority = Priority.HIGH,
            dueDate = Instant.parse("2024-02-01T09:30:00Z"),
        )

        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertThat(sent["title"]!!.jsonPrimitive.content).isEqualTo("Milk")
        assertThat(sent["done"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(sent["priority"]!!.jsonPrimitive.int).isEqualTo(3)
        assertThat(sent["due_date"]!!.jsonPrimitive.content).isEqualTo("2024-02-01T09:30:00Z")
    }

    @Test
    fun `relateTask points the copy at its original and lets Vikunja add the inverse`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))

        repository.relateTask(taskId = 9, otherTaskId = 7, kind = RELATION_KIND_COPIED_FROM)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.path).isEqualTo("/api/v1/tasks/9/relations")
        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertThat(sent["other_task_id"]!!.jsonPrimitive.long).isEqualTo(7L)
        assertThat(sent["relation_kind"]!!.jsonPrimitive.content).isEqualTo("copiedfrom")
        // Only the forward direction: Vikunja writes the copiedto side itself,
        // so sending it too would be a second, redundant relation.
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `createLabel and addLabelToTask use the routes and payloads Vikunja expects`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":4,"title":"sync-conflict","hex_color":"e8412c"}"""))
        server.enqueue(MockResponse().setBody("{}"))

        val label = repository.createLabel(title = "sync-conflict", hexColor = "e8412c")
        repository.addLabelToTask(taskId = 9, labelId = label.id)

        val createRequest = server.takeRequest()
        assertThat(createRequest.method).isEqualTo("PUT")
        assertThat(createRequest.path).isEqualTo("/api/v1/labels")
        val createBody = Json.parseToJsonElement(createRequest.body.readUtf8()).jsonObject
        assertThat(createBody["title"]!!.jsonPrimitive.content).isEqualTo("sync-conflict")
        assertThat(createBody["hex_color"]!!.jsonPrimitive.content).isEqualTo("e8412c")
        assertThat(label.id).isEqualTo(4L)

        val attachRequest = server.takeRequest()
        assertThat(attachRequest.method).isEqualTo("PUT")
        assertThat(attachRequest.path).isEqualTo("/api/v1/tasks/9/labels")
        val attachBody = Json.parseToJsonElement(attachRequest.body.readUtf8()).jsonObject
        // The task is in the path; only the label id belongs in the body.
        assertThat(attachBody.keys).containsExactly("label_id")
        assertThat(attachBody["label_id"]!!.jsonPrimitive.long).isEqualTo(4L)
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

    @Test
    fun `createTask puts the title to the project's task collection`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":42,"project_id":3,"title":"Buy milk"}"""))

        val task = repository.createTask(projectId = 3, title = "Buy milk")

        val request = server.takeRequest()
        // PUT creates and POST updates in Vikunja — the reverse of the usual
        // convention, so this assertion is the guard against "fixing" it.
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.path).isEqualTo("/api/v1/projects/3/tasks")
        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertThat(sent["title"]!!.jsonPrimitive.content).isEqualTo("Buy milk")

        assertThat(task.id).isEqualTo(42L)
        assertThat(task.title).isEqualTo("Buy milk")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `createTask hangs the new task under a parent with a second request`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":42,"project_id":3,"title":"Sub"}"""))
        server.enqueue(MockResponse().setBody("""{"other_task_id":7,"relation_kind":"parenttask"}"""))

        val task = repository.createTask(projectId = 3, title = "Sub", parentTaskId = 7)

        server.takeRequest() // the create
        val relationRequest = server.takeRequest()
        assertThat(relationRequest.method).isEqualTo("PUT")
        assertThat(relationRequest.path).isEqualTo("/api/v1/tasks/42/relations")
        val sent = Json.parseToJsonElement(relationRequest.body.readUtf8()).jsonObject
        assertThat(sent["other_task_id"]!!.jsonPrimitive.long).isEqualTo(7L)
        assertThat(sent["relation_kind"]!!.jsonPrimitive.content).isEqualTo("parenttask")

        // The returned task carries the parent the caller asked for, since the
        // create response predates the relation and doesn't mention it.
        assertThat(task.parentTaskId).isEqualTo(7L)
    }

    @Test
    fun `createTask sends no relation request when there is no parent`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":42,"project_id":3,"title":"Top level"}"""))

        val task = repository.createTask(projectId = 3, title = "Top level", parentTaskId = null)

        assertThat(task.parentTaskId).isNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `createTask surfaces a rejected create`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"no write access"}"""))

        val exception = try {
            repository.createTask(projectId = 3, title = "Nope")
            null
        } catch (e: VikunjaSyncException) {
            e
        }

        assertThat(exception).isInstanceOf(VikunjaSyncException.Server::class.java)
        assertThat((exception as VikunjaSyncException.Server).statusCode).isEqualTo(403)
    }

}
