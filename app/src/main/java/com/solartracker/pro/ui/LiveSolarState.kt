package com.solartracker.pro.ui

import com.solartracker.pro.i18n.tr
import androidx.compose.runtime.Immutable
import com.solartracker.pro.core.live.LiveSolarSnapshot
import com.solartracker.pro.core.live.SunPath
import com.solartracker.pro.core.solar.DayType
import com.solartracker.pro.core.weather.WeatherSource
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Immutable UI models for Live Solar. Values keep full double precision; rounding happens only
 * when composables format them. Split into parts so each card only reads what it shows.
 */

@Immutable
data class LiveSunUi(
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val zenithDeg: Double,
    val hourAngleDeg: Double,
    val declinationDeg: Double,
    val airMass: Double?,
    val isDay: Boolean,
    val dayType: DayType,
    val sunriseText: String,
    val sunsetText: String,
    val solarNoonText: String,
    val timeToSunriseText: String?,
    val timeToSunsetText: String?,
)

@Immutable
data class LivePvUi(
    val modeledPowerKw: Double,
    /** Real inverter/meter power; null = no measurement connected. */
    val measuredPowerKw: Double?,
    val peakPowerKw: Double,
    val tiltDeg: Double,
    val panelAzimuthDeg: Double,
    val angleOfIncidenceDeg: Double,
    val geometricUtilization: Double,
    val poa: Double,
    val ghi: Double,
    val dni: Double,
    val dhi: Double,
    val source: WeatherSource,
    val sourceLabel: String,
    val ambientTemperatureC: Double?,
    val cellTemperatureC: Double?,
)

@Immutable
data class LiveEnergyUi(
    val pvKw: Double,
    val loadKw: Double,
    val directKw: Double,
    val chargeKw: Double,
    val dischargeKw: Double,
    val gridImportKw: Double,
    val exportKw: Double,
    val hasBattery: Boolean,
    val socPercent: Double?,
    val storedKwh: Double?,
    val usableKwh: Double?,
    val backupLabel: String,
) {
    val batteryKw: Double get() = chargeKw - dischargeKw
    val netKw: Double get() = pvKw - loadKw
}

@Immutable
data class LiveUiState(
    val epochMillis: Long,
    val clockText: String,
    val dateText: String,
    val locationText: String,
    val sun: LiveSunUi,
    val pv: LivePvUi,
    val energy: LiveEnergyUi,
)

/** Today's sun path, rebuilt only when the date or location changes (not every second). */
@Immutable
data class SunPathUi(
    val dateText: String,
    /** (azimuth, elevation) pairs every few minutes over the day. */
    val azimuths: List<Double>,
    val elevations: List<Double>,
    val sunriseAzimuthDeg: Double?,
    val sunsetAzimuthDeg: Double?,
    val sunriseText: String,
    val sunsetText: String,
    val highestElevationDeg: Double,
    val highestAzimuthDeg: Double,
    val highestText: String,
)

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss", Format.locale)

fun liveClockText(instant: Instant, zone: ZoneId): String = instant.atZone(zone).format(clockFormat)

/** "2 h 05 min 13 s" */
fun formatCountdown(d: Duration): String {
    val total = d.seconds.coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h h ${m.toString().padStart(2, '0')} min ${s.toString().padStart(2, '0')} s"
    else "$m min ${s.toString().padStart(2, '0')} s"
}

fun irradianceSourceLabel(source: WeatherSource, weatherEnabled: Boolean): String = when (source) {
    WeatherSource.FORECAST -> tr("dane z prognozy (średnia godzinowa, Open-Meteo)", "forecast data (hourly average, Open-Meteo)")
    WeatherSource.CLIMATE -> tr("średnie klimatyczne (brak prognozy na tę godzinę)", "climate averages (no forecast for this hour)")
    WeatherSource.CLEAR_SKY ->
        if (weatherEnabled) tr("model bezchmurnego nieba (brak danych pogodowych)", "clear-sky model (no weather data)")
        else tr("model bezchmurnego nieba (pogoda wyłączona)", "clear-sky model (weather off)")
}

