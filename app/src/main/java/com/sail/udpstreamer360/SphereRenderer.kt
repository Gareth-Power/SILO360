package com.sail.udpstreamer360

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class SphereRenderer : GLSurfaceView.Renderer {

    var onSurfaceReady: ((surface: Surface, width: Int, height: Int) -> Unit)? = null
    var onSizeChanged: ((width: Int, height: Int) -> Unit)? = null
    var requestRender: (() -> Unit)? = null

    /** Camera orientation — write from any thread, read on GL thread each frame */
    @Volatile var yaw: Float = 0f    // horizontal degrees, increases = look right
    @Volatile var pitch: Float = 0f  // vertical degrees,   increases = look up

    // ---------- shaders ----------

    private val vertexShader = """
        uniform mat4 uMVP;
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMVP * aPosition;
            vTexCoord   = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShader = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTexture;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """.trimIndent()

    // ---------- GL resources ----------

    private var program      = 0
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var _surface: Surface? = null
    private var surfaceReadyFired = false

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var indexBuffer:  ShortBuffer
    private var indexCount = 0

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix  = FloatArray(16)

    // Sphere tessellation — 64×64 is smooth and well within Short index range
    private val STACKS = 64
    private val SLICES = 64

    // ---------- sphere geometry ----------

    private fun buildSphere() {
        val floatsPerVert = 5          // x, y, z, u, v
        val vertCount     = (STACKS + 1) * (SLICES + 1)
        val verts         = FloatArray(vertCount * floatsPerVert)
        var vi            = 0

        for (i in 0..STACKS) {
            val theta    = i.toFloat() / STACKS * PI.toFloat()   // 0 → π
            val sinTheta = sin(theta)
            val cosTheta = cos(theta)
            val v        = i.toFloat() / STACKS                   // 0 → 1 top→bottom

            for (j in 0..SLICES) {
                val phi    = j.toFloat() / SLICES * 2f * PI.toFloat()  // 0 → 2π
                val u      = j.toFloat() / SLICES                       // 0 → 1

                verts[vi++] = sinTheta * cos(phi)   // x
                verts[vi++] = cosTheta              // y
                verts[vi++] = sinTheta * sin(phi)   // z
                verts[vi++] = u                     // u
                verts[vi++] = v                     // v
            }
        }

        // Build indices — winding reversed so faces are visible from inside
        val indices = ShortArray(STACKS * SLICES * 6)
        var ii = 0
        for (i in 0 until STACKS) {
            for (j in 0 until SLICES) {
                val a = (i       * (SLICES + 1) + j    ).toShort()
                val b = ((i + 1) * (SLICES + 1) + j    ).toShort()
                val c = (i       * (SLICES + 1) + j + 1).toShort()
                val d = ((i + 1) * (SLICES + 1) + j + 1).toShort()
                // reversed winding for inside-out sphere
                indices[ii++] = a; indices[ii++] = c; indices[ii++] = b
                indices[ii++] = c; indices[ii++] = d; indices[ii++] = b
            }
        }
        indexCount = indices.size

        vertexBuffer = ByteBuffer.allocateDirect(verts.size   * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .also { it.put(verts);   it.position(0) }

        indexBuffer  = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
            .also { it.put(indices); it.position(0) }
    }

    // ---------- GLSurfaceView.Renderer ----------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // Create the OES texture that VLC writes decoded frames into
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTextureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setOnFrameAvailableListener { requestRender?.invoke() }
            _surface = Surface(this)
        }

        program = buildProgram(vertexShader, fragmentShader)
        buildSphere()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        surfaceTexture?.setDefaultBufferSize(width, height)

        // 90° vertical FoV — wide enough to feel immersive
        Matrix.perspectiveM(projMatrix, 0, 90f, width.toFloat() / height.toFloat(), 0.1f, 10f)

        if (!surfaceReadyFired) {
            surfaceReadyFired = true
            _surface?.let { onSurfaceReady?.invoke(it, width, height) }
        } else {
            onSizeChanged?.invoke(width, height)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        surfaceTexture?.updateTexImage()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)

        // Camera look direction from yaw / pitch
        val yRad = Math.toRadians(yaw.toDouble()).toFloat()
        val pRad = Math.toRadians(pitch.toDouble()).toFloat()
        val lx   =  cos(pRad) * sin(yRad)
        val ly   =  sin(pRad)
        val lz   = -cos(pRad) * cos(yRad)   // −Z = forward at yaw=0

        Matrix.setLookAtM(viewMatrix, 0,
            0f, 0f, 0f,          // eye at origin — inside the sphere
            lx, ly, lz,          // look target
            0f, 1f, 0f)          // up

        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(program, "uMVP"), 1, false, mvpMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)

        val stride    = 5 * 4   // 5 floats × 4 bytes
        val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, stride, vertexBuffer)

        vertexBuffer.position(3)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
    }

    // ---------- helpers ----------

    private fun buildProgram(vertSrc: String, fragSrc: String): Int {
        fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER,   vertSrc))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fragSrc))
            GLES20.glLinkProgram(it)
        }
    }

    fun updateVideoSize(width: Int, height: Int) {
        surfaceTexture?.setDefaultBufferSize(width, height)
    }

    fun release() {
        _surface?.release()
        surfaceTexture?.release()
        _surface = null
        surfaceTexture = null
    }
}
