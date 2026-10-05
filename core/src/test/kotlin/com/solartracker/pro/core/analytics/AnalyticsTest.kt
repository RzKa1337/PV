package com.solartracker.pro.core.analytics

import com.solartracker.pro.core.inverter.BatteryReading
import com.solartracker.pro.core.inverter.GridReading
import com.solartracker.pro.core.inverter.InverterEvent
import com.solartracker.pro.core.inverter.InverterStatusReading
import com.solartracker.pro.core.inverter.InverterTelemetry
import com.solartracker.pro.core.inverter.LinkStatus
import com.solartracker.pro.core.inverter.LoadReading
import com.solartracker.pro.core.inverter.PvReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AnalyticsTest {
    private val t0 = Instant.parse("2026-06-21T10:00:00Z")

    private fun tel(at: Instant, pv: Double?, load: Double?, battery: Double?, grid: Double?, soc: Double? = 50.0) = InverterTelemetry(
        at, "t", PvReading(powerW = pv), BatteryReading(voltageV = 52.0, powerW = battery, socPercent = soc, connected = true),
        GridReading(powerW = grid), LoadReading(powerW = load),
    )

    @Test
    fun flowPvChargesBatteryAndFeedsLoad() {
        val f = RealEnergyFlow.decompose(pvW = 2840.0, loadW = 1170.0, batteryW = 1670.0, gridW = 0.0)
        assertEquals(1170.0, f.pvToLoad, 1e-9)
        assertEquals(1670.0, f.pvToBattery, 1e-9)
        assertEquals(0.0, f.gridToLoad, 1e-9)
        assertTrue(f.consistent)
        assertEquals(listOf("PV → odbiory", "PV → bateria"), f.active.map { it.first })
    }

    @Test
    fun flowNightBatteryAndGrid() {
        val f = RealEnergyFlow.decompose(pvW = 0.0, loadW = 800.0, batteryW = -500.0, gridW = 300.0)
        assertEquals(500.0, f.batteryToLoad, 1e-9)
        assertEquals(300.0, f.gridToLoad, 1e-9)
        assertTrue(f.consistent)
        val g = RealEnergyFlow.decompose(pvW = 0.0, loadW = 400.0, batteryW = 1500.0, gridW = 1900.0)
        assertEquals(400.0, g.gridToLoad, 1e-9)
        assertEquals(1500.0, g.gridToBattery, 1e-9)
        val e = RealEnergyFlow.decompose(pvW = 3000.0, loadW = 500.0, batteryW = 1000.0, gridW = -1500.0)
        assertEquals(1500.0, e.pvToGrid, 1e-9)
        assertTrue(e.consistent)
    }

    @Test
    fun flowDetectsInconsistentOrMissingData() {
        assertFalse(RealEnergyFlow.decompose(pvW = 3000.0, loadW = 500.0, batteryW = 0.0, gridW = 0.0).consistent)
        val missing = RealEnergyFlow.from(tel(t0, 1000.0, null, 0.0, 0.0))
        assertFalse(missing.consistent)
        assertEquals(listOf("moc odbiorów"), missing.missing)
    }

    @Test
    fun aggregatorBucketsAndIntegratesWithoutBridgingGaps() {
        val agg = TelemetryAggregator(Duration.ofSeconds(30), maxGap = Duration.ofSeconds(60))
        val out = mutableListOf<HistorySample>()
        for (s in 0 until 60 step 5) out += agg.add(tel(t0.plusSeconds(s.toLong()), 3600.0, 1000.0, 2000.0, -600.0, soc = 50.0 + s / 10.0))
        assertEquals(1, out.size)
        val first = out[0]
        assertEquals(6, first.samples)
        assertEquals(3600.0, first.pvW!!, 1e-9)
        // 6 readings → 5 intervals of 5 s inside the first bucket = 25 s × 3.6 kW
        assertEquals(3.6 * 25 / 3600.0, first.pvEnergyKwh, 1e-9)
        assertEquals(0.6 * 25 / 3600.0, first.gridExportKwh, 1e-9)
        assertEquals(52.5, first.socPercent!!, 1e-9)
        // gap of 10 minutes: no energy invented
        out += agg.add(tel(t0.plusSeconds(660), 3600.0, 1000.0, 2000.0, -600.0))
        val second = out.last()
        assertEquals(Instant.parse("2026-06-21T10:00:30Z"), second.start)
        val partial = agg.flush()!!
        assertEquals(0.0, partial.pvEnergyKwh, 0.0)
        val summary = TelemetryAggregator.summarize(listOf(first, second), Duration.ofMinutes(15))
        assertEquals(1, summary.size)
        assertEquals(first.pvEnergyKwh + second.pvEnergyKwh, summary[0].pvEnergyKwh, 1e-12)
    }

    @Test
    fun comparisonExplainsClippingBatteryFullAndClouds() {
        val clip = ModelComparison.compare(6.8, 6.1, ComparisonContext(50.0, 6.8, inverterRatedKw = 6.2))
        assertTrue(clip.curtailed)
        assertEquals(DeviationCause.CLIPPING, clip.causes.first().cause)

        val full = ModelComparison.compare(3.1, 1.2, ComparisonContext(45.0, 3.1, batterySocPercent = 100.0, batteryMaxSocPercent = 100.0, batteryChargeKw = 0.0, gridExportAllowed = false, loadKw = 1.2))
        assertTrue(full.curtailed)
        assertEquals(DeviationCause.BATTERY_FULL, full.causes.first().cause)

        val cloudy = ModelComparison.compare(3.10, 2.84, ComparisonContext(40.0, 3.4, shadingLossKw = 0.3, cloudCoverPercent = 70.0))
        assertEquals(-8.39, cloudy.differencePercent!!, 0.01)
        assertTrue(cloudy.causes.map { it.cause }.containsAll(listOf(DeviationCause.CLOUDS, DeviationCause.SHADING)))
        assertFalse(cloudy.curtailed)

        assertNull(ModelComparison.compare(0.0, 0.0, ComparisonContext(-5.0, 0.0)).differencePercent)
    }

    @Test
    fun calibrationNeedsEnoughDataAndRejectsOutliers() {
        val cal = CalibrationEngine(minSamples = 30, minDays = 3)
        assertFalse(cal.offer(t0, 1.0, 3.0, 6.0, 40.0, curtailed = true))
        assertFalse(cal.offer(t0, 1.0, 0.5, 6.0, 40.0, curtailed = false)) // model too small
        repeat(10) { cal.offer(t0.plusSeconds(it * 60L), 2.7, 3.0, 6.0, 40.0, false) }
        val early = cal.result()
        assertFalse(early.ready)
        assertEquals(1.0, cal.appliedFactor(), 0.0) // never applied after few readings
        for (day in 1..3) repeat(10) { cal.offer(t0.plus(Duration.ofDays(day.toLong())).plusSeconds(it * 60L), 2.7 + (it % 3) * 0.03, 3.0, 6.0, 40.0, false) }
        repeat(3) { cal.offer(t0.plus(Duration.ofDays(3)).plusSeconds(5000L + it), 0.3, 3.0, 6.0, 40.0, false) } // outliers
        val r = cal.result()
        assertTrue(r.reason, r.ready)
        assertEquals(3, r.rejectedOutliers)
        assertEquals(0.91, r.factor, 0.02)
        assertTrue(r.confidence > 0.5)
        assertTrue(cal.appliedFactor() in 0.9..1.0)
    }

    @Test
    fun anomaliesUnderperformanceShadingFaultOffline() {
        fun input(pv: Double, unshaded: Double, shaded: Double, poa: Double = 800.0) = AnomalyInput(
            t0, tel(t0, pv, 500.0, 0.0, 0.0), LinkStatus.ONLINE, unshaded, shaded, poa, false, 20.0,
            typicalLoadKw = 0.5, previous = null, batteryCapacityKwh = 10.0,
        )
        assertEquals(AnomalyType.PV_UNDERPERFORMANCE, AnomalyDetector.detect(input(2100.0, 3.5, 3.5)).first().type)
        assertEquals(AnomalyType.POSSIBLE_PV_FAULT, AnomalyDetector.detect(input(0.0, 3.5, 3.5)).first().type)
        assertEquals(AnomalyType.EXPECTED_SHADING, AnomalyDetector.detect(input(2100.0, 3.5, 2.2)).first().type)
        assertTrue(AnomalyDetector.detect(input(3400.0, 3.5, 3.5)).isEmpty())
        val offline = AnomalyDetector.detect(input(0.0, 3.5, 3.5).copy(link = LinkStatus.OFFLINE))
        assertEquals(listOf(AnomalyType.INVERTER_OFFLINE), offline.map { it.type })

        val faulty = tel(t0, 0.0, 9000.0, -3000.0, 0.0, soc = 18.0).copy(
            load = LoadReading(powerW = 9000.0, percent = 145.0),
            inverter = InverterStatusReading(temperatureC = 80.0, faults = listOf(InverterEvent(11, "Przepięcie PV", InverterEvent.Severity.FAULT))),
        )
        val types = AnomalyDetector.detect(input(0.0, 0.0, 0.0).copy(telemetry = faulty)).map { it.type }
        assertTrue(types.toString(), types.containsAll(listOf(AnomalyType.BATTERY_LOW, AnomalyType.OVERLOAD, AnomalyType.INVERTER_OVERTEMPERATURE, AnomalyType.MPPT_FAULT, AnomalyType.UNEXPECTED_LOAD)))
    }

    @Test
    fun abnormalSocChange() {
        val prev = tel(t0, 0.0, 500.0, 500.0, 0.0, soc = 50.0)
        val now = tel(t0.plusSeconds(60), 0.0, 500.0, 500.0, 0.0, soc = 70.0)
        val i = AnomalyInput(now.timestamp, now, LinkStatus.ONLINE, null, null, null, false, 20.0, typicalLoadKw = null, previous = prev, batteryCapacityKwh = 10.0)
        assertEquals(listOf(AnomalyType.ABNORMAL_SOC), AnomalyDetector.detect(i).map { it.type })
    }

    @Test
    fun alertsAreDebouncedGroupedAndNotSpammed() {
        val m = AlertManager(clearAfter = Duration.ofMinutes(2), cooldown = Duration.ofMinutes(30), confirmations = 2)
        val under = Anomaly(AnomalyType.PV_UNDERPERFORMANCE, "x")
        assertTrue(m.update(t0, listOf(under)).isEmpty()) // needs confirmation
        val a = m.update(t0.plusSeconds(5), listOf(under)).single()
        assertTrue(a.active && a.notify)
        val repeated = (1..20).map { m.update(t0.plusSeconds(5L + it * 5), listOf(under)).single() }
        assertTrue(repeated.none { it.notify })
        assertEquals(22, repeated.last().occurrences)
        // critical alerts are immediate
        assertTrue(m.update(t0.plusSeconds(200), listOf(Anomaly(AnomalyType.INVERTER_OFFLINE, "x"))).any { it.type == AnomalyType.INVERTER_OFFLINE && it.notify })
        // cleared after the problem disappears
        assertTrue(m.update(t0.plusSeconds(1000), emptyList()).isEmpty())
    }
}
