package com.boxleits.vikunjaandroid.core.outline

import com.boxleits.vikunjaandroid.core.model.Task

data class OutlineNode(
    val task: Task,
    val children: List<OutlineNode>,
)

private val outlineOrder = compareBy<Task>({ it.done }, { it.position }, { it.title })

/**
 * Turns a flat list of tasks into an org-mode-style outline tree using each
 * task's [Task.parentTaskId]. A task whose declared parent isn't present in
 * [tasks] (different project, deleted, etc.) becomes a root itself, and a
 * `visited` guard keeps a malformed parent cycle from recursing forever.
 */
fun buildOutline(tasks: List<Task>): List<OutlineNode> {
    val byParent = tasks.groupBy { it.parentTaskId }
    val idsInList = tasks.mapTo(HashSet()) { it.id }

    fun buildNode(task: Task, visited: Set<Long>): OutlineNode {
        val children = byParent[task.id].orEmpty()
            .filter { it.id !in visited }
            .sortedWith(outlineOrder)
            .map { buildNode(it, visited + task.id) }
        return OutlineNode(task, children)
    }

    val roots = tasks.filter { it.parentTaskId == null || it.parentTaskId !in idsInList }

    return roots
        .sortedWith(outlineOrder)
        .map { buildNode(it, setOf(it.id)) }
}

/** Groups tasks by project first, then builds an outline within each project. */
fun buildOutlineByProject(tasks: List<Task>): Map<Long, List<OutlineNode>> =
    tasks.groupBy { it.projectId }.mapValues { (_, projectTasks) -> buildOutline(projectTasks) }
