package com.solartracker.pro.ui

import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.EnergyPrices
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.weather.HourlyWeather
import com.solartracker.pro.core.weather.MonthlyClimate
import com.solartracker.pro.core.weather.WeatherForecast
import com.solartracker.pro.core.weather.WeatherSource
import com.solartracker.pro.data.WeatherProvider
import com.solartracker.pro.data.WeatherResult
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import com.solartracker.pro.data.FakeDataStore
import com.solartracker.pro.data.LocationProvider
import com.solartracker.pro.data.LocationSource
import com.solartracker.pro.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private class FakeLocation(
        var permission: Boolean = true,
        var enabled: Boolean = true,
        var result: GeoLocation? = GeoLocation(50.06, 19.94),
    ) : LocationProvider {
        override fun hasPermission() = permission
        override fun isLocationEnabled() = enabled
        override suspend fun currentLocation(timeoutMillis: Long) = result
    }

    private val zone = ZoneId.of("Europe/Warsaw")
    private val noon = Instant.parse("2024-06-21T10:30:00Z")
    private lateinit var repository: SettingsRepository
    private lateinit var location: FakeLocation

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = SettingsRepository(FakeDataStore())
        location = FakeLocation()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = MainViewModel(
        settingsRepository = repository,
        locationRepository = location,
        zoneProvider = { zone },
        clock = { noon },
    )

    @Test
    fun dashboard_isComputedFromDefaultSettings() = runBlocking {
        val vm = viewModel()
        val state = withTimeout(10_000) { vm.dashboard.filterNotNull().first() }
        assertEquals(2.09, state.settings.system.peakPowerKw, 0.0)
        assertTrue(state.sun.elevationDeg > 50.0)
        assertTrue(state.currentPowerKw > 0.0)
        assertTrue(state.energySoFarKwh in 0.0..state.energyTodayKwh)
        assertTrue(state.profile.isNotEmpty())
    }

    @Test
    fun setTilt_updatesDashboardAndComparison() = runBlocking {
        val vm = viewModel()
        vm.setTilt(35.0)
        val state = withTimeout(10_000) {
            vm.dashboard.filterNotNull().first { it.settings.system.tiltDeg == 35.0 }
        }
        assertEquals(35.0, state.settings.system.tiltDeg, 0.0)
        val comparison = withTimeout(10_000) {
            vm.tiltComparison.filterNotNull().first { it.system.tiltDeg == 35.0 }
        }
        assertEquals(9, comparison.estimates.size)
    }

    @Test
    fun monthly_hasFiveTiltsAndTwelveMonths() = runBlocking {
        val vm = viewModel()
        val monthly = withTimeout(20_000) { vm.monthly.filterNotNull().first() }
        assertEquals(2024, monthly.year)
        assertEquals(listOf(0.0, 30.0, 45.0, 60.0, 90.0), monthly.estimates.map { it.tiltDeg })
        assertTrue(monthly.estimates.all { it.energyByMonthKwh.size == 12 })
    }

    @Test
    fun gps_successStoresLocation() = runBlocking {
        val vm = viewModel()
        vm.locateWithGps()
        withTimeout(10_000) { vm.gpsStatus.first { it == GpsStatus.Success } }
        val s = repository.settings.first()
        assertEquals(GeoLocation(50.06, 19.94), s.location)
        assertEquals(LocationSource.GPS, s.locationSource)
    }

    @Test
    fun gps_failureKeepsManualLocationAndReportsError() = runBlocking {
        location.result = null
        val vm = viewModel()
        vm.locateWithGps()
        val status = withTimeout(10_000) { vm.gpsStatus.first { it is GpsStatus.Error } }
        assertTrue(status is GpsStatus.Error)
        assertEquals(LocationSource.MANUAL, repository.settings.first().locationSource)
    }

    @Test
    fun gps_withoutPermissionReportsError() = runBlocking {
        location.permission = false
        val vm = viewModel()
        vm.locateWithGps()
        assertTrue(vm.gpsStatus.value is GpsStatus.Error)
    }

    @Test
    fun manualLocation_isStored() = runBlocking {
        val vm = viewModel()
        vm.setManualLocation(-33.87, 151.21, "Sydney")
        val s = withTimeout(10_000) { repository.settings.first { it.locationName == "Sydney" } }
        assertEquals(GeoLocation(-33.87, 151.21), s.location)
        assertEquals(LocationSource.MANUAL, s.locationSource)
    }

    @Test
    fun withoutBattery_energyBalanceHasNoBatteryFlows() = runBlocking {
        val vm = viewModel()
        val state = withTimeout(10_000) { vm.energy.filterNotNull().first() }
        assertEquals(EnergyPeriod.TODAY, state.period)
        assertNull(state.withoutBattery)
        assertNull(state.result.balance.endSocPercent)
        assertEquals(0.0, state.result.balance.toBatteryKwh, 0.0)
        assertTrue(state.result.balance.pvKwh > 0.0)
        val dash = withTimeout(10_000) { vm.dashboard.filterNotNull().first() }
        assertNull(dash.battery)
    }

    @Test
    fun withBattery_dashboardAndBalanceIncludeBattery() = runBlocking {
        repository.setBattery(true, BatteryStorage())
        val vm = viewModel()
        val dash = withTimeout(10_000) { vm.dashboard.filterNotNull().first { it.battery != null } }
        val b = dash.battery!!
        assertTrue(b.socPercent in 10.0..100.0)
        assertEquals(9.0, b.usableKwh, 1e-9)
        val state = withTimeout(10_000) { vm.energy.filterNotNull().first { it.result.hasBattery } }
        assertNotNull(state.withoutBattery)
        assertTrue(state.result.balance.surplusKwh <= state.withoutBattery!!.balance.surplusKwh)
        assertNotNull(state.result.statistics)
    }

    @Test
    fun tomorrowStartsWithTodaysFinalSoc() = runBlocking {
        repository.setBattery(true, BatteryStorage())
        val vm = viewModel()
        val today = withTimeout(10_000) { vm.energy.filterNotNull().first { it.result.hasBattery } }
        vm.setEnergyPeriod(EnergyPeriod.TOMORROW)
        val tomorrow = withTimeout(10_000) { vm.energy.filterNotNull().first { it.period == EnergyPeriod.TOMORROW } }
        assertEquals(today.startDate.plusDays(1), tomorrow.startDate)
        assertEquals(today.result.balance.endSocPercent!!, tomorrow.result.balance.startSocPercent!!, 1e-9)
    }

    @Test
    fun costs_requireBackupPriceAndShowSavings() = runBlocking {
        val vm = viewModel()
        assertNull(withTimeout(20_000) { vm.costs.first() })
        repository.setPrices(EnergyPrices(gridPricePerKwh = 1.0, feedInPricePerKwh = 0.2, batteryCost = 20000.0))
        repository.setBattery(true, BatteryStorage())
        val costs = withTimeout(30_000) { vm.costs.filterNotNull().first { it.hasBattery } }
        assertTrue(costs.comparison.yearlySavings > 0.0)
        assertNotNull(costs.comparison.paybackYears)
    }

    // --- Weather ---

    private class FakeWeather(var result: WeatherResult) : WeatherProvider {
        var calls = 0
        override suspend fun load(location: GeoLocation, forceRefresh: Boolean): WeatherResult {
            calls++
            return result
        }
    }

    /** Fully overcast and dark forecast around [noon]: no PV at all. */
    private fun darkForecast(): WeatherForecast {
        val start = noon.minusSeconds(2 * 86_400L)
        val hours = (1..24 * 4).map {
            HourlyWeather(start.plusSeconds(3600L * it), 0.0, 0.0, 0.0, 12.0, 100.0)
        }
        return WeatherForecast(hours, noon)
    }

    private fun viewModel(weather: WeatherProvider) = MainViewModel(
        settingsRepository = repository,
        locationRepository = location,
        weatherProvider = weather,
        zoneProvider = { zone },
        clock = { noon },
    )

    @Test
    fun forecastDrivesAllEstimates() = runBlocking {
        val weather = FakeWeather(WeatherResult(darkForecast(), null))
        val vm = viewModel(weather)
        val dash = withTimeout(10_000) {
            vm.dashboard.filterNotNull().first { it.weather?.state?.forecast != null }
        }
        assertEquals(0.0, dash.currentPowerKw, 0.0)
        assertEquals(0.0, dash.energyTodayKwh, 1e-9)
        assertEquals(WeatherSource.FORECAST, dash.weather!!.source)
        assertEquals(100.0, dash.weather!!.cloudCoverPercent!!, 0.0)
        val energy = withTimeout(10_000) { vm.energy.filterNotNull().first { it.sourceDescription.contains("prognoza") } }
        assertEquals(0.0, energy.result.balance.pvKwh, 1e-9)
        assertTrue(weather.calls >= 1)
    }

    @Test
    fun weatherDisabled_usesClearSky() = runBlocking {
        repository.setWeatherEnabled(false)
        val weather = FakeWeather(WeatherResult(darkForecast(), null))
        val vm = viewModel(weather)
        val dash = withTimeout(10_000) { vm.dashboard.filterNotNull().first() }
        assertNull(dash.weather)
        assertTrue(dash.energyTodayKwh > 5.0)
        assertEquals(0, weather.calls)
    }

    @Test
    fun climateLowersMonthlyEstimatesComparedToClearSky() = runBlocking {
        val clearVm = viewModel()
        val clear = withTimeout(20_000) { clearVm.monthly.filterNotNull().first() }
        val climateVm = viewModel(FakeWeather(WeatherResult(null, MonthlyClimate.DEFAULT_POLAND)))
        val climate = withTimeout(20_000) {
            climateVm.monthly.filterNotNull().first { it.sourceDescription.contains("klimatyczne") }
        }
        assertTrue(climate.estimates[0].yearlyKwh < clear.estimates[0].yearlyKwh * 0.85)
    }

    @Test
    fun refreshWeather_forcesReload() = runBlocking {
        val weather = FakeWeather(WeatherResult(null, MonthlyClimate.DEFAULT_POLAND, error = "brak internetu"))
        val vm = viewModel(weather)
        withTimeout(10_000) { vm.weather.first { it.climate != null && !it.loading } }
        assertEquals("brak internetu", vm.weather.value.error)
        withTimeout(10_000) { vm.settings.filterNotNull().first() }
        val before = weather.calls
        vm.refreshWeather()
        withTimeout(10_000) { while (weather.calls == before) kotlinx.coroutines.delay(10) }
        assertTrue(weather.calls > before)
    }
}
