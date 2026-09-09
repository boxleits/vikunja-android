package com.boxleits.vikunjaandroid.ui.agenda

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxleits.vikunjaandroid.core.agenda.AgendaItem
import com.boxleits.vikunjaandroid.core.agenda.AgendaSections
import com.boxleits.vikunjaandroid.ui.components.AgendaItemRow
import com.boxleits.vikunjaandroid.ui.components.ConflictDialog
import com.boxleits.vikunjaandroid.ui.components.EditTaskDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaScreen(viewModel: AgendaViewModel = hiltViewModel()) {
    val agenda by viewModel.agenda.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // By id, so the dialog survives a rotation and keeps showing the task as
    // the agenda currently has it.
    var editingTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editingTask = remember(agenda, editingTaskId) {
        editingTaskId?.let { id -> agenda.all.firstOrNull { it.task.id == id }?.task }
    }

    // Editing a due date can move a task out of the visible range, which would
    // otherwise leave a dialog open over a task the screen no longer shows.
    LaunchedEffect(editingTaskId, editingTask) {
        if (editingTaskId != null && editingTask == null) editingTaskId = null
    }

    editingTask?.let { task ->
        EditTaskDialog(
            task = task,
            onDismiss = { editingTaskId = null },
            onSave = { edits ->
                viewModel.updateTask(task.id, edits)
                editingTaskId = null
            },
        )
    }

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
            TopAppBar(title = { Text("Agenda") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (agenda.isEmpty) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Nothing due in the selected range. Widen it in Settings \u2192 Agenda.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                AgendaSectionsList(agenda, onEdit = { editingTaskId = it })
            }
        }
    }
}

@Composable
private fun AgendaSectionsList(agenda: AgendaSections, onEdit: (taskId: Long) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        agendaSection("Overdue", agenda.overdue, onEdit)
        agendaSection("Today", agenda.today, onEdit)
        agendaSection("Tomorrow", agenda.tomorrow, onEdit)
        agendaSection("This week", agenda.thisWeek, onEdit)
        agendaSection("Later", agenda.later, onEdit)
    }
}

private fun LazyListScope.agendaSection(
    title: String,
    items: List<AgendaItem>,
    onEdit: (taskId: Long) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "header-$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(items, key = { "agenda-${it.task.id}" }) { item ->
        AgendaItemRow(
            item = item,
            onClick = { onEdit(item.task.id) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
