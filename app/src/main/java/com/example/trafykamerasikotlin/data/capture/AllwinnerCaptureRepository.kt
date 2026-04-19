package com.example.trafykamerasikotlin.data.capture

import android.util.Log
import com.example.trafykamerasikotlin.data.allwinner.AllwinnerSession
import com.example.trafykamerasikotlin.data.allwinner.AllwinnerSessionHolder
import org.json.JSONObject

/** Outcome of a remote-capture call. */
data class AllwinnerCaptureResult(
    /** True if the device accepted the command (ret=0). */
    val ok: Boolean,
    /** Actual relay msg name that worked (e.g. `capturephoto`). Useful for logging + caching. */
    val command: String,
    /** Raw response JSON for callers that want to extract file metadata. */
    val response: JSONObject?,
    /** `ret` code returned by the device. -1 if we never got a response. */
    val ret: Int,
    /** List of filenames that appeared in the response, if any. */
    val files: List<String>,
)

/**
 * Remote-capture repository for Allwinner V853 (A19) — triggers the dashcam to take a
 * still photo or a short (~6 s) event video.
 *
 * Discovery status: the exact relay `msg` name is not visible in the OEM CloudSpirit
 * binary strings (TLS-pinned cloud API hid the live trigger). Dart method names in the
 * decompile are `capturePhoto` / `captureVideo`; the local protocol has historically
 * used all-lowercase joined names (`getvideos`, `setapn`, `sdinfo`, `setvol`), so our
 * top-bet msg names are `capturephoto` and `capturevideo`. This repository tries a
 * prioritized list of candidates and records which one works, so a single run against
 * the real device converges on the correct name.
 *
 * File side-effects are expected to show up under the SD-card browser:
 * filenames like `[F|B]YYYYMMDDhhmmss-<dur>-E.ts` (E flag = event, as opposed to the
 * `L` used by continuous loop recordings). Photos will land with a `.jpg` extension.
 */
class AllwinnerCaptureRepository {

    companion object {
        private const val TAG = "Trafy.AllwinnerCapture"

        // Candidates to probe, in descending likelihood. Once one returns ret==0 we
        // latch onto it for subsequent calls to skip the probe.
        private val PHOTO_CANDIDATES = listOf(
            "capturephoto",
            "capturepic",
            "snap",
            "snapshot",
            "takephoto",
            "takepic",
            "takesnap",
            "capturepicture",
            "snapphoto",
        )
        private val VIDEO_CANDIDATES = listOf(
            "capturevideo",
            "captureclip",
            "snapvideo",
            "takevideo",
            "takeclip",
            "recordclip",
            "recordevent",
            "eventrec",
            "eventclip",
        )
    }

    @Volatile private var lastGoodPhotoCmd: String? = null
    @Volatile private var lastGoodVideoCmd: String? = null

    /**
     * Triggers a still-photo capture. `camid = 0` (front) or 1 (back); the OEM's
     * cloud capture actually produced one file per camera simultaneously — we pass
     * camid anyway in case the device supports per-camera selection.
     */
    suspend fun capturePhoto(deviceIp: String, camid: Int = 0): AllwinnerCaptureResult {
        val session = AllwinnerSessionHolder.requireAlive(deviceIp) ?: return failed("no-session")
        return tryCandidates(session, PHOTO_CANDIDATES, ::lastGoodPhotoCmd, {
            lastGoodPhotoCmd = it
        }) { JSONObject().put("camid", camid) }
    }

    /**
     * Triggers a short event video capture of roughly [durationSec] seconds. The OEM
     * app default appears to be 6 s; the device likely clamps this internally.
     */
    suspend fun captureVideo(
        deviceIp: String,
        camid: Int = 0,
        durationSec: Int = 6,
    ): AllwinnerCaptureResult {
        val session = AllwinnerSessionHolder.requireAlive(deviceIp) ?: return failed("no-session")
        return tryCandidates(session, VIDEO_CANDIDATES, ::lastGoodVideoCmd, {
            lastGoodVideoCmd = it
        }) {
            JSONObject().apply {
                put("camid", camid)
                put("duration", durationSec)
                put("time", durationSec)  // belt-and-braces — some firmwares use `time`
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private suspend fun tryCandidates(
        session: AllwinnerSession,
        candidates: List<String>,
        sticky: () -> String?,
        latch: (String) -> Unit,
        buildPayload: () -> JSONObject,
    ): AllwinnerCaptureResult {
        // Try the already-known-good command first if we have one, so repeated taps
        // don't burn through the candidate list each time.
        val ordered = listOfNotNull(sticky()) +
            candidates.filter { it != sticky() }

        var lastResult: AllwinnerCaptureResult? = null
        for (cmd in ordered) {
            val result = attempt(session, cmd, buildPayload())
            lastResult = result
            if (result.ok) {
                if (sticky() != cmd) {
                    Log.i(TAG, "latched onto command '$cmd' (was ${sticky()})")
                    latch(cmd)
                }
                return result
            }
            // ret == -1 means transport failure (broken pipe etc) — stop probing.
            if (result.ret == -1) return result
            // ret == some other value means the firmware rejected the command name.
            // Likely "unknown command" or "bad params" — try the next candidate.
            Log.d(TAG, "'$cmd' rejected (ret=${result.ret}), trying next")
        }
        return lastResult ?: failed("no-candidates")
    }

    private suspend fun attempt(
        session: AllwinnerSession,
        cmd: String,
        payload: JSONObject,
    ): AllwinnerCaptureResult {
        Log.i(TAG, "probe '$cmd' payload=$payload")
        val resp = try {
            session.relay(cmd, payload)
        } catch (e: Exception) {
            Log.w(TAG, "'$cmd' threw: ${e.message}")
            return AllwinnerCaptureResult(false, cmd, null, -1, emptyList())
        }
        val ret = resp.optInt("ret", -1)
        val files = extractFilenames(resp)
        Log.i(TAG, "← '$cmd' ret=$ret files=$files resp=${resp.toString().take(300)}")
        return AllwinnerCaptureResult(
            ok = ret == 0,
            command = cmd,
            response = resp,
            ret = ret,
            files = files,
        )
    }

    /**
     * Best-effort extraction of filenames from a capture response. We don't know the
     * exact field layout yet, so we scan common keys (`file`, `files`, `name`,
     * `path`, nested arrays) and collect anything that looks like a recorded filename.
     */
    private fun extractFilenames(resp: JSONObject): List<String> {
        val out = mutableListOf<String>()

        // Direct scalars
        resp.optString("file").takeIf { it.isNotEmpty() }?.let { out += it }
        resp.optString("name").takeIf { it.isNotEmpty() }?.let { out += it }
        resp.optString("path").takeIf { it.isNotEmpty() }?.let { out += it }

        // Array under `files`
        resp.optJSONArray("files")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { out += it }
        }

        // Nested under `list`
        resp.optJSONArray("list")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                o.optString("name").takeIf { it.isNotEmpty() }?.let { out += it }
                o.optString("file").takeIf { it.isNotEmpty() }?.let { out += it }
            }
        }

        return out.distinct()
    }

    private fun failed(reason: String): AllwinnerCaptureResult {
        Log.w(TAG, "capture failed early: $reason")
        return AllwinnerCaptureResult(false, "", null, -1, emptyList())
    }
}
