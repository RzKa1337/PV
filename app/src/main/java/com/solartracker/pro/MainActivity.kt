package com.solartracker.pro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.solartracker.pro.data.AppLanguage
import com.solartracker.pro.data.ThemeMode
import com.solartracker.pro.i18n.AppLocale
import com.solartracker.pro.ui.MainViewModel
import com.solartracker.pro.ui.SolarTrackerApp
import com.solartracker.pro.ui.theme.SolarTrackerTheme
import com.solartracker.pro.update.UpdateNotifications
import com.solartracker.pro.widget.SolarWidgetProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }
    private val openSettingsRequest = mutableIntStateOf(0)
    private var appliedLanguage = AppLanguage.POLISH

    override fun attachBaseContext(newBase: Context) {
        // UI language chosen in the settings (Polish by default), applied before any resource is read.
        appliedLanguage = AppLocale.stored(newBase)
        super.attachBaseContext(AppLocale.wrap(newBase, appliedLanguage))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val language = settings?.language
            LaunchedEffect(language) {
                // A new language from the settings (or restored settings): store it and rebuild the UI.
                if (language != null && language != appliedLanguage) {
                    AppLocale.store(this@MainActivity, language)
                    SolarWidgetProvider.requestUpdate(this@MainActivity)
                    recreate()
                }
            }
            SolarTrackerTheme(themeMode = settings?.themeMode ?: ThemeMode.SYSTEM) {
                SolarTrackerApp(viewModel, openSettingsRequest.intValue)
            }
        }
        // After an update: once the new version has been usable in the foreground for a while,
        // it is considered healthy (no settings rollback needed).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(HEALTHY_AFTER_MS)
                (application as SolarTrackerApplication).updateManager.markHealthy()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Settings or new inverter data may have changed: refresh home-screen widgets.
        SolarWidgetProvider.requestUpdate(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdateNotifications.EXTRA_OPEN_UPDATES, false) == true) openSettingsRequest.intValue++
    }

    private companion object {
        const val HEALTHY_AFTER_MS = 15_000L
    }
}
