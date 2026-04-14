package com.example.trafykamerasikotlin.data.allwinner

import android.net.Network
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Process-wide holder for the Wi-Fi [Network] that Allwinner TCP sockets must bind to.
 *
 * Mirrors the same pattern used by `DashcamHttpClient` and `GeneralplusSession`: the
 * phone may still be on cellular data while the dashcam hotspot provides no internet,
 * so every outbound connection to the camera has to be explicitly routed through the
 * dashcam's Wi-Fi Network (API 29+). Without this binding, the socket resolves via the
 * default (cellular) route and never reaches 192.168.35.1.
 */
internal object AllwinnerNetwork {

    private const val TAG = "Trafy.AllwinnerNet"

    private const val CONNECT_TIMEOUT_MS = 4_000

    @Volatile private var boundNetwork: Network? = null

    fun bindToNetwork(network: Network?) {
        boundNetwork = network
        Log.i(TAG, "bindToNetwork: ${if (network != null) "bound to $network" else "unbound"}")
    }

    /** Opens a TCP socket to [ip]:[port], routed through the bound Wi-Fi Network if set. */
    fun createSocket(ip: String, port: Int): Socket {
        val net = boundNetwork
        val socket = if (net != null) net.socketFactory.createSocket() else Socket()
        socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
        return socket
    }
}
