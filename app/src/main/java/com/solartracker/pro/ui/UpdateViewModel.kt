package com.solartracker.pro.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.solartracker.pro.SolarTrackerApplication
import com.solartracker.pro.core.update.UpdateConfig
import com.solartracker.pro.update.UpdateManager
import com.solartracker.pro.update.UpdateSnapshot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Thin UI adapter over the app-wide [UpdateManager]. */
class UpdateViewModel(val manager: UpdateManager) : ViewModel() {

    val snapshot: StateFlow<UpdateSnapshot?> =
        manager.store.data.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val status = manager.status
    val log = manager.logEntries

    suspend fun saveConfig(config: UpdateConfig): List<String> = manager.saveConfig(config)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as SolarTrackerApplication
                UpdateViewModel(app.updateManager)
            }
        }
    }
}
