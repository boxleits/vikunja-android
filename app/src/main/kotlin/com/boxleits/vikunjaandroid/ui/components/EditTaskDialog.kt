package com.boxleits.vikunjaandroid.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boxleits.vikunjaandroid.core.model.Priority
import com.boxleits.vikunjaandroid.core.model.Task
import com.boxleits.vikunjaandroid.core.repository.TaskEdits
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.time.Instant as JavaInstant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Edits the three fields a task is usually wrong about: what it says, how much
 * it matters, and when it is due.
 *
 * Everything is prefilled and every field is submitted, changed or not. That
 * costs nothing — the push merges the form into the server's own copy of the
 * task — and it means the dialog shows the task as it is rather than as a set
 * of blanks to be filled in again.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditTaskDialog(
    task: Task,
    onDismiss: () -> Unit,
    onSave: (TaskEdits) -> Unit,
) {
    var title by rememberSaveable(task.id) { mutableStateOf(task.title) }
    var priority by rememberSaveable(task.id) { mutableStateOf(task.priority) }
    // Held as epoch millis so it survives a rotation without needing a custom
    // saver for Instant.
    var dueDateMillis by rememberSaveable(task.id) {
        mutableStateOf(task.dueDate?.toEpochMilliseconds())
    }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }

    val zone = TimeZone.currentSystemDefault()
    val dueDate = dueDateMillis?.let { Instant.fromEpochMilliseconds(it) }

    if (showDatePicker) {
        // The picker works in UTC calendar days, so the state is seeded with
        // the local date expressed as UTC midnight and read back the same way.
        // Feeding it the raw instant would show yesterday for anything due in
        // the small hours west of Greenwich.
        val localDate = (dueDate ?: Clock.System.now()).toLocalDateTime(zone).date
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = localDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { picked ->
                            val pickedDate = Instant.fromEpochMilliseconds(picked)
                                .toLocalDateTime(TimeZone.UTC).date
                            // Keep whatever time of day was already set; a task
                            // due at 09:00 that gets moved to Friday is due at
                            // 09:00 on Friday, not at midnight.
                            val timeOfDay = dueDate?.toLocalDateTime(zone)?.time ?: DEFAULT_TIME_OF_DAY
                            dueDateMillis = LocalDateTime(pickedDate, timeOfDay)
                                .toInstant(zone)
                                .toEpochMilliseconds()
                        }
                        showDatePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTimePicker) {
        val current = (dueDate ?: Clock.System.now()).toLocalDateTime(zone)
        val timeState = rememberTimePickerState(
            initialHour = current.hour,
            initialMinute = current.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val date = (dueDate ?: Clock.System.now()).toLocalDateTime(zone).date
                        dueDateMillis = LocalDateTime(
                            year = date.year,
                            monthNumber = date.monthNumber,
                            dayOfMonth = date.dayOfMonth,
                            hour = timeState.hour,
                            minute = timeState.minute,
                        ).toInstant(zone).toEpochMilliseconds()
                        showTimePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit task") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "Priority", style = MaterialTheme.typography.labelLarge)
                FlowRow {
                    Priority.entries.forEach { option ->
                        FilterChip(
                            selected = option == priority,
                            onClick = { priority = option },
                            label = { Text(option.displayName()) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "Due", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = dueDate?.let { formatDueDate(it) } ?: "No due date",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showDatePicker = true }) { Text("Date") }
                    TextButton(
                        onClick = { showTimePicker = true },
                        enabled = dueDate != null,
                    ) { Text("Time") }
                    TextButton(
                        onClick = { dueDateMillis = null },
                        enabled = dueDate != null,
                    ) { Text("Clear") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(TaskEdits(title = title, priority = priority, dueDate = dueDate)) },
                enabled = title.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Vikunja's 0-5 priorities named rather than shown only as org markers, because
 * two of them share `[#A]` and a chip row has to be pickable, not just
 * recognisable. The marker still leads, so the labels match what the outline
 * shows.
 */
private fun Priority.displayName(): String = when (this) {
    Priority.UNSET -> "None"
    Priority.LOW -> "[#D] Low"
    Priority.MEDIUM -> "[#C] Medium"
    Priority.HIGH -> "[#B] High"
    Priority.URGENT -> "[#A] Urgent"
    Priority.DO_NOW -> "[#A] Now"
}

/** What a due date gets when only a day was chosen. */
private val DEFAULT_TIME_OF_DAY = LocalTime(12, 0)

/** Formatted with java.time so the date and time follow the device's locale. */
private fun formatDueDate(at: Instant): String = DATE_TIME_FORMAT.format(
    JavaInstant.ofEpochMilli(at.toEpochMilliseconds()).atZone(ZoneId.systemDefault()),
)

private val DATE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
