package com.boxleits.vikunjaandroid

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.boxleits.vikunjaandroid.data.sync.ForegroundSyncObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VikunjaApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var foregroundSyncObserver: ForegroundSyncObserver

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        // Hilt injects during its own onCreate, so the fields above are only
        // safe to touch after this call.
        super.onCreate()
        registerActivityLifecycleCallbacks(foregroundSyncObserver)
    }
}
