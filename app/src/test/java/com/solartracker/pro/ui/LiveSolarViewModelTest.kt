package com.solartracker.pro.ui

import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.weather.HourlyWeather
import com.solartracker.pro.core.weather.OpenMeteo
import com.solartracker.pro.core.weather.WeatherForecast
import com.solartracker.pro.core.weather.WeatherSource
import com.solartracker.pro.data.FakeDataStore
import com.solartracker.pro.data.LocationProvider
import com.solartracker.pro.data.SettingsRepository
import com.solartracker.pro.data.WeatherProvider
import com.solartracker.pro.data.WeatherResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSolarViewModelTest {

    private val zone = ZoneId.of("Europe/Warsaw")
    private val start = Instant.parse("2024-06-21T08:42:31.006Z") // 10:42:31 in Warsaw
    private lateinit var repository: SettingsRepository

    /** Fast fake ticker: one simulated second every 50 ms of real time; counts emitted ticks. */
    private val ticks = AtomicInteger()
    private val running = AtomicInteger()
    private val fakeTicker: (() -> Instant) -> Flow<Instant> = {
        flow {
            running.incrementAndGet()
            try {
                var t = start
                while (true) {
                    emit(t)
                    ticks.incrementAndGet()
                    t = t.plusSeconds(1)
                    delay(50)
                }
            } finally {
                running.decrementAndGet()
            }
        }
    }

    private class NoGps : LocationProvider {
        override fun hasPermission() = false
        override fun isLocationEnabled() = false
        override suspend fun currentLocation(timeoutMillis: Long): GeoLocation? = null
    }

    private class CountingWeather(val result: WeatherResult) : WeatherProvider {
        val calls = AtomicInteger()
        override suspend fun load(location: GeoLocation, forceRefresh: Boolean): WeatherResult {
            calls.incrementAndGet()
            return result
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = SettingsRepository(FakeDataStore())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(weather: WeatherProvider? = null) = MainViewModel(
        settingsRepository = repository,
        locationRepository = NoGps(),
        weatherProvider = weather,
        zoneProvider = { zone },
        clock = { start },
        liveTicker = fakeTicker,
    )

    private class StopCollecting : RuntimeException()

    /** Collects live states, like the visible Live screen does, until [count] seconds were seen. */
    private suspend fun collectLiveSafe(vm: MainViewModel, count: Int): List<LiveUiState> {
        val states = mutableListOf<LiveUiState>()
        try {
            withTimeout(10_000) {
                vm.live.filterNotNull().collect {
                    if (states.lastOrNull()?.epochMillis != it.epochMillis) states += it
                    if (states.size >= count) throw StopCollecting()
                }
            }
        } catch (_: StopCollecting) {
        }
        return states
    }

    @Test
    fun live_updatesEverySecondWithFreshTimeAndClock() = runBlocking {
        val vm = viewModel()
        val states = collectLiveSafe(vm, 5)
        assertEquals(5, states.size)
        states.forEach { assertEquals(liveClockText(Instant.ofEpochMilli(it.epochMillis), zone), it.clockText) }
        assertTrue(states.first().clockText.startsWith("10:42:"))
        for (i in 1 until states.size) {
            assertEquals(1000L, states[i].epochMillis - states[i - 1].epochMillis)
            assertTrue(states[i].sun.azimuthDeg > states[i - 1].sun.azimuthDeg)
        }
        assertTrue(states.last().pv.modeledPowerKw > 0.0)
        assertNull("no inverter connected", states.last().pv.measuredPowerKw)
    }

    @Test
    fun live_stopsWhenScreenLeavesAndRestartsOnReturn() = runBlocking {
        val vm = viewModel()
        assertFalse(vm.liveActive.value)
        // Screen visible.
        val job = launch(Dispatchers.Default) { vm.live.collect { } }
        withTimeout(5_000) { vm.liveActive.first { it } }
        withTimeout(5_000) { while (ticks.get() < 5) delay(5) }
        // Screen left / app in background: collector cancelled.
        job.cancel()
        withTimeout(5_000) { vm.liveActive.first { !it } }
        withTimeout(5_000) { while (running.get() != 0) delay(5) }
        val stoppedAt = ticks.get()
        delay(200)
        assertEquals("ticker must not run in background", stoppedAt, ticks.get())
        // Back on the screen.
        val again = launch(Dispatchers.Default) { vm.live.collect { } }
        withTimeout(5_000) { vm.liveActive.first { it } }
        withTimeout(5_000) { while (ticks.get() < stoppedAt + 3) delay(5) }
        assertTrue(ticks.get() > stoppedAt)
        again.cancel()
    }

    @Test
    fun pauseButton_stopsTicker() = runBlocking {
        val vm = viewModel()
        val job = launch(Dispatchers.Default) { vm.live.collect { } }
        withTimeout(5_000) { vm.liveActive.first { it } }
        vm.setLivePaused(true)
        withTimeout(5_000) { vm.liveActive.first { !it } }
        assertTrue(vm.livePaused.value)
        vm.setLivePaused(false)
        withTimeout(5_000) { vm.liveActive.first { it } }
        job.cancel()
    }

    @Test
    fun withoutGps_usesSavedLocation() = runBlocking {
        val vm = viewModel()
        vm.locateWithGps() // permission denied → error, saved location stays
        val state = collectLiveSafe(vm, 1).single()
        assertTrue(state.locationText.startsWith("Warszawa"))
        assertTrue(vm.gpsStatus.value is GpsStatus.Error)
    }

    @Test
    fun withoutInternet_liveStillComputesAndWeatherIsNotFetchedEverySecond() = runBlocking {
        val weather = CountingWeather(WeatherResult(null, null, error = "Nie udało się pobrać prognozy pogody (brak internetu)."))
        val vm = viewModel(weather)
        val states = collectLiveSafe(vm, 20)
        assertEquals(20, states.size)
        assertEquals(WeatherSource.CLEAR_SKY, states.last().pv.source)
        assertTrue(states.last().pv.sourceLabel.contains("bezchmurnego"))
        assertTrue("weather loaded once, not per second: ${weather.calls.get()}", weather.calls.get() <= 1)
    }

    @Test
    fun invalidWeatherData_fallsBackSafely() = runBlocking {
        // Forecast hour without irradiance values (e.g. nulls in the API response).
        val broken = WeatherForecast(listOf(HourlyWeather(start.plusSeconds(1800), null, null, null, null, null)), start)
        val vm = viewModel(CountingWeather(WeatherResult(broken, null)))
        val s = collectLiveSafe(vm, 3).last()
        assertEquals(WeatherSource.CLEAR_SKY, s.pv.source)
        assertTrue(s.pv.modeledPowerKw.isFinite() && s.pv.modeledPowerKw >= 0.0)
        // Garbage from the network is rejected by the parser instead of reaching the model.
        val error = runCatching { OpenMeteo.parseForecast("{\"hourly\":{}}", start) }.exceptionOrNull()
        assertNotNull(error)
    }

    @Test
    fun live_usesForecastIrradianceWhenAvailable() = runBlocking {
        val forecast = WeatherForecast(
            (0..3).map { HourlyWeather(start.plusSeconds(3600L * it), 400.0, 300.0, 120.0, 31.4, 50.0) },
            start,
        )
        val vm = viewModel(CountingWeather(WeatherResult(forecast, null)))
        val s = withTimeout(10_000) { vm.live.filterNotNull().first { it.pv.source == WeatherSource.FORECAST } }
        // Hour means of 300 W/m²: inside the hour the beam follows the sun (clear-sky index interpolation), so the
        // value at a given second is close to – not exactly – the hourly mean.
        assertEquals(300.0, s.pv.dni, 300.0 * 0.1)
        assertEquals(31.4, s.pv.ambientTemperatureC!!, 0.0)
        assertTrue(s.pv.sourceLabel.contains("prognozy"))
    }

    @Test
    fun live_includesBatteryFlowAndSoc() = runBlocking {
        repository.setBattery(true, BatteryStorage())
        val vm = viewModel()
        val s = withTimeout(10_000) { vm.live.filterNotNull().first { it.energy.hasBattery } }
        val e = s.energy
        assertNotNull(e.socPercent)
        assertTrue(e.socPercent!! in 10.0..100.0)
        assertEquals(9.0, e.usableKwh!!, 1e-9)
        // Energy balance of the instantaneous flow.
        assertEquals(e.pvKw, e.directKw + e.chargeKw + e.exportKw, 1e-9)
        assertEquals(e.loadKw, e.directKw + e.dischargeKw + e.gridImportKw, 1e-9)
    }

    @Test
    fun sunPath_isProvidedForToday() = runBlocking {
        val vm = viewModel()
        val path = withTimeout(10_000) { vm.liveSunPath.filterNotNull().first() }
        assertEquals(61.2, path.highestElevationDeg, 0.3)
        assertNotNull(path.sunriseAzimuthDeg)
    }

    @Test
    fun countdownFormatting() {
        assertEquals("2 h 05 min 13 s", formatCountdown(java.time.Duration.ofSeconds(2 * 3600 + 5 * 60 + 13)))
        assertEquals("4 min 07 s", formatCountdown(java.time.Duration.ofSeconds(247)))
        assertEquals("10:42:31", liveClockText(start, zone))
    }
}
