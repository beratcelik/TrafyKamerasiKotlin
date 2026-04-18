package com.example.trafykamerasikotlin.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trafykamerasikotlin.data.allwinner.AllwinnerSession
import com.example.trafykamerasikotlin.data.allwinner.AllwinnerSessionHolder
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.model.DeviceInfo
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class RawSettingsState {
    object Idle : RawSettingsState()
    object Loading : RawSettingsState()
    data class Success(val entries: List<RawEntry>) : RawSettingsState()
    object Error : RawSettingsState()
}

data class RawEntry(
    val label: String,
    val url: String,
    val body: String,
)

class RawSettingsViewModel : ViewModel() {

    companion object {
        private const val TAG = "Trafy.RawSettings"
    }

    private val _state = MutableStateFlow<RawSettingsState>(RawSettingsState.Idle)
    val state: StateFlow<RawSettingsState> = _state

    fun load(device: DeviceInfo) {
        _state.value = RawSettingsState.Loading
        viewModelScope.launch {
            val ip = device.protocol.deviceIp
            val entries = when (device.protocol) {
                ChipsetProtocol.HI_DVR -> {
                    val url = "http://$ip/app/bin/cammenu.xml"
                    Log.i(TAG, "Fetching HiDVR: $url")
                    val body = DashcamHttpClient.get(url)
                    if (body != null) {
                        listOf(RawEntry("Settings Menu XML", url, body))
                    } else null
                }
                ChipsetProtocol.EEASYTECH -> {
                    val urlItems  = "http://$ip/app/getparamitems?param=all"
                    val urlValues = "http://$ip/app/getparamvalue?param=all"
                    Log.i(TAG, "Fetching Easytech: $urlItems + $urlValues")
                    val itemsDeferred  = async { DashcamHttpClient.get(urlItems) }
                    val valuesDeferred = async { DashcamHttpClient.get(urlValues) }
                    val items  = itemsDeferred.await()
                    val values = valuesDeferred.await()
                    if (items != null && values != null) {
                        listOf(
                            RawEntry("Parameter Items (options)", urlItems,  items),
                            RawEntry("Current Values",            urlValues, values),
                        )
                    } else null
                }
                ChipsetProtocol.ALLWINNER_V853 -> {
                    val session = AllwinnerSessionHolder.requireAlive(ip)
                    if (session == null) {
                        Log.e(TAG, "No Allwinner session available @ $ip")
                        null
                    } else {
                        val blob = session.getSettings()
                        if (blob != null) {
                            listOf(
                                RawEntry(
                                    label = "relay:getsettings",
                                    url   = "tcp://$ip:8000",
                                    body  = blob.toString(2),
                                )
                            )
                        } else null
                    }
                }
                ChipsetProtocol.GENERALPLUS -> {
                    listOf(
                        RawEntry(
                            label = "Protocol Info",
                            url   = "tcp://$ip:8081",
                            body  = """
                                Chipset:  GeneralPlus
                                Protocol: GPSOCKET (Binary TCP)
                                IP:       $ip
                                Port:     8081 (TCP)

                                Packet format (command):
                                  [8 B] "GPSOCKET" signature
                                  [1 B] Type  (0x01=CMD, 0x02=ACK, 0x03=NAK)
                                  [1 B] CMDIndex (sequence)
                                  [1 B] Mode
                                  [1 B] CMDID
                                  [4 B] optional param (extended commands)

                                Response adds:
                                  [4 B] DataSize (little-endian)
                                  [N B] payload

                                Connection handshake:
                                  → AuthDevice (CMDID=0x05, token=77 07 8C 12)
                                  ← ACK (Type=0x02)

                                RTSP stream:
                                  rtsp://$ip:8080/?action=stream
                            """.trimIndent()
                        )
                    )
                }
                else -> {
                    // Other chipsets not yet mapped — return a placeholder
                    listOf(
                        RawEntry(
                            label = "Not supported",
                            url   = "—",
                            body  = "Raw dump is not implemented for ${device.protocol.displayName} yet."
                        )
                    )
                }
            }

            _state.value = if (entries != null) {
                Log.i(TAG, "Raw dump success: ${entries.size} entry/entries")
                RawSettingsState.Success(entries)
            } else {
                Log.e(TAG, "Raw dump failed — null response")
                RawSettingsState.Error
            }
        }
    }
}
