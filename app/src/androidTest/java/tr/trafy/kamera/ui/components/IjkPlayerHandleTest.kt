package tr.trafy.kamera.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * Regression cover for user crash report #8:
 *
 *   java.lang.IllegalStateException: mpjni: stop: null mp
 *       at tv.danmaku.ijk.media.player.IjkMediaPlayer._stop(Native Method)
 *       at ...IjkVideoPlayerOverlay...onSurfaceTextureDestroyed(MediaScreen.kt:1259)
 *       at android.view.TextureView.onDetachedFromWindowInternal
 *
 * [hazardIsReal] pins the underlying ijkplayer behaviour the crash rests on, and
 * the rest prove [IjkPlayerHandle] neutralises it.
 */
@RunWith(AndroidJUnit4::class)
class IjkPlayerHandleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun newPlayer(): IjkMediaPlayer {
        IjkMediaPlayer.loadLibrariesOnce(null)
        return IjkMediaPlayer()
    }

    /**
     * The hazard itself: every ijkplayer JNI entry point throws once the native
     * player has been freed. This is what killed the app on the main thread.
     */
    @Test
    fun hazardIsReal_rawCallsAfterReleaseThrow() {
        val player = newPlayer()
        player.release()

        val stopFailure = runCatching { player.stop() }.exceptionOrNull()
        assertTrue(
            "expected IllegalStateException from stop() after release, got $stopFailure",
            stopFailure is IllegalStateException,
        )
        assertTrue(
            "expected the report #8 message, got ${stopFailure?.message}",
            stopFailure?.message?.contains("null mp") == true,
        )

        // setSurface(null), by contrast, is tolerated: ijkplayer's
        // _setVideoSurface null-check passes NULL as the exception class, so it
        // only logs. That asymmetry is why the crash trace shows a bare stop()
        // frame and nothing else — pinned here so a library bump that starts
        // throwing here does not slip past the guard silently.
        val surfaceFailure = runCatching { player.setSurface(null) }.exceptionOrNull()
        assertNull(
            "setSurface(null) after release was expected to be a silent no-op",
            surfaceFailure,
        )
    }

    /**
     * The crash path: TextureView teardown lands after the player was released.
     * Through the handle it must degrade to a no-op instead of throwing.
     */
    @Test
    fun surfaceTeardownAfterReleaseIsSilentNoOp() {
        val handle = IjkPlayerHandle("test", newPlayer())
        handle.release()
        assertFalse(handle.isAlive)

        handle.stopAndDetachSurface()
        handle.stopAndDetachSurface()
    }

    /** Teardown can arrive from both the effect and the view; release runs once. */
    @Test
    fun releaseIsIdempotent() {
        val handle = IjkPlayerHandle("test", newPlayer())
        assertTrue(handle.isAlive)
        handle.release()
        handle.release()
        handle.release()
        assertFalse(handle.isAlive)
    }

    /** Normal order still works: surface torn down first, then release. */
    @Test
    fun surfaceTeardownBeforeReleaseIsSafe() {
        val handle = IjkPlayerHandle("test", newPlayer())
        handle.stopAndDetachSurface()
        assertTrue(handle.isAlive)
        handle.release()
        assertFalse(handle.isAlive)
    }

    /**
     * The stale-capture shape from report #8: the media URL changes, the player
     * keyed on it is released, but the AndroidView's SurfaceTextureListener still
     * holds the old handle and tears it down later.
     */
    @Test
    fun keyChangeReleasesOldPlayerAndLateTeardownSurvives() {
        var key by mutableStateOf("first")
        var first: IjkPlayerHandle? = null
        var latest: IjkPlayerHandle? = null

        composeTestRule.setContent {
            val handle = rememberIjkPlayer(key = key, tag = "test") { }
            latest = handle
            if (key == "first" && first == null) first = handle
        }

        composeTestRule.runOnIdle { assertTrue(first!!.isAlive) }
        composeTestRule.runOnIdle { key = "second" }

        composeTestRule.runOnIdle {
            assertNotSame("key change must build a new player", first, latest)
            assertFalse("old player must be released on key change", first!!.isAlive)
            assertTrue(latest!!.isAlive)
            // Pre-fix this line threw IllegalStateException and killed the app.
            first!!.stopAndDetachSurface()
        }
    }

    /** Leaving composition releases the player exactly once. */
    @Test
    fun leavingCompositionReleasesPlayer() {
        var present by mutableStateOf(true)
        var handle: IjkPlayerHandle? = null

        composeTestRule.setContent {
            if (present) handle = rememberIjkPlayer(key = "only", tag = "test") { }
        }

        composeTestRule.runOnIdle { assertTrue(handle!!.isAlive) }
        composeTestRule.runOnIdle { present = false }
        composeTestRule.runOnIdle {
            assertFalse(handle!!.isAlive)
            handle!!.stopAndDetachSurface()
        }
    }
}
