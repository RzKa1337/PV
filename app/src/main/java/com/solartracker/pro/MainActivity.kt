package com.solartracker.pro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solartracker.pro.data.ThemeMode
import com.solartracker.pro.ui.MainViewModel
import com.solartracker.pro.ui.SolarTrackerApp
import com.solartracker.pro.ui.theme.SolarTrackerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            SolarTrackerTheme(themeMode = settings?.themeMode ?: ThemeMode.SYSTEM) {
                SolarTrackerApp(viewModel)
            }
        }
    }
}
