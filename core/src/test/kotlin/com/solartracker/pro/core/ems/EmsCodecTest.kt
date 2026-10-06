package com.solartracker.pro.core.ems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class EmsCodecTest {
    @Test
    fun loadsRoundTripAndInvalidDropped() {
        val loads = listOf(
            FlexibleLoad("a", "Pralka", 2.0, 2.0, LocalTime.of(9, 0), LocalTime.of(17, 0), 2, true),
            FlexibleLoad("b", "Bojler \"duży\"", 3.0, 1.5),
        )
        assertEquals(loads, EmsCodec.decodeLoads(EmsCodec.encodeLoads(loads)))
        val broken = EmsCodec.encodeLoads(loads + FlexibleLoad("c", "", 200.0, 1.0))
        assertEquals(2, EmsCodec.decodeLoads(broken).size)
        assertTrue(EmsCodec.decodeLoads("not json").isEmpty())
        assertTrue(EmsCodec.decodeLoads(null).isEmpty())
    }

    @Test
    fun validation() {
        assertTrue(FlexibleLoad("x", "Okno", 1.0, 3.0, LocalTime.of(10, 0), LocalTime.of(12, 0)).validate().any { it.contains("krótsze") })
        assertTrue(FlexibleLoad("x", "Odwrócone", 1.0, 1.0, LocalTime.of(12, 0), LocalTime.of(10, 0)).validate().isNotEmpty())
        assertTrue(GeneratorConfig(3.0, 50.0, 40.0).validate().isNotEmpty())
        assertTrue(GeneratorConfig(3.0, 25.0, 80.0, 1.0, 0.4).validate().isEmpty())
    }

    @Test
    fun generatorRoundTrip() {
        val g = GeneratorConfig(3.5, 25.0, 85.0, 2.0, null)
        assertEquals(g, EmsCodec.decodeGenerator(EmsCodec.encodeGenerator(g)))
        assertEquals("", EmsCodec.encodeGenerator(null))
        assertNull(EmsCodec.decodeGenerator(""))
        assertNull(EmsCodec.decodeGenerator(EmsCodec.encodeGenerator(GeneratorConfig(500.0))))
    }
}

class CoolingCodecTest {
    @Test
    fun coolingRoundTrip() {
        val c = com.solartracker.pro.core.energy.CoolingLoadProfile(
            nominalPowerW = 3000.0, minimumPowerW = 150.0, mode = com.solartracker.pro.core.energy.CoolingMode.DUTY_CYCLE, dutyAtReference = 0.45,
            targetTemperatureC = 2.0, operatingStart = LocalTime.of(8, 0), operatingEnd = LocalTime.of(20, 0), idleDutyFactor = 0.7,
            preCoolingEnabled = true, preCoolingTargetC = -1.0,
            schedule = listOf(com.solartracker.pro.core.energy.CoolingScheduleEntry(LocalTime.of(1, 0), LocalTime.of(2, 0), 500.0)),
        )
        assertEquals(c, EmsCodec.decodeCooling(EmsCodec.encodeCooling(c)))
        assertEquals("", EmsCodec.encodeCooling(null))
        assertNull(EmsCodec.decodeCooling("{}"))
        assertNull(EmsCodec.decodeCooling(EmsCodec.encodeCooling(c.copy(dutyAtReference = 3.0))))
    }
}
