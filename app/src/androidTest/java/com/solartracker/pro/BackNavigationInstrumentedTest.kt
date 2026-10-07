package com.solartracker.pro

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** The system Back button closes sub-pages and walks back through visited tabs instead of exiting. */
@RunWith(AndroidJUnit4::class)
class BackNavigationInstrumentedTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(300)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val tag = "BackNavigationTest"

    private fun scrollTo(selector: BySelector): UiObject2? {
        var found = device.findObject(selector)
        repeat(25) {
            if (found != null) return found
            device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }?.scroll(Direction.DOWN, 0.8f)
            found = device.wait(Until.findObject(selector), 1_000)
        }
        return found
    }

    @Test
    fun backClosesSubPageThenReturnsToPreviousTab() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val tools = UiTestSupport.findDismissingAnr(device, By.desc("Narzędzia"), tag)
            assertNotNull("Narzędzia tab", tools)
            tools!!.click()
            assertNotNull("tools page", device.wait(Until.findObject(By.text("Projektant PV")), 10_000))

            val center = device.findObject(By.desc("Centrum"))
            assertNotNull("Centrum tab", center)
            center!!.click()
            val open = scrollTo(By.textContains("Analizy: zdrowie"))
            assertNotNull("analyses button", open)
            open!!.click()
            assertNotNull("analyses page", device.wait(Until.findObject(By.text("ZDROWIE INSTALACJI")), 15_000))

            // Back #1: analyses sub-page -> Energy Center main page (same tab).
            device.pressBack()
            assertNotNull("energy center main page", device.wait(Until.findObject(By.text("Falownik, prognozy, zacienienie")), 10_000))
            assertTrue("analyses page closed", device.wait(Until.gone(By.text("ZDROWIE INSTALACJI")), 5_000))

            // Back #2: previous tab (Tools), the app stays open.
            device.pressBack()
            assertNotNull("back to tools", device.wait(Until.findObject(By.text("Projektant PV")), 10_000))
        }
    }
}
