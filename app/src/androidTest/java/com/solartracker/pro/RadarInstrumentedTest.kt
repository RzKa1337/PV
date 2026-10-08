package com.solartracker.pro

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
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

    /** Scrolls the page by swiping along the left margin – a swipe over the map would pan the map instead. */
    private fun scrollPage() {
        val x = (device.displayWidth * 0.03).toInt().coerceAtLeast(8)
        device.swipe(x, (device.displayHeight * 0.75).toInt(), x, (device.displayHeight * 0.35).toInt(), 25)
    }

    @Test
    fun radarTabShowsStatusTodayAndHourlyForecast() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val tab = UiTestSupport.findDismissingAnr(device, By.desc("Radar"), tag)
            assertNotNull("Radar tab not found", tab)
            tab!!.click()
            assertNotNull("screen", device.wait(Until.findObject(By.res("radar_screen")), 15_000))
            assertNotNull("now card", device.wait(Until.findObject(By.res("radar_now")), 15_000))
            screenshot("radar-top")
            // The radar card sits below the "now" card and today's tiles – scroll to it.
            var status = device.wait(Until.findObject(By.res("radar_status")), 5_000)
            repeat(30) {
                if (status != null) return@repeat
                scrollPage()
                status = device.wait(Until.findObject(By.res("radar_status")), 1_500)
            }
            assertNotNull("radar status", status)
            Log.i(tag, "radar status: ${status.text}")
            assertTrue(status.text, status.text in setOf("NA ŻYWO", "DANE NIEAKTUALNE", "Z PAMIĘCI", "NIEDOSTĘPNY", "LIVE", "STALE DATA", "CACHED", "UNAVAILABLE"))
            screenshot("radar-map")
            for (res in listOf("radar_chart", "radar_hourly")) {
                var o = device.findObject(By.res(res))
                repeat(30) {
                    if (o != null) return@repeat
                    scrollPage()
                    o = device.wait(Until.findObject(By.res(res)), 1_000)
                }
                assertNotNull(res, o)
            }
            screenshot("radar-forecast")
        }
    }
}
