package com.boxleits.vikunjaandroid.data.sync

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.boxleits.vikunjaandroid.core.sync.shouldSyncOnForeground
import com.boxleits.vikunjaandroid.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs when the app comes to the foreground.
 *
 * Counts started activities rather than observing ProcessLifecycleOwner. Both
 * express the same idea, but ProcessLifecycleOwner only works if its
 * androidx.startup initializer actually ran, and this app already edits that
 * provider in the manifest to keep WorkManager's initializer out. Counting
 * activities depends on nothing but the Application itself.
 *
 * Rotation doesn't re-trigger: the replacement activity starts before the old
 * one stops, so the count never returns to zero in between.
 */
@Singleton
class ForegroundSyncObserver @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
) : Application.ActivityLifecycleCallbacks {

    // Process-scoped: this lives as long as the app does, so there is nothing
    // later to cancel it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Stops two foregrounds in quick succession from running overlapping syncs. */
    private val syncGate = Mutex()

    private var startedActivities = 0

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities++ == 0) onEnteredForeground()
    }

    override fun onActivityStopped(activity: Activity) {
        if (startedActivities > 0) startedActivities--
    }

    private fun onEnteredForeground() {
        scope.launch {
            if (settingsRepository.settingsFlow.first() == null) return@launch

            syncGate.withLock {
                val lastSyncedAt = settingsRepository.lastSyncedAtFlow.first()
                if (!shouldSyncOnForeground(lastSyncedAt, Clock.System.now())) return@withLock

                // Run it here rather than handing it to WorkManager. The
                // scheduler's immediate-sync work is unique with KEEP, so a
                // failed run sitting in backoff would silently swallow every
                // later request — exactly when the user is looking at the
                // screen and expecting fresh data. A failure here just means
                // stale data until the next trigger, which is what the
                // periodic sync and the edit queue already handle.
                syncRepository.sync()
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
