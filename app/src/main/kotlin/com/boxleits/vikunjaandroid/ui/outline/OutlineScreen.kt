package com.boxleits.vikunjaandroid.ui.outline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxleits.vikunjaandroid.core.outline.OutlineNode
import com.boxleits.vikunjaandroid.data.sync.ProjectOutline
import com.boxleits.vikunjaandroid.ui.components.OutlineTaskRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineScreen(
    onOpenSettings: () -> Unit,
    viewModel: OutlineViewModel = hiltViewModel(),
) {
    val projectOutlines by viewModel.outline.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                OutlineList(projectOutlines)
            }
        }
    }
}

@Composable
private fun OutlineList(projectOutlines: List<ProjectOutline>) {
    val collapsedIds = rememberSaveable(saver = LongSetSaver) { mutableStateOf<Set<Long>>(emptySet()) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        projectOutlines.forEach { projectOutline ->
            item(key = "project-${projectOutline.project.id}") {
                Text(
                    text = projectOutline.project.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            val visibleRows = flattenOutline(projectOutline.nodes, depth = 0, collapsedIds = collapsedIds.value)
            items(visibleRows, key = { (node, _) -> "task-${node.task.id}" }) { (node, depth) ->
                OutlineTaskRow(
                    node = node,
                    depth = depth,
                    isCollapsed = node.task.id in collapsedIds.value,
                    onToggleCollapse = {
                        collapsedIds.value = collapsedIds.value.toggle(node.task.id)
                    },
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
