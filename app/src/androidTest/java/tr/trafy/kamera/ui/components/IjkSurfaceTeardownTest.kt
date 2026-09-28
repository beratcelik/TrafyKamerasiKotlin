package tr.trafy.kamera.ui.components

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end cover for crash report #8, in a real Compose hierarchy.
 *
 * Reproduces the exact shape of the reported stack: an AndroidView-hosted
 * TextureView whose SurfaceTextureListener tears the player down, with the
 * player already released because the media URL key changed underneath it.
 * Removing the subtree makes Compose detach the interop view, which dispatches
 * `onSurfaceTextureDestroyed` on the main thread — the frame that used to throw
 * `IllegalStateException: mpjni: stop: null mp` and take the process with it.
 */
@RunWith(AndroidJUnit4::class)
class IjkSurfaceTeardownTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun detachingTextureViewAfterPlayerReleaseDoesNotCrash() {
        var url by mutableStateOf("first")
        var mounted by mutableStateOf(true)

        // Handle captured by the listener at factory time, exactly as the
        // production AndroidView factory captures it.
        var captured: IjkPlayerHandle? = null
        var surfaceSeen = false
        var surfaceDestroyed = false

        composeTestRule.setContent {
            if (mounted) {
                val ijk = rememberIjkPlayer(key = url, tag = "test") { }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        captured = ijk
                        TextureView(ctx).also { tv ->
                            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    surface: SurfaceTexture, w: Int, h: Int,
                                ) {
                                    surfaceSeen = true
                                    if (!ijk.isAlive) return
                                    runCatching { ijk.player.setSurface(Surface(surface)) }
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    surface: SurfaceTexture, w: Int, h: Int,
                                ) = Unit

                                override fun onSurfaceTextureDestroyed(
                                    surface: SurfaceTexture,
                                ): Boolean {
                                    surfaceDestroyed = true
                                    // Pre-fix this was ijk.player.stop() and threw.
                                    ijk.stopAndDetachSurface()
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                            }
                        }
                    },
                )
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 10_000) { surfaceSeen }
        composeTestRule.runOnIdle { assertTrue(captured!!.isAlive) }

        // Media URL changes: the player the listener holds is released, but the
        // TextureView stays attached with the stale handle.
        composeTestRule.runOnIdle { url = "second" }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertFalse("key change must release the captured player", captured!!.isAlive)
        }

        // Now tear the subtree down. Compose detaches the TextureView, which
        // dispatches onSurfaceTextureDestroyed into the released player.
        composeTestRule.runOnIdle { mounted = false }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            assertTrue("TextureView teardown never ran", surfaceDestroyed)
        }
    }
}