fun LiveSolarSnapshot.toUi(
    locationName: String,
    weatherEnabled: Boolean,
    usableKwh: Double?,
    backupLabel: String,
    measuredPowerKw: Double? = null,
): LiveUiState {
    val est = estimate
    val sunTimes = todaySunTimes
    return LiveUiState(
        epochMillis = instant.toEpochMilli(),
        clockText = liveClockText(instant, zone),
        dateText = instant.atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Format.locale)),
        locationText = "${locationName.ifBlank { tr("Lokalizacja", "Location") }} · ${Format.coordinates(location.latitude, location.longitude)}" +
            if (location.elevationM != 0.0) " · ${Format.decimal(location.elevationM, 0)} ${tr("m n.p.m.", "m a.s.l.")}" else "",
        sun = LiveSunUi(
            azimuthDeg = est.sun.azimuthDeg,
            elevationDeg = est.sun.elevationDeg,
            zenithDeg = est.sun.zenithDeg,
            hourAngleDeg = est.sun.hourAngleDeg,
            declinationDeg = est.sun.declinationDeg,
            airMass = est.sun.airMass,
            isDay = isDay,
            dayType = sunTimes.dayType,
            sunriseText = Format.time(sunTimes.sunrise, zone),
            sunsetText = Format.time(sunTimes.sunset, zone),
            solarNoonText = Format.time(sunTimes.solarNoon, zone),
            timeToSunriseText = timeToSunrise?.let(::formatCountdown),
            timeToSunsetText = timeToSunset?.let(::formatCountdown),
        ),
        pv = LivePvUi(
            modeledPowerKw = est.powerKw,
            measuredPowerKw = measuredPowerKw,
            peakPowerKw = system.peakPowerKw,
            tiltDeg = system.tiltDeg,
            panelAzimuthDeg = system.azimuthDeg,
            angleOfIncidenceDeg = est.angleOfIncidenceDeg,
            geometricUtilization = est.geometricUtilization,
            poa = est.poa,
            ghi = est.ghi,
            dni = est.irradiance.dni,
            dhi = est.irradiance.dhi,
            source = irradianceSource,
            sourceLabel = irradianceSourceLabel(irradianceSource, weatherEnabled),
            ambientTemperatureC = est.irradiance.ambientTemperatureC,
            cellTemperatureC = est.cellTemperatureC,
        ),
        energy = LiveEnergyUi(
            pvKw = flow.pvKw,
            loadKw = flow.loadKw,
            directKw = flow.directKw,
            chargeKw = flow.chargeKw,
            dischargeKw = flow.dischargeKw,
            gridImportKw = flow.gridImportKw,
            exportKw = flow.exportKw,
            hasBattery = socPercent != null,
            socPercent = socPercent,
            storedKwh = storedKwh,
            usableKwh = usableKwh,
            backupLabel = backupLabel,
        ),
    )
}

fun SunPath.toUi(zone: ZoneId): SunPathUi {
    val visible = points
    val highest = highest
    val sunrise = sunTimes.sunrise
    val sunset = sunTimes.sunset
    fun azimuthAt(t: Instant?) = t?.let { time -> points.minByOrNull { kotlin.math.abs(it.time.toEpochMilli() - time.toEpochMilli()) }?.azimuthDeg }
    return SunPathUi(
        dateText = date.format(DateTimeFormatter.ofPattern("d MMMM", Format.locale)),
        azimuths = visible.map { it.azimuthDeg },
        elevations = visible.map { it.elevationDeg },
        sunriseAzimuthDeg = azimuthAt(sunrise),
        sunsetAzimuthDeg = azimuthAt(sunset),
        sunriseText = Format.time(sunrise, zone),
        sunsetText = Format.time(sunset, zone),
        highestElevationDeg = highest.elevationDeg,
        highestAzimuthDeg = highest.azimuthDeg,
        highestText = Format.time(highest.time, zone),
    )
}
