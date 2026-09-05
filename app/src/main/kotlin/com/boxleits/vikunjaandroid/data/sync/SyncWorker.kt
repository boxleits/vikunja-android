package com.boxleits.vikunjaandroid.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.boxleits.vikunjaandroid.widget.AgendaWidget
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: SyncRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (syncRepository.sync()) {
        SyncResult.Success -> {
            AgendaWidget().updateAll(applicationContext)
            Result.success()
        }
        SyncResult.NotConfigured -> Result.success()
        is SyncResult.Error -> Result.retry()
    }
}
