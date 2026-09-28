package tr.trafy.kamera.ui.components

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns an [IjkMediaPlayer] together with a one-way "released" latch, so the
 * player can never be driven after its native handle is gone.
 *
 * Why this exists (user crash report #8):
 *
 *   java.lang.IllegalStateException: mpjni: stop: null mp
 *       at tv.danmaku.ijk.media.player.IjkMediaPlayer._stop(Native Method)
 *       at ...MediaScreenKt$IjkVideoPlayerOverlay$...onSurfaceTextureDestroyed
 *       at android.view.TextureView.onDetachedFromWindowInternal
 *       at androidx.compose.runtime.changelist.Operation$RemoveNode.execute
 *
 * Every ijkplayer JNI entry point starts with a `null mp` check and throws
 * IllegalStateException when the native player has already been freed by
 * `release()` — that includes `stop()` *and* `setSurface()`. So the teardown we
 * run from a TextureView callback is only safe while the player is still alive.
 *
 * The player and the TextureView have two independent lifecycles:
 *
 *   - the player is owned by a `remember(key)` + `DisposableEffect(key)` pair,
 *     which frees it when the composable leaves composition *or* when the key
 *     (the media URL) changes;
 *   - the TextureView is owned by an `AndroidView`, whose `factory` lambda runs
 *     once and permanently captures whichever player existed at that moment.
 *
 * Nothing orders those two. Compose dispatches `onForgotten` (our release) and
 * detaches the interop view in separate steps of `applyChanges`, keeps nodes
 * alive across a deactivate-for-reuse, and can remove the node a frame later;
 * and when the URL key changes the captured player is released while the very
 * same TextureView stays attached. In all of those the SurfaceTextureListener
 * ends up calling `stop()` / `setSurface(null)` on a freed player and the app
 * dies on the main thread.
 *
 * So teardown goes through here: [release] runs at most once, and
 * [stopAndDetachSurface] becomes a no-op afterwards. Both also swallow throws
 * defensively, because the latch alone cannot close a cross-thread race.
 */
internal class IjkPlayerHandle(
    private val tag: String,
    val player: IjkMediaPlayer,
) {
    private val released = AtomicBoolean(false)

    /** True while the native player is still allocated and safe to call into. */
    val isAlive: Boolean get() = !released.get()

    /**
     * Halts playback and drops the render surface, so ijkplayer stops writing
     * into a SurfaceTexture that TextureView is about to free.
     *
     * Call this from `onSurfaceTextureDestroyed` *before* returning `true`.
     * No-op once the player has been released.
     */
    fun stopAndDetachSurface() {
        if (released.get()) {
            Log.d(tag, "surface teardown skipped — IjkPlayer already released")
            return
        }
        try {
            player.stop()
        } catch (t: Throwable) {
            Log.w(tag, "IjkPlayer stop on surface teardown failed: ${t.message}")
        }
        try {
            player.setSurface(null)
        } catch (t: Throwable) {
            Log.w(tag, "IjkPlayer setSurface(null) failed: ${t.message}")
        }
    }

    /** Stops and frees the native player. Safe to call repeatedly; runs once. */
    fun release() {
        if (!released.compareAndSet(false, true)) {
            Log.d(tag, "IjkPlayer release skipped — already released")
            return
        }
        try {
            player.stop()
        } catch (t: Throwable) {
            Log.w(tag, "IjkPlayer stop before release failed: ${t.message}")
        }
        try {
            player.release()
        } catch (t: Throwable) {
            Log.w(tag, "IjkPlayer release failed: ${t.message}")
        }
    }
}

/**
 * Creates an [IjkMediaPlayer] keyed on [key] (normally the media URL), applies
 * [configure] to it once, and guarantees it is released exactly once when the
 * key changes or the caller leaves composition.
 *
 * Callers must route TextureView teardown through the returned handle rather
 * than touching [IjkPlayerHandle.player] directly — see the class doc.
 */
@Composable
internal fun rememberIjkPlayer(
    key: Any?,
    tag: String,
    configure: IjkMediaPlayer.() -> Unit,
): IjkPlayerHandle {
    val handle = remember(key) {
        IjkMediaPlayer.loadLibrariesOnce(null)
        IjkPlayerHandle(tag, IjkMediaPlayer().apply(configure))
    }
    DisposableEffect(handle) {
        onDispose {
            Log.d(tag, "dispose — releasing IjkPlayer")
            handle.release()
        }
    }
    return handle
}
