package com.solartracker.pro

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.solartracker.pro.data.AppLanguage
import com.solartracker.pro.data.SettingsRepository
import com.solartracker.pro.i18n.AppLocale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** The English UI option: navigation and screen texts come from values-en; Polish is restored afterwards. */
@RunWith(AndroidJUnit4::class)
class LanguageInstrumentedTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(300)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val tag = "LanguageTest"

    private fun setLanguage(language: AppLanguage) {
        runBlocking { SettingsRepository(context).setLanguage(language) }
        AppLocale.store(context, language)
    }

    @After
    fun restorePolish() = setLanguage(AppLanguage.POLISH)

    @Test
    fun englishShowsEnglishTexts() {
        setLanguage(AppLanguage.ENGLISH)
        ActivityScenario.launch(MainActivity::class.java).use {
            val tools = UiTestSupport.findDismissingAnr(device, By.desc("Tools"), tag)
            assertNotNull("Tools tab (English)", tools)
            tools!!.click()
            assertNotNull("designer chip (English)", device.wait(Until.findObject(By.text("PV designer")), 10_000))
            val settings = device.findObject(By.desc("Settings"))
            assertNotNull("Settings tab (English)", settings)
            settings!!.click()
            assertNotNull("settings subtitle (English)", device.wait(Until.findObject(By.text("Saved locally on this phone")), 10_000))
        }
    }
}
