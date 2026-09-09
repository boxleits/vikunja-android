package com.boxleits.vikunjaandroid.core.agenda

import com.boxleits.vikunjaandroid.core.model.Task
import kotlinx.datetime.Instant
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
    /**
     * The moment [date] was derived from, kept so the UI can show a time of
     * day. [date] alone drops it, and "due today" reads very differently from
     * "due today at 09:00" when it is already the afternoon.
     */
    val at: Instant,
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

    /** Every item, in the order the sections are shown. */
    val all: List<AgendaItem>
        get() = overdue + today + tomorrow + thisWeek + later

    companion object {
        val EMPTY = AgendaSections(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
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
                AgendaItem(task, date, bucketFor(date, today), isDueDate = task.dueDate != null, at = it)
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

/**
 * How far ahead the agenda looks — on the screen and in the widget alike.
 * Overdue is always included.
 */
enum class AgendaHorizon {
    TODAY,
    TOMORROW,
    THIS_WEEK,
    EVERYTHING,
}

/**
 * How the agenda orders what it shows.
 *
 * Orgzly has no equivalent setting: its widget renders a saved search, and the
 * ordering rides along in the query string. Without a query language of our
 * own, an explicit choice is the honest substitute.
 */
enum class AgendaSort {
    /** Soonest first — what an agenda is usually for. */
    DATE,

    /** Highest priority first, date breaking ties. */
    PRIORITY,

    /** Alphabetical, for finding a known task by name. */
    TITLE,
}

/**
 * A ceiling on how much the widget builds, not a user setting: the list
 * scrolls, so there is nothing to gain by cutting it short, but RemoteViews
 * collections are not free and an unbounded list is a bad idea on a home
 * screen.
 */
const val WIDGET_MAX_ITEMS = 100

internal fun AgendaSort.comparator(): Comparator<AgendaItem> = when (this) {
    AgendaSort.DATE -> compareBy({ it.date }, { -it.task.priority.value }, { it.task.title })
    AgendaSort.PRIORITY -> compareBy({ -it.task.priority.value }, { it.date }, { it.task.title })
    AgendaSort.TITLE -> compareBy({ it.task.title.lowercase() }, { it.date })
}

/**
 * The same agenda, narrowed to [horizon] and re-sorted by [sort].
 *
 * Used by the agenda screen, which keeps its sections — unlike the widget,
 * which flattens them. Sections outside the horizon come back empty rather
 * than being dropped, so the caller still knows which is which. Overdue
 * survives every horizon: something already late is the last thing to hide.
 */
fun AgendaSections.limitedTo(horizon: AgendaHorizon, sort: AgendaSort): AgendaSections {
    val order = sort.comparator()
    fun keep(items: List<AgendaItem>, included: Boolean) =
        if (included) items.sortedWith(order) else emptyList()

    return AgendaSections(
        overdue = overdue.sortedWith(order),
        today = today.sortedWith(order),
        tomorrow = keep(tomorrow, horizon >= AgendaHorizon.TOMORROW),
        thisWeek = keep(thisWeek, horizon >= AgendaHorizon.THIS_WEEK),
        later = keep(later, horizon >= AgendaHorizon.EVERYTHING),
    )
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
    horizon: AgendaHorizon,
    sort: AgendaSort = AgendaSort.DATE,
    limit: Int = WIDGET_MAX_ITEMS,
): WidgetAgenda {
    // Overdue is in every horizon: something already late is the last thing
    // a widget should hide.
    val within = buildList {
        addAll(sections.overdue)
        addAll(sections.today)
        if (horizon >= AgendaHorizon.TOMORROW) addAll(sections.tomorrow)
        if (horizon >= AgendaHorizon.THIS_WEEK) addAll(sections.thisWeek)
        if (horizon >= AgendaHorizon.EVERYTHING) addAll(sections.later)
    }
    if (within.isNotEmpty()) {
        // Sorted across buckets rather than within them: the widget shows one
        // flat list, so an order that only held inside each bucket would look
        // arbitrary where the buckets meet.
        return WidgetAgenda(within.sortedWith(sort.comparator()).take(limit), showingUpcoming = false)
    }

    val beyond = buildList {
        if (horizon < AgendaHorizon.TOMORROW) addAll(sections.tomorrow)
        if (horizon < AgendaHorizon.THIS_WEEK) addAll(sections.thisWeek)
        if (horizon < AgendaHorizon.EVERYTHING) addAll(sections.later)
    }
    return WidgetAgenda(beyond.sortedWith(sort.comparator()).take(limit), showingUpcoming = true)
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
