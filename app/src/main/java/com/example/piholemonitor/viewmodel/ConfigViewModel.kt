package com.example.piholemonitor.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.piholemonitor.data.AppConfig
import com.example.piholemonitor.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Configuration screen.
 * Manages form state and persistence.
 */
class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SettingsRepository(application.applicationContext)

    private val _config = MutableStateFlow(AppConfig.DEFAULT)
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private val _saveMessage = MutableStateFlow<String?>(null)
    val saveMessage: StateFlow<String?> = _saveMessage.asStateFlow()

    init {
        loadConfig()
    }

    private fun loadConfig() {
        viewModelScope.launch {
            repo.configFlow.collect { cfg ->
                _config.value = cfg
            }
        }
    }

    fun updateConfig(newConfig: AppConfig) {
        _config.value = newConfig
    }

    fun saveConfig() {
        viewModelScope.launch {
            repo.saveConfig(_config.value)
            _saveMessage.value = "Configuration saved. Restart service to apply."
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            repo.resetToDefaults()
            _config.value = AppConfig.DEFAULT
            _saveMessage.value = "Configuration reset to defaults."
        }
    }

    fun clearSaveMessage() {
        _saveMessage.value = null
    }
}
