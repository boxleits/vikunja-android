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
 * Tells the user that a task changed on the server while they were editing it,
 * and that their version was kept as a separate task rather than thrown away.
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
                        "This task was changed on the server while you were editing it. " +
                            "It now shows the server's version, and your version was kept " +
                            "as a separate task marked [conflict]. Decide which one you " +
                            "want and delete the other."
                    } else {
                        "These tasks were changed on the server while you were editing " +
                            "them. They now show the server's versions, and yours were " +
                            "kept as separate tasks marked [conflict]. Decide which ones " +
                            "you want and delete the others."
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
