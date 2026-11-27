package com.example.simplevideoplayer

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

enum class FrameInterpolationMode(val label: String, val blendFactor: Float) {
    OFF("補間OFF", 0.0f),
    SMOOTH("補間標準(60fps風)", 0.35f),
    ULTRA("補間強(なめらか)", 0.55f)
}

@UnstableApi
private class FrameInterpolationEffect(
    private val blendFactor: Float
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return FrameInterpolationShaderProgram(
            useHdr = useHdr,
            blendFactor = blendFactor
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameInterpolationEffect) return false
        return blendFactor == other.blendFactor
    }

    override fun hashCode(): Int = blendFactor.hashCode()
}

@UnstableApi
private class FrameInterpolationShaderProgram(
    useHdr: Boolean,
    private val blendFactor: Float
) : BaseGlShaderProgram(useHdr, 1) {

    private val vertexShaderCode = """
        #version 300 es
        in vec4 aFramePosition;
        in vec2 aTexCoords;
        out vec2 vTexCoords;
        void main() {
            gl_Position = aFramePosition;
            vTexCoords = aTexCoords;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #version 300 es
        precision highp float;
        in vec2 vTexCoords;
        out vec4 outColor;

        uniform sampler2D uCurrentTex;
        uniform sampler2D uPrevTex;
        uniform vec2 uTexelSize;
        uniform float uBlendFactor;
        uniform float uHasPrev;

        void main() {
            vec3 curr = texture(uCurrentTex, vTexCoords).rgb;
            if (uBlendFactor <= 0.001 || uHasPrev < 0.5) {
                outColor = vec4(curr, 1.0);
                return;
            }

            vec3 prev = texture(uPrevTex, vTexCoords).rgb;

            vec2 bestOffset = vec2(0.0);
            float bestDiff = length(prev - curr);

            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    vec2 off = vec2(float(dx), float(dy)) * uTexelSize * 2.0;
                    vec3 cand = texture(uPrevTex, clamp(vTexCoords + off, vec2(0.0), vec2(1.0))).rgb;
                    float d = length(cand - curr);
                    if (d < bestDiff) {
                        bestDiff = d;
                        bestOffset = off;
                    }
                }
            }

            vec3 motionCompensatedPrev = texture(
                uPrevTex,
                clamp(vTexCoords + bestOffset, vec2(0.0), vec2(1.0))
            ).rgb;

            float sceneDiff = length(curr - motionCompensatedPrev);
            float sceneCutMask = 1.0 - smoothstep(0.18, 0.45, sceneDiff);

            float alpha = clamp(uBlendFactor * sceneCutMask, 0.0, 0.65);
            vec3 interpolated = mix(curr, motionCompensatedPrev, alpha);
            outColor = vec4(clamp(interpolated, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    private var program = 0
    private var texWidth = 1280
    private var texHeight = 720
    private val quadBuffer: FloatBuffer

    private var prevFbo = 0
    private var prevTextureId = 0
    private var hasPrevFrame = false

    init {
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
        )
        quadBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }
        try {
            setupProgram()
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }

    private fun setupProgram() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glBindAttribLocation(prog, 0, "aFramePosition")
            GLES20.glBindAttribLocation(prog, 1, "aTexCoords")
            GLES20.glLinkProgram(prog)
            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val info = GLES30.glGetProgramInfoLog(prog)
                GLES30.glDeleteProgram(prog)
                throw RuntimeException("Link failed: $info")
            }
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
    }

    private fun compileShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Compile failed: $info")
        }
        return shader
    }

    private fun ensureHistoryBuffer(width: Int, height: Int) {
        if (prevTextureId != 0 && texWidth == width && texHeight == height) return

        releaseHistoryBuffer()

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        prevTextureId = texIds[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTextureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        prevFbo = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            prevTextureId,
            0
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        hasPrevFrame = false
    }

    private fun releaseHistoryBuffer() {
        if (prevFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(prevFbo), 0)
            prevFbo = 0
        }
        if (prevTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(prevTextureId), 0)
            prevTextureId = 0
        }
        hasPrevFrame = false
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texWidth = inputWidth.coerceAtLeast(1)
        texHeight = inputHeight.coerceAtLeast(1)
        ensureHistoryBuffer(texWidth, texHeight)
        return Size(texWidth, texHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        ensureHistoryBuffer(texWidth, texHeight)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
        val currLoc = GLES20.glGetUniformLocation(program, "uCurrentTex")
        if (currLoc >= 0) GLES20.glUniform1i(currLoc, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTextureId)
        val prevLoc = GLES20.glGetUniformLocation(program, "uPrevTex")
        if (prevLoc >= 0) GLES20.glUniform1i(prevLoc, 1)

        val texelLoc = GLES20.glGetUniformLocation(program, "uTexelSize")
        if (texelLoc >= 0) GLES20.glUniform2f(texelLoc, 1f / texWidth, 1f / texHeight)

        val blendLoc = GLES20.glGetUniformLocation(program, "uBlendFactor")
        if (blendLoc >= 0) GLES20.glUniform1f(blendLoc, blendFactor)

        val hasPrevLoc = GLES20.glGetUniformLocation(program, "uHasPrev")
        if (hasPrevLoc >= 0) GLES20.glUniform1f(hasPrevLoc, if (hasPrevFrame) 1.0f else 0.0f)

        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        if (prevFbo != 0 && prevTextureId != 0) {
            val prevViewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo)
            GLES20.glViewport(0, 0, texWidth, texHeight)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
            if (blendLoc >= 0) GLES20.glUniform1f(blendLoc, 0.0f)
            if (hasPrevLoc >= 0) GLES20.glUniform1f(hasPrevLoc, 0.0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
            hasPrevFrame = true
        }

        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
    }

    override fun release() {
        releaseHistoryBuffer()
        super.release()
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }
}

@UnstableApi
fun buildFrameInterpolationEffects(mode: FrameInterpolationMode): List<Effect> {
    if (mode == FrameInterpolationMode.OFF) return emptyList()
    return listOf<Effect>(FrameInterpolationEffect(blendFactor = mode.blendFactor))
}
