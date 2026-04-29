package com.example.trafykamerasikotlin.data.video

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Surface-to-Surface composite for offline burn-in.
 *
 * Owns one EGL context that:
 *   1. Receives decoder frames into a [SurfaceTexture] (zero-copy GPU
 *      texture — no CPU YUV→ARGB conversion).
 *   2. Composites them, with an alpha-blended overlay quad on top, onto
 *      the [MediaCodec] encoder's input surface.
 *   3. Optionally taps a small downscaled snapshot for AI inference via
 *      `glReadPixels` into an offscreen FBO.
 *
 * Threading: this whole class is single-threaded. The thread that calls
 * `composite()` must be the same one that called any other GL method. The
 * SurfaceTexture's frame-available callback fires on its own thread (we
 * just signal a Object monitor); consumers wait via [awaitNewFrame].
 */
class GlOverlayPipeline(
    encoderInputSurface: Surface,
    /** Encoder output (= source) resolution; sets the overlay texture size. */
    private val width: Int,
    private val height: Int,
    /** Snapshot resolution fed to AI inference. Preserves source aspect. */
    snapshotWidth: Int = 1280,
) {

    // ── EGL ──────────────────────────────────────────────────────────────────
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE  // window surface ← encoder input

    // ── Decoder side (external OES) ──────────────────────────────────────────
    private val decoderTexId: Int
    val decoderSurfaceTexture: SurfaceTexture
    /** Pass this to `decoder.configure(format, decoderSurface, ...)`. */
    val decoderSurface: Surface

    private val frameLock = Object()
    @Volatile private var frameAvailable = false

    // ── Programs ─────────────────────────────────────────────────────────────
    private val externalProgram: ExternalOesProgram
    private val overlayProgram:  TexturedQuadProgram

    // ── Overlay (2D ARGB) ────────────────────────────────────────────────────
    private val overlayTexId: Int
    @Volatile private var overlayPopulated = false

    // ── Bitmap source path (used by AVI/MJPEG flow that has no Surface) ──────
    private val bitmapSourceTexId: Int
    private var bitmapSourceUploaded = false

    // ── Snapshot FBO ─────────────────────────────────────────────────────────
    val snapshotW: Int
    val snapshotH: Int
    private val snapshotFboId: Int
    private val snapshotTexId: Int
    private val snapshotPixelBuf: ByteBuffer

    // Texture transform for the SurfaceTexture (handles rotation / vendor flips).
    private val stMatrix = FloatArray(16)

    init {
        // 1. EGL context bound to the encoder's input surface.
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            // EGL_RECORDABLE_ANDROID — declares this surface feeds an encoder.
            0x3142, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numCfg = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numCfg, 0)) {
            "eglChooseConfig failed"
        }
        check(numCfg[0] > 0) { "no matching EGL configs" }
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val surfAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderInputSurface, surfAttribs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

        // 2. External OES texture for decoder output, wrapped as a Surface.
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        decoderTexId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, decoderTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        decoderSurfaceTexture = SurfaceTexture(decoderTexId)
        decoderSurfaceTexture.setOnFrameAvailableListener {
            synchronized(frameLock) { frameAvailable = true; frameLock.notifyAll() }
        }
        decoderSurface = Surface(decoderSurfaceTexture)

        // 3. 2D texture for the overlay quad (filled lazily by uploadOverlay).
        GLES20.glGenTextures(1, ids, 0)
        overlayTexId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // 4. Bitmap source texture (lazily uploaded if AVI/MJPEG path is used).
        GLES20.glGenTextures(1, ids, 0)
        bitmapSourceTexId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapSourceTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // 5. Programs.
        externalProgram = ExternalOesProgram()
        overlayProgram  = TexturedQuadProgram()

        // 6. Snapshot FBO for AI inference. Aspect-preserved downscale of the
        //    output. RGBA color attachment + glReadPixels = small Bitmap.
        val sw = snapshotWidth.coerceAtMost(width)
        val sh = (height.toLong() * sw / width).toInt().coerceAtLeast(2)
        snapshotW = sw and 1.inv()
        snapshotH = sh and 1.inv()
        GLES20.glGenTextures(1, ids, 0)
        snapshotTexId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, snapshotTexId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, snapshotW, snapshotH, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenFramebuffers(1, ids, 0)
        snapshotFboId = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, snapshotFboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, snapshotTexId, 0,
        )
        val fboStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(fboStatus == GLES20.GL_FRAMEBUFFER_COMPLETE) { "snapshot FBO incomplete: $fboStatus" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        snapshotPixelBuf = ByteBuffer.allocateDirect(snapshotW * snapshotH * 4)
            .order(ByteOrder.nativeOrder())

        Log.i(TAG, "init: out=${width}x$height snapshot=${snapshotW}x$snapshotH")
    }

    /**
     * Block until the SurfaceTexture has a new image, then `updateTexImage`.
     * Returns false on timeout (no frame arrived from the decoder in time).
     */
    fun awaitNewFrame(timeoutMs: Long = 2_000L): Boolean {
        synchronized(frameLock) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (!frameAvailable) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                try { frameLock.wait((remaining / 1_000_000L).coerceAtLeast(1L)) }
                catch (_: InterruptedException) { return false }
            }
            frameAvailable = false
        }
        decoderSurfaceTexture.updateTexImage()
        decoderSurfaceTexture.getTransformMatrix(stMatrix)
        return true
    }

    /**
     * Re-render the overlay texture from [overlayBitmap]. Call this only when
     * the AI scene changes — the texture stays on the GPU between calls, so
     * subsequent [composite] calls reuse it for free. Bitmap must match the
     * pipeline's output resolution.
     */
    fun uploadOverlay(overlayBitmap: Bitmap) {
        require(overlayBitmap.width == width && overlayBitmap.height == height) {
            "overlay bitmap ${overlayBitmap.width}x${overlayBitmap.height} ≠ output ${width}x$height"
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0)
        overlayPopulated = true
    }

    /**
     * Upload [sourceBitmap] into the bitmap-source texture for the
     * [compositeFromBitmap] path. Used by sources that don't render to a
     * Surface (e.g. AVI/MJPEG, where each frame arrives as a JPEG buffer
     * we decode to ARGB before uploading).
     */
    fun uploadSourceBitmap(sourceBitmap: Bitmap) {
        require(sourceBitmap.width == width && sourceBitmap.height == height) {
            "source bitmap ${sourceBitmap.width}x${sourceBitmap.height} ≠ output ${width}x$height"
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapSourceTexId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, sourceBitmap, 0)
        bitmapSourceUploaded = true
    }

    /**
     * Composite path for sources that upload via [uploadSourceBitmap]. Same
     * overlay logic as [composite] but uses the 2D bitmap-source texture.
     */
    fun compositeFromBitmap(presentationTimeNs: Long) {
        check(bitmapSourceUploaded) { "uploadSourceBitmap not called yet" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        overlayProgram.draw(bitmapSourceTexId)

        if (overlayPopulated) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            overlayProgram.draw(overlayTexId)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs)
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            val err = EGL14.eglGetError()
            error("eglSwapBuffers failed (EGL 0x${"%X".format(err)})")
        }
    }

    /**
     * Composite the most-recently-updated decoder texture, then the overlay
     * (if uploaded), and present at [presentationTimeNs] to the encoder.
     */
    fun composite(presentationTimeNs: Long) {
        // Render to the encoder's window surface.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 1. Decoder frame (external OES, with SurfaceTexture's transform).
        externalProgram.draw(decoderTexId, stMatrix)

        // 2. Overlay quad (alpha-blended). 2D texture in clip space, identity transform.
        if (overlayPopulated) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            overlayProgram.draw(overlayTexId)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs)
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            val err = EGL14.eglGetError()
            error("eglSwapBuffers failed (EGL 0x${"%X".format(err)})")
        }
    }

    /**
     * Render the current decoder texture into the snapshot FBO and read it
     * back as an ARGB Bitmap. Caller owns the returned bitmap (must recycle
     * after inference). Uses a swizzled fragment shader so the byte order
     * matches Android Bitmap.ARGB_8888 on little-endian.
     */
    fun snapshotForInference(): Bitmap {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, snapshotFboId)
        GLES20.glViewport(0, 0, snapshotW, snapshotH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        externalProgram.draw(decoderTexId, stMatrix, swizzleRb = true)

        snapshotPixelBuf.position(0)
        GLES20.glReadPixels(
            0, 0, snapshotW, snapshotH,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, snapshotPixelBuf,
        )

        // Restore default framebuffer for the next composite() call.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        snapshotPixelBuf.position(0)
        val bmp = Bitmap.createBitmap(snapshotW, snapshotH, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(snapshotPixelBuf)
        return bmp
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try { externalProgram.release() } catch (_: Throwable) {}
            try { overlayProgram.release()  } catch (_: Throwable) {}
            try {
                GLES20.glDeleteTextures(
                    4,
                    intArrayOf(decoderTexId, overlayTexId, bitmapSourceTexId, snapshotTexId),
                    0,
                )
            } catch (_: Throwable) {}
            try { GLES20.glDeleteFramebuffers(1, intArrayOf(snapshotFboId), 0) } catch (_: Throwable) {}
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        try { decoderSurface.release()        } catch (_: Throwable) {}
        try { decoderSurfaceTexture.release() } catch (_: Throwable) {}
    }

    companion object { private const val TAG = "Trafy.GlPipeline" }
}

