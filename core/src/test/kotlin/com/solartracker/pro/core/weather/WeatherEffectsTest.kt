package com.solartracker.pro.core.weather

import com.solartracker.pro.core.forecast.PredictivePvEngine
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WeatherEffectsTest {
    private val t = Instant.parse("2026-03-01T11:00:00Z")

    private fun hour(snow: Double? = null, temp: Double? = -3.0, wind: Double? = null, rain: Double? = null, vis: Double? = null) =
        HourlyWeather(t.plusSeconds(1800), 500.0, 600.0, 100.0, temp, 20.0, wind, 80.0, rain, snow, vis)

    @Test
    fun parsesExtendedFieldsAndToleratesMissingOnes() {
        val json = """{"hourly":{"time":[${t.epochSecond}],"shortwave_radiation":[400],"direct_normal_irradiance":[500],"diffuse_radiation":[100],
            "temperature_2m":[-2.5],"cloud_cover":[30],"wind_speed_10m":[6.5],"relative_humidity_2m":[88],"precipitation":[0.4],"snow_depth":[0.12],"visibility":[800]}}"""
        val h = OpenMeteo.parseForecast(json, t).hours.single()
        assertEquals(6.5, h.windSpeedMs!!, 0.0)
        assertEquals(88.0, h.relativeHumidityPercent!!, 0.0)
        assertEquals(0.4, h.precipitationMm!!, 0.0)
        assertEquals(0.12, h.snowDepthM!!, 0.0)
        assertEquals(800.0, h.visibilityM!!, 0.0)
        val old = """{"hourly":{"time":[${t.epochSecond}],"shortwave_radiation":[400],"direct_normal_irradiance":[500],"diffuse_radiation":[100],"temperature_2m":[5],"cloud_cover":[30]}}"""
        val o = OpenMeteo.parseForecast(old, t).hours.single()
        assertNull(o.windSpeedMs)
        assertNull(o.snowDepthM)
        assertTrue(OpenMeteo.forecastUrl(GeoLocation(52.0, 21.0)).contains("wind_speed_unit=ms"))
    }

    @Test
    fun windAndSnowEffects() {
        assertEquals(1.0, WeatherEffects.windFactor(800.0, 20.0, null), 0.0)
        assertEquals(1.0, WeatherEffects.windFactor(800.0, null, 5.0), 0.0)
        assertEquals(1.0, WeatherEffects.windFactor(800.0, 20.0, 1.0), 1e-12)
        assertTrue(WeatherEffects.windFactor(800.0, 20.0, 10.0) > 1.02)
        assertTrue(WeatherEffects.windFactor(800.0, 20.0, 0.0) < 1.0)
        assertTrue(WeatherEffects.snowCovered(hour(snow = 0.1), 30.0))
        assertFalse(WeatherEffects.snowCovered(hour(snow = 0.1), 70.0))
        assertFalse(WeatherEffects.snowCovered(hour(snow = 0.1, temp = 4.0), 30.0))
        assertFalse(WeatherEffects.snowCovered(hour(snow = null), 30.0))
        assertFalse(WeatherEffects.snowCovered(hour(snow = 0.1, temp = null), 30.0))
        assertNull(WeatherEffects.describe(hour()))
        val d = WeatherEffects.describe(hour(snow = 0.05, wind = 12.0, rain = 2.0, vis = 300.0))!!
        assertTrue(d.contains("wiatr") && d.contains("opady") && d.contains("śnieg") && d.contains("mgła"))
    }

    @Test
    fun forecastDropsToZeroUnderSnow() {
        val loc = GeoLocation(52.23, 21.01)
        val system = PvSystem(peakPowerKw = 5.0, tiltDeg = 30.0, azimuthDeg = 180.0)
        fun engine(h: HourlyWeather) = PredictivePvEngine(loc, system, WeatherAwareIrradianceModel(loc, WeatherForecast(listOf(h), t)), null)
        val clear = engine(hour(snow = 0.0)).at(t, t)
        val snowy = engine(hour(snow = 0.15)).at(t, t)
        assertTrue(clear.expectedKw > 0.5)
        assertEquals(0.0, snowy.expectedKw, 0.0)
        assertTrue(snowy.basis.contains("śnieg na panelach"))
        val windy = engine(hour(snow = 0.0, temp = 25.0, wind = 10.0)).at(t, t)
        val calm = engine(hour(snow = 0.0, temp = 25.0, wind = 0.0)).at(t, t)
        assertTrue(windy.expectedKw > calm.expectedKw)
    }
}

class CloudLayersTest {
    private val t = Instant.parse("2026-10-07T08:25:00Z")

    @Test
    fun parsesLayersAndExplainsSunThroughClouds() {
        val json = """{"hourly":{"time":[${t.epochSecond}],"shortwave_radiation":[600],"direct_normal_irradiance":[700],"diffuse_radiation":[120],
            "temperature_2m":[23],"cloud_cover":[91],"cloud_cover_low":[3],"cloud_cover_mid":[8],"cloud_cover_high":[90]}}"""
        val h = OpenMeteo.parseForecast(json, t).hours.single()
        assertEquals(90.0, h.cloudHighPercent!!, 0.0)
        assertEquals(3.0, h.cloudLowPercent!!, 0.0)
        assertTrue(WeatherEffects.cloudExplanation(h, 0.9)!!.contains("wysokie"))
        assertTrue(WeatherEffects.cloudExplanation(h.copy(cloudLowPercent = 80.0), 0.9)!!.contains("przerwy"))
        assertNull(WeatherEffects.cloudExplanation(h, 0.3)) // forecast irradiance agrees with clouds
        assertNull(WeatherEffects.cloudExplanation(h.copy(cloudCoverPercent = 20.0), 0.9))
        assertTrue(OpenMeteo.forecastUrl(com.solartracker.pro.core.solar.GeoLocation(37.3, 27.3)).contains("cloud_cover_high"))
    }
}
