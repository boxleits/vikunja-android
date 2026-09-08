package com.boxleits.vikunjaandroid.data.settings

import com.boxleits.vikunjaandroid.core.agenda.WidgetHorizon

/**
 * How the home screen widget is configured. App-wide rather than per-widget:
 * a per-instance configuration screen is possible later, but every widget
 * showing the same thing is what a single-user client usually wants.
 */
data class WidgetSettings(
    val horizon: WidgetHorizon,
    val maxItems: Int,
) {
    companion object {
        val DEFAULT = WidgetSettings(horizon = WidgetHorizon.TOMORROW, maxItems = 8)

        /** Offered in Settings; small enough to fit a widget without scrolling. */
        val ITEM_COUNT_CHOICES = listOf(3, 5, 8, 12)
    }
}
