package com.solartracker.pro

import android.appwidget.AppWidgetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.solartracker.pro.data.WeatherRepository
import com.solartracker.pro.widget.SolarWidgetProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun widgetIsRegisteredWithTheLauncher() {
        val providers = AppWidgetManager.getInstance(context).installedProviders
        val ours = providers.firstOrNull { it.provider.className == SolarWidgetProvider::class.java.name }
        assertNotNull("widget provider not registered", ours)
        assertTrue(ours!!.minWidth > 0)
        // A refresh without placed widgets must be a no-op, not a crash.
        SolarWidgetProvider.requestUpdate(context)
    }

    @Test
    fun cachedWeatherNeedsNoNetwork() {
        val result = WeatherRepository(context).cachedOnly(com.solartracker.pro.data.AppSettings.DEFAULT_LOCATION)
        assertNotNull("climate fallback available offline", result.climate)
    }
}
