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
        File(instrumentation.targetContext.getExternalFilesDir(null), "live-test").apply { mkdirs() }
    }

    private data class Sample(
        val clockText: String,
        val clock: LocalTime,
        val azimuth: Double,
        val elevation: Double,
        val power: String,
        val soc: String,
        val wall: LocalTime,
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

    @Test
    fun liveSolar_updatesEverySecondInRealTime() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: MainViewModel
            scenario.onActivity { vm = ViewModelProvider(it, MainViewModel.Factory)[MainViewModel::class.java] }
            runBlocking { assertTrue(vm.saveBattery(true, BatteryStorage())) }

            val liveTab = device.wait(Until.findObject(By.desc("Live")), 20_000)
            assertNotNull("Live tab not found", liveTab)
            liveTab.click()
            assertTrue("Live screen did not appear", device.wait(Until.hasObject(By.res(LiveTags.CLOCK)), 20_000))
            waitFor("ticker running") { vm.liveActive.value }

            val seconds = InstrumentationRegistry.getArguments().getString("liveSeconds")?.toLongOrNull() ?: 180L
            val samples = mutableListOf<Sample>()
            val csv = File(outDir, "live-samples.csv").printWriter()
            csv.println("deviceTime,displayedClock,azimuth,elevation,power,soc")
            screenshot("01-live-start")
            val end = SystemClock.elapsedRealtime() + seconds * 1000
            while (SystemClock.elapsedRealtime() < end) {
                val clockText = text(LiveTags.CLOCK)
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
                            wall = LocalTime.now(),
                        )
                        samples += s
                        val line = "${s.wall},$clockText,${s.azimuth},${s.elevation},\"${s.power}\",\"${s.soc}\""
                        csv.println(line)
                        csv.flush()
                        Log.i(logTag, line)
                    }
                }
                if (samples.size == 90) screenshot("02-live-middle")
                Thread.sleep(100)
            }
            csv.close()
            screenshot("03-live-end")
            runCatching {
                repeat(4) { device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 1f) }
                Thread.sleep(1500)
                screenshot("04-live-energy")
                repeat(4) { device.findObject(By.scrollable(true))?.scroll(Direction.UP, 1f) }
            }

            Log.i(logTag, "observed ${samples.size} seconds in $seconds s")
            // Every displayed second appears, in order (no skipped or repeated seconds).
            assertTrue("observed ${samples.size} seconds in $seconds s", samples.size >= seconds - 3)
            for (i in 1 until samples.size) {
                val step = Duration.between(samples[i - 1].clock, samples[i].clock).let { if (it.isNegative) it.plusDays(1) else it }
                assertEquals("step between ${samples[i - 1].clock} and ${samples[i].clock}", 1L, step.seconds)
            }
            // The displayed clock follows the device clock.
            samples.forEach { s ->
                val diff = abs(Duration.between(s.clock, s.wall).seconds)
                assertTrue("clock ${s.clock} vs device ${s.wall}", diff <= 1 || diff >= 86_399)
            }
            // The sun moves smoothly: small steps, and the position changes over the run.
            for (i in 1 until samples.size) {
                val dAz = abs(samples[i].azimuth - samples[i - 1].azimuth)
                assertTrue("azimuth step $dAz", dAz < 0.1 || dAz > 359.0)
                assertTrue("elevation step", abs(samples[i].elevation - samples[i - 1].elevation) < 0.1)
            }
            assertTrue("azimuth must change", samples.first().azimuth != samples.last().azimuth)
            Log.i(logTag, "OK: ${samples.size} consecutive seconds, az ${samples.first().azimuth} → ${samples.last().azimuth}")

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
