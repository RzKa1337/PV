package com.solartracker.pro.data

import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.solartracker.pro.core.energy.BackupSource
import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.BatteryType
import com.solartracker.pro.core.energy.ConsumptionPeriod
import com.solartracker.pro.core.energy.EnergyPrices
import com.solartracker.pro.core.solar.GeoLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun defaults_matchSpecification() = runTest {
        val settings = SettingsRepository(FakeDataStore()).settings.first()
        assertEquals(2.09, settings.system.peakPowerKw, 0.0)
        assertEquals(0.0, settings.system.tiltDeg, 0.0)
        assertEquals(180.0, settings.system.azimuthDeg, 0.0)
        assertEquals(AppSettings.DEFAULT_LOCATION, settings.location)
        assertEquals(LocationSource.MANUAL, settings.locationSource)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals(AppLanguage.POLISH, settings.language)
    }

    @Test
    fun updateSystem_persistsAndClampsValues() = runTest {
        val repo = SettingsRepository(FakeDataStore())
        repo.updateSystem { it.copy(peakPowerKw = 5.5, tiltDeg = 135.0, azimuthDeg = 400.0) }
        val system = repo.settings.first().system
        assertEquals(5.5, system.peakPowerKw, 0.0)
        assertEquals(90.0, system.tiltDeg, 0.0)
        assertEquals(40.0, system.azimuthDeg, 1e-9)
    }

    @Test
    fun setLocation_persistsLocationNameAndSource() = runTest {
        val repo = SettingsRepository(FakeDataStore())
        repo.setLocation(GeoLocation(50.06, 19.94), "  Kraków  ", LocationSource.GPS)
        val s = repo.settings.first()
        assertEquals(GeoLocation(50.06, 19.94), s.location)
        assertEquals("Kraków", s.locationName)
        assertEquals(LocationSource.GPS, s.locationSource)
    }

    @Test
    fun setThemeMode_persists() = runTest {
        val repo = SettingsRepository(FakeDataStore())
        repo.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repo.settings.first().themeMode)
    }

    @Test
    fun setLanguage_persistsAndUnknownValueFallsBackToPolish() = runTest {
        val repo = SettingsRepository(FakeDataStore())
        repo.setLanguage(AppLanguage.ENGLISH)
        assertEquals(AppLanguage.ENGLISH, repo.settings.first().language)
        val corrupted = SettingsRepository(FakeDataStore(mutablePreferencesOf(stringPreferencesKey("app_language") to "KLINGON")))
        assertEquals(AppLanguage.POLISH, corrupted.settings.first().language)
    }

    @Test
    fun corruptedStoredValues_fallBackToSafeDefaults() = runTest {
        val prefs = mutablePreferencesOf(
            doublePreferencesKey("latitude") to 123.0,
            doublePreferencesKey("longitude") to 10.0,
            doublePreferencesKey("tilt_deg") to -40.0,
            stringPreferencesKey("theme_mode") to "PURPLE",
        )
        val s = SettingsRepository(FakeDataStore(prefs)).settings.first()
        assertEquals(AppSettings.DEFAULT_LOCATION, s.location)
        assertEquals(0.0, s.system.tiltDeg, 0.0)
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
    }

    @Test
    fun battery_isDisabledByDefaultWithSpecDefaults() = runTest {
        val s = SettingsRepository(FakeDataStore()).settings.first()
        assertFalse(s.batteryEnabled)
        assertNull(s.activeBattery)
        assertEquals(10.0, s.battery.nominalCapacityKwh, 0.0)
        assertEquals(90.0, s.battery.usableCapacityPercent, 0.0)
        assertEquals(50.0, s.battery.initialSocPercent, 0.0)
        assertEquals(10.0, s.battery.minSocPercent, 0.0)
        assertEquals(100.0, s.battery.maxSocPercent, 0.0)
        assertEquals(5.0, s.battery.maxChargePowerKw, 0.0)
        assertEquals(5.0, s.battery.maxDischargePowerKw, 0.0)
        assertEquals(95.0, s.battery.chargeEfficiencyPercent, 0.0)
        assertEquals(95.0, s.battery.dischargeEfficiencyPercent, 0.0)
    }

    @Test
    fun setBattery_persistsValidConfiguration() = runTest {
        val repo = SettingsRepository(FakeDataStore())
        val battery = BatteryStorage(nominalCapacityKwh = 16.0, maxChargePowerKw = 3.0, type = BatteryType.AGM)
        assertTrue(repo.setBattery(true, battery))
        val s = repo.settings.first()
        assertTrue(s.batteryEnabled)
        assertEquals(battery, s.battery)
        assertEquals(battery, s.activeBattery)
    }

    @Test
    fun setBattery_rejectsInvalidConfiguration() = runTest {
        val repo = SettingsRepository(FakeDataStore())
        assertFalse(repo.setBattery(true, BatteryStorage(nominalCapacityKwh = 0.0)))
        assertFalse(repo.setBattery(true, BatteryStorage(minSocPercent = 90.0, maxSocPercent = 20.0)))
        assertFalse(repo.setBattery(true, BatteryStorage(chargeEfficiencyPercent = 120.0)))
        val s = repo.settings.first()
        assertFalse(s.batteryEnabled)
        assertEquals(BatteryStorage(), s.battery)
    }

    @Test
    fun consumption_hourlyPeriodsRoundTrip() = runTest {
        val repo = SettingsRepository(FakeDataStore())
        val periods = listOf(ConsumptionPeriod(0, 6, 0.5), ConsumptionPeriod(6, 24, 1.25))
        assertTrue(repo.setConsumption(ConsumptionSettings(mode = ConsumptionMode.HOURLY, periods = periods)))
        val c = repo.settings.first().consumption
        assertEquals(ConsumptionMode.HOURLY, c.mode)
        assertEquals(periods, c.periods)
        assertEquals(3.0 + 22.5, c.profile().dailyKwh, 1e-9)
    }

    @Test
    fun consumption_invalidIsRejected() = runTest {
        val repo = SettingsRepository(FakeDataStore())
        val overlapping = listOf(ConsumptionPeriod(0, 10, 1.0), ConsumptionPeriod(5, 12, 1.0))
        assertFalse(repo.setConsumption(ConsumptionSettings(mode = ConsumptionMode.HOURLY, periods = overlapping)))
        assertFalse(repo.setConsumption(ConsumptionSettings(constantKw = -1.0)))
        assertEquals(ConsumptionSettings(), repo.settings.first().consumption)
    }

    @Test
    fun prices_areOptionalAndPersisted() = runTest {
        val repo = SettingsRepository(FakeDataStore())
        assertNull(repo.settings.first().prices.backupPricePerKwh)
        val prices = EnergyPrices(generatorPricePerKwh = 3.5, feedInPricePerKwh = 0.3, batteryCost = 15000.0, backupSource = BackupSource.GENERATOR)
        assertTrue(repo.setPrices(prices))
        assertEquals(prices, repo.settings.first().prices)
        assertFalse(repo.setPrices(EnergyPrices(gridPricePerKwh = -1.0)))
        assertTrue(repo.setPrices(EnergyPrices()))
        assertEquals(EnergyPrices(), repo.settings.first().prices)
    }

    @Test
    fun periods_encodingIsLocaleIndependent() {
        val periods = listOf(ConsumptionPeriod(0, 6, 0.5), ConsumptionPeriod(6, 24, 2.0))
        val encoded = SettingsRepository.encodePeriods(periods)
        assertEquals("0-6:0.5;6-24:2.0", encoded)
        assertEquals(periods, SettingsRepository.decodePeriods(encoded))
        assertNull(SettingsRepository.decodePeriods("garbage"))
    }

    @Test
    fun weather_isEnabledByDefaultAndCanBeDisabled() = runTest {
        val repo = SettingsRepository(FakeDataStore())
        assertTrue(repo.settings.first().weatherEnabled)
        repo.setWeatherEnabled(false)
        assertFalse(repo.settings.first().weatherEnabled)
    }
}
