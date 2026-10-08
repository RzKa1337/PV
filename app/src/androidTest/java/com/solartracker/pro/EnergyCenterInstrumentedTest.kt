package com.solartracker.pro

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.solartracker.pro.core.analytics.CalibrationSample
import com.solartracker.pro.core.analytics.ForecastHorizon
import com.solartracker.pro.core.analytics.HistorySample
import com.solartracker.pro.core.inverter.InverterConfig
import com.solartracker.pro.core.inverter.InverterLink
import com.solartracker.pro.core.inverter.OperatingMode
import com.solartracker.pro.energy.EnergySettingsStore
import com.solartracker.pro.energy.HistoryDatabase
import com.solartracker.pro.energy.SecretStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * Energy Center on a real Android system with the simulated Anenji provider: connection status,
 * the SYMULATOR label, LIVE values and the advisor. Also checks Keystore encryption and SQLite history.
 */
@RunWith(AndroidJUnit4::class)
class EnergyCenterInstrumentedTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(300)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val tag = "EnergyCenterTest"

    @After
    fun disableInverter() = runBlocking {
        EnergySettingsStore(context).setInverter(InverterConfig(enabled = false))
    }

    private fun screenshot(name: String) {
        val dir = File(context.filesDir, "live-test").apply { mkdirs() }
        runCatching { device.takeScreenshot(File(dir, "$name.png")) }
    }

    @Test
    fun simulatedInverterIsShownAsSimulatedAndOnline() {
        runBlocking { EnergySettingsStore(context).setInverter(InverterConfig(enabled = true, link = InverterLink.SIMULATOR, pollIntervalSeconds = 2)) }
        ActivityScenario.launch(MainActivity::class.java).use {
            val tab = UiTestSupport.findDismissingAnr(device, By.desc("Centrum"), tag)
            assertNotNull("Centrum tab not found", tab)
            tab!!.click()
            assertNotNull("simulator label", device.wait(Until.findObject(By.textContains("SYMULATOR – dane testowe")), 30_000))
            assertNotNull("status ONLINE", device.wait(Until.findObject(By.textContains(": ONLINE")), 30_000))
            assertNotNull("LIVE section", device.wait(Until.findObject(By.text("LIVE")), 15_000))
            assertNotNull("PV metric", device.findObject(By.text("PV")))
            screenshot("energy-center-live")

            // Advisor answers from the (simulated) telemetry and says it is a simulator.
            // The screen has horizontal rows that are scrollable too – scroll the tallest (vertical) container.
            val chipSelector = By.text("Co teraz robi mój falownik?")
            var chip = device.findObject(chipSelector)
            repeat(25) {
                if (chip != null) return@repeat
                val page = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
                assertNotNull("vertical scroll container", page)
                page!!.scroll(Direction.DOWN, 0.8f)
                chip = device.wait(Until.findObject(chipSelector), 1_000)
            }
            assertNotNull("advisor chip", chip)
            chip.click()
            // The answer appears below the chips; more cards above may push it off screen.
            val answerSelector = By.textContains("Tryb:")
            var answer = device.wait(Until.findObject(answerSelector), 5_000)
            repeat(10) {
                if (answer != null) return@repeat
                device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }?.scroll(Direction.DOWN, 0.5f)
                answer = device.wait(Until.findObject(answerSelector), 1_000)
            }
            assertNotNull("advisor answer", answer)
            Log.i(tag, "advisor: ${answer.text}")
            assertTrue(answer.text.contains("SYMULATOR"))
            screenshot("energy-center-advisor")
        }
    }

    @Test
    fun diagnosticsScreenShowsStatusRecommendationAndRegisterLog() {
        runBlocking { EnergySettingsStore(context).setInverter(InverterConfig(enabled = true, link = InverterLink.SIMULATOR, pollIntervalSeconds = 2)) }
        ActivityScenario.launch(MainActivity::class.java).use {
            val tab = UiTestSupport.findDismissingAnr(device, By.desc("Centrum"), tag)
            assertNotNull("Centrum tab not found", tab)
            tab!!.click()
            assertNotNull("status ONLINE", device.wait(Until.findObject(By.textContains(": ONLINE")), 30_000))
            val open = device.wait(Until.findObject(By.text("Diagnostyka PV")), 15_000)
            assertNotNull("diagnostics button", open)
            open.click()
            assertNotNull("PV status card", device.wait(Until.findObject(By.text("STATUS PV")), 15_000))
            assertNotNull("recommendation", device.wait(Until.findObject(By.text("ZALECENIE")), 30_000))
            screenshot("diagnostics-top")
            // Scroll to the register log: the simulator must say it has no registers (never fake ones).
            val selector = By.textContains("Symulator nie ma rejestrów")
            var note = device.findObject(selector)
            repeat(25) {
                if (note != null) return@repeat
                device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }?.scroll(Direction.DOWN, 0.8f)
                note = device.wait(Until.findObject(selector), 1_000)
            }
            assertNotNull("simulator register note", note)
            screenshot("diagnostics-registers")
        }
    }

    @Test
    fun analyzerRunsOnHistoryAndShowsHealth() {
        runBlocking { EnergySettingsStore(context).setInverter(InverterConfig(enabled = true, link = InverterLink.SIMULATOR, pollIntervalSeconds = 2)) }
        ActivityScenario.launch(MainActivity::class.java).use {
            val tab = UiTestSupport.findDismissingAnr(device, By.desc("Centrum"), tag)
            assertNotNull("Centrum tab not found", tab)
            tab!!.click()
            val open = device.wait(Until.findObject(By.text("Analiza Anenji")), 30_000)
            assertNotNull("analyzer button", open)
            open.click()
            assertNotNull("read-only notice", device.wait(Until.findObject(By.textContains("tylko czyta dane")), 15_000))
            val run = device.wait(Until.findObject(By.text("Analizuj historię")), 10_000)
            assertNotNull("analyse button", run)
            run.click()
            assertNotNull("health", device.wait(Until.findObject(By.text("SYSTEM HEALTH")), 60_000))
            screenshot("analyzer")
        }
    }

    @Test
    fun forensicScreenShowsDataStatusAndValidationMode() {
        runBlocking { EnergySettingsStore(context).setInverter(InverterConfig(enabled = true, link = InverterLink.SIMULATOR, pollIntervalSeconds = 2)) }
        ActivityScenario.launch(MainActivity::class.java).use {
            val tab = UiTestSupport.findDismissingAnr(device, By.desc("Centrum"), tag)
            assertNotNull("Centrum tab not found", tab)
            tab!!.click()
            assertNotNull("status ONLINE", device.wait(Until.findObject(By.textContains(": ONLINE")), 30_000))
            val openSelector = By.text("Co się stało? – analiza śledcza")
            var open = device.wait(Until.findObject(openSelector), 5_000)
            repeat(15) {
                if (open != null) return@repeat
                device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }?.scroll(Direction.DOWN, 0.5f)
                open = device.wait(Until.findObject(openSelector), 1_000)
            }
            assertNotNull("forensic button", open)
            open.click()
            assertNotNull("title", device.wait(Until.findObject(By.text("CO SIĘ STAŁO?")), 15_000))
            val run = device.wait(Until.findObject(By.text("Analizuj historię")), 10_000)
            assertNotNull("analyse button", run)
            run.click()
            // Whatever the history holds, the data status is always stated (OK / BRAK DANYCH / ANALIZA CZĘŚCIOWA / DANE NIEAKTUALNE).
            assertNotNull("data status", device.wait(Until.findObject(By.textStartsWith("Dane: ")), 90_000))
            screenshot("forensic")
            val selector = By.text("Tryb walidacji rejestrów")
            var card = device.findObject(selector)
            repeat(25) {
                if (card != null) return@repeat
                device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }?.scroll(Direction.DOWN, 0.8f)
                card = device.wait(Until.findObject(selector), 1_000)
            }
            assertNotNull("validation mode", card)
            screenshot("forensic-validation")
        }
    }

    @Test
    fun keystoreEncryptsSecrets() {
        val secret = "github_pat_TEST_123"
        val stored = SecretStore.encrypt(secret)
        assertTrue(stored.startsWith("v1:"))
        assertTrue("plaintext must not be stored", !stored.contains(secret))
        assertNotEquals("random IV", stored, SecretStore.encrypt(secret))
        assertEquals(secret, SecretStore.decrypt(stored))
        assertEquals("", SecretStore.decrypt(""))
        assertNull(SecretStore.decrypt("plain-text-token"))
        assertNull(SecretStore.decrypt("v1:AAAA"))
    }

    @Test
    fun historyDatabaseStoresAndPrunes() {
        context.deleteDatabase(HistoryDatabase.NAME)
        val db = HistoryDatabase(context)
        val now = Instant.now()
        fun row(start: Instant, pv: Double) = HistorySample(start, start.plusSeconds(30), 6, pv, pv, 500.0, 200.0, null, 52.1, 3.8, 64.0, 41.0, null,
            pv / 1000.0 * 30 / 3600, 0.5 * 30 / 3600, 0.0, 0.0, 0.2 * 30 / 3600, 0.0, OperatingMode.OFF_GRID, setOf(7), emptySet())
        db.insert(row(now.minusSeconds(60), 3000.0))
        db.insert(row(now.minusSeconds(30), 2800.0))
        db.insert(row(now.minus(java.time.Duration.ofDays(40)), 1000.0))
        db.addCalibration(CalibrationSample(now, 2.8, 3.1))
        val recent = db.history(now.minusSeconds(3600), now.plusSeconds(1))
        assertEquals(2, recent.size)
        assertEquals(3000.0, recent[0].pvW!!, 0.0)
        assertEquals(OperatingMode.OFF_GRID, recent[0].mode)
        assertEquals(setOf(7), recent[0].faultCodes)
        assertNull(recent[0].gridW)
        db.prune(now)
        assertEquals(0, db.history(now.minus(java.time.Duration.ofDays(60)), now.minus(java.time.Duration.ofDays(31))).size)
        assertEquals(1, db.calibration(now.minusSeconds(10)).size)
        val obs = com.solartracker.pro.core.analytics.CalibrationObservation(now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).minusSeconds(5), 2.0, 2.5, 40.0, nearLimit = true,
            sunElevationDeg = 45.0, condition = com.solartracker.pro.core.analytics.SkyCondition.CLEAR, invalidTelemetry = true)
        db.addObservation(obs, usable = false)
        assertEquals(obs, db.observations(now.minusSeconds(60)).single { it.time == obs.time })
        assertTrue("not usable rows are not used by the global calibration", db.calibration(now.minusSeconds(60)).none { it.time == obs.time })
        val hour = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusSeconds(3600)
        db.putForecast(hour, ForecastHorizon.HOUR_AHEAD, 1.2, now)
        db.putForecast(hour, ForecastHorizon.HOUR_AHEAD, 1.5, now.plusSeconds(1)) // later issue replaces
        db.putForecast(hour, ForecastHorizon.DAY_AHEAD, 9.9, hour.plusSeconds(1)) // issued after the hour started → ignored
        assertEquals(mapOf(hour to 1.5), db.forecasts(ForecastHorizon.HOUR_AHEAD, now, hour.plusSeconds(1)))
        assertTrue(db.forecasts(ForecastHorizon.DAY_AHEAD, now, hour.plusSeconds(1)).isEmpty())
        db.close()
        context.deleteDatabase(HistoryDatabase.NAME)
    }
}
