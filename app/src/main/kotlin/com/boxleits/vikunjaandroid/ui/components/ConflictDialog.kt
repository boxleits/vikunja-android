package com.boxleits.vikunjaandroid.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boxleits.vikunjaandroid.data.sync.TaskConflict

/**
 * Tells the user that edits of theirs were dropped in favour of the server's
 * newer version, and which tasks it happened to.
 */
@Composable
fun ConflictDialog(
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
