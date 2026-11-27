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

    private data class FrameBufferTarget(
        val fboId: Int,
        val texId: Int,
        val width: Int,
        val height: Int
    )

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
    private var lastFrameTimestampNs = -1L

    private var oesTo2dShader: ShaderProgram? = null
    private var pass1FlowShader: ShaderProgram? = null
    private var pass2InterpShader: ShaderProgram? = null
    private var pass3FsrShader: ShaderProgram? = null
    private var pass3Anime4kShader: ShaderProgram? = null
    private lateinit var quadBuffer: FloatBuffer

    private var fboFramePrev: FrameBufferTarget? = null
    private var fboFrameCurr: FrameBufferTarget? = null
    private var fboFlowMap: FrameBufferTarget? = null
    private var fboInterpOut: FrameBufferTarget? = null

    private var videoWidth = 1280
    private var videoHeight = 720
    private var viewportWidth = 1920
    private var viewportHeight = 1080
    private var hasValidPrevFrame = false

    private var prevFrameArrivalNs = 0L
    private var currFrameArrivalNs = 0L
    private var estimatedSourceIntervalNs = 41_666_666L

    @Volatile var sourceFps: Float = 24.0f
        set(value) {
            field = value.coerceAtLeast(1.0f)
            estimatedSourceIntervalNs = (1_000_000_000.0 / field).toLong()
            updateDisplayFrameRate()
        }

    @Volatile var interpolationMultiplier: Float = 2.0f
        set(value) {
            field = value.coerceIn(1.0f, 3.0f)
            updateDisplayFrameRate()
        }

    @Volatile var upscaleMode: UpscaleMode = UpscaleMode.FSR_REALISTIC
    @Volatile var sharpness: Float = 0.65f
    @Volatile var splitPosition: Float = 0.5f

    @Volatile private var isRunning = false
    private var renderedFrameCount = 0
    private var statsWindowStartNs = 0L
    private var accumulatedGpuTimeNs = 0L

    private val renderLoopRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            renderTick()
            val targetFps = (sourceFps * interpolationMultiplier).coerceIn(24f, 120f)
            val delayMs = (1000.0 / targetFps).toLong().coerceIn(8L, 42L)
            glHandler.postDelayed(this, delayMs)
        }
    }

    fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        glHandler.post {
            try {
                viewportWidth = width.coerceAtLeast(1)
                viewportHeight = height.coerceAtLeast(1)
                val outSurface = Surface(surfaceTexture)
                displaySurface = outSurface
                initEgl(outSurface)
                initShadersAndInputTexture()
                updateDisplayFrameRate()
                isRunning = true
                statsWindowStartNs = System.nanoTime()
                glHandler.post(renderLoopRunnable)
            } catch (e: Throwable) {
                isRunning = false
                onError("GL初期化エラー: ${e.message}")
            }
        }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        glHandler.post {
            viewportWidth = width.coerceAtLeast(1)
            viewportHeight = height.coerceAtLeast(1)
            updateDisplayFrameRate()
        }
    }

    fun onSurfaceDestroyed() {
        isRunning = false
        glHandler.removeCallbacks(renderLoopRunnable)
        glHandler.post {
            releaseGlResources()
        }
    }

    fun onVideoSizeChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        glHandler.post {
            videoSurfaceTexture?.setDefaultBufferSize(width, height)
            if (isRunning && (videoWidth != width || videoHeight != height)) {
                videoWidth = width
                videoHeight = height
                recreateFbos(width, height)
            }
        }
    }

    private fun updateDisplayFrameRate() {
        val surface = displaySurface ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && surface.isValid) {
            val targetFps = (sourceFps * interpolationMultiplier).coerceIn(24f, 120f)
            try {
                surface.setFrameRate(targetFps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            } catch (_: Exception) {
            }
        }
    }

    private fun renderTick() {
        try {
            val nowNs = System.nanoTime()
            val st = videoSurfaceTexture ?: return
            val callbackFired = newFrameAvailable.compareAndSet(true, false)
            st.updateTexImage()
            val currentTs = st.timestamp
            val timestampChanged = (currentTs != lastFrameTimestampNs)
            val gotNewFrame = callbackFired || timestampChanged

            if (gotNewFrame) {
                lastFrameTimestampNs = currentTs
                ingestNewVideoFrame(nowNs)
            }

            val alpha = if (interpolationMultiplier <= 1.01f || gotNewFrame || !hasValidPrevFrame) {
                1.0f
            } else {
                val elapsedSinceCurrFrame = (nowNs - currFrameArrivalNs).coerceAtLeast(0L)
                (elapsedSinceCurrFrame.toFloat() / estimatedSourceIntervalNs.toFloat()).coerceIn(0.0f, 1.0f)
            }

            val gpuStartNs = System.nanoTime()
            renderPass2AndPass3(alpha)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            }
            val gpuEndNs = System.nanoTime()

            renderedFrameCount++
            accumulatedGpuTimeNs += (gpuEndNs - gpuStartNs)
            val windowElapsedNs = nowNs - statsWindowStartNs
            if (windowElapsedNs >= 500_000_000L) {
                val actualFps = renderedFrameCount * 1_000_000_000f / windowElapsedNs
                val avgGpuMs = (accumulatedGpuTimeNs / renderedFrameCount) / 1_000_000f
                onStatsUpdated(actualFps, avgGpuMs)
                renderedFrameCount = 0
                accumulatedGpuTimeNs = 0L
                statsWindowStartNs = nowNs
            }
        } catch (e: Throwable) {
            isRunning = false
            onError("描画エラー: ${e.message}")
        }
    }
    private fun ingestNewVideoFrame(arrivalTimeNs: Long) {
        val st = videoSurfaceTexture ?: return
        st.getTransformMatrix(texMatrix)

        if (currFrameArrivalNs > 0L) {
            val delta = arrivalTimeNs - currFrameArrivalNs
            if (delta in 5_000_000L..100_000_000L) {
                estimatedSourceIntervalNs = (estimatedSourceIntervalNs * 0.8 + delta * 0.2).toLong()
            }
        }
        prevFrameArrivalNs = currFrameArrivalNs
        currFrameArrivalNs = arrivalTimeNs

        val temp = fboFramePrev
        fboFramePrev = fboFrameCurr
        fboFrameCurr = temp

        val currTarget = fboFrameCurr ?: return
        val prevTarget = fboFramePrev ?: return
        val flowTarget = fboFlowMap ?: return

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, currTarget.fboId)
        GLES30.glViewport(0, 0, currTarget.width, currTarget.height)
        oesTo2dShader?.apply {
            use()
            bindTexture("uOesTex", 0, oesTextureId, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            setMat4("uTexMatrix", texMatrix)
            ShaderProgram.drawQuad(quadBuffer)
        }
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        if (!hasValidPrevFrame) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevTarget.fboId)
            GLES30.glViewport(0, 0, prevTarget.width, prevTarget.height)
            oesTo2dShader?.apply {
                use()
                bindTexture("uOesTex", 0, oesTextureId, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
                setMat4("uTexMatrix", texMatrix)
                ShaderProgram.drawQuad(quadBuffer)
            }
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            hasValidPrevFrame = true
            return
        }

        if (interpolationMultiplier > 1.01f) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, flowTarget.fboId)
            GLES30.glViewport(0, 0, flowTarget.width, flowTarget.height)
            pass1FlowShader?.apply {
                use()
                bindTexture("uTexPrev", 0, prevTarget.texId)
                bindTexture("uTexCurr", 1, currTarget.texId)
                setVec2("uTexelSize", 1.0f / currTarget.width, 1.0f / currTarget.height)
                ShaderProgram.drawQuad(quadBuffer)
            }
        }
    }

    private fun renderPass2AndPass3(alpha: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glClearColor(0.04f, 0.06f, 0.12f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (!hasValidPrevFrame) return

        val prevTarget = fboFramePrev ?: return
        val currTarget = fboFrameCurr ?: return
        val flowTarget = fboFlowMap ?: return
        val interpTarget = fboInterpOut ?: return

        val interpEnabled = if (interpolationMultiplier > 1.01f) 1 else 0

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, interpTarget.fboId)
        GLES30.glViewport(0, 0, interpTarget.width, interpTarget.height)
        pass2InterpShader?.apply {
            use()
            bindTexture("uTexPrev", 0, prevTarget.texId)
            bindTexture("uTexCurr", 1, currTarget.texId)
            bindTexture("uFlowMap", 2, flowTarget.texId)
            setVec2("uFlowTexelSize", 1.0f / flowTarget.width, 1.0f / flowTarget.height)
            setFloat("uAlpha", alpha)
            setInt("uEnableInterp", interpEnabled)
            ShaderProgram.drawQuad(quadBuffer)
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        applyAspectFitViewport()

        val activeUpscaleShader = when (upscaleMode) {
            UpscaleMode.ANIME_4K -> pass3Anime4kShader
            UpscaleMode.FSR_REALISTIC, UpscaleMode.OFF -> pass3FsrShader
        }
        val enableUpscaleFlag = if (upscaleMode != UpscaleMode.OFF) 1 else 0

        activeUpscaleShader?.apply {
            use()
            bindTexture("uInputTex", 0, interpTarget.texId)
            bindTexture("uOriginalTex", 1, currTarget.texId)
            setVec2("uInputTexelSize", 1.0f / videoWidth, 1.0f / videoHeight)
            setVec2("uOutputResolution", viewportWidth.toFloat(), viewportHeight.toFloat())
            setFloat("uSharpness", sharpness)
            setFloat("uSplitPos", splitPosition)
            setInt("uEnableUpscale", enableUpscaleFlag)
            ShaderProgram.drawQuad(quadBuffer)
        }
    }

    private fun applyAspectFitViewport() {
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val viewAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val drawW: Int
        val drawH: Int
        if (videoAspect > viewAspect) {
            drawW = viewportWidth
            drawH = (viewportWidth / videoAspect).roundToInt()
        } else {
            drawH = viewportHeight
            drawW = (viewportHeight * videoAspect).roundToInt()
        }
        GLES30.glViewport((viewportWidth - drawW) / 2, (viewportHeight - drawH) / 2, drawW, drawH)
    }

    private fun initEgl(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || configs[0] == null) {
            throw RuntimeException("eglChooseConfig failed")
        }

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")

        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    private fun initShadersAndInputTexture() {
        quadBuffer = ShaderProgram.createQuadBuffer()
        oesTo2dShader = ShaderProgram(context, "shaders/common_vertex.glsl", "shaders/oes_to_2d.glsl")
        pass1FlowShader = ShaderProgram(context, "shaders/common_vertex.glsl", "shaders/pass1_optical_flow.glsl")
        pass2InterpShader = ShaderProgram(context, "shaders/common_vertex.glsl", "shaders/pass2_interpolate.glsl")
        pass3FsrShader = ShaderProgram(context, "shaders/common_vertex.glsl", "shaders/pass3_upscale_fsr.glsl")
        pass3Anime4kShader = ShaderProgram(context, "shaders/common_vertex.glsl", "shaders/pass3_upscale_anime4k.glsl")

        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        oesTextureId = texIds[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        videoSurfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(videoWidth, videoHeight)
            setOnFrameAvailableListener({ newFrameAvailable.set(true) }, glHandler)
        }
        val surface = Surface(videoSurfaceTexture)
        currentInputSurface = surface
        onVideoInputSurfaceReady(surface)
        recreateFbos(videoWidth, videoHeight)
    }

    private fun recreateFbos(width: Int, height: Int) {
        deleteFbos()
        hasValidPrevFrame = false
        lastFrameTimestampNs = -1L
        fboFramePrev = createFboTarget(width, height)
        fboFrameCurr = createFboTarget(width, height)
        fboInterpOut = createFboTarget(width, height)
        val flowW = (width / 2).coerceAtLeast(16)
        val flowH = (height / 2).coerceAtLeast(16)
        fboFlowMap = createFboTarget(flowW, flowH)
    }

    private fun createFboTarget(width: Int, height: Int): FrameBufferTarget {
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        val texId = texIds[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fboIds = IntArray(1)
        GLES30.glGenFramebuffers(1, fboIds, 0)
        val fboId = fboIds[0]

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texId, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        return FrameBufferTarget(fboId, texId, width, height)
    }

    private fun deleteFbos() {
        listOfNotNull(fboFramePrev, fboFrameCurr, fboFlowMap, fboInterpOut).forEach {
            GLES30.glDeleteFramebuffers(1, intArrayOf(it.fboId), 0)
            GLES30.glDeleteTextures(1, intArrayOf(it.texId), 0)
        }
        fboFramePrev = null
        fboFrameCurr = null
        fboFlowMap = null
        fboInterpOut = null
    }

    private fun releaseGlResources() {
        deleteFbos()
        oesTo2dShader?.release()
        pass1FlowShader?.release()
        pass2InterpShader?.release()
        pass3FsrShader?.release()
        pass3Anime4kShader?.release()
        currentInputSurface?.release()
        currentInputSurface = null
        videoSurfaceTexture?.release()
        videoSurfaceTexture = null
        if (oesTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        displaySurface?.release()
        displaySurface = null
    }

    fun shutdown() {
        onSurfaceDestroyed()
        glThread.quitSafely()
    }
}
