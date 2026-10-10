package com.solartracker.pro.core.weather

import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.ConsumptionProfile
import com.solartracker.pro.core.energy.EnergyFlowSimulator
import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset

class WeatherTest {

    private val warsaw = GeoLocation(52.2297, 21.0122)
    private val zone = ZoneId.of("Europe/Warsaw")
    private val day = LocalDate.of(2024, 6, 21)
    private val system = PvSystem()

    // --- Open-Meteo parsing ---

    /** Builds a response in Open-Meteo's documented format (timeformat=unixtime). */
    private fun forecastJson(
        date: LocalDate,
        hours: Int = 48,
        dni: (Int) -> String = { "0.0" },
        dhi: (Int) -> String = { "0.0" },
        ghi: (Int) -> String = { "0.0" },
        temp: (Int) -> String = { "15.0" },
        cloud: (Int) -> String = { "100" },
    ): String {
        val start = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        fun arr(f: (Int) -> String) = (0 until hours).joinToString(",", "[", "]") { f(it) }
        return """
            {"latitude":52.22,"longitude":21.0,"generationtime_ms":0.5,"utc_offset_seconds":0,
             "timezone":"GMT","timezone_abbreviation":"GMT","elevation":110.0,
             "hourly_units":{"time":"unixtime","temperature_2m":"°C","cloud_cover":"%",
               "shortwave_radiation":"W/m²","direct_normal_irradiance":"W/m²","diffuse_radiation":"W/m²"},
             "hourly":{"time":${arr { (start + it * 3600L).toString() }},
               "temperature_2m":${arr(temp)},"cloud_cover":${arr(cloud)},
               "shortwave_radiation":${arr(ghi)},"direct_normal_irradiance":${arr(dni)},
               "diffuse_radiation":${arr(dhi)}}}
        """.trimIndent()
    }

    @Test
    fun parseForecast_readsHourlyValuesAndNulls() {
        val json = forecastJson(day, hours = 3, dni = { if (it == 1) "null" else "500.5" }, temp = { "${20 + it}" })
        val f = OpenMeteo.parseForecast(json, Instant.EPOCH)
        assertEquals(3, f.hours.size)
        assertEquals(500.5, f.hours[0].dni!!, 0.0)
        assertNull(f.hours[1].dni)
        assertEquals(22.0, f.hours[2].temperatureC!!, 0.0)
        assertEquals(100.0, f.hours[0].cloudCoverPercent!!, 0.0)
    }

