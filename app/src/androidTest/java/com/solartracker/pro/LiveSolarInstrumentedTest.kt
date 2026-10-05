package com.solartracker.pro

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.ui.MainViewModel
import com.solartracker.pro.ui.screens.LiveTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.LocalTime
import kotlin.math.abs

/**
 * Real-device check of Live Solar: opens the Live tab and watches the screen for several
 * minutes (default 180 s, instrumentation argument `liveSeconds`), sampling 10× per second.
 *
 * The screen is read through the accessibility tree with UiAutomator (like a user/screen reader
 * would), so the test does not depend on Compose idling – the Live screen is never "idle"
 * because it changes every second and shows a continuous energy-flow animation.
 * Every observed second goes to live-samples.csv; screenshots are saved next to it.
 */
@RunWith(AndroidJUnit4::class)
class LiveSolarInstrumentedTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(600)

    private val logTag = "LiveSolarTest"
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val outDir: File by lazy {
        // Internal app storage: pulled by CI with `adb exec-out run-as` (works on debuggable builds).
        File(instrumentation.targetContext.filesDir, "live-test").apply { mkdirs() }
    }

    private data class Sample(
        val clockText: String,
        val clock: LocalTime,
        val azimuth: Double,
        val elevation: Double,
        val power: String,
        val soc: String,
        /** Device time right before and right after reading the displayed clock. */
        val wall: LocalTime,
        val wallAfter: LocalTime,
    )

    private fun text(tag: String): String? = runCatching { device.findObject(By.res(tag))?.text }.getOrNull()

    private fun number(text: String?): Double? =
        text?.filter { it.isDigit() || it == ',' || it == '.' || it == '-' }?.replace(',', '.')?.toDoubleOrNull()

    private fun screenshot(name: String) {
        runCatching { device.takeScreenshot(File(outDir, "$name.png")) }
            .onFailure { Log.w(logTag, "screenshot $name failed", it) }
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timeout: $what" }
            Thread.sleep(100)
        }
    }

    /**
     * A freshly booted emulator is sometimes slow to show the first frame or shows a system
     * "isn't responding" dialog (e.g. for System UI); wait longer and dismiss such dialogs.
     */
    private fun findLiveTab(): androidx.test.uiautomator.UiObject2? {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            device.wait(Until.findObject(By.desc("Live")), 5_000)?.let { return it }
            device.findObject(By.res("android:id/aerr_wait"))?.let {
                Log.w(logTag, "dismissing system 'not responding' dialog")
                it.click()
            }
        }
        return null
    }

    @Test
    fun liveSolar_updatesEverySecondInRealTime() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: MainViewModel
            scenario.onActivity { vm = ViewModelProvider(it, MainViewModel.Factory)[MainViewModel::class.java] }
            runBlocking { assertTrue(vm.saveBattery(true, BatteryStorage())) }

            val liveTab = findLiveTab()
            if (liveTab == null) screenshot("00-live-tab-missing")
            assertNotNull("Live tab not found", liveTab)
            liveTab!!.click()
            assertTrue("Live screen did not appear", device.wait(Until.hasObject(By.res(LiveTags.CLOCK)), 20_000))
            waitFor("ticker running") { vm.liveActive.value }

            // Record every computed live state (unconflated; the screen shows the newest of them).
            val states = java.util.concurrent.ConcurrentLinkedQueue<com.solartracker.pro.ui.LiveUiState>()
            val stateScope = CoroutineScope(Dispatchers.Default)
            stateScope.launch {
                vm.liveComputed.collect { s ->
                    if (states.lastOrNull()?.epochMillis?.div(1000) != s.epochMillis / 1000) states += s
                }
            }

            val seconds = InstrumentationRegistry.getArguments().getString("liveSeconds")?.toLongOrNull() ?: 180L
            val samples = mutableListOf<Sample>()
            val csv = File(outDir, "live-samples.csv").printWriter()
            csv.println("deviceTime,displayedClock,azimuth,elevation,power,soc")
            screenshot("01-live-start")
            val end = SystemClock.elapsedRealtime() + seconds * 1000
            device.setCompressedLayoutHeirarchy(true)
            while (SystemClock.elapsedRealtime() < end) {
                val before = LocalTime.now()
                val clockText = text(LiveTags.CLOCK)
                val after = LocalTime.now()
                if (clockText != null && clockText.length == 8 && samples.lastOrNull()?.clockText != clockText) {
                    val az = number(text(LiveTags.AZIMUTH))
                    val el = number(text(LiveTags.ELEVATION))
                    if (az != null && el != null) {
                        val s = Sample(
                            clockText = clockText,
                            clock = LocalTime.parse(clockText),
                            azimuth = az,
                            elevation = el,
                            power = text(LiveTags.POWER) ?: "",
                            soc = text(LiveTags.SOC) ?: "",
                            wall = before,
                            wallAfter = after,
                        )
                        samples += s
                        val line = "${s.wall},$clockText,${s.azimuth},${s.elevation},\"${s.power}\",\"${s.soc}\""
                        csv.println(line)
                        csv.flush()
                        Log.i(logTag, line)
                    }
                }
                if (samples.size == 90) screenshot("02-live-middle")
                Thread.sleep(200)
            }
            csv.close()
            stateScope.cancel() // stop our extra subscriber before the background check
            val stateList = states.toList()
            File(outDir, "live-states.csv").printWriter().use { out ->
                out.println("epochMillis,clock,azimuth,elevation,poa,pvKw,socPercent,batteryKw")
                stateList.forEach {
                    val line = "${it.epochMillis},${it.clockText},${it.sun.azimuthDeg},${it.sun.elevationDeg},${it.pv.poa},${it.pv.modeledPowerKw},${it.energy.socPercent},${it.energy.batteryKw}"
                    out.println(line)
                    Log.i(logTag, "STATE $line")
                }
            }
            screenshot("03-live-end")
            runCatching {
                repeat(4) { device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 1f) }
                Thread.sleep(1500)
                screenshot("04-live-energy")
                repeat(4) { device.findObject(By.scrollable(true))?.scroll(Direction.UP, 1f) }
            }

            Log.i(logTag, "app states: ${stateList.size}, UI observed: ${samples.size} seconds in $seconds s")
            // 1) The state stream that drives the UI produced every second exactly once, in order.
            assertTrue("states ${stateList.size} in $seconds s", stateList.size >= seconds - 2)
            for (i in 1 until stateList.size) {
                val step = stateList[i].epochMillis / 1000 - stateList[i - 1].epochMillis / 1000
                assertEquals("state step at ${stateList[i - 1].clockText} → ${stateList[i].clockText}", 1L, step)
                // A late wake-up on a busy emulator is tolerated (see the latency check below); a tick
                // slipping into the next second would break the 1-second step checked above.
                // The sun moves smoothly (full precision).
                assertTrue(abs(stateList[i].sun.elevationDeg - stateList[i - 1].sun.elevationDeg) < 0.01)
                val dAz = abs(stateList[i].sun.azimuthDeg - stateList[i - 1].sun.azimuthDeg)
                assertTrue("azimuth step $dAz", dAz < 0.05 || dAz > 359.9)
            }
            assertTrue("azimuth must change", stateList.first().sun.azimuthDeg != stateList.last().sun.azimuthDeg)
            // Ticks are computed right after the system second boundary: the vast majority within 200 ms.
            val late = stateList.drop(1).map { it.epochMillis % 1000 }.filter { it >= 200 }
            Log.i(logTag, "ticks ≥200 ms after the boundary: ${late.size} of ${stateList.size - 1} $late")
            assertTrue("late ticks: $late", late.size <= (stateList.size - 1) / 20)
            // 2) The screen shows those values: displayed second never repeats or goes back, follows the
            //    device clock, and almost every second is observed despite UiAutomator's slow reads.
            assertTrue("UI observed ${samples.size} of $seconds seconds", samples.size >= seconds * 0.8)
            val stateClocks = stateList.map { it.clockText }.toSet()
            for (i in 1 until samples.size) {
                val step = Duration.between(samples[i - 1].clock, samples[i].clock).let { if (it.isNegative) it.plusDays(1) else it }
                assertTrue("UI step ${samples[i - 1].clock} → ${samples[i].clock}", step.seconds in 1..3)
            }
            // The displayed second must lie between the device time before (minus 1 s for a render in
            // flight) and after the read. A busy emulator occasionally renders late, so: at least 95% of
            // reads current, and the screen never more than 3 s behind (a frozen screen still fails).
            val lags = samples.filter { it.wallAfter >= it.wall }.map { s ->
                val fromStart = Duration.between(s.wall.withNano(0).minusSeconds(1), s.clock).seconds
                val window = Duration.between(s.wall.withNano(0).minusSeconds(1), s.wallAfter).seconds
                assertTrue("clock ${s.clock} ahead of device ${s.wallAfter}", fromStart <= window)
                (-fromStart).coerceAtLeast(0) // seconds behind the window
            }
            Log.i(logTag, "UI lag (s) histogram: ${lags.groupingBy { it }.eachCount().toSortedMap()}")
            assertTrue("UI current in ${lags.count { it == 0L }} of ${lags.size} reads", lags.count { it == 0L } >= lags.size * 0.95)
            assertTrue("UI at most 3 s behind, max ${lags.maxOrNull()}", (lags.maxOrNull() ?: 0L) <= 2L)
            samples.forEach { s -> assertTrue("UI clock ${s.clockText} comes from the live state", s.clockText in stateClocks) }
            Log.i(logTag, "OK: ${stateList.size} consecutive seconds, az ${stateList.first().sun.azimuthDeg} → ${stateList.last().sun.azimuthDeg}")

            // Background → ticker stops; foreground → it resumes with the current time.
            scenario.moveToState(Lifecycle.State.CREATED)
            waitFor("ticker stopped in background") { !vm.liveActive.value }
            Thread.sleep(2_000)
            assertFalse("ticker must not run in background", vm.liveActive.value)
            Log.i(logTag, "background: liveActive=${vm.liveActive.value}")
            scenario.moveToState(Lifecycle.State.RESUMED)
            waitFor("ticker resumed") { vm.liveActive.value }
            waitFor("clock current after resume") {
                val c = text(LiveTags.CLOCK)?.takeIf { it.length == 8 }?.let(LocalTime::parse)
                c != null && abs(Duration.between(c, LocalTime.now()).seconds) <= 1
            }
            Log.i(logTag, "foreground: liveActive=${vm.liveActive.value}, clock ${text(LiveTags.CLOCK)}")
            screenshot("05-live-resumed")
        }
    }
}
