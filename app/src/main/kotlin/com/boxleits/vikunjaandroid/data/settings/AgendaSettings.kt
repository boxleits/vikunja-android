package com.boxleits.vikunjaandroid.data.settings

import com.boxleits.vikunjaandroid.core.agenda.AgendaHorizon
import com.boxleits.vikunjaandroid.core.agenda.AgendaSort

/**
 * How the agenda is presented, on the Agenda screen and in the home screen
 * widget alike — one answer to "what counts as my agenda", rather than the
 * screen and the widget disagreeing about it.
 *
 * App-wide rather than per-widget. Orgzly configures each placed widget
 * separately, which is the better model once there is something like a saved
 * search to point at.
 *
 * There is deliberately no item cap: both views scroll.
 */
data class AgendaSettings(
    val horizon: AgendaHorizon,
    val sort: AgendaSort,
) {
    companion object {
        val DEFAULT = AgendaSettings(horizon = AgendaHorizon.TOMORROW, sort = AgendaSort.DATE)
    }
}
