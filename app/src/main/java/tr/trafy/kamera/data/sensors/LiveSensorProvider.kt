package tr.trafy.kamera.data.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot of the data the live HUD widgets consume. Fields are nullable so
 * the overlay can fade a widget out (rather than show "0") when a signal
 * isn't available yet — typical at app start before the first GPS fix.
 *
 * - [speedKmh]: ground speed reported by GPS, rounded to whole km/h.
 * - [altitudeM]: GPS altitude (rakım), rounded to whole metres.
 * - [headingDeg]: device azimuth from [SensorManager.TYPE_ROTATION_VECTOR],
 *   degrees clockwise from north. This is *device* heading, not car heading
 *   — fine for a fancy compass widget but not for nav.
 */
data class LiveSensorData(
    val speedKmh:      Int?    = null,
    val altitudeM:     Int?    = null,
    val headingDeg:    Float?  = null,
    /**
     * Longitudinal "G" — derivative of GPS ground speed, in units of g
     * (9.8 m/s²). Sign convention: positive = car accelerating (gaining
     * speed), negative = decelerating, ~0 = constant. We use GPS deltas
     * rather than the raw accelerometer because the latter depends on the
     * phone's mount orientation (landscape vs portrait vs random) which we
     * have no way to calibrate; GPS speed change is unambiguous.
     */
    val accelerationG: Float?  = null,
)

/**
 * Pulls live phone-sensor data for the in-app HUD overlay. Combines:
 *  - [LocationManager] (GPS provider): speed + altitude
 *  - [Sensor.TYPE_ROTATION_VECTOR]:    heading (more stable than the bare
 *    magnetometer; auto-fused with accelerometer + gyro by Android)
 *
 * No Play-services dependency — pure `LocationManager` so this works on
 * GMS-less devices too. Per-update jitter is fine for a HUD readout.
 *
 * Lifecycle: caller drives [start]/[stop]. Stopping unregisters listeners
 * and pauses GPS — important for battery once the user leaves the live
 * screen.
 */
class LiveSensorProvider(private val context: Context) {

    companion object { private const val TAG = "Trafy.LiveSensors" }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager   = context.getSystemService(Context.SENSOR_SERVICE)   as SensorManager
    private val rotationSensor  = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _data = MutableStateFlow(LiveSensorData())
    val data: StateFlow<LiveSensorData> = _data.asStateFlow()

    private var started = false

    // Per-update state for longitudinal-G derivation.
    private var lastSpeedMs: Float = Float.NaN
    private var lastSpeedAtNs: Long = 0L
    private var emaAccelG: Float = 0f

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            // Verbose per-fix log so the user can confirm the phone IS
            // receiving locations from the platform — indispensable when
            // testing indoors where speed/altitude/heading might all be
            // null even with a valid lat/lon fix.
            Log.i(
                TAG,
                "phone fix: lat=%.6f lon=%.6f acc=%.1fm spd=%s alt=%s hdg=%s provider=%s".format(
                    loc.latitude, loc.longitude,
                    if (loc.hasAccuracy()) loc.accuracy else -1f,
                    if (loc.hasSpeed())     "%.2fm/s".format(loc.speed)     else "—",
                    if (loc.hasAltitude())  "%.0fm".format(loc.altitude)    else "—",
                    if (loc.hasBearing())   "%.1f°".format(loc.bearing)     else "—",
                    loc.provider ?: "?",
                ),
            )
            val newSpeedMs = if (loc.hasSpeed()) loc.speed else Float.NaN
            val now = SystemClock.elapsedRealtimeNanos()

            // dV/dt → g. Skip the first fix (no previous speed) and skip
            // updates that are unrealistically close in time (would
            // produce a divide-by-near-zero spike). 0.20 s lower bound is
            // generous — typical providers fire at 1 Hz.
            val accelG: Float? = if (!newSpeedMs.isNaN() && !lastSpeedMs.isNaN()) {
                val dt = (now - lastSpeedAtNs) / 1_000_000_000f
                if (dt >= 0.20f) {
                    val raw = (newSpeedMs - lastSpeedMs) / dt / 9.80665f
                    // EMA smoothing — at 1 Hz updates a raw delta jumps too
                    // visibly between frames. 0.35 trades off responsiveness
                    // vs jitter; tune if it feels laggy.
                    emaAccelG = emaAccelG + (raw.coerceIn(-2f, 2f) - emaAccelG) * 0.35f
                    emaAccelG
                } else emaAccelG
            } else null

            if (!newSpeedMs.isNaN()) {
                lastSpeedMs = newSpeedMs
                lastSpeedAtNs = now
            }

            val speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt().coerceIn(0, 300) else null
            val altitudeM = if (loc.hasAltitude()) loc.altitude.toInt() else null
            _data.value = _data.value.copy(
                speedKmh      = speedKmh,
                altitudeM     = altitudeM,
                accelerationG = accelG,
            )
        }
        @Deprecated("Required by API <29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String)  {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            val rotMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            val orient = FloatArray(3)
            SensorManager.getOrientation(rotMatrix, orient)
            // Azimuth: bearing relative to magnetic north, radians, [-π, π].
            val azimuthDeg = Math.toDegrees(orient[0].toDouble()).toFloat()
            val heading = ((azimuthDeg % 360f) + 360f) % 360f
            _data.value = _data.value.copy(headingDeg = heading)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        if (started) return
        started = true
        if (hasFineLocationPermission()) {
            try {
                // Update every 1 s / 1 m. Faster than typical nav apps but the
                // HUD reads stale just feels weird; smoothing happens at the
                // widget level (no need for low-pass here).
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 1f, locListener, Looper.getMainLooper(),
                )
                // Seed from last known fix so the HUD has something at start.
                val last = try {
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                } catch (_: Throwable) { null }
                last?.let(locListener::onLocationChanged)
            } catch (t: Throwable) {
                Log.w(TAG, "GPS subscribe failed: ${t.message}")
            }
        } else {
            Log.i(TAG, "ACCESS_FINE_LOCATION not granted — HUD will show heading only")
        }
        if (rotationSensor != null) {
            sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            Log.w(TAG, "No rotation-vector sensor on this device — compass disabled")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { locationManager.removeUpdates(locListener) }
        sensorManager.unregisterListener(sensorListener)
    }

    private fun hasFineLocationPermission(): Boolean = ContextCompat
        .checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
