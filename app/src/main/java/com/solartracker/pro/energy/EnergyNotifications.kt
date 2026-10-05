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
import com.solartracker.pro.core.analytics.Alert
import com.solartracker.pro.update.UpdateNotifications

/** Alert notifications (grouped by AlertManager, so each problem notifies at most once per cooldown). */
object EnergyNotifications {
    private const val CHANNEL = "energy_alerts"

    @SuppressLint("MissingPermission") // checked below
    fun alert(context: Context, alert: Alert) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Alarmy instalacji", NotificationManager.IMPORTANCE_DEFAULT))
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(alert.type.title)
            .setContentText(alert.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.detail))
            .setAutoCancel(true)
            .setContentIntent(UpdateNotifications.openAppIntent(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(alert.key.hashCode(), n) }
    }
}
