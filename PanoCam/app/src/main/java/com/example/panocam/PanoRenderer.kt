package com.example.panocam

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** One full-screen shader: equirectangular panorama behind, person (via mask) in front. */
class PanoRenderer : GLSurfaceView.Renderer {
    @Volatile var rot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f) // column-major mat3

    private val lock = Any()
    private var pendingPano: Bitmap? = null
    private var pendingCam: Bitmap? = null
    private var pendingMask: ByteBuffer? = null
    private var maskW = 0; private var maskH = 0

    private var prog = 0
    private val tex = IntArray(3) // 0 pano, 1 cam, 2 mask
    private var hasPano = false; private var hasCam = false
    private var w = 1; private var h = 1
    private var camAspect = 3f / 4f

    fun setPano(b: Bitmap) = synchronized(lock) { pendingPano = b }
    fun submitFrame(cam: Bitmap, mask: ByteArray, mw: Int, mh: Int) = synchronized(lock) {
        pendingCam = cam
        pendingMask = ByteBuffer.allocateDirect(mask.size).order(ByteOrder.nativeOrder()).put(mask).apply { position(0) }
        maskW = mw; maskH = mh
    }

    private val vsh = "attribute vec2 p; varying vec2 v; void main(){ v=p*0.5+0.5; gl_Position=vec4(p,0.0,1.0); }"
    private val fsh = """
        precision highp float;
        varying vec2 v;
        uniform sampler2D uPano, uCam, uMask;
        uniform mat3 uRot;
        uniform float uAspect, uHasPano, uHasCam;
        uniform vec2 uCamScale;
        void main(){
            vec2 q = (v - 0.5) * 2.0;
            vec3 ray = normalize(vec3(q.x * uAspect * 0.75, q.y * 0.75, -1.0));
            vec3 d = uRot * ray;
            float lon = atan(d.x, d.y);
            float lat = asin(clamp(d.z, -1.0, 1.0));
            vec2 uv = vec2(lon / 6.2831853 + 0.5, 0.5 - lat / 3.1415927);
            vec3 col = uHasPano > 0.5 ? texture2D(uPano, uv).rgb : vec3(0.08);
            if (uHasCam > 0.5) {
                vec2 c = (v - 0.5) * uCamScale + 0.5;
                c.y = 1.0 - c.y;
                float m = smoothstep(0.35, 0.65, texture2D(uMask, c).r);
                col = mix(col, texture2D(uCam, c).rgb, m);
            }
            gl_FragColor = vec4(col, 1.0);
        }
    """.trimIndent()

    private fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        prog = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vsh))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fsh))
            GLES20.glLinkProgram(it)
        }
        GLES20.glGenTextures(3, tex, 0)
        for (t in tex) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        hasPano = false; hasCam = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width; h = height; GLES20.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(lock) {
            pendingPano?.let {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, it, 0)
                it.recycle(); pendingPano = null; hasPano = true
            }
            val cam = pendingCam; val mask = pendingMask
            if (cam != null && mask != null) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[1]); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, cam, 0)
                camAspect = cam.width.toFloat() / cam.height
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[2])
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, maskW, maskH, 0,
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, mask)
                cam.recycle(); pendingCam = null; pendingMask = null; hasCam = true
            }
        }
        GLES20.glUseProgram(prog)
        val screenAspect = w.toFloat() / h
        val sx = if (screenAspect > camAspect) 1f else screenAspect / camAspect   // "cover" fit
        val sy = if (screenAspect > camAspect) camAspect / screenAspect else 1f
        for (i in 0..2) { GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i]) }
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uPano"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uCam"), 1)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uMask"), 2)
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(prog, "uRot"), 1, false, rot, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uAspect"), screenAspect)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uHasPano"), if (hasPano) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uHasCam"), if (hasCam) 1f else 0f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uCamScale"), sx, sy)
        val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }
        val loc = GLES20.glGetAttribLocation(prog, "p")
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
