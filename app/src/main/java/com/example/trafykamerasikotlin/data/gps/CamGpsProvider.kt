package com.example.trafykamerasikotlin.data.gps

import android.content.Context
import com.example.trafykamerasikotlin.data.model.MediaFile
import com.example.trafykamerasikotlin.data.sensors.GpsTrack

/**
 * Per-chipset adapter that returns a [GpsTrack] for a recorded clip, when
 * the cam itself has GPS data (built-in or pluggable module).
 *
 * Implementations are simple suspend functions wrapping whatever protocol
 * the chipset uses to hand us GPS — HiSilicon's `/sd//GPSdata/<basename>.TXT`
 * sidecar, Easytech's HTTP CGI (TBD), Allwinner's relay RPC (TBD), etc.
 *
 * The contract is: try to find data for [file], return [GpsTrack] on hit,
 * return null on miss. Null is the "this cam has no GPS for this clip"
 * signal — the caller falls back to the phone log (and ultimately to a
 * synthetic estimator).
 */
interface CamGpsProvider {
    suspend fun trackFor(
        file: MediaFile,
        deviceIp: String,
        context: Context,
    ): GpsTrack?
}

/** Stub that always returns null. Used for chipsets with no GPS module. */
object NullCamGpsProvider : CamGpsProvider {
    override suspend fun trackFor(
        file: MediaFile, deviceIp: String, context: Context,
    ): GpsTrack? = null
}
