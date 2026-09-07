package com.boxleits.vikunjaandroid.core.sync

import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * How stale the cache has to be before bringing the app to the foreground is
 * worth a sync. Short enough that opening the app shows current data, long
 * enough that flicking between apps doesn't sync on every return.
 */
val FOREGROUND_SYNC_MIN_INTERVAL: Duration = 1.minutes

/**
 * Whether entering the foreground should trigger a sync.
 *
 * Never synced before is always worth a sync. A [lastSyncedAt] in the future —
 * a device clock that moved backwards, or a timestamp taken from the server —
 * counts as stale rather than fresh, so a skewed clock can't wedge syncing off
 * until real time catches up.
 */
fun shouldSyncOnForeground(
    lastSyncedAt: Instant?,
    now: Instant,
    minInterval: Duration = FOREGROUND_SYNC_MIN_INTERVAL,
): Boolean {
    if (lastSyncedAt == null) return true
    val elapsed = now - lastSyncedAt
    if (elapsed.isNegative()) return true
    return elapsed >= minInterval
}