/* ────────────────────────────────────────────────────────────────────────── */

/**
 * Samples a [SurfaceTexture]'s external OES texture, applies the texture's
 * transform matrix, and draws full-screen. Optional R↔B swizzle so the
 * snapshot path can read back into an ARGB_8888 Bitmap directly.
 */
private class ExternalOesProgram {
    private val vsSrc = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord   = (uTexMatrix * aTexCoord).xy;
        }
    """.trimIndent()

    private val fsSrc = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uTexture;
        uniform float uSwizzleRb;  // 0 = pass through, 1 = swap R↔B
        void main() {
            vec4 c = texture2D(uTexture, vTexCoord);
            gl_FragColor = mix(c, vec4(c.b, c.g, c.r, c.a), uSwizzleRb);
        }
    """.trimIndent()

    private val program: Int
    private val aPos: Int
    private val aTex: Int
    private val uTexMatrix: Int
    private val uTexture: Int
    private val uSwizzle: Int
    private val verts: FloatBuffer

    init {
        program = compileProgram(vsSrc, fsSrc)
        aPos       = GLES20.glGetAttribLocation(program, "aPosition")
        aTex       = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTexture   = GLES20.glGetUniformLocation(program, "uTexture")
        uSwizzle   = GLES20.glGetUniformLocation(program, "uSwizzleRb")

        // Interleaved [pos.xy, tex.xy] — 4 floats per vertex × 4 verts.
        val data = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f,
        )
        verts = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(data); position(0) }
    }

    fun draw(texId: Int, texMatrix: FloatArray, swizzleRb: Boolean = false) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1f(uSwizzle, if (swizzleRb) 1f else 0f)

        verts.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, verts)
        GLES20.glEnableVertexAttribArray(aPos)
        verts.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, verts)
        GLES20.glEnableVertexAttribArray(aTex)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() { GLES20.glDeleteProgram(program) }
}

