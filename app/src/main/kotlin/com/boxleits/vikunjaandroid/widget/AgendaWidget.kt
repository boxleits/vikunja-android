package com.boxleits.vikunjaandroid.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import androidx.glance.material3.GlanceTheme
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.boxleits.vikunjaandroid.MainActivity
import com.boxleits.vikunjaandroid.core.agenda.AgendaItem
import com.boxleits.vikunjaandroid.core.agenda.AgendaSections
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

private const val MAX_WIDGET_ITEMS = 8

class AgendaWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = entryPoint(context).taskQueryRepository()
        val agenda = repository.observeAgenda().first()

        provideContent {
            GlanceTheme {
                AgendaWidgetContent(agenda)
            }
        }
    }

    private fun entryPoint(context: Context): WidgetEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
}

@Composable
private fun AgendaWidgetContent(agenda: AgendaSections) {
    val items = (agenda.overdue + agenda.today + agenda.tomorrow).take(MAX_WIDGET_ITEMS)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            text = "Agenda",
            style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onBackground),
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        if (items.isEmpty()) {
            Text(
                text = "Nothing due",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
        } else {
            items.forEach { item -> AgendaWidgetRow(item) }
        }
    }
}

@Composable
private fun AgendaWidgetRow(item: AgendaItem) {
    val marker = item.task.priority.orgMarker
    val label = if (marker.isEmpty()) item.task.title else "$marker ${item.task.title}"
    Text(
        text = label,
        style = TextStyle(color = GlanceTheme.colors.onBackground),
        maxLines = 1,
        modifier = GlanceModifier.padding(vertical = 2.dp),
    )
}
