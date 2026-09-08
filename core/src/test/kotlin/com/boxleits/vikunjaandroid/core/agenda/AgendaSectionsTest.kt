package com.boxleits.vikunjaandroid.core.agenda

import com.boxleits.vikunjaandroid.core.model.Priority
import com.boxleits.vikunjaandroid.core.sampleTask
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.Test

class AgendaSectionsTest {

    private val timeZone = TimeZone.UTC
    private val today = LocalDate(2024, 1, 10)

    private fun instantFor(date: LocalDate) = date.atStartOfDayIn(timeZone)

    @Test
    fun `buckets tasks relative to today`() {
        val tasks = listOf(
            sampleTask(id = 1, title = "Overdue", dueDate = instantFor(LocalDate(2024, 1, 5))),
            sampleTask(id = 2, title = "Today", dueDate = instantFor(today)),
            sampleTask(id = 3, title = "Tomorrow", dueDate = instantFor(LocalDate(2024, 1, 11))),
            sampleTask(id = 4, title = "This week", dueDate = instantFor(LocalDate(2024, 1, 14))),
            sampleTask(id = 5, title = "Later", dueDate = instantFor(LocalDate(2024, 2, 1))),
        )

        val agenda = buildAgenda(tasks, today, timeZone)

        assertThat(agenda.overdue.map { it.task.id }).containsExactly(1L)
        assertThat(agenda.today.map { it.task.id }).containsExactly(2L)
        assertThat(agenda.tomorrow.map { it.task.id }).containsExactly(3L)
        assertThat(agenda.thisWeek.map { it.task.id }).containsExactly(4L)
        assertThat(agenda.later.map { it.task.id }).containsExactly(5L)
    }

    @Test
    fun `done tasks are excluded even with a due date`() {
        val tasks = listOf(sampleTask(id = 1, done = true, dueDate = instantFor(today)))

        val agenda = buildAgenda(tasks, today, timeZone)

        assertThat(agenda.isEmpty).isTrue()
    }

    @Test
    fun `tasks without a due or start date are excluded`() {
        val tasks = listOf(sampleTask(id = 1))

        val agenda = buildAgenda(tasks, today, timeZone)

        assertThat(agenda.isEmpty).isTrue()
    }

    @Test
    fun `start date is used when there is no due date`() {
        val tasks = listOf(sampleTask(id = 1, startDate = instantFor(today)))

        val agenda = buildAgenda(tasks, today, timeZone)

        assertThat(agenda.today).hasSize(1)
        assertThat(agenda.today.single().isDueDate).isFalse()
    }

    @Test
    fun `due date wins over start date when both are set`() {
        val tasks = listOf(
            sampleTask(id = 1, startDate = instantFor(today), dueDate = instantFor(LocalDate(2024, 2, 1))),
        )

        val agenda = buildAgenda(tasks, today, timeZone)

        assertThat(agenda.today).isEmpty()
        assertThat(agenda.later.single().isDueDate).isTrue()
    }

    /** One task in each bucket, so a horizon's cut-off is visible. */
    private fun spreadOfTasks() = listOf(
        sampleTask(id = 1, title = "Overdue", dueDate = instantFor(LocalDate(2024, 1, 5))),
        sampleTask(id = 2, title = "Today", dueDate = instantFor(today)),
        sampleTask(id = 3, title = "Tomorrow", dueDate = instantFor(LocalDate(2024, 1, 11))),
        sampleTask(id = 4, title = "This week", dueDate = instantFor(LocalDate(2024, 1, 14))),
        sampleTask(id = 5, title = "Later", dueDate = instantFor(LocalDate(2024, 3, 1))),
    )

    @Test
    fun `each horizon includes exactly its buckets`() {
        val agenda = buildAgenda(spreadOfTasks(), today, timeZone)

        fun idsFor(horizon: WidgetHorizon) =
            widgetAgenda(agenda, horizon, limit = 10).items.map { it.task.id }

        assertThat(idsFor(WidgetHorizon.TODAY)).containsExactly(1L, 2L)
        assertThat(idsFor(WidgetHorizon.TOMORROW)).containsExactly(1L, 2L, 3L)
        assertThat(idsFor(WidgetHorizon.THIS_WEEK)).containsExactly(1L, 2L, 3L, 4L)
        assertThat(idsFor(WidgetHorizon.EVERYTHING)).containsExactly(1L, 2L, 3L, 4L, 5L)
    }

    @Test
    fun `overdue tasks appear in every horizon`() {
        val tasks = listOf(sampleTask(id = 1, dueDate = instantFor(LocalDate(2024, 1, 5))))
        val agenda = buildAgenda(tasks, today, timeZone)

        WidgetHorizon.entries.forEach { horizon ->
            val widget = widgetAgenda(agenda, horizon, limit = 10)
            assertThat(widget.items.map { it.task.id }).containsExactly(1L)
            assertThat(widget.showingUpcoming).isFalse()
        }
    }

