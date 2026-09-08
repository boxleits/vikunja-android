package com.boxleits.vikunjaandroid.ui.outline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxleits.vikunjaandroid.core.model.Project
import com.boxleits.vikunjaandroid.core.outline.OutlineNode
import com.boxleits.vikunjaandroid.data.sync.ProjectOutline
import com.boxleits.vikunjaandroid.data.sync.TaskConflict
import com.boxleits.vikunjaandroid.ui.components.OutlineTaskRow

/**
 * Tells the user that edits of theirs were dropped in favour of the server's
 * newer version, and which tasks it happened to.
 */
/**
 * Capture: a title, and which project it lands in.
 *
 * Deliberately the smallest thing that works. Everything else a task can carry
 * — dates, priority, labels — is an edit after the fact, and making capture
 * wait for those decisions is how a quick-add stops being quick.
 */
@Composable
private fun CreateTaskDialog(
    projects: List<Project>,
    defaultProjectId: suspend () -> Long?,
    onDismiss: () -> Unit,
    onCreate: (projectId: Long, title: String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var selectedProjectId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Resolved once: the project last captured into, which is nearly always
    // the one wanted again.
    LaunchedEffect(Unit) {
        if (selectedProjectId == null) selectedProjectId = defaultProjectId()
    }

    val projectId = selectedProjectId ?: projects.firstOrNull()?.id

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New task") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (projects.size > 1) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Project", style = MaterialTheme.typography.labelLarge)
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        projects.forEach { project ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedProjectId = project.id },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = project.id == projectId,
                                    onClick = { selectedProjectId = project.id },
                                )
                                Text(text = project.title, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { projectId?.let { onCreate(it, title) } },
                enabled = title.isNotBlank() && projectId != null,
            ) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConflictDialog(
    conflicts: List<TaskConflict>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (conflicts.size == 1) "A task changed on the server" else "Tasks changed on the server",
            )
        },
        text = {
            Column {
                Text(
                    text = if (conflicts.size == 1) {
                        "This task was changed on the server in the meantime. Your " +
                            "change to it was undone, and it now shows the most " +
                            "recent version from the server."
                    } else {
                        "These tasks were changed on the server in the meantime. Your " +
                            "changes to them were undone, and they now show the most " +
                            "recent version from the server."
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                conflicts.forEach { conflict ->
                    Text(
                        text = "• ${conflict.taskTitle}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineScreen(viewModel: OutlineViewModel = hiltViewModel()) {
    val projectOutlines by viewModel.outline.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreate by rememberSaveable { mutableStateOf(false) }

    if (showCreate) {
        CreateTaskDialog(
            projects = projectOutlines.map { it.project },
            defaultProjectId = { viewModel.defaultProjectId() },
            onDismiss = { showCreate = false },
            onCreate = { projectId, title ->
                viewModel.createTask(projectId, title)
                showCreate = false
            },
        )
    }

    // A dialog rather than a snackbar: the user's change was thrown away, and
    // a message that disappears on its own is the wrong way to say so.
    if (conflicts.isNotEmpty()) {
        ConflictDialog(conflicts = conflicts, onDismiss = viewModel::acknowledgeConflicts)
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Outline") },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Sync now")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Only offered once a project exists to put a task in — Vikunja
            // has no notion of a task without one.
            if (projectOutlines.isNotEmpty()) {
                FloatingActionButton(onClick = { showCreate = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New task")
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (projectOutlines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No tasks yet. Pull down to sync.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                OutlineList(projectOutlines, onSetDone = viewModel::setDone)
            }
        }
    }
}

@Composable
private fun OutlineList(
    projectOutlines: List<ProjectOutline>,
    onSetDone: (taskId: Long, done: Boolean) -> Unit,
) {
    val collapsedIds = rememberSaveable(saver = LongSetSaver) { mutableStateOf<Set<Long>>(emptySet()) }
    val collapsed = collapsedIds.value

    // Flattened once per actual change, rather than inside the LazyColumn's
    // content lambda: that lambda re-runs whenever anything it reads changes,
    // so the walk over every tree was repeating on each collapse toggle.
    val sections = remember(projectOutlines, collapsed) {
        projectOutlines.map { it to flattenOutline(it.nodes, depth = 0, collapsedIds = collapsed) }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        sections.forEach { (projectOutline, visibleRows) ->
            item(key = "project-${projectOutline.project.id}") {
                Text(
                    text = projectOutline.project.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(visibleRows, key = { (node, _) -> "task-${node.task.id}" }) { (node, depth) ->
                OutlineTaskRow(
                    node = node,
                    depth = depth,
                    isCollapsed = node.task.id in collapsed,
                    onToggleCollapse = {
                        collapsedIds.value = collapsedIds.value.toggle(node.task.id)
                    },
                    onSetDone = { done -> onSetDone(node.task.id, done) },
                )
            }
        }
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

private fun flattenOutline(
    nodes: List<OutlineNode>,
    depth: Int,
    collapsedIds: Set<Long>,
): List<Pair<OutlineNode, Int>> {
    val result = mutableListOf<Pair<OutlineNode, Int>>()
    for (node in nodes) {
        result += node to depth
        if (node.children.isNotEmpty() && node.task.id !in collapsedIds) {
            result += flattenOutline(node.children, depth + 1, collapsedIds)
        }
    }
    return result
}

private val LongSetSaver = Saver<MutableState<Set<Long>>, LongArray>(
    save = { it.value.toLongArray() },
    restore = { mutableStateOf(it.toSet()) },
)
