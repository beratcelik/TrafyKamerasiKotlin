package tr.trafy.kamera.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import tr.trafy.kamera.data.allwinner.AllwinnerSession
import tr.trafy.kamera.data.allwinner.AllwinnerSessionHolder
import tr.trafy.kamera.data.model.ChipsetProtocol
import tr.trafy.kamera.data.model.DeviceInfo
import tr.trafy.kamera.data.network.DashcamHttpClient
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
                    } else {
                        // Old/"new Hi" firmware without cammenu.xml (e.g. Trafy Dos):
                        // dump device attributes plus value + capability per key.
                        Log.i(TAG, "No cammenu.xml — dumping per-key CGIs")
                        hiDvrPerKeyDump(ip)
                    }
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

            _state.value = if (!entries.isNullOrEmpty()) {
                Log.i(TAG, "Raw dump success: ${entries.size} entry/entries")
                RawSettingsState.Success(entries)
            } else {
                Log.e(TAG, "Raw dump failed — null response")
                RawSettingsState.Error
            }
        }
    }

    /**
     * Per-key raw dump for HiDVR firmware that serves no cammenu.xml. Sequential
     * on purpose: the cam's thttpd handles one request at a time comfortably.
     * Returns null only if the cam answered nothing at all.
     */
    private suspend fun hiDvrPerKeyDump(ip: String): List<RawEntry>? {
        val base = "http://$ip/cgi-bin/hisnet"
        val camKeys = setOf("MEDIAMODE", "ENC_PAYLOAD_TYPE", "Rec_Split_Time", "FLIP", "MIRROR")
        val keys = listOf(
            "MEDIAMODE", "ENC_PAYLOAD_TYPE", "AUDIO", "Rec_Split_Time",
            "GSR_SENSITIVITY", "GSR_PARKING", "LOW_FPS_REC", "LOW_FPS_REC_TIME",
            "LOW_POWER_PROTECT", "VOLUME", "FLIP", "MIRROR",
            "MD_SENSITIVITY", "SCREEN_DORMANT", "ANTIFLICKER",
        )
        val entries = mutableListOf<RawEntry>()
        "$base/getdeviceattr.cgi?".let { url ->
            DashcamHttpClient.get(url)?.let { entries += RawEntry("Device Attributes", url, it) }
        }
        for (key in keys) {
            val (valueUrl, capUrl) = if (key in camKeys) {
                "$base/getcamparam.cgi?&-workmode=NORM_REC&-type=$key" to
                    "$base/getcamparamcapability.cgi?&-workmode=NORM_REC&-type=$key"
            } else {
                "$base/getcommparam.cgi?&-type=$key" to
                    "$base/getcommparamcapability.cgi?&-type=$key"
            }
            val value = DashcamHttpClient.get(valueUrl)
            val cap = DashcamHttpClient.get(capUrl)
            if (value == null && cap == null) continue
            entries += RawEntry(
                label = key,
                url   = valueUrl,
                body  = "${value?.trim() ?: "<no answer>"}\n${cap?.trim() ?: "<no answer>"}",
            )
        }
        return entries.takeIf { it.isNotEmpty() }
    }
}
