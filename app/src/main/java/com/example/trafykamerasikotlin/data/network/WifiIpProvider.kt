package com.example.trafykamerasikotlin.data.network

import android.content.Context
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

class WifiIpProvider(private val context: Context) {

    companion object {
        private const val TAG = "Trafy.WifiIpProvider"
    }

    fun getClientIp(): String? {
        Log.d(TAG, "getClientIp() called")
        return try {
            val allInterfaces = NetworkInterface.getNetworkInterfaces()
            if (allInterfaces == null) {
                Log.e(TAG, "NetworkInterface.getNetworkInterfaces() returned null")
                return null
            }
            for (iface in allInterfaces.asSequence()) {
                Log.v(TAG, "Interface: name=${iface.name} isUp=${iface.isUp} isLoopback=${iface.isLoopback}")
                if (!iface.name.startsWith("wlan") || !iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.asSequence()) {
                    Log.v(TAG, "  Address: ${addr.hostAddress} isLoopback=${addr.isLoopbackAddress} type=${addr.javaClass.simpleName}")
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        Log.i(TAG, "Detected WiFi client IP: $ip (interface: ${iface.name})")
                        return ip
                    }
                }
            }
            Log.w(TAG, "No wlan IPv4 address found — phone not connected to WiFi or IP not assigned yet")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Exception reading NetworkInterface: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
