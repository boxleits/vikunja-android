package com.boxleits.vikunjaandroid.data.sync

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.boxleits.vikunjaandroid.widget.AgendaWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // Application-scoped on purpose: see refresh().
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Redraws every placed widget.
     *
     * The update runs in an application-scoped job rather than the caller's,
     * because most callers are view models: changing a widget setting and then
     * navigating away cancels viewModelScope, and a cancelled updateAll can
     * leave the widget having been told to redraw without ever receiving the
     * content — which shows up as a widget that has inexplicably gone empty.
     *
     * The join keeps the guarantee callers that must not finish early need —
     * the sync worker, whose process may be killed the moment it returns.
     * Cancelling the caller cancels the join, not the update.
     */
    suspend fun refresh() {
        val job = scope.launch {
            withContext(NonCancellable) {
                AgendaWidget().updateAll(context)
            }
        }
        job.join()
    }
}
