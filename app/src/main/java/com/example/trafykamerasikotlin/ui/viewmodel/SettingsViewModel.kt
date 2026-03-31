package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.model.SettingItem
import com.example.trafykamerasikotlin.data.settings.HiDvrSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class SettingsUiState {
    data object NotConnected : SettingsUiState()
    data object Loading : SettingsUiState()
    data class Loaded(val items: List<SettingItem>) : SettingsUiState()
    data class Applying(val items: List<SettingItem>) : SettingsUiState()
    data class Error(val message: String) : SettingsUiState()
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "Trafy.SettingsVM"
    }

    private val repository = HiDvrSettingsRepository()

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.NotConnected)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var loadedForIp: String? = null

    /**
     * Loads all settings from the camera. Idempotent — won't reload if already
     * loaded for the same IP (unless an error occurred).
     */
    fun load(deviceIp: String) {
        if (loadedForIp == deviceIp && _uiState.value is SettingsUiState.Loaded) {
            Log.d(TAG, "Already loaded for $deviceIp — skipping")
            return
        }
        loadedForIp = deviceIp
        _uiState.update { SettingsUiState.Loading }
        Log.i(TAG, "Loading settings for $deviceIp")
        viewModelScope.launch {
            val items = repository.fetchAll(deviceIp)
            if (items != null) {
                Log.i(TAG, "Loaded ${items.size} settings")
                _uiState.update { SettingsUiState.Loaded(items) }
            } else {
                Log.e(TAG, "Failed to load settings")
                _uiState.update { SettingsUiState.Error("Could not load camera settings") }
            }
        }
    }

    /**
     * Applies a new value for [key]. Shows Applying state while in-flight,
     * then updates the item in-place on success or restores on failure.
     */
    fun apply(key: String, newValue: String) {
        val ip = loadedForIp ?: return
        val current = currentItems() ?: return
        Log.i(TAG, "Applying $key = $newValue")
        _uiState.update { SettingsUiState.Applying(current) }
        viewModelScope.launch {
            val success = repository.applySetting(ip, key, newValue)
            if (success) {
                val updated = current.map { item ->
                    if (item.key == key) {
                        val label = item.options.find { it.value == newValue }?.label ?: newValue
                        item.copy(currentValue = newValue, currentValueLabel = label)
                    } else item
                }
                Log.i(TAG, "Applied $key=$newValue successfully")
                _uiState.update { SettingsUiState.Loaded(updated) }
            } else {
                Log.e(TAG, "Failed to apply $key=$newValue")
                _uiState.update { SettingsUiState.Loaded(current) }
            }
        }
    }

    /** Forces a fresh reload from the camera (e.g. after an error). */
    fun reload(deviceIp: String) {
        loadedForIp = null
        load(deviceIp)
    }

    private fun currentItems(): List<SettingItem>? = when (val s = _uiState.value) {
        is SettingsUiState.Loaded   -> s.items
        is SettingsUiState.Applying -> s.items
        else                        -> null
    }
}
