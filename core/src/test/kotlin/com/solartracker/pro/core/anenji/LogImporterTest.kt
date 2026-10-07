package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.export.RegisterLogExport
import com.solartracker.pro.core.fixtures.AnenjiFixtures
import com.solartracker.pro.core.inverter.OperatingMode
import com.solartracker.pro.core.inverter.RegisterDiagnostics
import com.solartracker.pro.core.inverter.RegisterLogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Parser tests on synthetic log snippets (formats as written by common tools; not recordings of a real device). */
class LogImporterTest {
    private val zone = ZoneId.of("Europe/Warsaw")

    @Test
    fun csvWithUnitsInHeaderAndLocalTimestamps() {
        val csv = """
            Date Time,PV Power (kW),PV Voltage [V],Battery Voltage (V),SOC,Load Power (W),Grid Power (W),Mode,Warnings,Faults,Mystery
            2026-10-07 06:40:00,0.18,180.5,49.2,31,2400,0,B,,,x
            2026-10-07 06:45:00,0.25,182.0,48.9,29,2350,0,B,8,,x
            2026-10-07 06:50:00,N/A,,48.1,26,2380,600,L,8 12,,x
        """.trimIndent()
        val log = LogImporter.import(csv, zone, "anenji.csv")
        assertEquals(LogFormat.CSV, log.format)
        assertEquals(3, log.samples.size)
        val first = log.samples.first()
        assertEquals(Instant.parse("2026-10-07T04:40:00Z"), first.time) // local time normalised to UTC
        assertEquals(180.0, first[Channel.PV_POWER]!!, 1e-9) // kW → W
        assertEquals(31.0, first[Channel.SOC]!!, 0.0)
        assertEquals(OperatingMode.OFF_GRID, first.mode)
        assertEquals(setOf(8, 12), log.samples.last().warnings)
        assertEquals(OperatingMode.GRID, log.samples.last().mode)
        assertNull("missing stays missing", log.samples.last()[Channel.PV_POWER])
        assertEquals(1, log.missingByChannel[Channel.PV_POWER])
        assertTrue(log.issues.any { it.contains("Mystery") })
        assertEquals(DataOrigin.IMPORTED, log.origin)
    }

    @Test
    fun semicolonDecimalCommaEpochAndDuplicates() {
        val csv = "timestamp;pv_w;battery_v;soc\n1791369600;1200,5;52,1;80\n1791369600;1200,5;52,1;80\n1791369900;;52,0;79\nzły;1;2;3\n"
        val log = LogImporter.import(csv, zone)
        assertEquals(2, log.samples.size)
        assertEquals(1200.5, log.samples.first()[Channel.PV_POWER]!!, 1e-9)
        assertEquals(1, log.duplicates)
        assertTrue(log.issues.any { it.contains("nieczytelnym czasem") })
    }

    @Test
    fun jsonArrayAndOurHistoryExport() {
        val json = """[{"time":"2026-10-07T10:00:00Z","pv_power":1500,"soc":70,"load_w":400,"max_charge_current":60},
                       {"time":"2026-10-07T10:05:00Z","pv_power":1520,"soc":71,"load_w":420,"max_charge_current":80}]"""
        val log = LogImporter.import(json, zone)
        assertEquals(LogFormat.JSON, log.format)
        assertEquals(2, log.samples.size)
        assertEquals(listOf(60.0, 80.0), log.settings.map { it.second.getValue(SettingKey.MAX_CHARGE_CURRENT) })
        val history = """{"format":"solar-tracker-pro-history/1","samples":[{"start":"2026-10-07T10:00:00Z","pv_w":900.0,"soc_pct":55.0,"mode":"GRID","warnings":"9"}]}"""
        val h = LogImporter.import(history, zone)
        assertEquals(900.0, h.samples.single()[Channel.PV_POWER]!!, 0.0)
        assertEquals(setOf(9), h.samples.single().warnings)
        assertTrue(LogImporter.import("{oops", zone).issues.first().contains("JSON"))
    }

    @Test
    fun txtKeyValueWithInlineUnitsAndWhitespaceTable() {
        val txt = "2026-10-07 06:11:00 PV=0.05kW SOC=31% Load=600W\n2026-10-07 06:31:00 PV=0.18kW SOC=27% Load=2400W\n"
        val log = LogImporter.import(txt, zone, "log.txt")
        assertEquals(LogFormat.TXT, log.format)
        assertEquals(LogLayout.KEY_VALUE, log.layout)
        assertEquals(180.0, log.samples.last()[Channel.PV_POWER]!!, 1e-9)
        assertEquals(2400.0, log.samples.last()[Channel.LOAD_POWER]!!, 1e-9)
        val table = "time soc pv_w\n2026-10-07T06:00:00Z 40 100\n2026-10-07T06:05:00Z 39 120\n"
        assertEquals(2, LogImporter.import(table, zone, "t.txt").samples.size)
    }

    @Test
    fun ourRegisterLogRoundTripsIncludingTimeouts() {
        val t = Instant.parse("2026-10-07T10:00:00Z")
        val records = listOf(
            RegisterLogRecord(t, true, "OK", RegisterDiagnostics.smgSamples(AnenjiFixtures.blocks(AnenjiFixtures.CLEAR_SUMMER_NOON), t)),
            RegisterLogRecord(t.plusSeconds(5), false, "Przekroczony czas: brak odpowiedzi", emptyList()),
        )
        for (text in listOf(RegisterLogExport.csv(records), RegisterLogExport.json(records, t, "x", "Anenji"))) {
            val log = LogImporter.import(text, zone)
            assertEquals(text.take(60), LogLayout.REGISTER_LONG, log.layout)
            assertEquals(1, log.samples.size)
            assertEquals(380.0, log.samples.single()[Channel.PV_VOLTAGE]!!, 1e-9)
            assertEquals(3914.0, log.samples.single()[Channel.PV_POWER]!!, 1e-9)
            assertEquals(2, log.comm.size)
            assertEquals(CommError.TIMEOUT, log.comm.last().error)
        }
        val sim = listOf(records.first().copy(simulated = true))
        assertEquals(DataOrigin.SIMULATOR, LogImporter.import(RegisterLogExport.csv(sim), zone).origin)
    }

    @Test
    fun unknownFormatsAndMissingTimeColumn() {
        assertEquals(LogFormat.UNKNOWN, LogImporter.import("", zone).format)
        val noTime = LogImporter.import("pv_w,soc\n100,50\n200,51\n", zone)
        assertTrue(noTime.samples.isEmpty())
        assertTrue(noTime.issues.single().contains("czasu"))
        assertTrue(LogImporter.import("timestamp,pv_w\n", zone).issues.isNotEmpty())
    }

    @Test
    fun suspiciousKilowattsWithoutUnitAreReportedNotRescaled() {
        val rows = (0 until 5).joinToString("\n") { "2026-10-07T10:0$it:00Z,1.5,300,6" }
        val log = LogImporter.import("time,pv_power,pv_voltage,pv_current\n$rows", zone)
        assertEquals(1.5, log.samples.first()[Channel.PV_POWER]!!, 0.0)
        assertTrue(log.issues.any { it.contains("kW") })
    }
}
