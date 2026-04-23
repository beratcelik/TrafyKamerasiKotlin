package com.example.trafykamerasikotlin.data.video

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface

/**
 * Minimal EGL host for rendering to a [MediaCodec] encoder's input surface.
 *
 * MediaCodec input surfaces are meant to be producer surfaces for GL, not
 * Canvas — so we have to set up an EGL context, wrap the encoder surface
 * as an EGL window surface, and issue GL draw commands. This class keeps
 * the EGL bookkeeping out of the encoder's way.
 *
 * Intentionally single-threaded: the encoder loop holds the GL context
 * for its entire duration, so there's no inter-thread shuffling.
 */
class InputSurfaceEgl(surface: Surface) {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "no EGL display" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            // EGL_RECORDABLE_ANDROID — tells the driver this surface feeds an encoder.
            0x3142, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            "eglChooseConfig failed"
        }
        check(numConfigs[0] > 0) { "no matching EGL configs" }

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, surfaceAttribs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed"
        }
    }

    /** Tag the next buffer's presentation timestamp, then swap. */
    fun setPresentationTime(nanos: Long) {
        android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nanos)
    }

    fun swapBuffers() {
        val ok = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (!ok) {
            val code = EGL14.eglGetError()
            error("eglSwapBuffers failed — EGL error 0x${"%X".format(code)} (${eglErrorName(code)})")
        }
    }

    /** Short human-readable name for EGL error codes so stack traces are useful. */
    private fun eglErrorName(code: Int): String = when (code) {
        EGL14.EGL_SUCCESS             -> "EGL_SUCCESS"
        EGL14.EGL_NOT_INITIALIZED     -> "EGL_NOT_INITIALIZED"
        EGL14.EGL_BAD_ACCESS          -> "EGL_BAD_ACCESS"
        EGL14.EGL_BAD_ALLOC           -> "EGL_BAD_ALLOC"
        EGL14.EGL_BAD_ATTRIBUTE       -> "EGL_BAD_ATTRIBUTE"
        EGL14.EGL_BAD_CONFIG          -> "EGL_BAD_CONFIG"
        EGL14.EGL_BAD_CONTEXT         -> "EGL_BAD_CONTEXT"
        EGL14.EGL_BAD_CURRENT_SURFACE -> "EGL_BAD_CURRENT_SURFACE"
        EGL14.EGL_BAD_DISPLAY         -> "EGL_BAD_DISPLAY"
        EGL14.EGL_BAD_MATCH           -> "EGL_BAD_MATCH"
        EGL14.EGL_BAD_NATIVE_PIXMAP   -> "EGL_BAD_NATIVE_PIXMAP"
        EGL14.EGL_BAD_NATIVE_WINDOW   -> "EGL_BAD_NATIVE_WINDOW"
        EGL14.EGL_BAD_PARAMETER       -> "EGL_BAD_PARAMETER"
        EGL14.EGL_BAD_SURFACE         -> "EGL_BAD_SURFACE"
        EGL14.EGL_CONTEXT_LOST        -> "EGL_CONTEXT_LOST"
        else -> "unknown"
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }
}

/**
 * Tiny ES 2.0 program that blits a 2D texture over the full-screen quad.
 * Used by [OverlayVideoEncoder] to upload the composited frame bitmap as
 * a texture and draw it to the encoder surface in one GL pass.
 */
internal class FullScreenQuadBlitter {
    private val vertexShader = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord   = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShader = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }
    """.trimIndent()

    private val program: Int
    private val aPos: Int
    private val aTex: Int
    private val uTex: Int
    private val vertexBuffer: java.nio.FloatBuffer
    private val texCoordBuffer: java.nio.FloatBuffer
    private val textureIds = IntArray(1)

    init {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "GL program link failed: ${GLES20.glGetProgramInfoLog(program)}" }

        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTex = GLES20.glGetUniformLocation(program, "uTexture")

        // Full-screen quad in normalized device coordinates.
        val verts = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f,
        )
        val bb = java.nio.ByteBuffer.allocateDirect(verts.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(verts).position(0)

        val texCoords = floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f)  // unused — pos already carries uv
        val tb = java.nio.ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        texCoordBuffer = tb.asFloatBuffer()
        texCoordBuffer.put(texCoords).position(0)

        GLES20.glGenTextures(1, textureIds, 0)
    }

    /** Upload `bitmap` into a GL texture and draw it full-screen. */
    fun draw(bitmap: android.graphics.Bitmap) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,     GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,     GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glUniform1i(uTex, 0)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPos)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTex)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        GLES20.glDeleteTextures(1, textureIds, 0)
        GLES20.glDeleteProgram(program)
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
}
