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

    @Test
    fun `widget prefers imminent tasks`() {
        val tasks = listOf(
            sampleTask(id = 1, title = "Today", dueDate = instantFor(today)),
            sampleTask(id = 2, title = "Later", dueDate = instantFor(LocalDate(2024, 3, 1))),
        )

        val widget = widgetAgenda(buildAgenda(tasks, today, timeZone), limit = 8)

        assertThat(widget.items.map { it.task.id }).containsExactly(1L)
        assertThat(widget.showingUpcoming).isFalse()
    }

    @Test
    fun `widget falls back to upcoming tasks when nothing is imminent`() {
        val tasks = listOf(
            sampleTask(id = 1, title = "This week", dueDate = instantFor(LocalDate(2024, 1, 14))),
            sampleTask(id = 2, title = "Later", dueDate = instantFor(LocalDate(2024, 3, 1))),
        )

        val widget = widgetAgenda(buildAgenda(tasks, today, timeZone), limit = 8)

        assertThat(widget.items.map { it.task.id }).containsExactly(1L, 2L).inOrder()
        assertThat(widget.showingUpcoming).isTrue()
    }

    @Test
    fun `widget respects the item limit`() {
        val tasks = (1L..10L).map { sampleTask(id = it, dueDate = instantFor(today)) }

        val widget = widgetAgenda(buildAgenda(tasks, today, timeZone), limit = 3)

        assertThat(widget.items).hasSize(3)
    }

    @Test
    fun `widget is empty when there is nothing dated at all`() {
        val widget = widgetAgenda(buildAgenda(listOf(sampleTask(id = 1)), today, timeZone), limit = 8)

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
}
