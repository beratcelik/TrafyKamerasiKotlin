package com.example.trafykamerasikotlin.data.sensors

/**
 * One GPS sample, normalised across all sources (phone-side log, cam-side
 * sidecar, cam-side live RPC). Fields are nullable so a source that only
 * carries a subset (e.g. NMEA `$GPRMC` doesn't include altitude) doesn't
 * have to fake values it doesn't know.
 *
 * Time is stored as epoch milliseconds in UTC — finer than the seconds
 * the camera APIs typically use, so we can sub-second align to the
 * microsecond-precision `presentationTimeUs` the burn-in encoder hands us.
 */
data class GpsFix(
    val tEpochMs:    Long,
    val lat:         Double?,
    val lon:         Double?,
    val speedMs:     Float?,
    val altitudeM:   Int?,
    val headingDeg:  Float?,
    val accelG:      Float?,
)
