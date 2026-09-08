package com.boxleits.vikunjaandroid.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.boxleits.vikunjaandroid.MainActivity
import com.boxleits.vikunjaandroid.core.agenda.AgendaItem
import com.boxleits.vikunjaandroid.core.agenda.AgendaSections
import com.boxleits.vikunjaandroid.core.agenda.widgetAgenda
import com.boxleits.vikunjaandroid.data.settings.WidgetSettings
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

class AgendaWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = entryPoint(context)
        val agenda = entryPoint.taskQueryRepository().observeAgenda().first()
        val settings = entryPoint.settingsRepository().widgetSettingsFlow.first()

        val openApp = Intent(context, MainActivity::class.java)

        provideContent {
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
    settings: WidgetSettings,
    openAppIntent: Intent,
) {
    val widget = widgetAgenda(agenda, settings.horizon, settings.maxItems)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .padding(12.dp)
            .clickable(actionStartActivity(openAppIntent)),
    ) {
        Text(
            text = if (widget.showingUpcoming) "Upcoming" else "Agenda",
            style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onBackground),
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        if (widget.items.isEmpty()) {
            Text(
                text = "Nothing scheduled",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
        } else {
            // Dates only earn their space once the items aren't all imminent.
            widget.items.forEach { item -> AgendaWidgetRow(item, showDate = widget.showingUpcoming) }
        }
    }
}

@Composable
private fun AgendaWidgetRow(item: AgendaItem, showDate: Boolean) {
    val label = buildString {
        val marker = item.task.priority.orgMarker
        if (marker.isNotEmpty()) {
            append(marker)
            append(' ')
        }
        append(item.task.title)
        if (showDate) {
            append("  ")
            append(item.date.toString())
        }
    }
    Text(
        text = label,
        style = TextStyle(color = GlanceTheme.colors.onBackground),
        maxLines = 1,
        modifier = GlanceModifier.padding(vertical = 2.dp),
    )
}
