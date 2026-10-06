package com.solartracker.pro.core.health

import com.solartracker.pro.core.analytics.AccuracyReport
import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.BatteryType
import com.solartracker.pro.core.fixtures.AnenjiFixtures
import com.solartracker.pro.core.fixtures.EnergyFixtures
import com.solartracker.pro.core.forecast.EnergySecurityAnalyzer
import com.solartracker.pro.core.inverter.SmgModbusMapper
import com.solartracker.pro.core.quality.DataKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PredictiveAlertsTest {
    private val now = Instant.parse("2026-06-21T16:00:00Z")
    private val mapper = SmgModbusMapper("anenji-smg")
    private fun read(s: AnenjiFixtures.Snapshot, t: Instant = now) = mapper.map(AnenjiFixtures.blocks(s), t)

    @Test
    fun quietInputGivesNoAlerts() {
        assertTrue(PredictiveFaultEngine.predictive(PredictiveAlertInput(now, telemetry = read(AnenjiFixtures.CLOUDY_SUMMER))).isEmpty())
    }

    @Test
    fun batteryDepletionFromEnergySecurity() {
        val zone = ZoneId.of("Europe/Warsaw")
        val battery = BatteryStorage(nominalCapacityKwh = 10.0, usableCapacityPercent = 100.0, minSocPercent = 20.0)
        val s = EnergySecurityAnalyzer(battery, zone).analyze(now, 30.0, DataKind.MEASURED,
            EnergyFixtures.pv(EnergyFixtures.HIGH_LOAD, zone), EnergyFixtures.load(EnergyFixtures.HIGH_LOAD, zone), gridAvailable = false)
        val alerts = PredictiveFaultEngine.predictive(PredictiveAlertInput(now, security = s))
        val a = alerts.single { it.id == "battery-depletion" }
        assertEquals(FaultSeverity.CRITICAL, a.severity)
        assertTrue(a.evidence.any { it.contains("06:00") })
        assertTrue(a.recommendation.isNotBlank())
        assertTrue(a.confidence in 0.05..0.99)
    }

    @Test
    fun liveDataAlerts() {
        val hot = read(AnenjiFixtures.HIGH_LOAD.copy(invTemp = 80))
        val recent = (0 until 10).map { read(AnenjiFixtures.HIGH_LOAD.copy(loadW = 5200), now.minusSeconds(300L - it * 30)) }
        val alerts = PredictiveFaultEngine.predictive(PredictiveAlertInput(now, telemetry = hot, recent = recent, typicalLoadKw = 0.8,
            linkFailures = 4, linkOfflineSince = now.minusSeconds(900), clippingShare = 0.3,
            accuracyRecent = AccuracyReport(48, 0.5, 0.6, null, null, null), accuracyPrevious = AccuracyReport(48, 0.3, 0.4, null, null, null)))
        val ids = alerts.map { it.id }.toSet()
        assertTrue(ids.toString(), ids.containsAll(setOf("inverter-overtemp", "unexpected-load", "comm-loss", "pv-clipping", "forecast-deterioration")))
        assertEquals(FaultLevel.FAULT, alerts.single { it.id == "comm-loss" }.level)
        assertEquals(FaultSeverity.CRITICAL, alerts.single { it.id == "comm-loss" }.severity)
        assertTrue(alerts.all { it.reason.isNotBlank() && it.evidence.isNotEmpty() && it.recommendation.isNotBlank() })
    }

    @Test
    fun chargingAndVoltageAnomalies() {
        val notCharging = (0 until 12).map {
            read(AnenjiFixtures.CLEAR_SUMMER_NOON.copy(batA = 0.0, batW = 0, soc = 60, loadW = 500), now.minusSeconds(360L - it * 30))
        }
        val c = PredictiveFaultEngine.predictive(PredictiveAlertInput(now, telemetry = notCharging.last(), recent = notCharging))
        assertTrue(c.any { it.id == "charging-anomaly" })
        // AGM pack at rest: 12.0 V/12 V-block equivalent on 48 V = 48.0 V ≈ low SOC, inverter claims 95%.
        val agm = read(AnenjiFixtures.BATTERY_LOW.copy(batV = 48.0, batA = 0.5, batW = 24, soc = 95))
        val v = PredictiveFaultEngine.predictive(PredictiveAlertInput(now, telemetry = agm, batteryType = BatteryType.AGM))
        assertTrue(v.map { it.id }.toString(), v.any { it.id == "battery-voltage-anomaly" })
        // LiFePO4 curve is too flat to judge → no alert.
        assertTrue(PredictiveFaultEngine.predictive(PredictiveAlertInput(now, telemetry = agm, batteryType = BatteryType.LIFEPO4)).none { it.id == "battery-voltage-anomaly" })
    }
}
