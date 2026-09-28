package tr.trafy.kamera.data.network

import android.net.ConnectivityManager
import android.net.Network
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the process-wide network pin that made the app unrecoverable after a
 * dashcam power-cycle: the process stayed bound to a destroyed Network and every
 * socket failed with ENONET, including the reconnect handshake.
 */
@RunWith(AndroidJUnit4::class)
class DashcamProcessNetworkTest {

    private lateinit var cm: ConnectivityManager
    private var live: Network? = null

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        cm = DashcamProcessNetwork.connectivityManager(ctx)
        DashcamProcessNetwork.clear(cm)
        live = cm.activeNetwork
        assumeNotNull("needs a live network to bind to", live)
    }

    @After
    fun tearDown() {
        DashcamProcessNetwork.clear(cm)
    }

    /**
     * A Network that refers to a netId the system does not have — what the app
     * is left holding after a dashcam is power-cycled. Built from a real
     * handle so the encoding stays whatever the platform uses; Network's
     * int constructor is not reflectively reachable on current Android.
     */
    private fun destroyedNetwork(): Network {
        val handle = live!!.networkHandle
        val magic = handle and 0xFFFFFFFFL
        val netId = handle ushr 32
        return Network.fromNetworkHandle(((netId + 100_000L) shl 32) or magic)
    }

    @Test
    fun bindThenReleasePinsAndUnpinsTheProcess() {
        DashcamProcessNetwork.bind(cm, live)
        assertEquals(live, cm.boundNetworkForProcess)

        DashcamProcessNetwork.release(cm, live)
        assertNull(cm.boundNetworkForProcess)
    }

    /**
     * A late teardown from one screen must not unbind a different screen that
     * has since pinned the process for its own stream.
     */
    @Test
    fun releaseOfAnotherNetworkLeavesTheCurrentPinAlone() {
        DashcamProcessNetwork.bind(cm, live)

        DashcamProcessNetwork.release(cm, destroyedNetwork())
        assertEquals("release must be a no-op for a network we are not on", live, cm.boundNetworkForProcess)
    }

    /** clear() drops the pin whatever it points at. */
    @Test
    fun clearAlwaysUnpins() {
        DashcamProcessNetwork.bind(cm, live)
        DashcamProcessNetwork.clear(cm)
        assertNull(cm.boundNetworkForProcess)
    }

    /** A live pin is healthy and must survive the staleness backstop untouched. */
    @Test
    fun clearIfStaleKeepsALivePin() {
        DashcamProcessNetwork.bind(cm, live)
        assertFalse(DashcamProcessNetwork.clearIfStale(cm))
        assertEquals(live, cm.boundNetworkForProcess)
    }

    /** With nothing pinned there is nothing to clear, and no crash. */
    @Test
    fun clearIfStaleIsANoOpWhenUnbound() {
        assertFalse(DashcamProcessNetwork.clearIfStale(cm))
        assertNull(cm.boundNetworkForProcess)
    }

    /** Restoring a captured pin puts the process back where it was. */
    @Test
    fun restorePutsBackALivePreviousPin() {
        DashcamProcessNetwork.bind(cm, live)
        val previous = cm.boundNetworkForProcess
        DashcamProcessNetwork.clear(cm)

        DashcamProcessNetwork.restore(cm, previous)
        assertEquals(live, cm.boundNetworkForProcess)
    }

    /** Restoring a destroyed pin must leave the process unbound, not re-break it. */
    @Test
    fun restoreRefusesADeadPreviousPin() {
        DashcamProcessNetwork.restore(cm, destroyedNetwork())
        assertNull(cm.boundNetworkForProcess)
    }
}
