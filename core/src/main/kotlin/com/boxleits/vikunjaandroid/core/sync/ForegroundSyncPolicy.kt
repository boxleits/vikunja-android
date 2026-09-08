package com.boxleits.vikunjaandroid.core.sync

import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How stale the cache has to be before bringing the app to the foreground is
 * worth a sync.
 *
 * A minute turned out to be too long to feel like it worked at all: switching
 * away and straight back is the obvious way to test this, and it did nothing.
 * Thirty seconds still collapses a burst of app-switching into one sync while
 * making the behaviour visible.
 */
val FOREGROUND_SYNC_MIN_INTERVAL: Duration = 30.seconds

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
