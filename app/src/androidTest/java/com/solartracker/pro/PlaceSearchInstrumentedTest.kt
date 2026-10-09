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
            // Text is set without tapping (no soft keyboard covering the results); the results appear below the
            // field and can be off screen, so the page is scrolled while waiting.
            field!!.text = "Warsz"
            val outcomeSelector = By.res(java.util.regex.Pattern.compile("place_suggestion|place_search_empty|place_search_error"))
            var outcome = device.wait(Until.findObject(outcomeSelector), 10_000)
            repeat(20) {
                if (outcome != null) return@repeat
                device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }?.scroll(Direction.DOWN, 0.3f)
                outcome = device.wait(Until.findObject(outcomeSelector), 2_000)
            }
            assertNotNull("suggestions, empty or error state", outcome)
            val found = outcome!!
            Log.i(tag, "outcome: ${found.resourceName}")
            if (found.resourceName == "place_suggestion") {
                device.findObjects(By.res("place_suggestion")).forEach { Log.i(tag, "suggestion: ${it.children.joinToString { c -> c.text ?: "" }}") }
                found.click()
                var name = device.wait(Until.findObject(By.res("current_location_name").textContains("Warsz")), 15_000)
                repeat(10) {
                    if (name != null) return@repeat
                    device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }?.scroll(Direction.UP, 0.6f)
                    name = device.wait(Until.findObject(By.res("current_location_name").textContains("Warsz")), 2_000)
                }
                assertNotNull("picked city shown as current location", name)
                assertTrue(name!!.text.startsWith("Warsz"))
            }
        }
    }
}