/* ────────────────────────────────────────────────────────────────────────── */

/**
 * Plain 2D-texture full-screen quad. Used to draw the overlay on top of the
 * decoder frame. Bitmap top-left maps to clip-space top-left, so the y-axis
 * is flipped relative to GL's default convention.
 */
private class TexturedQuadProgram {
    private val vsSrc = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord   = aTexCoord;
        }
    """.trimIndent()

    private val fsSrc = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
    """.trimIndent()

    private val program: Int
    private val aPos: Int
    private val aTex: Int
    private val uTexture: Int
    private val verts: FloatBuffer

    init {
        program = compileProgram(vsSrc, fsSrc)
        aPos     = GLES20.glGetAttribLocation(program, "aPosition")
        aTex     = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")

        // Bitmap origin = top-left, GL clip = bottom-left; flip texY here.
        val data = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f,
        )
        verts = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(data); position(0) }
    }

    fun draw(texId: Int) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(uTexture, 0)

        verts.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, verts)
        GLES20.glEnableVertexAttribArray(aPos)
        verts.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, verts)
        GLES20.glEnableVertexAttribArray(aTex)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() { GLES20.glDeleteProgram(program) }
}

private fun compileProgram(vertexSrc: String, fragmentSrc: String): Int {
    val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
    val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vs)
    GLES20.glAttachShader(program, fs)
    GLES20.glLinkProgram(program)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    check(status[0] == GLES20.GL_TRUE) { "GL link failed: ${GLES20.glGetProgramInfoLog(program)}" }
    GLES20.glDeleteShader(vs)
    GLES20.glDeleteShader(fs)
    return program
}

private fun compileShader(type: Int, src: String): Int {
    val id = GLES20.glCreateShader(type)
    GLES20.glShaderSource(id, src)
    GLES20.glCompileShader(id)
    val status = IntArray(1)
    GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
    check(status[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(id)}" }
    return id
}

@Suppress("unused")
private fun identityMatrix(): FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
