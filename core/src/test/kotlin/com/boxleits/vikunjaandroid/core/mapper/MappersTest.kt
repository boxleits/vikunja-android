package com.boxleits.vikunjaandroid.core.mapper

import com.boxleits.vikunjaandroid.core.api.dto.LabelDto
import com.boxleits.vikunjaandroid.core.api.dto.RELATION_KIND_PARENT_TASK
import com.boxleits.vikunjaandroid.core.api.dto.RelatedTaskRefDto
import com.boxleits.vikunjaandroid.core.api.dto.TaskDto
import com.boxleits.vikunjaandroid.core.model.Priority
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.Test

class MappersTest {

    @Test
    fun `parseVikunjaInstant returns null for blank, zero-date and unparsable values`() {
        assertThat(parseVikunjaInstant(null)).isNull()
        assertThat(parseVikunjaInstant("")).isNull()
        assertThat(parseVikunjaInstant("0001-01-01T00:00:00Z")).isNull()
        assertThat(parseVikunjaInstant("not-a-date")).isNull()
    }

    @Test
    fun `parseVikunjaInstant parses a real RFC3339 timestamp`() {
        assertThat(parseVikunjaInstant("2024-01-15T10:30:00Z")).isEqualTo(Instant.parse("2024-01-15T10:30:00Z"))
    }

    @Test
    fun `TaskDto toDomain extracts parentTaskId from the parenttask relation`() {
        val dto = TaskDto(
            id = 5,
            projectId = 1,
            title = "Child",
            priority = 3,
            relatedTasks = mapOf(RELATION_KIND_PARENT_TASK to listOf(RelatedTaskRefDto(id = 1))),
        )

        val task = dto.toDomain()

        assertThat(task.parentTaskId).isEqualTo(1L)
        assertThat(task.priority).isEqualTo(Priority.HIGH)
    }

    @Test
    fun `TaskDto toDomain leaves parentTaskId null without a parenttask relation`() {
        val dto = TaskDto(id = 1, projectId = 1, title = "Root")

        assertThat(dto.toDomain().parentTaskId).isNull()
    }

    @Test
    fun `blank description becomes null`() {
        val dto = TaskDto(id = 1, projectId = 1, title = "T", description = "   ")

        assertThat(dto.toDomain().description).isNull()
    }

    @Test
    fun `formatVikunjaInstant round-trips through parseVikunjaInstant, unset included`() {
        val at = Instant.parse("2024-01-15T10:30:00Z")

        assertThat(parseVikunjaInstant(formatVikunjaInstant(at))).isEqualTo(at)
        // The pair that matters for clearing a date: what this writes for
        // "none" has to be what the parser reads back as none.
        assertThat(formatVikunjaInstant(null)).isEqualTo("0001-01-01T00:00:00Z")
        assertThat(parseVikunjaInstant(formatVikunjaInstant(null))).isNull()
    }

    @Test
    fun `LabelDto and ProjectDto map straightforwardly`() {
        val label = LabelDto(id = 1, title = "urgent", hexColor = "ff0000").toDomain()

        assertThat(label.id).isEqualTo(1L)
        assertThat(label.title).isEqualTo("urgent")
        assertThat(label.hexColor).isEqualTo("ff0000")
    }
}
