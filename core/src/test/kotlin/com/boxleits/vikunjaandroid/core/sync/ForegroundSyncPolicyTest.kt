package com.boxleits.vikunjaandroid.core.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ForegroundSyncPolicyTest {

    private val now = Instant.parse("2024-05-01T12:00:00Z")

    @Test
    fun `syncs when nothing has ever been synced`() {
        assertThat(shouldSyncOnForeground(lastSyncedAt = null, now = now)).isTrue()
    }

    @Test
    fun `skips a sync that just happened`() {
        val justNow = now - 5.seconds
        assertThat(shouldSyncOnForeground(justNow, now)).isFalse()
    }

    @Test
    fun `syncs once the cache is older than the interval`() {
        val stale = now - 90.seconds
        assertThat(shouldSyncOnForeground(stale, now)).isTrue()
    }

    @Test
    fun `treats exactly the interval as due`() {
        assertThat(shouldSyncOnForeground(now - FOREGROUND_SYNC_MIN_INTERVAL, now)).isTrue()
    }

    @Test
    fun `a timestamp in the future does not wedge syncing off`() {
        // A device clock that jumped backwards would otherwise leave the app
        // refusing to sync until real time caught up with the stored value.
        val future = now + 10.minutes
        assertThat(shouldSyncOnForeground(future, now)).isTrue()
    }

    @Test
    fun `honours a caller-supplied interval`() {
        val fiveMinutesAgo = now - 5.minutes
        assertThat(shouldSyncOnForeground(fiveMinutesAgo, now, minInterval = 10.minutes)).isFalse()
        assertThat(shouldSyncOnForeground(fiveMinutesAgo, now, minInterval = 1.minutes)).isTrue()
    }
}
