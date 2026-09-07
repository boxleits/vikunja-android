package com.boxleits.vikunjaandroid.core.agenda

import com.boxleits.vikunjaandroid.core.model.Task
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

enum class AgendaBucket {
    OVERDUE,
    TODAY,
    TOMORROW,
    THIS_WEEK,
    LATER,
}

data class AgendaItem(
    val task: Task,
    val date: LocalDate,
    val bucket: AgendaBucket,
    /** true if [date] came from the due date, false if it came from the scheduled/start date. */
    val isDueDate: Boolean,
)

data class AgendaSections(
    val overdue: List<AgendaItem>,
    val today: List<AgendaItem>,
    val tomorrow: List<AgendaItem>,
    val thisWeek: List<AgendaItem>,
    val later: List<AgendaItem>,
) {
    val isEmpty: Boolean
        get() = overdue.isEmpty() && today.isEmpty() && tomorrow.isEmpty() && thisWeek.isEmpty() && later.isEmpty()
}

/**
 * Builds an Orgzly-style agenda: not-done tasks with a due or scheduled
 * (start) date, bucketed relative to [today]. A due date takes precedence
 * over a start date when a task has both.
 */
fun buildAgenda(
    tasks: List<Task>,
    today: LocalDate,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): AgendaSections {
    val items = tasks
        .asSequence()
        .filter { !it.done }
        .mapNotNull { task ->
            val instant = task.dueDate ?: task.startDate
            instant?.let {
                val date = it.toLocalDateTime(timeZone).date
                AgendaItem(task, date, bucketFor(date, today), isDueDate = task.dueDate != null)
            }
        }
        .toList()

    val grouped = items.groupBy { it.bucket }
    val itemOrder = compareBy<AgendaItem>({ it.date }, { -it.task.priority.value }, { it.task.title })
    fun section(bucket: AgendaBucket) = grouped[bucket].orEmpty().sortedWith(itemOrder)

    return AgendaSections(
        overdue = section(AgendaBucket.OVERDUE),
        today = section(AgendaBucket.TODAY),
        tomorrow = section(AgendaBucket.TOMORROW),
        thisWeek = section(AgendaBucket.THIS_WEEK),
        later = section(AgendaBucket.LATER),
    )
}

/** How far ahead the home screen widget looks. Overdue is always included. */
enum class WidgetHorizon {
    TODAY,
    TOMORROW,
    THIS_WEEK,
    EVERYTHING,
}

/**
 * What the home screen widget shows: whatever falls inside the chosen
 * horizon, or — rather than sitting empty when nothing is due that soon —
 * the next items beyond it. [showingUpcoming] lets the widget say which.
 */
data class WidgetAgenda(
    val items: List<AgendaItem>,
    val showingUpcoming: Boolean,
)

fun widgetAgenda(
    sections: AgendaSections,
    horizon: WidgetHorizon,
    limit: Int,
): WidgetAgenda {
    // Overdue is in every horizon: something already late is the last thing
    // a widget should hide.
    val within = buildList {
        addAll(sections.overdue)
        addAll(sections.today)
        if (horizon >= WidgetHorizon.TOMORROW) addAll(sections.tomorrow)
        if (horizon >= WidgetHorizon.THIS_WEEK) addAll(sections.thisWeek)
        if (horizon >= WidgetHorizon.EVERYTHING) addAll(sections.later)
    }
    if (within.isNotEmpty()) {
        return WidgetAgenda(within.take(limit), showingUpcoming = false)
    }

    val beyond = buildList {
        if (horizon < WidgetHorizon.TOMORROW) addAll(sections.tomorrow)
        if (horizon < WidgetHorizon.THIS_WEEK) addAll(sections.thisWeek)
        if (horizon < WidgetHorizon.EVERYTHING) addAll(sections.later)
    }
    return WidgetAgenda(beyond.take(limit), showingUpcoming = true)
}

private fun bucketFor(date: LocalDate, today: LocalDate): AgendaBucket {
    val daysBetween = today.daysUntil(date)
    return when {
        daysBetween < 0 -> AgendaBucket.OVERDUE
        daysBetween == 0 -> AgendaBucket.TODAY
        daysBetween == 1 -> AgendaBucket.TOMORROW
        daysBetween in 2..6 -> AgendaBucket.THIS_WEEK
        else -> AgendaBucket.LATER
    }
}
