package com.example.videoenhancer.gl

import android.content.Context
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class ShaderProgram(
    context: Context,
    vertexAssetPath: String,
    fragmentAssetPath: String
) {
    val programId: Int
    private val uniformLocations = mutableMapOf<String, Int>()

    init {
        val vertexSrc = context.assets.open(vertexAssetPath).bufferedReader().use { it.readText() }
        val fragmentSrc = context.assets.open(fragmentAssetPath).bufferedReader().use { it.readText() }

        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc, vertexAssetPath)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc, fragmentAssetPath)

        programId = GLES30.glCreateProgram().also { prog ->
            GLES30.glAttachShader(prog, vs)
            GLES30.glAttachShader(prog, fs)
            GLES30.glLinkProgram(prog)

            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(prog)
                GLES30.glDeleteProgram(prog)
                throw RuntimeException("Program link error ($fragmentAssetPath): $log")
            }
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
    }

    fun use() = GLES30.glUseProgram(programId)

    private fun getUniformLoc(name: String): Int =
        uniformLocations.getOrPut(name) { GLES30.glGetUniformLocation(programId, name) }

    fun setInt(name: String, value: Int) {
        val loc = getUniformLoc(name)
        if (loc >= 0) GLES30.glUniform1i(loc, value)
    }

    fun setFloat(name: String, value: Float) {
        val loc = getUniformLoc(name)
        if (loc >= 0) GLES30.glUniform1f(loc, value)
    }

    fun setVec2(name: String, x: Float, y: Float) {
        val loc = getUniformLoc(name)
        if (loc >= 0) GLES30.glUniform2f(loc, x, y)
    }

    fun setMat4(name: String, matrix: FloatArray) {
        val loc = getUniformLoc(name)
        if (loc >= 0) GLES30.glUniformMatrix4fv(loc, 1, false, matrix, 0)
    }

    fun bindTexture(name: String, unit: Int, textureId: Int, target: Int = GLES30.GL_TEXTURE_2D) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(target, textureId)
        setInt(name, unit)
    }

    fun release() = GLES30.glDeleteProgram(programId)

    private fun compileShader(type: Int, source: String, name: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compile error ($name): $log")
        }
        return shader
    }

    companion object {
        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
        )

        fun createQuadBuffer(): FloatBuffer =
            ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(QUAD_VERTICES)
                    position(0)
                }

        fun drawQuad(quadBuffer: FloatBuffer) {
            quadBuffer.position(0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quadBuffer)

            quadBuffer.position(2)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quadBuffer)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
        }
    }
}
