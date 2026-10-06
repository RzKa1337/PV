package com.solartracker.pro

import android.util.Log
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Tools tab (designer validation, location comparison) and the Energy Center analyses page. */
@RunWith(AndroidJUnit4::class)
class ToolsInstrumentedTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(300)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val tag = "ToolsTest"

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
    fun designerValidatesAndLocationsCompare() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val tab = UiTestSupport.findDismissingAnr(device, By.desc("Narzędzia"), tag)
            assertNotNull("Narzędzia tab", tab)
            tab.click()
            assertNotNull("designer chip", device.wait(Until.findObject(By.text("Projektant PV")), 10_000))
            val run = scrollTo(By.text("Zaprojektuj"))
            assertNotNull("design button", run)
            run!!.click()
            assertNotNull("validation message", device.wait(Until.findObject(By.textContains("Uzupełnij wszystkie pola")), 10_000))

            device.findObject(By.text("Lokalizacje"))?.click() ?: scrollUpAndClick("Lokalizacje")
            val compare = scrollTo(By.text("Porównaj"))
            assertNotNull("compare button", compare)
            compare!!.click()
            val result = device.wait(Until.findObject(By.textContains("kWh/kWp")), 60_000)
            assertNotNull("comparison result", result)
            Log.i(tag, "locations: ${result.text}")
        }
    }

    private fun scrollUpAndClick(text: String) {
        repeat(10) {
            device.findObject(By.text(text))?.let { it.click(); return }
            device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }?.scroll(Direction.UP, 0.8f)
        }
        throw AssertionError("$text not found")
    }

    @Test
    fun insightsPageShowsEmsAndHistory() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val tab = UiTestSupport.findDismissingAnr(device, By.desc("Centrum"), tag)
            assertNotNull("Centrum tab", tab)
            tab.click()
            val open = scrollTo(By.textContains("Analizy: zdrowie"))
            assertNotNull("analyses button", open)
            open!!.click()
            assertNotNull("health card", device.wait(Until.findObject(By.text("ZDROWIE INSTALACJI")), 15_000))
            assertNotNull("EMS card", scrollTo(By.text("ZARZĄDZANIE ENERGIĄ (EMS)")))
            assertNotNull("history card", scrollTo(By.text("HISTORIA (POMIARY)")))
            assertNotNull("export", scrollTo(By.text("CSV")))
        }
    }
}
