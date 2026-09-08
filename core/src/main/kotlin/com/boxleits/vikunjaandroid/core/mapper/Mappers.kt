package com.boxleits.vikunjaandroid.core.mapper

import com.boxleits.vikunjaandroid.core.api.dto.LabelDto
import com.boxleits.vikunjaandroid.core.api.dto.ProjectDto
import com.boxleits.vikunjaandroid.core.api.dto.RELATION_KIND_PARENT_TASK
import com.boxleits.vikunjaandroid.core.api.dto.TaskDto
import com.boxleits.vikunjaandroid.core.model.Label
import com.boxleits.vikunjaandroid.core.model.Priority
import com.boxleits.vikunjaandroid.core.model.Project
import com.boxleits.vikunjaandroid.core.model.Task
import kotlinx.datetime.Instant

/** Vikunja represents "no date set" with this zero-value timestamp rather than `null`. */
private const val VIKUNJA_ZERO_DATE_PREFIX = "0001-01-01"

/**
 * What to send to clear a date.
 *
 * Omitting the field would leave the old value standing, and Vikunja rejects
 * `null` for it, so "no date" has to be written as the zero date it reads back
 * as unset. [parseVikunjaInstant] is the other half of this round trip.
 */
private const val VIKUNJA_ZERO_DATE = "0001-01-01T00:00:00Z"

/** Parses a Vikunja RFC3339 timestamp, treating blank/zero-date/unparsable values as "unset". */
fun parseVikunjaInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank() || raw.startsWith(VIKUNJA_ZERO_DATE_PREFIX)) return null
    return try {
        Instant.parse(raw)
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Renders an instant for Vikunja's API, writing "no date" as the zero date it expects. */
fun formatVikunjaInstant(instant: Instant?): String = instant?.toString() ?: VIKUNJA_ZERO_DATE

fun LabelDto.toDomain(): Label = Label(id = id, title = title, hexColor = hexColor)

fun ProjectDto.toDomain(): Project = Project(
    id = id,
    title = title,
    parentProjectId = parentProjectId,
    hexColor = hexColor,
    isArchived = isArchived,
)

fun TaskDto.toDomain(): Task = Task(
    id = id,
    projectId = projectId,
    parentTaskId = relatedTasks?.get(RELATION_KIND_PARENT_TASK)?.firstOrNull()?.id,
    title = title,
    description = description?.takeIf { it.isNotBlank() },
    done = done,
    doneAt = parseVikunjaInstant(doneAt),
    priority = Priority.fromValue(priority),
    dueDate = parseVikunjaInstant(dueDate),
    startDate = parseVikunjaInstant(startDate),
    endDate = parseVikunjaInstant(endDate),
    labels = labels.orEmpty().map { it.toDomain() },
    position = position,
    updatedAt = parseVikunjaInstant(updated),
)
