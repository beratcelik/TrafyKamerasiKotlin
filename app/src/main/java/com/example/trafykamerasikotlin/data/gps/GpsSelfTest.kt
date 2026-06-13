package com.example.trafykamerasikotlin.data.gps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.trafykamerasikotlin.data.model.ChipsetProtocol
import com.example.trafykamerasikotlin.data.network.DashcamHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-shot diagnostic — probes both potential GPS sources right now and
 * returns a human-readable summary. Used by the "Konum testi" button so
 * the user can verify both pipes are healthy without having to download +
 * process a clip.
 *
 * Phone side: reads `getLastKnownLocation` (no listener subscription, so
 * we don't wait for a fresh fix). If no fix is cached yet, the result
 * line says so honestly.
 *
 * Cam side: probes the chipset-specific candidate URLs. For unknown-
 * protocol cams (no provider) we log "no cam-side support."
 */
object GpsSelfTest {

    private const val TAG = "Trafy.GpsSelfTest"

    data class Result(
        val phoneLine: String,
        val camLines:  List<String>,
        val anyHit:    Boolean,
    )

    suspend fun run(
        context: Context,
        protocol: ChipsetProtocol?,
        sampleClipBasename: String?,
    ): Result = withContext(Dispatchers.IO) {
        val phoneLine = probePhone(context)
        val camLines  = probeCam(context, protocol, sampleClipBasename)
        val anyHit = phoneLine.contains("✓") || camLines.any { it.contains("✓") }

        Log.i(TAG, "── Konum testi ──")
        Log.i(TAG, "  PHONE: $phoneLine")
        camLines.forEach { Log.i(TAG, "  CAM:   $it") }
        Log.i(TAG, "── done (anyHit=$anyHit) ──")
        Result(phoneLine, camLines, anyHit)
    }

    private fun probePhone(context: Context): String {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return "✗ ACCESS_FINE_LOCATION izni yok"

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return "✗ LocationManager bulunamadı"

        val providers = lm.getProviders(true)
        if (providers.isEmpty()) return "✗ Aktif konum sağlayıcı yok"

        val gpsOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val netOn = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        // getLastKnownLocation is cheap and non-blocking. It returns the
        // platform's cached fix, which is null when the GPS hardware has
        // never produced one in this boot (typical indoors).
        val last = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: if (netOn) lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null
        } catch (_: SecurityException) { null }

        if (last == null) {
            return "△ GPS:$gpsOn NET:$netOn — son fix yok (kapalı mekan?)"
        }
        return "✓ lat=%.6f lon=%.6f acc=%.1fm spd=%s alt=%s provider=%s yaş=%ds".format(
            last.latitude, last.longitude,
            if (last.hasAccuracy()) last.accuracy else -1f,
            if (last.hasSpeed())    "%.2fm/s".format(last.speed)   else "—",
            if (last.hasAltitude()) "%.0fm".format(last.altitude)  else "—",
            last.provider ?: "?",
            (System.currentTimeMillis() - last.time) / 1000,
        )
    }

    private suspend fun probeCam(
        context: Context,
        protocol: ChipsetProtocol?,
        sampleBasename: String?,
    ): List<String> {
        if (protocol == null) return listOf("△ Cam bağlı değil")
        val ip = protocol.deviceIp
        val base = sampleBasename ?: "TEST_PROBE"
        return when (protocol) {
            ChipsetProtocol.EEASYTECH -> probeEasytech(context, ip, base)
            ChipsetProtocol.HI_DVR    -> probeHiDvr(ip, base)
            ChipsetProtocol.ALLWINNER_V853 ->
                listOf("△ Allwinner — RPC henüz keşfedilmedi (stub)")
            ChipsetProtocol.GENERALPLUS ->
                listOf("△ GeneralPlus — GPS donanımı bulunmuyor")
            else ->
                listOf("△ Cam protokolü için GPS desteği yok")
        }
    }

    private suspend fun probeEasytech(context: Context, ip: String, base: String): List<String> {
        // Discovery sweep: enumerate the SD root via getfilelist, then probe
        // the most plausible sidecar URLs. GoLook's static URL inventory
        // contains NO Easytech-specific GPS path (all GPS code is for
        // HiSilicon at 192.168.0.1), so we're flying blind here — the
        // results help us decide whether the cam serves GPS as a sidecar
        // at all, or only burns it onto video pixels as an OSD.
        val lines = ArrayList<String>(8)

        // 1. Confirm cam is reachable + which gps value the cam reports.
        val paramAll = DashcamHttpClient.getQuick(
            "http://$ip/app/getparamvalue?param=all", timeoutMs = 2_500L
        )
        if (paramAll != null) {
            val gpsKv = Regex(""""name":"gps"\s*,\s*"value":\s*(\d+)""")
                .find(paramAll)?.groupValues?.getOrNull(1)
            lines += if (gpsKv != null) "ℹ︎ cam param gps=$gpsKv"
                     else "ℹ︎ cam yanıt veriyor ama 'gps' alanı yok (modül takılı değil?)"
        } else {
            lines += "✗ cam yanıt vermiyor — bağlantıyı kontrol et"
            return lines
        }

        // 2. List the SD card root to see what folders the firmware exposes.
        val rootList = DashcamHttpClient.getQuick(
            "http://$ip/app/getfilelist?folder=/&start=0&end=20", timeoutMs = 2_500L
        )
        if (rootList != null) {
            lines += "ℹ︎ /getfilelist?folder=/ → ${rootList.take(160).replace('\n', ' ')}"
        } else {
            lines += "△ /getfilelist?folder=/ yanıt yok"
        }

        // Note: an earlier version probed TCP :5000 directly here. On at
        // least one Trafy Dos Pro unit that raw connect caused the cam
        // firmware to reboot — its TCP listener apparently expects a
        // specific handshake the OEM app sends, and silence wedges it.
        // We've removed the probe to avoid further reboots. Keep the
        // information line so the user knows the channel exists but is
        // currently considered unsafe to talk to without the handshake.
        lines += "△ tcp://$ip:5000 (cam reboots on raw probe — handshake unknown)"

        // 4. HTTP sidecar URL variants — kept for completeness, but
        //    these all timed out in earlier runs because Easytech firmware
        //    does not expose GPS over HTTP CGI. Probing only adds 7s if
        //    they fail, so still useful as a sanity check.
        val urls = listOf(
            "http://$ip/upload/mnt/sdcard/GPSdata/$base.TXT",
            "http://$ip/upload/mnt/sdcard/$base.gps",
            "http://$ip/app/getgps?file=$base",
        )
        urls.forEach { url ->
            val body = DashcamHttpClient.getQuick(url, timeoutMs = 2_500L)
            lines += describeBody(url, body)
        }
        return lines
    }

    /**
     * One-shot TCP probe of the push-GPS channel. Connect, read for
     * [READ_WINDOW_MS], then close — reports connect success/failure +
     * a preview of the first few bytes received (if any).
     *
     * This is THE definitive check: if the cam answers, the push channel
     * is the right mechanism. If `connection refused` / timeout, either
     * the cam doesn't speak it (different firmware variant) or the GPS
     * module isn't fully initialised yet.
     */
    private suspend fun probeTcpPush(ip: String, port: Int, network: android.net.Network?): String {
        val urlLabel = "tcp://$ip:$port" + (if (network == null) " (default network)" else "")
        val factory = network?.socketFactory ?: javax.net.SocketFactory.getDefault()
        val sock = try {
            factory.createSocket().apply {
                tcpNoDelay = true
                soTimeout  = READ_WINDOW_MS
                connect(java.net.InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
            }
        } catch (t: Throwable) {
            return "✗ $urlLabel — ${t.javaClass.simpleName}: ${t.message}"
        }
        try {
            // Best-effort read for a short window. Cam pushes ~1 msg/s; if
            // the link is healthy we should see at least one line.
            val deadline = System.currentTimeMillis() + READ_WINDOW_MS
            val buf = ByteArray(2048)
            val sb = StringBuilder()
            val input = sock.getInputStream()
            while (System.currentTimeMillis() < deadline && sb.length < 1500) {
                val n = try { input.read(buf) } catch (_: java.net.SocketTimeoutException) { -2 }
                if (n == -1) break
                if (n == -2) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
            }
            val text = sb.toString().replace('\n', ' ').take(200)
            return if (text.isEmpty())
                "△ $urlLabel — bağlandı ama veri yok (modül henüz hazır değil)"
            else
                "✓ $urlLabel — preview=\"$text\""
        } finally {
            runCatching { sock.close() }
        }
    }

    private const val READ_WINDOW_MS = 3_500
    private const val CONNECT_TIMEOUT_MS = 2_500

    private suspend fun probeHiDvr(ip: String, base: String): List<String> {
        val urls = listOf(
            "http://$ip/sd//GPSdata/$base.TXT",
            "http://$ip/sd//GPSdata/$base.txt",
        )
        return urls.map { url ->
            val body = DashcamHttpClient.getQuick(url, timeoutMs = 2_500L)
            describeBody(url, body)
        }
    }

    private fun describeBody(url: String, body: String?): String {
        if (body == null)    return "✗ $url (yanıt yok / zaman aşımı)"
        if (body.isBlank())  return "✗ $url (boş gövde)"
        val looksNmea = body.lineSequence().any { it.startsWith("\$") && it.length > 6 }
        val preview = body.take(80).replace('\n', ' ')
        return if (looksNmea)
            "✓ $url (${body.length}B, NMEA) preview=\"$preview\""
        else
            "△ $url (${body.length}B, NMEA değil) preview=\"$preview\""
    }
}
