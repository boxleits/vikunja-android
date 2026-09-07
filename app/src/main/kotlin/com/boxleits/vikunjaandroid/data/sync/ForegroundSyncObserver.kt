package com.boxleits.vikunjaandroid.data.sync

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.boxleits.vikunjaandroid.core.sync.shouldSyncOnForeground
import com.boxleits.vikunjaandroid.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs when the app comes to the foreground.
 *
 * Observes the *process* lifecycle rather than an activity's, so it fires once
 * when the app becomes visible and not again on every rotation or screen
 * change. Whether a sync is actually due is [shouldSyncOnForeground]'s call,
 * which lives in :core so it can be tested.
 *
 * This is what makes the app feel current without a push channel: the moment
 * that matters is the one where the user is looking at the screen.
 */
@Singleton
class ForegroundSyncObserver @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
) : DefaultLifecycleObserver {

    // Process-scoped: this observer lives as long as the app does, so there is
    // nothing later to cancel it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStart(owner: LifecycleOwner) {
        scope.launch {
            if (settingsRepository.settingsFlow.first() == null) return@launch

            val lastSyncedAt = settingsRepository.lastSyncedAtFlow.first()
            if (shouldSyncOnForeground(lastSyncedAt, Clock.System.now())) {
                // Through the scheduler rather than run directly, so it inherits
                // the connectivity constraint and backoff instead of failing
                // outright when the app opens with no signal.
                syncScheduler.requestImmediateSync()
            }
        }
    }
}
