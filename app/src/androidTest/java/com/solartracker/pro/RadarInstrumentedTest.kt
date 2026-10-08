package com.solartracker.pro

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File

/**
 * Radar & Prognoza tab on a real Android system: the radar always states its data status (LIVE / STALE / CACHED /
 * UNAVAILABLE – also without internet, never a crash), and the forecast shows today's PV and the hourly list.
 */
@RunWith(AndroidJUnit4::class)
class RadarInstrumentedTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(240)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val tag = "RadarTest"

    private fun screenshot(name: String) {
        val dir = File(context.filesDir, "live-test").apply { mkdirs() }
        runCatching { device.takeScreenshot(File(dir, "$name.png")) }
    }

    @Test
    fun radarTabShowsStatusTodayAndHourlyForecast() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val tab = UiTestSupport.findDismissingAnr(device, By.desc("Radar"), tag)
            assertNotNull("Radar tab not found", tab)
            tab!!.click()
            assertNotNull("screen", device.wait(Until.findObject(By.res("radar_screen")), 15_000))
            val status = device.wait(Until.findObject(By.res("radar_status")), 30_000)
            assertNotNull("radar status", status)
            Log.i(tag, "radar status: ${status.text}")
            assertTrue(status.text, status.text in setOf("NA ŻYWO", "DANE NIEAKTUALNE", "Z PAMIĘCI", "NIEDOSTĘPNY", "LIVE", "STALE DATA", "CACHED", "UNAVAILABLE"))
            screenshot("radar-top")
            for (res in listOf("radar_today", "radar_chart", "radar_hourly")) {
                var o = device.findObject(By.res(res))
                repeat(30) {
                    if (o != null) return@repeat
                    device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }?.scroll(Direction.DOWN, 0.6f)
                    o = device.wait(Until.findObject(By.res(res)), 1_000)
                }
                assertNotNull(res, o)
            }
            screenshot("radar-forecast")
        }
    }
}
