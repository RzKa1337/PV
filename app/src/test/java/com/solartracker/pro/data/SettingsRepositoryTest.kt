package com.solartracker.pro.data

import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.solartracker.pro.core.solar.GeoLocation
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
}
