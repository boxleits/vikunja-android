package com.boxleits.vikunjaandroid.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.boxleits.vikunjaandroid.MainActivity
import com.boxleits.vikunjaandroid.core.agenda.AgendaBucket
import com.boxleits.vikunjaandroid.core.agenda.AgendaItem
import com.boxleits.vikunjaandroid.core.agenda.AgendaSections
import com.boxleits.vikunjaandroid.core.agenda.widgetAgenda
import com.boxleits.vikunjaandroid.data.settings.AgendaSettings
import dagger.hilt.android.EntryPointAccessors
import java.time.Instant as JavaInstant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class AgendaWidget : GlanceAppWidget() {

    /**
     * Data is collected *inside* provideContent, not read once before it.
     *
     * That distinction is the whole bug this fixes. provideContent starts a
     * session that stays alive; a later update() recomposes that existing
     * session rather than re-running provideGlance. Values captured before
     * provideContent are therefore frozen for the session's lifetime, so
     * changing a setting redrew the widget with the settings it had when the
     * session began — which looked like the widget ignoring the change. It
     * also explains a widget stuck on "Nothing scheduled" when its session
     * happened to start before the first sync.
     *
     * Collecting the flows in the composition makes the widget follow the
     * database and the settings on its own, so it is correct even when nobody
     * remembers to ask it to redraw.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = entryPoint(context)
        val agendaFlow = entryPoint.taskQueryRepository().observeAgenda()
        val settingsFlow = entryPoint.settingsRepository().agendaSettingsFlow

        val openApp = Intent(context, MainActivity::class.java)

        provideContent {
            val agenda by agendaFlow.collectAsState(initial = AgendaSections.EMPTY)
            val settings by settingsFlow.collectAsState(initial = AgendaSettings.DEFAULT)

            GlanceTheme {
                AgendaWidgetContent(agenda, settings, openApp)
            }
        }
    }

    private fun entryPoint(context: Context): WidgetEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
}

@Composable
private fun AgendaWidgetContent(
    agenda: AgendaSections,
    settings: AgendaSettings,
    openAppIntent: Intent,
) {
    val widget = widgetAgenda(agenda, settings.horizon, settings.sort)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .padding(12.dp),
    ) {
        Text(
            text = if (widget.showingUpcoming) "Upcoming" else "Agenda",
            style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onBackground),
            modifier = GlanceModifier.clickable(actionStartActivity(openAppIntent)),
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        if (widget.items.isEmpty()) {
            Text(
                text = "Nothing scheduled",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.clickable(actionStartActivity(openAppIntent)),
            )
        } else {
            // LazyColumn rather than Column: it becomes a RemoteViews
            // collection, which scrolls. A plain Column silently clips at the
            // widget's height, which is what made an item cap necessary before.
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(widget.items) { item ->
                    // The click has to live on each row: a LazyColumn's items
                    // are separate RemoteViews, so an action on the parent
                    // doesn't reach them.
                    AgendaWidgetRow(item, openAppIntent = openAppIntent)
                }
            }
        }
    }
}

@Composable
private fun AgendaWidgetRow(item: AgendaItem, openAppIntent: Intent) {
    val overdue = item.bucket == AgendaBucket.OVERDUE
    val title = buildString {
        val marker = item.task.priority.orgMarker
        if (marker.isNotEmpty()) {
            append(marker)
            append(' ')
        }
        append(item.task.title)
    }

    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(actionStartActivity(openAppIntent)),
    ) {
        Text(
            text = title,
            style = TextStyle(color = GlanceTheme.colors.onBackground),
            maxLines = 1,
        )
        Text(
            text = subtitleFor(item),
            style = TextStyle(
                fontSize = 11.sp,
                // Overdue earns the error colour: it is the one state where
                // the date is not just information but a problem.
                color = if (overdue) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
            ),
            maxLines = 1,
        )
    }
}

/**
 * "Due 8 Sept 2026, 09:00" / "Overdue · 5 Sept 2026, 17:30".
 *
 * Formatted with java.time rather than in :core so the date and time follow
 * the device's locale and 12/24-hour setting; :core has no notion of either.
 */
private fun subtitleFor(item: AgendaItem): String {
    val formatted = DATE_TIME_FORMAT.format(
        JavaInstant.ofEpochMilli(item.at.toEpochMilliseconds()).atZone(ZoneId.systemDefault()),
    )
    val prefix = when {
        item.bucket == AgendaBucket.OVERDUE -> "Overdue \u00b7 "
        // Says which date this is, since a task with only a start date is
        // scheduled rather than due, and the two mean different things.
        item.isDueDate -> "Due "
        else -> "Scheduled "
    }
    return prefix + formatted
}

private val DATE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
