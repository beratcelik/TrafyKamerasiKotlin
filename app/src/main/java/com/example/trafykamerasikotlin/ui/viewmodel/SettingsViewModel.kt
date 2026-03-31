package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.model.SettingItem
import com.example.trafykamerasikotlin.data.settings.EeasytechSettingsRepository
import com.example.trafykamerasikotlin.data.settings.HiDvrSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class WifiDialogState {
    data object Hidden : WifiDialogState()
    data object Loading : WifiDialogState()
    data class Loaded(val ssid: String, val password: String) : WifiDialogState()
    data object Saving : WifiDialogState()
    data object Error : WifiDialogState()
}

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

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.NotConnected)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Non-null while an action result (or error) should be shown to the user. */
    private val _actionFeedback = MutableStateFlow<String?>(null)
    val actionFeedback: StateFlow<String?> = _actionFeedback.asStateFlow()

    private val _wifiDialog = MutableStateFlow<WifiDialogState>(WifiDialogState.Hidden)
    val wifiDialog: StateFlow<WifiDialogState> = _wifiDialog.asStateFlow()

    private var loadedForDevice: DeviceInfo? = null
    private var eeasyRepo: EeasytechSettingsRepository? = null
    private var hiDvrRepo: HiDvrSettingsRepository? = null

    /**
     * Loads all settings from the camera. Idempotent — won't reload if already
     * loaded for the same device (unless an error occurred).
     * Picks the correct repository based on [device.protocol].
     */
    fun load(device: DeviceInfo) {
        if (loadedForDevice == device && _uiState.value is SettingsUiState.Loaded) {
            Log.d(TAG, "Already loaded for ${device.protocol.deviceIp} — skipping")
            return
        }
        loadedForDevice = device
        _uiState.update { SettingsUiState.Loading }
        Log.i(TAG, "Loading settings for ${device.protocol} @ ${device.protocol.deviceIp}")
        viewModelScope.launch {
            val items = fetchAll(device)
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
        val device = loadedForDevice ?: return
        val current = currentItems() ?: return
        Log.i(TAG, "Applying $key = $newValue")
        _uiState.update { SettingsUiState.Applying(current) }
        viewModelScope.launch {
            val success = applySetting(device, key, newValue)
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

    /**
     * Executes an action-type item (Format SD Card, About Camera, etc.).
     * Only valid for HiDVR devices. Shows Applying overlay while in-flight.
     */
    fun triggerAction(key: String) {
        val device = loadedForDevice ?: return
        val current = currentItems() ?: return
        _uiState.update { SettingsUiState.Applying(current) }
        viewModelScope.launch {
            val result = getHiDvrRepo().executeAction(device.protocol.deviceIp, key)
            _uiState.update { SettingsUiState.Loaded(current) }
            _actionFeedback.update { result ?: "Action failed — check device connection." }
        }
    }

    fun clearActionFeedback() { _actionFeedback.update { null } }

    /** Opens the Wi-Fi password dialog and fetches current settings from the camera. */
    fun openWifiSettings() {
        val device = loadedForDevice ?: return
        _wifiDialog.update { WifiDialogState.Loading }
        viewModelScope.launch {
            val settings = getHiDvrRepo().getWifiSettings(device.protocol.deviceIp)
            _wifiDialog.update {
                if (settings != null) WifiDialogState.Loaded(settings.ssid, settings.password)
                else WifiDialogState.Error
            }
        }
    }

    /** Saves a new password to the camera. */
    fun saveWifiPassword(ssid: String, newPassword: String) {
        val device = loadedForDevice ?: return
        val current = (_wifiDialog.value as? WifiDialogState.Loaded) ?: return
        _wifiDialog.update { WifiDialogState.Saving }
        viewModelScope.launch {
            val ok = getHiDvrRepo().setWifiPassword(device.protocol.deviceIp, ssid, newPassword)
            _wifiDialog.update {
                if (ok) WifiDialogState.Loaded(current.ssid, newPassword)
                else WifiDialogState.Error
            }
            if (ok) _actionFeedback.update { "Wi-Fi password updated. Reconnect to apply the new password." }
        }
    }

    fun dismissWifiDialog() { _wifiDialog.update { WifiDialogState.Hidden } }

    /** Forces a fresh reload from the camera (e.g. after an error). */
    fun reload(device: DeviceInfo) {
        loadedForDevice = null
        load(device)
    }

    /**
     * Called when the user leaves the Settings screen.
     * For Easytech devices, tells the camera to exit settings mode.
     */
    fun onLeave() {
        val device = loadedForDevice ?: return
        if (device.protocol == ChipsetProtocol.EEASYTECH) {
            viewModelScope.launch {
                getEeasyRepo().exitSettings(device.protocol.deviceIp)
            }
        }
    }

    // ── Repository dispatch ────────────────────────────────────────────────

    private suspend fun fetchAll(device: DeviceInfo): List<SettingItem>? =
        if (device.protocol == ChipsetProtocol.EEASYTECH) {
            getEeasyRepo().fetchAll(device.protocol.deviceIp)
        } else {
            getHiDvrRepo().fetchAll(device.protocol.deviceIp)
        }

    private suspend fun applySetting(device: DeviceInfo, key: String, value: String): Boolean =
        if (device.protocol == ChipsetProtocol.EEASYTECH) {
            getEeasyRepo().applySetting(device.protocol.deviceIp, key, value)
        } else {
            getHiDvrRepo().applySetting(device.protocol.deviceIp, key, value)
        }

    private fun getEeasyRepo(): EeasytechSettingsRepository =
        eeasyRepo ?: EeasytechSettingsRepository().also { eeasyRepo = it }

    private fun getHiDvrRepo(): HiDvrSettingsRepository =
        hiDvrRepo ?: HiDvrSettingsRepository().also { hiDvrRepo = it }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun currentItems(): List<SettingItem>? = when (val s = _uiState.value) {
        is SettingsUiState.Loaded   -> s.items
        is SettingsUiState.Applying -> s.items
        else                        -> null
    }
}
