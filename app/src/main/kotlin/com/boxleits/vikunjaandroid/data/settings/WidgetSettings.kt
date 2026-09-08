package com.boxleits.vikunjaandroid.data.settings

import com.boxleits.vikunjaandroid.core.agenda.WidgetHorizon
import com.boxleits.vikunjaandroid.core.agenda.WidgetSort

/**
 * How the home screen widget is configured. App-wide rather than per-widget:
 * Orgzly configures each placed widget separately (you pick a saved search
 * when you drop it), which is the better model once there is something like a
 * saved search to pick. Until then, one setting for one kind of agenda.
 *
 * There is deliberately no item cap: the widget scrolls.
 */
data class WidgetSettings(
    val horizon: WidgetHorizon,
    val sort: WidgetSort,
) {
    companion object {
        val DEFAULT = WidgetSettings(horizon = WidgetHorizon.TOMORROW, sort = WidgetSort.DATE)
    }
}