    @Test
    fun `falls back to what lies beyond the horizon rather than showing nothing`() {
        val tasks = listOf(
            sampleTask(id = 4, title = "This week", dueDate = instantFor(LocalDate(2024, 1, 14))),
            sampleTask(id = 5, title = "Later", dueDate = instantFor(LocalDate(2024, 3, 1))),
        )

        val widget = widgetAgenda(buildAgenda(tasks, today, timeZone), WidgetHorizon.TODAY, limit = 8)

        assertThat(widget.items.map { it.task.id }).containsExactly(4L, 5L).inOrder()
        assertThat(widget.showingUpcoming).isTrue()
    }

    @Test
    fun `the widest horizon has nothing to fall back to`() {
        val widget = widgetAgenda(
            buildAgenda(spreadOfTasks(), today, timeZone),
            WidgetHorizon.EVERYTHING,
            limit = 10,
        )

        assertThat(widget.showingUpcoming).isFalse()
    }

    @Test
    fun `widget respects the item limit`() {
        val tasks = (1L..10L).map { sampleTask(id = it, dueDate = instantFor(today)) }

        val widget = widgetAgenda(buildAgenda(tasks, today, timeZone), WidgetHorizon.TODAY, limit = 3)

        assertThat(widget.items).hasSize(3)
    }

    @Test
    fun `widget is empty when there is nothing dated at all`() {
        val agenda = buildAgenda(listOf(sampleTask(id = 1)), today, timeZone)

        val widget = widgetAgenda(agenda, WidgetHorizon.TOMORROW, limit = 8)

        assertThat(widget.items).isEmpty()
    }

    @Test
    fun `same-day tasks sort by priority then title`() {
        val tasks = listOf(
            sampleTask(id = 1, title = "B low", dueDate = instantFor(today), priority = Priority.LOW),
            sampleTask(id = 2, title = "A urgent", dueDate = instantFor(today), priority = Priority.URGENT),
            sampleTask(id = 3, title = "A low", dueDate = instantFor(today), priority = Priority.LOW),
        )

        val agenda = buildAgenda(tasks, today, timeZone)

        assertThat(agenda.today.map { it.task.id }).containsExactly(2L, 3L, 1L).inOrder()
    }

    @Test
    fun `widget sorting by date puts the soonest first, across buckets`() {
        val tasks = listOf(
            sampleTask(id = 1, title = "Later low", dueDate = instantFor(LocalDate(2024, 1, 14))),
            sampleTask(id = 2, title = "Overdue", dueDate = instantFor(LocalDate(2024, 1, 5))),
            sampleTask(id = 3, title = "Today", dueDate = instantFor(today)),
        )

        val widget = widgetAgenda(
            buildAgenda(tasks, today, timeZone),
            WidgetHorizon.EVERYTHING,
            sort = WidgetSort.DATE,
        )

        assertThat(widget.items.map { it.task.id }).containsExactly(2L, 3L, 1L).inOrder()
    }

    @Test
    fun `widget sorting by priority beats the date order`() {
        val tasks = listOf(
            sampleTask(id = 1, title = "Soon but trivial", dueDate = instantFor(today), priority = Priority.UNSET),
            sampleTask(
                id = 2,
                title = "Far off but urgent",
                dueDate = instantFor(LocalDate(2024, 2, 1)),
                priority = Priority.DO_NOW,
            ),
        )

        val widget = widgetAgenda(
            buildAgenda(tasks, today, timeZone),
            WidgetHorizon.EVERYTHING,
            sort = WidgetSort.PRIORITY,
        )

        assertThat(widget.items.map { it.task.id }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `widget sorting by title ignores case`() {
        val tasks = listOf(
            sampleTask(id = 1, title = "banana", dueDate = instantFor(today)),
            sampleTask(id = 2, title = "Apple", dueDate = instantFor(today)),
        )

        val widget = widgetAgenda(
            buildAgenda(tasks, today, timeZone),
            WidgetHorizon.EVERYTHING,
            sort = WidgetSort.TITLE,
        )

        assertThat(widget.items.map { it.task.id }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `the fallback to upcoming items is sorted too`() {
        // Nothing due today, so the widget falls back — that list needs the
        // chosen order just as much as the primary one.
        val tasks = listOf(
            sampleTask(id = 1, title = "zulu", dueDate = instantFor(LocalDate(2024, 2, 1))),
            sampleTask(id = 2, title = "alpha", dueDate = instantFor(LocalDate(2024, 2, 2))),
        )

        val widget = widgetAgenda(
            buildAgenda(tasks, today, timeZone),
            WidgetHorizon.TODAY,
            sort = WidgetSort.TITLE,
        )

        assertThat(widget.showingUpcoming).isTrue()
        assertThat(widget.items.map { it.task.id }).containsExactly(2L, 1L).inOrder()
    }

}
