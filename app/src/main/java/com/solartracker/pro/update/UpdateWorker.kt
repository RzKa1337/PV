package com.solartracker.pro.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.solartracker.pro.SolarTrackerApplication
import com.solartracker.pro.core.update.UpdateConfig
import java.util.concurrent.TimeUnit

/** Periodic background check (and optional download/installation) of updates. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as SolarTrackerApplication
        app.updateManager.backgroundRun()
        // Failures are logged and retried on the next period; no tight retry loop.
        return Result.success()
    }

    companion object {
        private const val NAME = "update-check"

        fun schedule(context: Context, config: UpdateConfig) {
            val wm = WorkManager.getInstance(context)
            val interval = config.interval.duration
            if (!config.enabled || interval == null) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(interval.toMinutes(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
