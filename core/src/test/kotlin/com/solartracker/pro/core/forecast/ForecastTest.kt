package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.analytics.ComparisonContext
import com.solartracker.pro.core.analytics.HistorySample
import com.solartracker.pro.core.analytics.ModelComparison
import com.solartracker.pro.core.analytics.RealEnergyFlow
import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.ConsumptionProfile
import com.solartracker.pro.core.inverter.FakeAnenjiProvider
import com.solartracker.pro.core.inverter.Freshness
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.shading.HeightSource
import com.solartracker.pro.core.shading.HeightValue
import com.solartracker.pro.core.shading.LatLon
import com.solartracker.pro.core.shading.Local
import com.solartracker.pro.core.shading.LocalFrame
import com.solartracker.pro.core.shading.LocationAccuracy
import com.solartracker.pro.core.shading.Obstacle
import com.solartracker.pro.core.shading.ObstacleShape
import com.solartracker.pro.core.shading.ObstacleType
import com.solartracker.pro.core.shading.PvArrayGeometry
import com.solartracker.pro.core.shading.ShadingAnalysisEngine
import com.solartracker.pro.core.shading.ShadingForecastService
import com.solartracker.pro.core.shading.ShadingSite
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ForecastTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val loc = GeoLocation(52.23, 21.01, 100.0)
    private val system = PvSystem(peakPowerKw = 6.0, tiltDeg = 35.0, azimuthDeg = 180.0)
    private val weather = WeatherAwareIrradianceModel(loc)
    private val noon = LocalDate.of(2026, 6, 21).atTime(13, 0).atZone(zone).toInstant()

    private fun sample(start: Instant, loadKw: Double) = HistorySample(start, start.plusSeconds(1800), 60, null, null, loadKw * 1000, null, null, null, null, null, null, null, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null, emptySet(), emptySet())

    @Test
    fun loadForecastLearnsHourlyProfilesAndRejectsAnomalies() {
        val now = LocalDate.of(2026, 10, 5).atTime(12, 0).atZone(zone).toInstant() // Monday
        val history = (1..21).flatMap { d ->
            val day = LocalDate.of(2026, 10, 5).minusDays(d.toLong())
            val weekend = day.dayOfWeek.value >= 6
            (0..23).map { h -> sample(day.atTime(h, 0).atZone(zone).toInstant(), if (h == 18) (if (weekend) 1.0 else 2.0) else 0.5) }
        } + sample(LocalDate.of(2026, 10, 4).atTime(18, 0).atZone(zone).toInstant(), 40.0) // anomaly
        val f = LoadForecaster(history, zone, ConsumptionProfile.constant(0.3), now)
        assertTrue(f.usesHistory)
        val mon18 = f.at(LocalDate.of(2026, 10, 5).atTime(18, 15).atZone(zone).toInstant())
        assertEquals(2.0, mon18.kw, 0.01)
        assertEquals(DataKind.FORECAST, mon18.kind)
        val sat18 = f.at(LocalDate.of(2026, 10, 10).atTime(18, 15).atZone(zone).toInstant())
        assertEquals(1.0, sat18.kw, 0.05)
        assertEquals(0.5, f.typicalKw(LocalDate.of(2026, 10, 5).atTime(3, 0).atZone(zone).toInstant())!!, 0.01)

        val none = LoadForecaster(emptyList(), zone, ConsumptionProfile.constant(0.3), now)
        assertFalse(none.usesHistory)
        assertEquals(DataKind.ESTIMATED, none.at(now).kind)
        assertEquals(0.3, none.at(now).kw, 0.0)
        assertNull(none.typicalKw(now))
    }

    @Test
    fun predictivePvAppliesCalibrationNowcastLimitAndShading() {
        val plain = PredictivePvEngine(loc, system, weather, null)
        val p = plain.at(noon, noon)
        assertTrue(p.expectedKw > 3.0)
        assertEquals(DataKind.ESTIMATED, p.kind) // clear sky only, no forecast
        val calibrated = PredictivePvEngine(loc, system, weather, null, calibrationFactor = 0.9, calibrationConfidence = 1.0).at(noon, noon)
        assertEquals(p.expectedKw * 0.9, calibrated.expectedKw, 1e-9)
        val nowcast = plain.at(noon, noon, nowcastRatio = 0.5)
        assertEquals(p.expectedKw * 0.5, nowcast.expectedKw, 1e-9)
        val later = plain.at(noon.plus(Duration.ofHours(3)), noon, nowcastRatio = 0.5)
        val laterNoNowcast = plain.at(noon.plus(Duration.ofHours(3)), noon)
        assertTrue("nowcast fades", later.expectedKw > laterNoNowcast.expectedKw * 0.9)
        val limited = PredictivePvEngine(loc, system, weather, null, inverterLimitKw = 2.0).at(noon, noon)
        assertEquals(2.0, limited.expectedKw, 1e-9)
        assertTrue(limited.clipped)
        assertTrue(p.minKw <= p.expectedKw && p.expectedKw <= p.maxKw)

        val frame = LocalFrame(LatLon(loc.latitude, loc.longitude))
        fun at(e: Double, n: Double) = frame.toLatLon(Local(e, n))
        val wall = Obstacle("wall", ObstacleType.BUILDING, ObstacleShape.Polygon(listOf(at(-20.0, -6.0), at(20.0, -6.0), at(20.0, -8.0), at(-20.0, -8.0))), HeightValue(9.0, HeightSource.USER_CONFIRMED), source = "t")
        val site = ShadingSite(loc, LocationAccuracy.MAP_POINT, true, PvArrayGeometry(35.0, 180.0), listOf(wall), null, true)
        val shaded = PredictivePvEngine(loc, system, weather, ShadingAnalysisEngine(site)).at(LocalDate.of(2026, 12, 21).atTime(12, 0).atZone(zone).toInstant(), noon)
        assertTrue(shaded.shadingLossKw > 0)
        assertEquals(shaded.unshadedKw - shaded.shadingLossKw, shaded.expectedKw, 1e-6)
    }

    @Test
    fun batteryPredictionNightDischargeAndDayCharge() {
        val battery = BatteryStorage(nominalCapacityKwh = 10.0, usableCapacityPercent = 100.0, minSocPercent = 20.0, maxSocPercent = 100.0, maxChargePowerKw = 3.0, maxDischargePowerKw = 3.0)
        val predictor = BatteryPredictor(battery, zone)
        val evening = LocalDate.of(2026, 6, 21).atTime(20, 0).atZone(zone).toInstant()
        val pv = PredictivePvEngine(loc, system, weather, null)
        val load = LoadForecaster(emptyList(), zone, ConsumptionProfile.constant(0.5), evening)
        val p = predictor.predict(evening, 60.0, DataKind.MEASURED, { pv.at(it, evening) }, { load.at(it) })
        val midnight = p.milestones.first { it.label == "o północy" }
        assertEquals(60.0 - 4.0 * 0.5 / 0.95 / 10.0 * 100.0, midnight.socPercent, 1.0) // 4 h × 0.5 kW from the battery
        assertNotNull(p.emptyAt) // reaches 20% before sunrise production
        assertNotNull(p.chargingStarts)
        assertTrue(p.chargingStarts!!.isAfter(p.emptyAt))
        assertEquals((6.0 - 2.0) * 0.95, p.energyAvailableKwh, 1e-9)
        assertTrue(p.rows.all { (it.socPercent ?: 0.0) in 19.99..100.0 })
        assertEquals(listOf("za 1 h", "za 3 h", "wieczorem (21:00)", "o północy", "rano (07:00)"), p.milestones.map { it.label })
    }

    @Test
    fun dayForecastCombinesMeasuredAndForecast() {
        val engine = EnergyForecastEngine(PredictivePvEngine(loc, system, weather, null), LoadForecaster(emptyList(), zone, ConsumptionProfile.constant(0.5), noon), null, zone)
        val whole = engine.day(LocalDate.of(2026, 6, 21), LocalDate.of(2026, 6, 20).atTime(12, 0).atZone(zone).toInstant(), null)
        assertNull(whole.producedKwh)
        assertTrue(whole.expectedKwh > 20)
        val today = engine.day(LocalDate.of(2026, 6, 21), noon, producedSoFarKwh = 12.0)
        assertEquals(12.0, today.producedKwh!!, 0.0)
        assertEquals(DataKind.MEASURED, today.producedKind)
        assertEquals(12.0 + today.remainingKwh, today.expectedKwh, 1e-9)
        assertTrue(today.minKwh <= today.expectedKwh && today.expectedKwh <= today.maxKwh)
        val short = engine.shortTerm(noon, nowcastRatio = 0.8)
        assertEquals(6, short.size)
        assertTrue(short.first().point.confidence >= short.last().point.confidence)
    }

    @Test
    fun advisorAnswersFromDataAndAdmitsMissingData() {
        val t = FakeAnenjiProvider.defaultReading(noon)
        val base = AdvisorContext(
            now = noon, zone = zone, telemetry = t, freshness = Freshness.LIVE, simulated = true, flow = RealEnergyFlow.from(t),
            today = null, battery = null, batteryMinSoc = 20.0, sunsetAt = null,
            comparison = ModelComparison.compare(3.1, 2.84, ComparisonContext(50.0, 3.1, cloudCoverPercent = 60.0)),
            shadingToday = emptyList(), nextShadow = null, shadingConfidence = null, dailyShadingLossKwh = null, alerts = emptyList(),
        )
        val now = SolarAdvisor.answer(AdvisorQuestion.INVERTER_NOW, base)
        assertTrue(now.text, now.text.contains("off-grid") && now.text.contains("PV "))
        assertTrue(now.usedData.contains("SYMULATOR (nie pomiar)"))
        val why = SolarAdvisor.answer(AdvisorQuestion.WHY_LESS, base)
        assertTrue(why.text, why.text.contains("-8.4%") && why.text.contains("zachmurzenie"))
        val offline = SolarAdvisor.answer(AdvisorQuestion.PRODUCTION_NOW, base.copy(freshness = Freshness.LAST_KNOWN))
        assertTrue(offline.text.contains("OFFLINE"))
        val noBattery = SolarAdvisor.answer(AdvisorQuestion.BATTERY_UNTIL_MORNING, base)
        assertFalse(noBattery.complete)
        assertTrue(SolarAdvisor.answer(AdvisorQuestion.SHADOW_WHEN, base).text.contains("nie jest skonfigurowany"))
        assertEquals(AdvisorQuestion.SHADOW_WHEN, SolarAdvisor.match("Kiedy dzisiaj pojawi się cień?"))
        assertEquals(AdvisorQuestion.BATTERY_UNTIL_MORNING, SolarAdvisor.match("czy bateria wystarczy do rana"))
        assertEquals(AdvisorQuestion.HEIGHT_CONFIRMED, SolarAdvisor.match("Czy wysokość tego budynku jest potwierdzona?"))
        AdvisorQuestion.entries.forEach { assertEquals(it.text, it, SolarAdvisor.match(it.text)) }
        assertNull(SolarAdvisor.match("jaka jest stolica Francji"))
    }

    @Test
    fun advisorShadowAnswersUseShadingModel() {
        val frame = LocalFrame(LatLon(loc.latitude, loc.longitude))
        fun at(e: Double, n: Double) = frame.toLatLon(Local(e, n))
        val dom = Obstacle("dom", ObstacleType.BUILDING, ObstacleShape.Polygon(listOf(at(-15.0, -15.0), at(15.0, -15.0), at(15.0, -25.0), at(-15.0, -25.0))), HeightValue.fromLevels(4.0), name = "Blok", source = "OSM")
        val engine = ShadingAnalysisEngine(ShadingSite(loc, LocationAccuracy.MAP_POINT, true, PvArrayGeometry(35.0, 180.0), listOf(dom), null, true))
        val service = ShadingForecastService(engine, system, zone)
        val date = LocalDate.of(2026, 12, 21)
        val (day, events) = service.forDay(date)
        val morning = date.atTime(7, 0).atZone(zone).toInstant()
        val ctx = AdvisorContext(morning, zone, null, Freshness.NONE, false, null, null, null, null, null, null, events, null, engine.confidence(), day.lossKwh, emptyList())
        assertTrue(SolarAdvisor.answer(AdvisorQuestion.SHADOW_WHEN, ctx).text.contains("Blok:"))
        assertTrue(SolarAdvisor.answer(AdvisorQuestion.SHADOW_WHICH, ctx).text.contains("Szacunek z liczby kondygnacji"))
        assertTrue(SolarAdvisor.answer(AdvisorQuestion.HEIGHT_CONFIRMED, ctx).text.contains("Szacunek"))
        assertTrue(SolarAdvisor.answer(AdvisorQuestion.SHADOW_LOSS, ctx).text.contains("OBLICZONE"))
        assertTrue(SolarAdvisor.answer(AdvisorQuestion.INVERTER_NOW, ctx).text.contains("Brak danych z falownika"))
    }
}
