package com.example.trafykamerasikotlin.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.model.SettingItem
import com.example.trafykamerasikotlin.data.settings.AllwinnerSettingsRepository
import com.example.trafykamerasikotlin.data.settings.EeasytechSettingsRepository
import com.example.trafykamerasikotlin.data.settings.GeneralplusSettingsRepository
import com.example.trafykamerasikotlin.data.settings.HiDvrSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class ApnDialogState {
    data object Hidden : ApnDialogState()
    data class Loaded(val apn: String, val user: String, val password: String) : ApnDialogState()
    data object Saving : ApnDialogState()
    data object Error : ApnDialogState()
}

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
    data object Error : SettingsUiState()
}

/**
 * Action-feedback message kinds. SettingsScreen resolves each to a localized string.
 * [Raw] is a pass-through for device-sourced strings (HiDVR action responses) which
 * arrive already formatted from the camera and should not be translated.
 */
sealed class SettingsActionFeedback {
    data object FormatOk    : SettingsActionFeedback()
    data object ResetOk     : SettingsActionFeedback()
    data object GenericOk   : SettingsActionFeedback()
    data object GenericFail : SettingsActionFeedback()
    /** Both initial attempt and the auto-retry failed — likely a connectivity drop. */
    data object NetworkUnavailable : SettingsActionFeedback()
    /** Camera ACKed the write but verify-readback shows it didn't take — firmware silently rejecting. */
    data object UnsupportedByCamera : SettingsActionFeedback()
    data object WifiSaved   : SettingsActionFeedback()
    data object ApnSaved    : SettingsActionFeedback()
    data class  Raw(val message: String) : SettingsActionFeedback()
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "Trafy.SettingsVM"
        // Wait this long after a GP action fails before retrying. The dashcam
        // auto-reconnect path is ~1.5 s + Wi-Fi reattach ~3-5 s + DHCP settle
        // ~1.5 s, so 6 s comfortably covers a typical recovery.
        private const val GP_RETRY_DELAY_MS = 6_000L
    }

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.NotConnected)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Non-null while an action result (or error) should be shown to the user. */
    private val _actionFeedback = MutableStateFlow<SettingsActionFeedback?>(null)
    val actionFeedback: StateFlow<SettingsActionFeedback?> = _actionFeedback.asStateFlow()

    private val _wifiDialog = MutableStateFlow<WifiDialogState>(WifiDialogState.Hidden)
    val wifiDialog: StateFlow<WifiDialogState> = _wifiDialog.asStateFlow()

    private val _apnDialog = MutableStateFlow<ApnDialogState>(ApnDialogState.Hidden)
    val apnDialog: StateFlow<ApnDialogState> = _apnDialog.asStateFlow()

    private var loadedForDevice: DeviceInfo? = null
    private var eeasyRepo: EeasytechSettingsRepository? = null
    private var hiDvrRepo: HiDvrSettingsRepository? = null
    private var generalplusRepo: GeneralplusSettingsRepository? = null
    private var allwinnerRepo: AllwinnerSettingsRepository? = null

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
                _uiState.update { SettingsUiState.Error }
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
            val result = if (device.protocol == ChipsetProtocol.GENERALPLUS) {
                applyGpWithRetry(device, key, newValue)
            } else {
                if (applySetting(device, key, newValue)) ApplyResult.SUCCESS
                else ApplyResult.GENERIC_FAIL
            }
            if (result == ApplyResult.SUCCESS) {
                val updated = current.map { item ->
                    if (item.key == key) {
                        val label = item.options.find { it.value == newValue }?.label ?: newValue
                        item.copy(currentValue = newValue, currentValueLabel = label)
                    } else item
                }
                Log.i(TAG, "Applied $key=$newValue successfully")
                _uiState.update { SettingsUiState.Loaded(updated) }
            } else {
                Log.e(TAG, "Failed to apply $key=$newValue (result=$result)")
                _uiState.update { SettingsUiState.Loaded(current) }
                _actionFeedback.update {
                    when (result) {
                        ApplyResult.NETWORK_UNAVAILABLE  -> SettingsActionFeedback.NetworkUnavailable
                        ApplyResult.UNSUPPORTED          -> SettingsActionFeedback.UnsupportedByCamera
                        else                              -> SettingsActionFeedback.GenericFail
                    }
                }
            }
        }
    }

    /**
     * Executes an action-type item (Format SD Card, Factory Reset, etc.).
     * Shows Applying overlay while in-flight.
     */
    fun triggerAction(key: String) {
        val device = loadedForDevice ?: return
        val current = currentItems() ?: return
        _uiState.update { SettingsUiState.Applying(current) }
        viewModelScope.launch {
            when (device.protocol) {
                ChipsetProtocol.GENERALPLUS -> {
                    val result = applyWithGpRetry(device) {
                        getGeneralplusRepo().triggerAction(device.protocol.deviceIp, key)
                    }
                    _uiState.update { SettingsUiState.Loaded(current) }
                    _actionFeedback.update {
                        when (result) {
                            ApplyResult.SUCCESS -> when (key) {
                                "format"     -> SettingsActionFeedback.FormatOk
                                "reset.cgi?" -> SettingsActionFeedback.ResetOk
                                else          -> SettingsActionFeedback.GenericOk
                            }
                            ApplyResult.NETWORK_UNAVAILABLE -> SettingsActionFeedback.NetworkUnavailable
                            ApplyResult.UNSUPPORTED         -> SettingsActionFeedback.UnsupportedByCamera
                            ApplyResult.GENERIC_FAIL        -> SettingsActionFeedback.GenericFail
                        }
                    }
                }
                ChipsetProtocol.ALLWINNER_V853 -> {
                    _uiState.update { SettingsUiState.Loaded(current) }
                }
                else -> {
                    val result = getHiDvrRepo().executeAction(device.protocol.deviceIp, key)
                    _uiState.update { SettingsUiState.Loaded(current) }
                    _actionFeedback.update {
                        if (result != null) {
                            val trimmed = result.trim()
                            when {
                                key == "format"     -> SettingsActionFeedback.FormatOk
                                key == "reset.cgi?" -> SettingsActionFeedback.ResetOk
                                trimmed.isEmpty()   -> SettingsActionFeedback.GenericOk
                                else                -> SettingsActionFeedback.Raw(trimmed)
                            }
                        } else SettingsActionFeedback.GenericFail
                    }
                }
            }
        }
    }

    fun clearActionFeedback() { _actionFeedback.update { null } }

    /** Opens the Wi-Fi password dialog and fetches current settings from the camera. */
    fun openWifiSettings() {
        val device = loadedForDevice ?: return
        _wifiDialog.update { WifiDialogState.Loading }
        viewModelScope.launch {
            when (device.protocol) {
                ChipsetProtocol.GENERALPLUS -> {
                    val settings = getGeneralplusRepo().getWifiSettings(device.protocol.deviceIp)
                    _wifiDialog.update {
                        if (settings != null) WifiDialogState.Loaded(settings.ssid, settings.password)
                        else WifiDialogState.Error
                    }
                }
                ChipsetProtocol.ALLWINNER_V853 -> {
                    val pair = getAllwinnerRepo().getWifiSettingsFromCache()
                    _wifiDialog.update {
                        if (pair != null) WifiDialogState.Loaded(pair.first, pair.second)
                        else WifiDialogState.Error
                    }
                }
                else -> {
                    val settings = getHiDvrRepo().getWifiSettings(device.protocol.deviceIp)
                    _wifiDialog.update {
                        if (settings != null) WifiDialogState.Loaded(settings.ssid, settings.password)
                        else WifiDialogState.Error
                    }
                }
            }
        }
    }

    /** Saves a new password to the camera. */
    fun saveWifiPassword(ssid: String, newPassword: String) {
        val device = loadedForDevice ?: return
        val current = (_wifiDialog.value as? WifiDialogState.Loaded) ?: return
        _wifiDialog.update { WifiDialogState.Saving }
        viewModelScope.launch {
            val ok = when (device.protocol) {
                ChipsetProtocol.GENERALPLUS ->
                    getGeneralplusRepo().setWifiPassword(device.protocol.deviceIp, newPassword)
                ChipsetProtocol.ALLWINNER_V853 ->
                    getAllwinnerRepo().setWifiAp(device.protocol.deviceIp, ssid, newPassword)
                else ->
                    getHiDvrRepo().setWifiPassword(device.protocol.deviceIp, ssid, newPassword)
            }
            _wifiDialog.update {
                if (ok) WifiDialogState.Loaded(current.ssid, newPassword)
                else WifiDialogState.Error
            }
            if (ok) _actionFeedback.update { SettingsActionFeedback.WifiSaved }
        }
    }

    /** Opens the APN configuration dialog (Allwinner only). */
    fun openApnDialog() {
        val device = loadedForDevice ?: return
        if (device.protocol != ChipsetProtocol.ALLWINNER_V853) return
        val (apn, user, pass) = getAllwinnerRepo().getApnFromCache()
        _apnDialog.update { ApnDialogState.Loaded(apn, user, pass) }
    }

    /** Saves new APN settings to the camera (Allwinner only). */
    fun saveApn(apn: String, user: String, password: String) {
        val device = loadedForDevice ?: return
        if (device.protocol != ChipsetProtocol.ALLWINNER_V853) return
        _apnDialog.update { ApnDialogState.Saving }
        viewModelScope.launch {
            val ok = getAllwinnerRepo().setApn(device.protocol.deviceIp, apn, user, password)
            _apnDialog.update {
                if (ok) ApnDialogState.Loaded(apn, user, password)
                else ApnDialogState.Error
            }
            if (ok) _actionFeedback.update { SettingsActionFeedback.ApnSaved }
        }
    }

    fun dismissApnDialog() { _apnDialog.update { ApnDialogState.Hidden } }

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
        when (device.protocol) {
            ChipsetProtocol.EEASYTECH      -> getEeasyRepo().fetchAll(device.protocol.deviceIp)
            ChipsetProtocol.GENERALPLUS    -> getGeneralplusRepo().fetchAll(device.protocol.deviceIp)
            ChipsetProtocol.ALLWINNER_V853 -> getAllwinnerRepo().fetchAll(device.protocol.deviceIp)
            else                           -> getHiDvrRepo().fetchAll(device.protocol.deviceIp)
        }

    /** Result of a GP settings call, fine-grained enough to drive the user-facing feedback. */
    private enum class ApplyResult { SUCCESS, NETWORK_UNAVAILABLE, UNSUPPORTED, GENERIC_FAIL }

    /**
     * GP apply with one automatic retry. Only retries connection-class
     * failures — a firmware refusal (NAK or verify mismatch) won't change
     * by retrying, so we surface it as [ApplyResult.UNSUPPORTED] right away.
     */
    private suspend fun applyGpWithRetry(
        device: DeviceInfo,
        key: String,
        value: String,
    ): ApplyResult {
        val first = getGeneralplusRepo().applySettingWithOutcome(device.protocol.deviceIp, key, value)
        when (first) {
            GeneralplusSettingsRepository.ApplyOutcome.SUCCESS         -> return ApplyResult.SUCCESS
            GeneralplusSettingsRepository.ApplyOutcome.NAK,
            GeneralplusSettingsRepository.ApplyOutcome.VERIFY_FAILED   -> return ApplyResult.UNSUPPORTED
            GeneralplusSettingsRepository.ApplyOutcome.NO_CONNECTION   -> { /* fall through to retry */ }
        }
        Log.w(TAG, "GP apply: connection error — waiting for auto-reconnect then retrying once")
        kotlinx.coroutines.delay(GP_RETRY_DELAY_MS)
        val second = getGeneralplusRepo().applySettingWithOutcome(device.protocol.deviceIp, key, value)
        return when (second) {
            GeneralplusSettingsRepository.ApplyOutcome.SUCCESS         -> ApplyResult.SUCCESS
            GeneralplusSettingsRepository.ApplyOutcome.NAK,
            GeneralplusSettingsRepository.ApplyOutcome.VERIFY_FAILED   -> ApplyResult.UNSUPPORTED
            GeneralplusSettingsRepository.ApplyOutcome.NO_CONNECTION   -> ApplyResult.NETWORK_UNAVAILABLE
        }
    }

    /**
     * Wrapper that retries a GP triggerAction once on failure. triggerAction
     * still uses Boolean return — for Format/Reset we can't easily distinguish
     * connection vs cam-rejected, so we keep the existing retry-on-false logic.
     */
    private suspend fun applyWithGpRetry(
        device: DeviceInfo,
        action: suspend () -> Boolean,
    ): ApplyResult {
        val first = action()
        if (first) return ApplyResult.SUCCESS
        if (device.protocol != ChipsetProtocol.GENERALPLUS) return ApplyResult.GENERIC_FAIL
        Log.w(TAG, "GP action failed — waiting for auto-reconnect then retrying once")
        kotlinx.coroutines.delay(GP_RETRY_DELAY_MS)
        val second = action()
        return if (second) ApplyResult.SUCCESS else ApplyResult.NETWORK_UNAVAILABLE
    }

    private suspend fun applySetting(device: DeviceInfo, key: String, value: String): Boolean =
        when (device.protocol) {
            ChipsetProtocol.EEASYTECH      -> getEeasyRepo().applySetting(device.protocol.deviceIp, key, value)
            ChipsetProtocol.GENERALPLUS    -> getGeneralplusRepo().applySetting(device.protocol.deviceIp, key, value)
            ChipsetProtocol.ALLWINNER_V853 -> getAllwinnerRepo().applySetting(device.protocol.deviceIp, key, value)
            else                           -> getHiDvrRepo().applySetting(device.protocol.deviceIp, key, value)
        }

    private fun getEeasyRepo(): EeasytechSettingsRepository =
        eeasyRepo ?: EeasytechSettingsRepository().also { eeasyRepo = it }

    private fun getHiDvrRepo(): HiDvrSettingsRepository =
        hiDvrRepo ?: HiDvrSettingsRepository(getApplication()).also { hiDvrRepo = it }

    private fun getGeneralplusRepo(): GeneralplusSettingsRepository =
        generalplusRepo ?: GeneralplusSettingsRepository(getApplication()).also { generalplusRepo = it }

    private fun getAllwinnerRepo(): AllwinnerSettingsRepository =
        allwinnerRepo ?: AllwinnerSettingsRepository().also { allwinnerRepo = it }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun currentItems(): List<SettingItem>? = when (val s = _uiState.value) {
        is SettingsUiState.Loaded   -> s.items
        is SettingsUiState.Applying -> s.items
        else                        -> null
    }
}
