package com.solartracker.pro.core.pv

import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.solar.SolarPosition
import com.solartracker.pro.core.weather.HourlyWeather
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.core.weather.WeatherForecast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Physical behaviour of the shared estimator; reference numbers are worked out by hand in the comments. */
class PvPhysicsTest {
    private val warsaw = GeoLocation(52.23, 21.01)
    private val zone = ZoneId.of("Europe/Warsaw")
    private val day = LocalDate.of(2026, 6, 21)
    private val south30 = PvSystem(peakPowerKw = 5.0, tiltDeg = 30.0, azimuthDeg = 180.0)

    @Test
    fun hayDaviesReferenceValueAndLimits() {
        // Sun 60° high in the south, panel 30° south: AOI = 0. DNI 800, DHI 100, E0n 1361 W/m².
        // A = 800/1361 = 0.587803, Rb = 1/cos 30° = 1.154701, (1+cos 30°)/2 = 0.933013
        // sky = 100 · (0.587803·1.154701 + 0.412197·0.933013) = 100 · (0.678738 + 0.384585) = 106.332 W/m²
        val sun = SolarPosition(elevationDeg = 60.0, azimuthDeg = 180.0)
        val irr = Irradiance(dni = 800.0, dhi = 100.0)
        val hd = poaComponents(irr, sun, 30.0, 180.0, albedo = 0.0, extraterrestrialDni = 1361.0)
        assertEquals(106.332, hd.skyDiffuse, 0.01)
        assertEquals(800.0, hd.beam, 1e-9)
        // Isotropic: 100 · 0.933013 = 93.30 W/m² – Hay–Davies adds the circumsolar light a sun-facing panel sees.
        assertEquals(93.301, poaComponents(irr, sun, 30.0, 180.0, 0.0, SkyDiffuseModel.ISOTROPIC).skyDiffuse, 0.01)
        // Overcast (no beam) = isotropic; horizontal plane = GHI for both models.
        val overcast = Irradiance(0.0, 150.0)
        assertEquals(poaComponents(overcast, sun, 30.0, 180.0, 0.0, SkyDiffuseModel.ISOTROPIC).total,
            poaComponents(overcast, sun, 30.0, 180.0, 0.0).total, 1e-9)
        assertEquals(irr.ghi(sun), planeOfArrayIrradiance(irr, sun, 0.0, 180.0, 0.2), 1e-9)
        // Panel 70° facing north: cos AOI = cos30·cos70 − sin30·sin70 = 0.2962 − 0.4698 < 0 → sun behind it,
        // no beam and no circumsolar part; sky = 100 · 0.412197 · (1 + cos 70°)/2 = 100 · 0.412197 · 0.671010 = 27.659.
        val north = poaComponents(irr, sun, 70.0, 0.0, albedo = 0.0, extraterrestrialDni = 1361.0)
        assertEquals(0.0, north.beam, 1e-9)
        assertEquals(27.659, north.skyDiffuse, 0.01)
    }

    @Test
    fun clearDayShapeNightSunriseNoonSunset() {
        val est = PvEstimator()
        val profile = est.dailyProfile(south30, warsaw, day, zone, stepMinutes = 5)
        val times = SolarCalculator.sunTimes(warsaw, day)
        // Night: exactly zero.
        profile.filter { it.time.isBefore(times.sunrise!!.minusSeconds(60)) || it.time.isAfter(times.sunset!!.plusSeconds(60)) }
            .forEach { assertEquals(0.0, it.powerKw, 0.0) }
        // Morning rises, afternoon falls; the maximum is within 20 min of solar noon for a south panel.
        val peak = profile.maxBy { it.powerKw }
        assertTrue(abs(Duration.between(peak.time, times.solarNoon).toMinutes()) <= 20)
        val morning = profile.filter { it.time.isAfter(times.sunrise) && it.time.isBefore(times.solarNoon.minusSeconds(3600)) }
        assertTrue(morning.zipWithNext().all { (a, b) -> b.powerKw >= a.powerKw - 1e-9 })
        // Never above the peak power; a sensible clear midsummer day for 5 kWp at 52° N (≈ 6–7 kWh/kWp).
        assertTrue(profile.all { it.powerKw <= 5.0 })
        val kwh = est.dailyEnergyKwh(south30, warsaw, day, zone)
        assertTrue("clear June day $kwh kWh", kwh in 25.0..40.0)
    }

