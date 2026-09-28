package tr.trafy.kamera.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log

/**
 * Single owner of the **process-wide** network binding.
 *
 * Live preview and in-app playback have to pin the whole process to the dashcam
 * Wi-Fi, because IjkPlayer's native FFmpeg sockets and MediaMetadataRetriever's
 * HTTP stack never see OkHttp's per-client socket factory. That binding is far
 * more dangerous than the per-client ones: it applies to *every* socket the app
 * opens, and it survives the network it points at.
 *
 * When a dashcam is power-cycled while live preview is running, its Network is
 * destroyed. A process still bound to it fails at `socket()` with
 * `ENONET (Machine is not on the network)` for the rest of the process's life —
 * including the reconnect handshake, which only ever rebound the per-client
 * socket factories. The app could not recover until it was force-stopped.
 *
 * Every process-level bind therefore goes through here, so that:
 *   - [release] only unbinds when we are still on the network we pinned, and so
 *     cannot stomp a newer binding put in place by another screen;
 *   - [clear] is available on connection loss, when nothing should stay pinned;
 *   - [clearIfStale] can drop a binding whose network no longer exists, which is
 *     the backstop that lets a reconnect succeed no matter which path leaked it.
 *
 * `ConnectivityManager.boundNetworkForProcess` is the single source of truth
 * here rather than a cached field, so bindings made elsewhere are still seen.
 */
object DashcamProcessNetwork {

    private const val TAG = "Trafy.ProcNetwork"

    fun connectivityManager(context: Context): ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Pins every socket in this process to [network]. Null unbinds. */
    fun bind(cm: ConnectivityManager, network: Network?) {
        cm.bindProcessToNetwork(network)
        Log.i(TAG, "bind: process bound to ${network ?: "default"}")
    }

    /**
     * Undoes a [bind] of [network].
     *
     * No-op when the process has since been pinned to a different network, so a
     * late teardown cannot unbind a screen that is still streaming.
     */
    fun release(cm: ConnectivityManager, network: Network?) {
        val current = cm.boundNetworkForProcess
        if (current == null) return
        if (network != null && current != network) {
            Log.i(TAG, "release: skipped — process now bound to $current, not $network")
            return
        }
        cm.bindProcessToNetwork(null)
        Log.i(TAG, "release: process unbound from $current")
    }

    /**
     * Restores a binding captured before a temporary [bind], skipping it when
     * that network has since been destroyed — restoring a dead pin would
     * re-break every socket in the process.
     */
    fun restore(cm: ConnectivityManager, previous: Network?) {
        if (previous == null) {
            clear(cm)
            return
        }
        if (cm.getNetworkCapabilities(previous) == null) {
            Log.w(TAG, "restore: previous binding $previous is gone — leaving process unbound")
            cm.bindProcessToNetwork(null)
            return
        }
        cm.bindProcessToNetwork(previous)
    }

    /** Unconditionally unbinds. Use when the dashcam connection is gone. */
    fun clear(cm: ConnectivityManager) {
        if (cm.boundNetworkForProcess == null) return
        cm.bindProcessToNetwork(null)
        Log.i(TAG, "clear: process unbound")
    }

    /**
     * Drops the binding when the network it points at no longer exists.
     *
     * A destroyed Network still compares and prints fine but has no
     * capabilities, so that is the liveness test. Returns true when a dead
     * binding was cleared. Cheap enough to call on every connect attempt.
     */
    fun clearIfStale(cm: ConnectivityManager): Boolean {
        val current = cm.boundNetworkForProcess ?: return false
        if (cm.getNetworkCapabilities(current) != null) return false
        cm.bindProcessToNetwork(null)
        Log.w(TAG, "clearIfStale: dropped stale binding to $current — sockets would have failed with ENONET")
        return true
    }
}