    @Test
    fun forecastLookup_usesPrecedingHourConvention() {
        val f = OpenMeteo.parseForecast(forecastJson(day, hours = 3), Instant.EPOCH)
        val t1 = day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600)
        assertEquals(t1, f.at(t1.minusSeconds(1))!!.endTime) // 00:59:59 belongs to hour ending 01:00
        assertEquals(t1, f.at(t1)!!.endTime) // end is inclusive
        assertEquals(t1.plusSeconds(3600), f.at(t1.plusSeconds(1))!!.endTime)
        assertNull(f.at(t1.plusSeconds(3 * 3600)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseForecast_rejectsApiError() {
        OpenMeteo.parseForecast("""{"error":true,"reason":"Latitude must be in range"}""", Instant.EPOCH)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseForecast_rejectsGarbage() {
        OpenMeteo.parseForecast("<html>", Instant.EPOCH)
    }

    private fun climateJson(years: IntRange, mjPerDay: (Month) -> Double, temp: (Month) -> Double?): String {
        val days = years.flatMap { y ->
            generateSequence(LocalDate.of(y, 1, 1)) { it.plusDays(1) }.takeWhile { it.year == y }.toList()
        }
        return """
            {"daily_units":{"time":"unixtime","shortwave_radiation_sum":"MJ/m²","temperature_2m_mean":"°C"},
             "daily":{"time":${days.joinToString(",", "[", "]") { it.atStartOfDay(ZoneOffset.UTC).toEpochSecond().toString() }},
               "shortwave_radiation_sum":${days.joinToString(",", "[", "]") { mjPerDay(it.month).toString() }},
               "temperature_2m_mean":${days.joinToString(",", "[", "]") { temp(it.month).toString() }}}}
        """.trimIndent()
    }

    @Test
    fun parseClimate_averagesPerMonthAndConvertsMegajoules() {
        val c = OpenMeteo.parseClimate(climateJson(2021..2023, { if (it == Month.JUNE) 18.0 else 3.6 }, { it.value.toDouble() }))
        assertEquals(5.0, c.dailyGhiKwhPerM2.getValue(Month.JUNE), 1e-9)
        assertEquals(1.0, c.dailyGhiKwhPerM2.getValue(Month.JANUARY), 1e-9)
        assertEquals(12.0, c.meanTemperatureC.getValue(Month.DECEMBER), 1e-9)
        assertEquals(ClimateSource.ARCHIVE, c.source)
        assertEquals(3, c.years)
    }

    @Test
    fun parseClimate_monthWithoutTemperatureStaysMissing() {
        val c = OpenMeteo.parseClimate(climateJson(2021..2023, { 3.6 }, { if (it == Month.MARCH) null else -1.5 }))
        assertEquals(null, c.meanTemperatureC[Month.MARCH])
        assertEquals(-1.5, c.meanTemperatureC.getValue(Month.OCTOBER), 1e-9)
        assertEquals(11, c.meanTemperatureC.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseClimate_rejectsIncompleteYear() {
        OpenMeteo.parseClimate("""{"daily":{"time":[0,86400],"shortwave_radiation_sum":[1.0,2.0],"temperature_2m_mean":[1.0,2.0]}}""")
    }

    @Test
    fun urls_useDotDecimalsAndRequiredParameters() {
        val f = OpenMeteo.forecastUrl(GeoLocation(52.2297, -21.5))
        assertTrue(f.startsWith("https://api.open-meteo.com/v1/forecast?latitude=52.2297&longitude=-21.5000"))
        assertTrue("direct_normal_irradiance" in f && "timeformat=unixtime" in f)
        val c = OpenMeteo.climateUrl(warsaw, 2022, 2024)
        assertTrue("start_date=2022-01-01" in c && "end_date=2024-12-31" in c)
    }

    // --- Irradiance model ---

    @Test
    fun noWeatherData_isIdenticalToClearSky() {
        val model = WeatherAwareIrradianceModel(warsaw)
        val withModel = PvEstimator(model).dailyEnergyKwh(system, warsaw, day, zone)
        val clear = PvEstimator().dailyEnergyKwh(system, warsaw, day, zone)
        assertEquals(clear, withModel, 1e-12)
        assertEquals(WeatherSource.CLEAR_SKY, model.sourceAt(Instant.now()))
    }

    @Test
    fun forecastHourMeansArePreservedInsideTheHour() {
        // Provider values are hour means. Inside the hour the irradiance follows the sun (clear-sky index
        // interpolation), but the hour's mean global irradiance, its diffuse share and the air temperature stay.
        val noon = SolarCalculator.sunTimes(warsaw, day).solarNoon
        val json = forecastJson(day, dni = { "300.0" }, dhi = { "120.0" }, temp = { "25.0" })
        val model = WeatherAwareIrradianceModel(warsaw, OpenMeteo.parseForecast(json, Instant.EPOCH))
        val hourEnd = Instant.ofEpochSecond((noon.epochSecond / 3600 + 1) * 3600)
        var ghiSum = 0.0; var dhiSum = 0.0; var cosSum = 0.0
        for (k in 0 until 60) {
            val t = hourEnd.minusSeconds(3600L - k * 60L - 30L)
            val pos = SolarCalculator.position(warsaw, t)
            val irr = model.irradiance(pos, t)
            ghiSum += irr.ghi(pos); dhiSum += irr.dhi; cosSum += kotlin.math.cos(Math.toRadians(pos.zenithDeg))
        }
        val providerGhi = 300.0 * cosSum / 60 + 120.0
        assertEquals(providerGhi, ghiSum / 60, providerGhi * 0.02)
        assertEquals(120.0, dhiSum / 60, 120.0 * 0.03)
        val irr = model.irradiance(SolarCalculator.position(warsaw, noon), noon)
        assertEquals(25.0, irr.ambientTemperatureC!!, 0.0)
        assertEquals(WeatherSource.FORECAST, model.sourceAt(noon))
    }

    @Test
    fun overcastForecast_producesMuchLessThanClearSky() {
        // Overcast: no beam, 80 W/m² diffuse during the day.
        val json = forecastJson(day, dhi = { if (it in 4..19) "80.0" else "0.0" })
        val model = WeatherAwareIrradianceModel(warsaw, OpenMeteo.parseForecast(json, Instant.EPOCH))
        val cloudy = PvEstimator(model).dailyEnergyKwh(system, warsaw, day, zone)
        val clear = PvEstimator().dailyEnergyKwh(system, warsaw, day, zone)
        assertTrue("cloudy $cloudy clear $clear", cloudy in 0.5..clear * 0.25)
    }

    @Test
    fun darkForecast_givesZeroEnergyAndEmptyBatteryCharging() {
        val json = forecastJson(day.minusDays(1), hours = 96)
        val estimator = PvEstimator(WeatherAwareIrradianceModel(warsaw, OpenMeteo.parseForecast(json, Instant.EPOCH)))
        assertEquals(0.0, estimator.dailyEnergyKwh(system, warsaw, day, zone), 1e-9)
        val sim = EnergyFlowSimulator(estimator).simulate(system, warsaw, day, 1, zone, ConsumptionProfile.constant(0.5), BatteryStorage())
        assertEquals(0.0, sim.balance.toBatteryKwh, 1e-9)
        assertEquals(12.0, sim.balance.consumptionKwh, 1e-9)
        assertTrue(sim.balance.fromBatteryKwh > 0.0)
    }

    @Test
    fun outsideForecast_fallsBackToClimate() {
        val json = forecastJson(day, hours = 24)
        val model = WeatherAwareIrradianceModel(warsaw, OpenMeteo.parseForecast(json, Instant.EPOCH), MonthlyClimate.DEFAULT_POLAND)
        val later = day.plusDays(3).atTime(12, 0).atZone(zone).toInstant()
        assertEquals(WeatherSource.CLIMATE, model.sourceAt(later))
        assertEquals(WeatherSource.FORECAST, model.sourceAt(day.atTime(12, 0).atZone(zone).toInstant()))
    }

    @Test
    fun climate_scalesMonthlyIrradiationToTypicalValues() {
        val model = WeatherAwareIrradianceModel(warsaw, climate = MonthlyClimate.DEFAULT_POLAND)
        val factors = model.monthlyClearnessFactor!!
        assertTrue(factors.values.all { it in 0.05..1.0 })
        assertTrue("winter is cloudier", factors.getValue(Month.DECEMBER) < factors.getValue(Month.JUNE))
        // Integrated horizontal irradiation for a month matches the climate value.
        val days = Month.MAY.length(false)
        val flatNoLoss = PvSystem(peakPowerKw = 1.0, tiltDeg = 0.0, performanceRatio = 1.0)
        val noTemperature = WeatherAwareIrradianceModel(warsaw, climate = MonthlyClimate.DEFAULT_POLAND.copy(meanTemperatureC = emptyMap()))
        val may = PvEstimator(noTemperature).monthlyEnergy(flatNoLoss, warsaw, 2025, ZoneOffset.UTC, tilts = listOf(0.0))[0]
            .energyByMonthKwh.getValue(Month.MAY)
        val expected = MonthlyClimate.DEFAULT_POLAND.dailyGhiKwhPerM2.getValue(Month.MAY) * days
        assertEquals(expected, may, expected * 0.03)
    }

    @Test
    fun climate_givesRealisticYearlyYieldForPoland() {
        val estimator = PvEstimator(WeatherAwareIrradianceModel(warsaw, climate = MonthlyClimate.DEFAULT_POLAND))
        val yearly = estimator.monthlyEnergy(PvSystem(peakPowerKw = 1.0), warsaw, 2025, zone, tilts = listOf(0.0, 35.0))
        val flat = yearly[0].yearlyKwh
        val tilted = yearly[1].yearlyKwh
        // PVGIS: roughly 850–950 kWh/kWp flat and ~1000–1100 kWh/kWp at optimum tilt in central Poland.
        assertTrue("flat $flat", flat in 750.0..1000.0)
        assertTrue("tilted $tilted", tilted in 900.0..1200.0)
        assertTrue("optimum tilt gains ≥ 7%: $flat → $tilted", tilted > flat * 1.07)
        val clear = PvEstimator().monthlyEnergy(PvSystem(peakPowerKw = 1.0), warsaw, 2025, zone, tilts = listOf(0.0))[0].yearlyKwh
        assertTrue(flat < clear * 0.85)
    }

    @Test
    fun defaultClimate_onlyForPoland() {
        assertNotNull(MonthlyClimate.defaultFor(warsaw))
        assertNull(MonthlyClimate.defaultFor(GeoLocation(-33.87, 151.21)))
    }

    // --- Two-state sky and temperature ---

    @Test
    fun mixClearAndOvercast_preservesMeanGlobalIrradiance() {
        val noon = SolarCalculator.sunTimes(warsaw, day).solarNoon
        val pos = SolarCalculator.position(warsaw, noon)
        val clear = ClearSkyModel().irradiance(pos, noon)
        for (k in listOf(0.1, 0.25, 0.4, 0.7, 1.0)) {
            val mixed = WeatherAwareIrradianceModel.mixClearAndOvercast(clear, pos, k)
            assertEquals("k=$k", clear.ghi(pos) * k, mixed.ghi(pos), 1e-6)
        }
        val cloudy = WeatherAwareIrradianceModel.mixClearAndOvercast(clear, pos, 0.4)
        assertTrue(cloudy.dhi / cloudy.ghi(pos) > clear.dhi / clear.ghi(pos))
        assertEquals(0.0, WeatherAwareIrradianceModel.mixClearAndOvercast(clear, pos, 0.2).dni, 0.0)
        assertEquals(clear, WeatherAwareIrradianceModel.mixClearAndOvercast(clear, pos, 1.0))
    }

    @Test
    fun temperatureFactor_hotReducesColdIncreasesUnknownNeutral() {
        assertEquals(1.0, PvEstimator.temperatureFactor(800.0, null), 0.0)
        assertTrue(PvEstimator.temperatureFactor(900.0, 32.0) < 0.95)
        assertTrue(PvEstimator.temperatureFactor(600.0, -5.0) > 1.0)
        assertEquals(70.0, PvEstimator.cellTemperatureC(800.0, 45.0), 1e-9)
    }

    @Test
    fun hotForecast_producesLessThanCoolForecastWithSameSun() {
        val noon = SolarCalculator.sunTimes(warsaw, day).solarNoon
        fun power(temp: String): Double {
            val json = forecastJson(day, dni = { "800.0" }, dhi = { "100.0" }, temp = { temp })
            return PvEstimator(WeatherAwareIrradianceModel(warsaw, OpenMeteo.parseForecast(json, Instant.EPOCH)))
                .powerKw(system, warsaw, noon)
        }
        assertTrue(power("35.0") < power("5.0"))
    }
}
