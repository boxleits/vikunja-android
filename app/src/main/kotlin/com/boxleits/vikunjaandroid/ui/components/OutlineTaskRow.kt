package com.boxleits.vikunjaandroid.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.boxleits.vikunjaandroid.core.outline.OutlineNode

/** Fixed width for the expand/collapse column, so every checkbox in a level lines up. */
private val CHEVRON_SLOT = 40.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutlineTaskRow(
    node: OutlineNode,
    depth: Int,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onSetDone: (Boolean) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val task = node.task

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = (depth * 20).dp, top = 2.dp, bottom = 2.dp, end = 12.dp),
        // Centred, not Top: a Checkbox draws its box in the middle of a 48dp
        // touch target, so aligning to the top leaves the box sitting well
        // below its own title — close enough to the next row to look like it
        // belongs to it.
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Both branches occupy this same fixed slot. Sizing the IconButton
        // directly doesn't work: it applies its own minimum touch target, so
        // the chevron and the empty spacer could end up different widths and
        // shift the checkbox depending on whether a task has children.
        Box(modifier = Modifier.size(CHEVRON_SLOT), contentAlignment = Alignment.Center) {
            if (node.children.isNotEmpty()) {
                IconButton(onClick = onToggleCollapse) {
                    Icon(
                        imageVector = if (isCollapsed) {
                            Icons.Filled.KeyboardArrowRight
                        } else {
                            Icons.Filled.KeyboardArrowDown
                        },
                        contentDescription = if (isCollapsed) "Expand" else "Collapse",
                    )
                }
            }
        }

        Checkbox(checked = task.done, onCheckedChange = onSetDone)

        // Tapping the heading opens it for editing, the way tapping a note
        // does in Orgzly. The checkbox and the chevron keep their own targets,
        // so ticking a task off is still one tap and never opens anything.
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit)
                .padding(vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.priority.orgMarker.isNotEmpty()) {
                    Text(
                        text = task.priority.orgMarker,
                        color = priorityColor(task.priority),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    color = if (task.done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            if (task.labels.isNotEmpty()) {
                FlowRow {
                    task.labels.forEach { label -> LabelChip(label, modifier = Modifier.padding(end = 4.dp, top = 2.dp)) }
                }
            }
        }
    }
}
