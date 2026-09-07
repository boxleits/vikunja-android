package com.boxleits.vikunjaandroid.data.sync

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.boxleits.vikunjaandroid.widget.AgendaWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Glance redraws a widget only when told to, so anything that changes what
 * the widget would show has to say so. One place for that, rather than every
 * caller reaching for the widget class and a Context.
 */
@Singleton
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun refresh() {
        AgendaWidget().updateAll(context)
    }
}
