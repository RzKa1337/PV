package com.solartracker.pro.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.solartracker.pro.MainActivity

object UpdateNotifications {
    private const val CHANNEL = "updates"
    const val ID_STATUS = 7001
    const val ID_PROGRESS = 7002
    const val EXTRA_OPEN_UPDATES = "open_updates"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Aktualizacje", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_UPDATES, true)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(context: Context, title: String, text: String, tap: PendingIntent? = null, id: Int = ID_STATUS) {
        post(context, id) {
            setContentTitle(title)
            setContentText(text)
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setAutoCancel(true)
            setContentIntent(tap ?: openAppIntent(context))
        }
    }

    fun progress(context: Context, title: String, percent: Int?) {
        post(context, ID_PROGRESS) {
            setContentTitle(title)
            setSmallIcon(android.R.drawable.stat_sys_download)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(100, percent ?: 0, percent == null)
            setContentIntent(openAppIntent(context))
        }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    /** Opens a web page (e.g. the releases list) – used after a rollback. */
    fun browserIntent(context: Context, url: String): PendingIntent = PendingIntent.getActivity(
        context, 1, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun activityIntent(context: Context, intent: Intent): PendingIntent = PendingIntent.getActivity(
        context, 2, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS is checked right below (Android 13+)
    private fun post(context: Context, id: Int, build: NotificationCompat.Builder.() -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL).apply(build).build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
