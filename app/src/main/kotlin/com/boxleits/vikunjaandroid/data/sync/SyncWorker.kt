package com.boxleits.vikunjaandroid.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: SyncRepository,
) : CoroutineWorker(context, params) {

    // SyncRepository.sync() refreshes the widget itself, so every caller —
    // this worker included — gets that for free.
    override suspend fun doWork(): Result = when (syncRepository.sync()) {
        SyncResult.Success, SyncResult.NotConfigured -> Result.success()
        is SyncResult.Error -> Result.retry()
    }
}