    @Test
    fun orientationEastWestAndTilt() {
        val est = PvEstimator()
        val morning = day.atTime(8, 0).atZone(zone).toInstant()
        val evening = day.atTime(18, 0).atZone(zone).toInstant()
        val east = south30.copy(azimuthDeg = 90.0)
        val west = south30.copy(azimuthDeg = 270.0)
        assertTrue(est.powerKw(east, warsaw, morning) > est.powerKw(west, warsaw, morning))
        assertTrue(est.powerKw(west, warsaw, evening) > est.powerKw(east, warsaw, evening))
        // December: a steep south panel beats a flat one at noon (low sun).
        val dec = LocalDate.of(2026, 12, 21)
        val noon = SolarCalculator.sunTimes(warsaw, dec).solarNoon
        assertTrue(est.powerKw(south30.copy(tiltDeg = 60.0), warsaw, noon) > est.powerKw(south30.copy(tiltDeg = 0.0), warsaw, noon))
        // Power scales with the installed peak power.
        assertEquals(2 * est.powerKw(south30, warsaw, noon), est.powerKw(south30.copy(peakPowerKw = 10.0), warsaw, noon), 1e-9)
    }

    private fun model(temp: Double, wind: Double?): PvEstimator {
        val noon = SolarCalculator.sunTimes(warsaw, day).solarNoon
        val end = Instant.ofEpochSecond((noon.epochSecond / 3600 + 1) * 3600)
        val hours = (-3..3).map { HourlyWeather(end.plusSeconds(it * 3600L), null, 800.0, 100.0, temp, 0.0, windSpeedMs = wind) }
        return PvEstimator(WeatherAwareIrradianceModel(warsaw, WeatherForecast(hours, noon)))
    }

    @Test
    fun temperatureWindAndDatasheetCoefficient() {
        val noon = SolarCalculator.sunTimes(warsaw, day).solarNoon
        val hot = model(35.0, 1.0).pointEstimate(south30, warsaw, noon)
        val cold = model(5.0, 1.0).pointEstimate(south30, warsaw, noon)
        assertTrue(hot.powerKw < cold.powerKw)
        // Faiman: Tcell = Ta + POA / (25 + 6.84·v).
        assertEquals(35.0 + hot.poa / (25.0 + 6.84), hot.cellTemperatureC!!, 1e-6)
        val windy = model(25.0, 8.0).pointEstimate(south30, warsaw, noon)
        val calm = model(25.0, 0.0).pointEstimate(south30, warsaw, noon)
        assertTrue("wind cools the cells", windy.cellTemperatureC!! < calm.cellTemperatureC!! - 10)
        assertTrue(windy.powerKw > calm.powerKw)
        // A smaller |γ| (e.g. TOPCon −0.29 %/°C) loses less in the heat.
        val topcon = model(35.0, 1.0).pointEstimate(south30.copy(temperatureCoefficient = -0.0029), warsaw, noon)
        assertTrue(topcon.powerKw > hot.powerKw)
        // Ratio check: (1 + γ(T−25)) with T ≈ 60 °C → ≈ 0.86 vs 0.90; independent of the rest of the chain.
        val expectedRatio = (1 - 0.0029 * (hot.cellTemperatureC!! - 25)) / (1 - 0.004 * (hot.cellTemperatureC!! - 25))
        assertEquals(expectedRatio, topcon.powerKw / hot.powerKw, 1e-6)
    }

