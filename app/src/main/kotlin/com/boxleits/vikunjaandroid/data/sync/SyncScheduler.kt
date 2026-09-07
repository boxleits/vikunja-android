package com.boxleits.vikunjaandroid.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val PERIODIC_SYNC_WORK_NAME = "vikunja_periodic_sync"
private const val IMMEDIATE_SYNC_WORK_NAME = "vikunja_immediate_sync"

@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Keeps the local cache (and widget) fresh in the background; safe to call repeatedly. */
    fun ensurePeriodicSyncScheduled() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelPeriodicSync() {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_SYNC_WORK_NAME)
    }

    /**
     * Runs one sync as soon as there's a network — after onboarding, or to
     * retry edits that couldn't be sent.
     *
     * Unique work with KEEP: several edits made offline should wait on one
     * pending run, not queue a run each.
     */
    fun requestImmediateSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(IMMEDIATE_SYNC_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
