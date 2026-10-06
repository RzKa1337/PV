package com.solartracker.pro.energy

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.solartracker.pro.core.analytics.DailyReportBuilder
import com.solartracker.pro.core.analytics.ForecastAccuracy
import com.solartracker.pro.core.analytics.ForecastHorizon
import com.solartracker.pro.update.UpdateNotifications
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Evening daily energy report from the local history (no network). Nothing is sent when the day has no
 * recorded inverter data.
 */
class DailyReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val db = HistoryDatabase(applicationContext)
        try {
            val from = today.minusDays(8).atStartOfDay(zone).toInstant()
            val to = today.plusDays(1).atStartOfDay(zone).toInstant()
            val rows = db.history(from, to, summary = true)
            val dayStart = today.atStartOfDay(zone).toInstant()
            val accuracy = ForecastAccuracy.evaluate(
                ForecastAccuracy.pairHourly(db.forecasts(ForecastHorizon.DAY_AHEAD, dayStart, to), rows.filter { it.start >= dayStart })
                    .filter { it.forecast > 0.01 || it.actual > 0.01 },
            ).takeIf { it.count > 0 }
            val report = DailyReportBuilder.build(today, rows, zone, accuracy) ?: return Result.success()
            notify(applicationContext, report.text())
        } finally {
            db.close()
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "daily-energy-report"
        private const val CHANNEL = "daily_report"
        private val REPORT_TIME: LocalTime = LocalTime.of(21, 30)

        fun schedule(context: Context) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(REPORT_TIME)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next)
            val request = PeriodicWorkRequestBuilder<DailyReportWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        @SuppressLint("MissingPermission") // checked below
        private fun notify(context: Context, text: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Raport dzienny", NotificationManager.IMPORTANCE_LOW))
            val lines = text.lines()
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(lines.first())
                .setContentText(lines.drop(1).firstOrNull().orEmpty())
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines.drop(1).joinToString("\n")))
                .setAutoCancel(true)
                .setContentIntent(UpdateNotifications.openAppIntent(context))
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(NAME.hashCode(), n) }
        }
    }
}
