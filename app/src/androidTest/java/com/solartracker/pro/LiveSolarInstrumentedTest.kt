package com.solartracker.pro

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.ui.MainViewModel
import com.solartracker.pro.ui.screens.LiveTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.LocalTime
import kotlin.math.abs

/**
 * Real-device check of Live Solar: opens the Live tab and watches the screen for several
 * minutes (default 180 s, instrumentation argument `liveSeconds`), sampling 10× per second.
 * Writes every observed second to live-samples.csv and screenshots to the app's external files.
 */
@RunWith(AndroidJUnit4::class)
class LiveSolarInstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val logTag = "LiveSolarTest"
    private val outDir: File by lazy {
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "live-test").apply { mkdirs() }
    }

    private data class Sample(val clockText: String, val clock: LocalTime, val azimuth: Double, val elevation: Double, val power: String, val soc: String, val wall: LocalTime)

    private fun text(tag: String): String? = runCatching {
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("") { it.text }
    }.getOrNull()

    private fun number(text: String?): Double =
        text!!.filter { it.isDigit() || it == ',' || it == '.' || it == '-' }.replace(',', '.').toDouble()

    private fun screenshot(name: String) {
        runCatching {
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { Log.w(logTag, "screenshot $name failed", it) }
    }

    /** Advances one Compose frame (animations are not auto-advanced, so the UI never blocks idling). */
    private fun frame() {
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    @Test
    fun liveSolar_updatesEverySecondInRealTime() {
        rule.mainClock.autoAdvance = false
        val vm = ViewModelProvider(rule.activity, MainViewModel.Factory)[MainViewModel::class.java]
        runBlocking { assertTrue(vm.saveBattery(true, BatteryStorage())) }

        rule.onNodeWithContentDescription("Live").performClick()
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (rule.onAllNodesWithTag(LiveTags.CLOCK, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Live screen did not appear" }
            frame()
            Thread.sleep(50)
        }
        assertTrue("ticker should run while the screen is visible", vm.liveActive.value)

        val seconds = InstrumentationRegistry.getArguments().getString("liveSeconds")?.toLongOrNull() ?: 180L
        val samples = mutableListOf<Sample>()
        val csv = File(outDir, "live-samples.csv").printWriter()
        csv.println("deviceTime,displayedClock,azimuth,elevation,power,soc")
        screenshot("01-live-start")
        val end = SystemClock.elapsedRealtime() + seconds * 1000
        while (SystemClock.elapsedRealtime() < end) {
            frame()
            val clockText = text(LiveTags.CLOCK)
            if (clockText != null && samples.lastOrNull()?.clockText != clockText) {
                val s = Sample(
                    clockText = clockText,
                    clock = LocalTime.parse(clockText),
                    azimuth = number(text(LiveTags.AZIMUTH)),
                    elevation = number(text(LiveTags.ELEVATION)),
                    power = text(LiveTags.POWER) ?: "",
                    soc = text(LiveTags.SOC) ?: "",
                    wall = LocalTime.now(),
                )
                samples += s
                val line = "${s.wall},$clockText,${s.azimuth},${s.elevation},\"${s.power}\",\"${s.soc}\""
                csv.println(line)
                Log.i(logTag, line)
            }
            if (samples.size == 90) screenshot("02-live-middle")
            Thread.sleep(100)
        }
        csv.close()
        screenshot("03-live-end")
        runCatching { rule.onNodeWithText("Energia teraz").performScrollTo(); frame(); screenshot("04-live-energy") }

        // Every displayed second appears exactly once, in order (no skipped or repeated seconds).
        assertTrue("observed ${samples.size} seconds in $seconds s", samples.size >= seconds - 2)
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
            assertTrue(abs(samples[i].azimuth - samples[i - 1].azimuth) < 0.1 || abs(samples[i].azimuth - samples[i - 1].azimuth) > 359.0)
            assertTrue(abs(samples[i].elevation - samples[i - 1].elevation) < 0.1)
        }
        assertTrue("azimuth must change", samples.first().azimuth != samples.last().azimuth)
        Log.i(logTag, "OK: ${samples.size} consecutive seconds, az ${samples.first().azimuth} → ${samples.last().azimuth}")

        // Background → ticker stops; foreground → it resumes.
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitFor("ticker stopped in background") { !vm.liveActive.value }
        Thread.sleep(2_000)
        assertFalse(vm.liveActive.value)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("ticker resumed") { frame(); vm.liveActive.value }
        waitFor("clock current after resume") {
            frame()
            val c = text(LiveTags.CLOCK)?.let(LocalTime::parse)
            c != null && abs(Duration.between(c, LocalTime.now()).seconds) <= 1
        }
        screenshot("05-live-resumed")
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timeout: $what" }
            Thread.sleep(100)
        }
    }
}
