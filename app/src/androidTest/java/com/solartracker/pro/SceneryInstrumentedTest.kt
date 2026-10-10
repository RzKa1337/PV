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
import com.solartracker.pro.data.SceneryRepository
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * Scenic backdrop: the bar with the credit and "change scenery" is on every tab, a new set can be drawn, photos can
 * be turned off (built-in illustration, no network) and fast tab switching keeps the screens working. Passes with or
 * without internet on the emulator: without it the credit names the illustration.
 */
@RunWith(AndroidJUnit4::class)
class SceneryInstrumentedTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(300)

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val tag = "SceneryTest"

    @After
    fun restorePhotos() {
        SceneryRepository.get(InstrumentationRegistry.getInstrumentation().targetContext).setEnabled(true)
    }

    private fun scrollTo(selector: BySelector): UiObject2? {
        var found = device.findObject(selector)
        repeat(30) {
            if (found != null) return found
            device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }?.scroll(Direction.DOWN, 0.8f)
            found = device.wait(Until.findObject(selector), 1_000)
        }
        return found
    }

    /** Navigation item: unselected items carry the label as icon description, the selected one shows it as text. */
    private fun tabSelector(label: String): BySelector = By.desc(label)

    private fun tab(label: String): UiObject2? = device.findObject(tabSelector(label)) ?: device.findObject(By.text(label))

    @Test
    fun sceneryBarShuffleTurnOffAndTabSwitching() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // Any tab other than the start one has its label as a description – wait for the app via that one.
            assertNotNull("navigation", UiTestSupport.findDismissingAnr(device, tabSelector("Ustawienia"), tag))
            val dashboard = tab("Pulpit")
            assertNotNull("Pulpit tab", dashboard)
            dashboard!!.click()
            assertNotNull("hero card", device.wait(Until.findObject(By.res("dash_production")), 15_000))
            val credit = device.wait(Until.findObject(By.res("scenery_credit")), 10_000)
            assertNotNull("photo credit / illustration caption", credit)
            Log.i(tag, "credit: ${credit!!.text}")

            val shuffle = device.findObject(By.res("scenery_shuffle"))
            assertNotNull("change scenery button", shuffle)
            assertTrue("button is labelled", shuffle!!.contentDescription?.isNotBlank() == true)
            shuffle.click()
            assertNotNull("dashboard still shown after a new set", device.wait(Until.findObject(By.res("dash_production")), 10_000))

            // Fast tab switching must not break the screens (the backdrop loads in the background).
            repeat(3) {
                tab("Radar")?.click()
                tab("Pulpit")?.click()
            }
            assertNotNull("dashboard after fast switching", device.wait(Until.findObject(By.res("dash_production")), 15_000))

            // Photos off: the built-in illustration is named in the bar (works offline, nothing downloaded).
            val settingsTab = device.wait(Until.findObject(tabSelector("Ustawienia")), 5_000) ?: tab("Ustawienia")
            assertNotNull("Ustawienia tab", settingsTab)
            settingsTab!!.click()
            val switch = scrollTo(By.res("scenery_photos_switch"))
            assertNotNull("photos switch", switch)
            if (switch!!.isChecked) switch.click()
            val illustration = device.wait(Until.findObject(By.res("scenery_credit").textContains("Ilustracja")), 10_000)
            assertNotNull("illustration caption with photos off", illustration)

            val again = scrollTo(By.res("scenery_photos_switch"))
            if (again != null && !again.isChecked) again.click()
        }
    }
}
