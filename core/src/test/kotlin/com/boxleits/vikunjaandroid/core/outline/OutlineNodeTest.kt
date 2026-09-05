package com.boxleits.vikunjaandroid.core.outline

import com.boxleits.vikunjaandroid.core.sampleTask
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutlineNodeTest {

    @Test
    fun `builds a nested tree from parentTaskId`() {
        val tasks = listOf(
            sampleTask(id = 1, title = "Root"),
            sampleTask(id = 2, parentTaskId = 1, title = "Child A"),
            sampleTask(id = 3, parentTaskId = 1, title = "Child B"),
            sampleTask(id = 4, parentTaskId = 2, title = "Grandchild"),
        )

        val outline = buildOutline(tasks)

        assertThat(outline).hasSize(1)
        val root = outline.single()
        assertThat(root.task.id).isEqualTo(1)
        assertThat(root.children.map { it.task.id }).containsExactly(2L, 3L).inOrder()
        val childA = root.children.first { it.task.id == 2L }
        assertThat(childA.children.map { it.task.id }).containsExactly(4L)
    }

    @Test
    fun `task with a parent outside the given list becomes its own root`() {
        val tasks = listOf(
            sampleTask(id = 2, parentTaskId = 999, title = "Orphan"),
        )

        val outline = buildOutline(tasks)

        assertThat(outline.map { it.task.id }).containsExactly(2L)
    }

    @Test
    fun `a parent cycle produces no roots instead of recursing forever`() {
        val tasks = listOf(
            sampleTask(id = 1, parentTaskId = 2, title = "A"),
            sampleTask(id = 2, parentTaskId = 1, title = "B"),
        )

        // Both tasks declare a parent that's present in the list, so neither
        // qualifies as a root under buildOutline's rule; the tree is empty
        // rather than the call recursing forever.
        val outline = buildOutline(tasks)

        assertThat(outline).isEmpty()
    }

    @Test
    fun `done tasks sort after not-done tasks at the same level`() {
        val tasks = listOf(
            sampleTask(id = 1, done = true, position = 0.0, title = "Done first by position"),
            sampleTask(id = 2, done = false, position = 1.0, title = "Not done"),
        )

        val outline = buildOutline(tasks)

        assertThat(outline.map { it.task.id }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `buildOutlineByProject groups roots per project`() {
        val tasks = listOf(
            sampleTask(id = 1, projectId = 10, title = "P10 root"),
            sampleTask(id = 2, projectId = 20, title = "P20 root"),
        )

        val byProject = buildOutlineByProject(tasks)

        assertThat(byProject.keys).containsExactly(10L, 20L)
        assertThat(byProject.getValue(10L).single().task.id).isEqualTo(1)
        assertThat(byProject.getValue(20L).single().task.id).isEqualTo(2)
    }
}
