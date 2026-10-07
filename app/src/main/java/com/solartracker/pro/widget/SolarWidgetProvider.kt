package com.solartracker.pro.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.solartracker.pro.MainActivity
import com.solartracker.pro.R
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.core.weather.WeatherSource
import com.solartracker.pro.core.widget.LastMeasurement
import com.solartracker.pro.core.widget.SolarSummary
import com.solartracker.pro.core.widget.SolarSummaryBuilder
import com.solartracker.pro.data.SettingsRepository
import com.solartracker.pro.data.WeatherRepository
import com.solartracker.pro.energy.HistoryDatabase
import com.solartracker.pro.i18n.AppLocale
import com.solartracker.pro.ui.dataKindLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Home-screen widget: model PV power now, today's expected production, the last inverter reading
 * (labelled MEASURED only when recent) and the sun. Uses cached weather only – no network access.
 * Updated every 30 min by the system, when the app goes to the background and on tap of ⟳.
 */
class SolarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            refresh(context, manager.getAppWidgetIds(ComponentName(context, SolarWidgetProvider::class.java)))
        }
    }

    private fun refresh(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Texts in the app language chosen in the settings (Polish by default).
                val localized = AppLocale.wrap(context.applicationContext)
                val views = runCatching { withTimeout(8_000) { render(localized, build(context.applicationContext)) } }
                    .getOrElse { error(localized, it.message ?: it.javaClass.simpleName) }
                AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun build(context: Context): SolarSummary {
        val settings = SettingsRepository(context).settings.first()
        val weather = if (settings.weatherEnabled) WeatherRepository(context).cachedOnly(settings.location) else null
        val model = WeatherAwareIrradianceModel(settings.location, weather?.forecast, weather?.climate)
        val last = runCatching {
            val db = HistoryDatabase(context)
            try {
                db.latest()?.let { LastMeasurement(it.end, it.pvW, it.socPercent) }
            } finally {
                db.close()
            }
        }.getOrNull()
        return SolarSummaryBuilder.build(settings.location, settings.system, model, Instant.now(), ZoneId.systemDefault(), last)
    }

    private fun render(context: Context, s: SolarSummary): RemoteViews {
        val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        fun f(v: Double, d: Int = 2) = String.format(Locale.ROOT, "%.${d}f", v)
        return RemoteViews(context.packageName, R.layout.widget_solar).apply {
            setTextViewText(R.id.widget_power, "${f(s.modelPvKw)} kW")
            setTextViewText(
                R.id.widget_power_label,
                "${dataKindLabel(s.modelKind)} – " + context.getString(
                    when (s.weatherSource) {
                        WeatherSource.FORECAST -> R.string.widget_source_forecast
                        WeatherSource.CLIMATE -> R.string.widget_source_climate
                        WeatherSource.CLEAR_SKY -> R.string.widget_source_clear_sky
                    },
                ) + " · ${hm.format(s.time)}",
            )
            setTextViewText(R.id.widget_today, context.getString(R.string.widget_today, f(s.todayExpectedKwh, 1), f(s.remainingTodayKwh, 1)))
            setTextViewText(
                R.id.widget_measured,
                when (s.measuredKind) {
                    DataKind.MEASURED -> context.getString(R.string.widget_inverter, hm.format(s.measuredAt!!), s.measuredPvKw?.let { f(it) + " kW" } ?: "N/A") +
                        (s.measuredSoc?.let { " · SOC ${it.toInt()}%" } ?: "")
                    DataKind.LAST_KNOWN -> context.getString(R.string.widget_last_reading, DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(s.measuredAt!!)) +
                        (s.measuredSoc?.let { " · SOC ${it.toInt()}%" } ?: "") + context.getString(R.string.widget_outdated)
                    else -> context.getString(R.string.widget_no_inverter)
                },
            )
            setTextViewText(
                R.id.widget_sun,
                if (s.sunElevationDeg > 0) context.getString(R.string.widget_sun_up, s.sunElevationDeg.toInt(), s.sunset?.let(hm::format) ?: "—")
                else context.getString(R.string.widget_sun_down, s.sunrise?.let(hm::format) ?: "—"),
            )
            bindClicks(context, this)
        }
    }

    private fun error(context: Context, message: String) = RemoteViews(context.packageName, R.layout.widget_solar).apply {
        setTextViewText(R.id.widget_power, "—")
        setTextViewText(R.id.widget_power_label, context.getString(R.string.widget_refresh_failed, message))
        bindClicks(context, this)
    }

    private fun bindClicks(context: Context, views: RemoteViews) {
        val open = PendingIntent.getActivity(
            context, 10, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context))
    }

    companion object {
        const val ACTION_REFRESH = "com.solartracker.pro.WIDGET_REFRESH"

        private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 11, Intent(context, SolarWidgetProvider::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Asks all widgets to refresh (e.g. after settings changed in the app). */
        fun requestUpdate(context: Context) {
            context.sendBroadcast(Intent(context, SolarWidgetProvider::class.java).setAction(ACTION_REFRESH))
        }
    }
}
