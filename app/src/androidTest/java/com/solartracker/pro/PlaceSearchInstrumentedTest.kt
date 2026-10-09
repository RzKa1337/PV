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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * City search on a real Android system: typing shows provider suggestions (or a clear empty / error state when the
 * emulator has no internet – never a crash), and picking a suggestion sets the location shown in the settings.
 */
@RunWith(AndroidJUnit4::class)
class PlaceSearchInstrumentedTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(180)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val tag = "PlaceSearchTest"

    private fun scrollTo(selector: BySelector): UiObject2? {
        var found = device.findObject(selector)
        repeat(20) {
            if (found != null) return found
            device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }?.scroll(Direction.DOWN, 0.6f)
            found = device.wait(Until.findObject(selector), 1_000)
        }
        return found
    }

    @Test
    fun typingShowsSuggestionsAndPickingSetsTheLocation() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val tab = UiTestSupport.findDismissingAnr(device, By.desc("Ustawienia"), tag)
            assertNotNull("Ustawienia tab", tab)
            tab!!.click()
            val field = scrollTo(By.res("place_search_field"))
            assertNotNull("search field", field)
            field!!.click()
            field.text = "Warsz"
            val outcome = device.wait(
                Until.findObject(By.res(java.util.regex.Pattern.compile("place_suggestion|place_search_empty|place_search_error"))),
                30_000,
            )
            assertNotNull("suggestions, empty or error state", outcome)
            Log.i(tag, "outcome: ${outcome.resourceName}")
            if (outcome.resourceName == "place_suggestion") {
                device.findObjects(By.res("place_suggestion")).forEach { Log.i(tag, "suggestion: ${it.children.joinToString { c -> c.text ?: "" }}") }
                outcome.click()
                val name = device.wait(Until.findObject(By.res("current_location_name").textContains("Warsz")), 30_000)
                assertNotNull("picked city shown as current location", name)
                assertTrue(name.text.startsWith("Warsz"))
            }
        }
    }
}