    @Test
    fun inverterLimitClipsAndIsReported() {
        val noon = SolarCalculator.sunTimes(warsaw, day).solarNoon
        val free = PvEstimator().pointEstimate(south30, warsaw, noon)
        assertTrue(free.powerKw > 3.0)
        val limited = PvEstimator().pointEstimate(south30.copy(inverterLimitKw = 3.0), warsaw, noon)
        assertEquals(3.0, limited.powerKw, 1e-9)
        assertTrue(limited.clipped)
        assertTrue(!free.clipped)
        // Clipping costs energy only around noon.
        val e1 = PvEstimator().dailyEnergyKwh(south30, warsaw, day, zone)
        val e2 = PvEstimator().dailyEnergyKwh(south30.copy(inverterLimitKw = 3.0), warsaw, day, zone)
        assertTrue(e2 < e1 && e2 > e1 * 0.8)
    }

    @Test
    fun angleOfIncidenceLossShapesTheDayButIsNormalisedOverTheYear() {
        val est = PvEstimator()
        val f = est.typicalAngleFactor(warsaw, 30.0, 180.0)
        assertTrue("typical clear-sky angle factor $f", f in 0.93..0.995)
        // A vertical east wall sees the sun at grazing angles much more often: larger typical loss.
        assertTrue(est.typicalAngleFactor(warsaw, 90.0, 90.0) < f)
    }

    @Test
    fun unitsPowerToEnergyAndDst() {
        val est = PvEstimator()
        // Energy over one hour = mean power over that hour (kW · 1 h = kWh), not the sum of samples.
        val from = day.atTime(12, 0).atZone(zone).toInstant()
        val to = from.plusSeconds(3600)
        val samples = (0 until 3600 step 10).map { est.powerKw(south30, warsaw, from.plusSeconds(it + 5L)) }
        assertEquals(samples.average(), est.energyKwh(south30, warsaw, from, to, stepMinutes = 1), 0.005)
        // Spring DST day in Warsaw has 23 hours: 92 quarter-hours + the 24:00 point.
        val dst = LocalDate.of(2026, 3, 29)
        assertEquals(93, est.dailyProfile(south30, warsaw, dst, zone, 15).size)
        assertEquals(97, est.dailyProfile(south30, warsaw, dst.plusDays(1), zone, 15).size)
    }

    @Test
    fun forecastHoursJoinWithoutStepsAndNoBeamBeforeSunrise() {
        val times = SolarCalculator.sunTimes(warsaw, day)
        val start = Instant.ofEpochSecond(times.sunrise!!.epochSecond / 3600 * 3600)
        val hours = (1..10).map { HourlyWeather(start.plusSeconds(it * 3600L), null, 200.0 + it * 40, 80.0, 15.0, 30.0) }
        val model = WeatherAwareIrradianceModel(warsaw, WeatherForecast(hours, start))
        // Just before sunrise inside the first forecast hour: nothing.
        val before = times.sunrise!!.minusSeconds(120)
        if (before.isAfter(start)) assertEquals(0.0, model.irradiance(SolarCalculator.position(warsaw, before), before).ghi(SolarCalculator.position(warsaw, before)), 0.0)
        // Across every full hour the global irradiance changes smoothly (no jump of the hour-mean step).
        for (h in 3..8) {
            val b = start.plusSeconds(h * 3600L)
            val p1 = SolarCalculator.position(warsaw, b.minusSeconds(1)); val p2 = SolarCalculator.position(warsaw, b.plusSeconds(1))
            val g1 = model.irradiance(p1, b.minusSeconds(1)).ghi(p1); val g2 = model.irradiance(p2, b.plusSeconds(1)).ghi(p2)
            assertTrue("hour $h: $g1 → $g2", abs(g2 - g1) < 0.02 * maxOf(g1, g2) + 1.0)
        }
    }
}
