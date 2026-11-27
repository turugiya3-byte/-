package com.example.videoenhancer.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.example.videoenhancer.ui.UpscaleMode
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class EnhancerRenderer(
    private val context: Context,
    private val onVideoInputSurfaceReady: (Surface) -> Unit,
    private val onStatsUpdated: (outputFps: Float, gpuFrameTimeMs: Float) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private data class FboTarget(val fboId: Int, val texId: Int, val width: Int, val height: Int)

    private val glThread = HandlerThread("EnhancerGLThread").apply { start() }
    private val glHandler = Handler(glThread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var displaySurface: Surface? = null

    private var oesTextureId = 0
    private var videoSurfaceTexture: SurfaceTexture? = null
    @Volatile var currentInputSurface: Surface? = null
        private set
    private val texMatrix = FloatArray(16)
    private val newFrameAvailable = AtomicBoolean(false)
    private var lastFrameTs = -1L

    private var oesShader: ShaderProgram? = null
    private var flowShader: ShaderProgram? = null
    private var interpShader: ShaderProgram? = null
    private var fsrShader: ShaderProgram? = null
    private var anime4kShader: ShaderProgram? = null
    private lateinit var quadBuf: FloatBuffer

    private var fboPrev: FboTarget? = null
    private var fboCurr: FboTarget? = null
    private var fboFlow: FboTarget? = null
    private var fboInterp: FboTarget? = null

    private var videoW = 1280
    private var videoH = 720
    private var viewW = 1920
    private var viewH = 1080
    private var hasPrevFrame = false
    private var currArrivalNs = 0L
    private var estIntervalNs = 41_666_666L

    @Volatile var sourceFps = 24f
        set(v) { field = v.coerceAtLeast(1f); estIntervalNs = (1e9 / field).toLong(); updateDisplayFps() }
    @Volatile var interpolationMultiplier = 2f
        set(v) { field = v.coerceIn(1f, 3f); updateDisplayFps() }
    @Volatile var upscaleMode = UpscaleMode.FSR_REALISTIC
    @Volatile var sharpness = 0.65f
    @Volatile var splitPosition = 0.5f

    @Volatile private var isRunning = false
    private var frameCount = 0
    private var windowStartNs = 0L
    private var gpuTimeNs = 0L

    private val loopRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            renderTick()
            val fps = (sourceFps * interpolationMultiplier).coerceIn(24f, 120f)
            glHandler.postDelayed(this, (1000.0 / fps).toLong().coerceIn(8L, 42L))
        }
    }

    fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        glHandler.post {
            try {
                viewW = width.coerceAtLeast(1)
                viewH = height.coerceAtLeast(1)
                val outSurface = Surface(st)
                displaySurface = outSurface
                initEgl(outSurface)
                initShadersAndInput()
                updateDisplayFps()
                isRunning = true
                windowStartNs = System.nanoTime()
                glHandler.post(loopRunnable)
            } catch (e: Throwable) {
                isRunning = false
                onError("GL初期化エラー: ${e.message}")
            }
        }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        glHandler.post { viewW = width.coerceAtLeast(1); viewH = height.coerceAtLeast(1); updateDisplayFps() }
    }

    fun onSurfaceDestroyed() {
        isRunning = false
        glHandler.removeCallbacks(loopRunnable)
        glHandler.post { releaseGl() }
    }

    fun onVideoSizeChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        glHandler.post {
            videoSurfaceTexture?.setDefaultBufferSize(width, height)
            if (isRunning && (videoW != width || videoH != height)) {
                videoW = width; videoH = height; recreateFbos(width, height)
            }
        }
    }

    private fun updateDisplayFps() {
        val s = displaySurface ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && s.isValid) {
            try { s.setFrameRate((sourceFps * interpolationMultiplier).coerceIn(24f, 120f), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) } catch (_: Exception) {}
        }
    }

    private fun renderTick() {
        try {
            val now = System.nanoTime()
            val st = videoSurfaceTexture ?: return
            val fired = newFrameAvailable.compareAndSet(true, false)
            st.updateTexImage()
            val ts = st.timestamp
            val gotNew = fired || (ts != lastFrameTs)
            if (gotNew) { lastFrameTs = ts; ingestFrame(now) }

            val alpha = if (interpolationMultiplier <= 1.01f || gotNew || !hasPrevFrame) 1f
            else ((now - currArrivalNs).coerceAtLeast(0L).toFloat() / estIntervalNs.toFloat()).coerceIn(0f, 1f)

            val t0 = System.nanoTime()
            drawScreen(alpha)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            }
            gpuTimeNs += (System.nanoTime() - t0)
            frameCount++
            if (now - windowStartNs >= 500_000_000L) {
                onStatsUpdated(frameCount * 1e9f / (now - windowStartNs), (gpuTimeNs / frameCount) / 1e6f)
                frameCount = 0; gpuTimeNs = 0L; windowStartNs = now
            }
        } catch (e: Throwable) {
            isRunning = false
            onError("描画エラー: ${e.message}")
        }
    }

    private fun ingestFrame(nowNs: Long) {
        val st = videoSurfaceTexture ?: return
        st.getTransformMatrix(texMatrix)
        if (currArrivalNs > 0L) {
            val d = nowNs - currArrivalNs
            if (d in 5_000_000L..100_000_000L) estIntervalNs = (estIntervalNs * 0.8 + d * 0.2).toLong()
        }
        currArrivalNs = nowNs

        val tmp = fboPrev; fboPrev = fboCurr; fboCurr = tmp
        val curr = fboCurr ?: return
        val prev = fboPrev ?: return
        val flow = fboFlow ?: return

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, curr.fboId)
        GLES30.glViewport(0, 0, curr.width, curr.height)
        oesShader?.apply {
            use(); bindTexture("uOesTex", 0, oesTextureId, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            setMat4("uTexMatrix", texMatrix); ShaderProgram.drawQuad(quadBuf)
        }
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        if (!hasPrevFrame) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prev.fboId)
            GLES30.glViewport(0, 0, prev.width, prev.height)
            oesShader?.apply {
                use(); bindTexture("uOesTex", 0, oesTextureId, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
                setMat4("uTexMatrix", texMatrix); ShaderProgram.drawQuad(quadBuf)
            }
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            hasPrevFrame = true
            return
        }

        if (interpolationMultiplier > 1.01f) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, flow.fboId)
            GLES30.glViewport(0, 0, flow.width, flow.height)
            flowShader?.apply {
                use(); bindTexture("uTexPrev", 0, prev.texId); bindTexture("uTexCurr", 1, curr.texId)
                setVec2("uTexelSize", 1f / curr.width, 1f / curr.height); ShaderProgram.drawQuad(quadBuf)
            }
        }
    }

    private fun drawScreen(alpha: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (!hasPrevFrame) return

        val prev = fboPrev ?: return
        val curr = fboCurr ?: return
        val flow = fboFlow ?: return
        val interp = fboInterp ?: return

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, interp.fboId)
        GLES30.glViewport(0, 0, interp.width, interp.height)
        interpShader?.apply {
            use(); bindTexture("uTexPrev", 0, prev.texId); bindTexture("uTexCurr", 1, curr.texId)
            bindTexture("uFlowMap", 2, flow.texId); setVec2("uFlowTexelSize", 1f / flow.width, 1f / flow.height)
            setFloat("uAlpha", alpha); setInt("uEnableInterp", if (interpolationMultiplier > 1.01f) 1 else 0)
            ShaderProgram.drawQuad(quadBuf)
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val va = videoW.toFloat() / videoH.toFloat()
        val sa = viewW.toFloat() / viewH.toFloat()
        val dw = if (va > sa) viewW else (viewH * va).roundToInt()
        val dh = if (va > sa) (viewW / va).roundToInt() else viewH
        GLES30.glViewport((viewW - dw) / 2, (viewH - dh) / 2, dw, dh)

        val shader = if (upscaleMode == UpscaleMode.ANIME_4K) anime4kShader else fsrShader
        shader?.apply {
            use(); bindTexture("uInputTex", 0, interp.texId); bindTexture("uOriginalTex", 1, curr.texId)
            setVec2("uInputTexelSize", 1f / videoW, 1f / videoH); setVec2("uOutputResolution", viewW.toFloat(), viewH.toFloat())
            setFloat("uSharpness", sharpness); setFloat("uSplitPos", splitPosition)
            setInt("uEnableUpscale", if (upscaleMode != UpscaleMode.OFF) 1 else 0)
            ShaderProgram.drawQuad(quadBuf)
        }
    }

    private fun initEgl(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, IntArray(1), 0)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun initShadersAndInput() {
        quadBuf = ShaderProgram.createQuadBuffer()
        oesShader = ShaderProgram(context, "shaders/common_vertex.glsl", "shaders/oes_to_2d.glsl")
        flowShader = ShaderProgram(context, "shaders/common_vertex.glsl", "shaders/pass1_optical_flow.glsl")
        interpShader = ShaderProgram(context, "shaders/common_vertex.glsl", "shaders/pass2_interpolate.glsl")
        fsrShader = ShaderProgram(context, "shaders/common_vertex.glsl", "shaders/pass3_upscale_fsr.glsl")
        anime4kShader = ShaderProgram(context, "shaders/common_vertex.glsl", "shaders/pass3_upscale_anime4k.glsl")

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        oesTextureId = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        videoSurfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(videoW, videoH)
            setOnFrameAvailableListener({ newFrameAvailable.set(true) }, glHandler)
        }
        val s = Surface(videoSurfaceTexture)
        currentInputSurface = s
        onVideoInputSurfaceReady(s)
        recreateFbos(videoW, videoH)
    }

    private fun recreateFbos(w: Int, h: Int) {
        deleteFbos(); hasPrevFrame = false; lastFrameTs = -1L
        fboPrev = createFbo(w, h); fboCurr = createFbo(w, h); fboInterp = createFbo(w, h)
        fboFlow = createFbo((w / 2).coerceAtLeast(16), (h / 2).coerceAtLeast(16))
    }

    private fun createFbo(w: Int, h: Int): FboTarget {
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t[0], 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return FboTarget(f[0], t[0], w, h)
    }

    private fun deleteFbos() {
        listOfNotNull(fboPrev, fboCurr, fboFlow, fboInterp).forEach {
            GLES30.glDeleteFramebuffers(1, intArrayOf(it.fboId), 0); GLES30.glDeleteTextures(1, intArrayOf(it.texId), 0)
        }
        fboPrev = null; fboCurr = null; fboFlow = null; fboInterp = null
    }

    private fun releaseGl() {
        deleteFbos()
        oesShader?.release(); flowShader?.release(); interpShader?.release(); fsrShader?.release(); anime4kShader?.release()
        currentInputSurface?.release(); currentInputSurface = null
        videoSurfaceTexture?.release(); videoSurfaceTexture = null
        if (oesTextureId != 0) { GLES30.glDeleteTextures(1, intArrayOf(oesTextureId), 0); oesTextureId = 0 }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface); EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY; eglContext = EGL14.EGL_NO_CONTEXT; eglSurface = EGL14.EGL_NO_SURFACE
        displaySurface?.release(); displaySurface = null
    }

    fun shutdown() { onSurfaceDestroyed(); glThread.quitSafely() }
}
