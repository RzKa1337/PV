package com.solartracker.pro.energy

import com.solartracker.pro.core.ems.FlexibleLoad
import com.solartracker.pro.core.ems.GeneratorConfig
import com.solartracker.pro.core.inverter.InverterConfig
import com.solartracker.pro.core.inverter.InverterLink
import com.solartracker.pro.core.inverter.InverterProtocol
import com.solartracker.pro.core.shading.LocationAccuracy
import com.solartracker.pro.data.FakeDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class EnergySettingsStoreTest {

    @Test
    fun defaultsAreDisabledAndUnconfirmed() = runTest {
        val store = EnergySettingsStore(FakeDataStore())
        assertFalse(store.inverter.first().enabled)
        val site = store.site.first()
        assertFalse(site.locationConfirmed)
        assertEquals(LocationAccuracy.CITY, site.locationAccuracy)
    }

    @Test
    fun inverterAndSiteRoundTrip() = runTest {
        val store = EnergySettingsStore(FakeDataStore())
        val inverter = InverterConfig(enabled = true, link = InverterLink.MODBUS_TCP_GATEWAY, protocol = InverterProtocol.MODBUS_SMG,
            host = "192.168.1.50", port = 502, slaveId = 2, pollIntervalSeconds = 3, ratedPowerW = 6200.0, mpptCount = 1)
        store.setInverter(inverter)
        assertEquals(inverter, store.inverter.first())
        val site = SiteConfig(locationAccuracy = LocationAccuracy.MAP_POINT, locationConfirmed = true, panelCount = 8, rows = 2, strings = 2,
            panelBaseHeightM = 4.5, gridExportAllowed = true, panelModel = "Test 450 W")
        store.setSite(site)
        assertEquals(site, store.site.first())
        assertEquals(4, site.columns)
    }

    @Test
    fun invalidSiteFallsBackToDefaultsButKeepsLocationFlags() = runTest {
        val store = EnergySettingsStore(FakeDataStore())
        val invalid = SiteConfig(locationAccuracy = LocationAccuracy.ADDRESS, locationConfirmed = true, panelCount = 0)
        assertTrue(invalid.validate().isNotEmpty())
        store.setSite(invalid)
        val loaded = store.site.first()
        assertEquals(SiteConfig().panelCount, loaded.panelCount)
        assertTrue(loaded.locationConfirmed)
        assertEquals(LocationAccuracy.ADDRESS, loaded.locationAccuracy)
    }

    @Test
    fun siteValidation() {
        assertTrue(SiteConfig().validate().isEmpty())
        assertTrue(SiteConfig(rows = 5, panelCount = 4).validate().any { it.contains("rzędów") })
        assertTrue(SiteConfig(strings = 9, panelCount = 4).validate().any { it.contains("stringów") })
        assertTrue(SiteConfig(obstacleRadiusM = 5000).validate().any { it.contains("Promień") })
    }

    @Test
    fun emsLoadsAndGeneratorRoundTrip() = runTest {
        val store = EnergySettingsStore(FakeDataStore())
        assertTrue(store.flexibleLoads.first().isEmpty())
        assertEquals(null, store.generator.first())
        val loads = listOf(FlexibleLoad("1", "Pralka", 2.0, 2.0, LocalTime.of(8, 0), LocalTime.of(18, 0)), FlexibleLoad("2", "Zły", 0.0, 1.0))
        store.setFlexibleLoads(loads)
        assertEquals(loads.take(1), store.flexibleLoads.first())
        val g = GeneratorConfig(3.0, 25.0, 80.0, 1.0, 0.4)
        store.setGenerator(g)
        assertEquals(g, store.generator.first())
        store.setGenerator(null)
        assertEquals(null, store.generator.first())
    }
}
