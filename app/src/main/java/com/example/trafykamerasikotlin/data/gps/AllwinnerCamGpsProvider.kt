package com.example.trafykamerasikotlin.data.gps

import android.content.Context
import android.util.Log
import com.example.trafykamerasikotlin.data.model.MediaFile
import com.example.trafykamerasikotlin.data.sensors.GpsTrack

/**
 * Stub until we reverse-engineer the relay RPC that returns Allwinner V853
 * per-clip GPS.
 *
 * What we know: the cam has a GPS module — `getsettings` already returns
 * `"features":"adapter,tf,lte,gps,mp"` plus `"fixed":0,"satellites":0`
 * fields. What we DON'T know yet: the relay message that retrieves
 * lat/lon/speed/heading for a recorded clip (or live state).
 *
 * **Investigation plan** (deferred):
 *  1. Locate the CloudSpirit APK (likely `/tmp/cloudspirit.apk` from the
 *     thumbnail investigation, or re-pull via `pm path`).
 *  2. `strings lib/arm64-v8a/libcloudspirit_native.so | grep -iE
 *     'gps|nmea|gprmc|gpsdata|gpsinfo'`.
 *  3. PCAP a CloudSpirit session opening a recorded clip with the cam's
 *     GPS module fixed; decode the relay traffic.
 *  4. If GPS is "live-only" (no per-clip historic API), fall back to
 *     polling `getsettings` while connected and merging into the phone
 *     log — same source-shape, different writer.
 */
object AllwinnerCamGpsProvider : CamGpsProvider {
    private const val TAG = "Trafy.AllwinnerCamGps"
    override suspend fun trackFor(
        file: MediaFile, deviceIp: String, context: Context,
    ): GpsTrack? {
        Log.d(TAG, "stub — Allwinner cam GPS not yet wired; falling back to phone log")
        return null
    }
}
