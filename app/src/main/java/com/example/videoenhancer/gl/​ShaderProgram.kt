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

    init {
        val vertexSrc = context.assets.open(vertexAssetPath).bufferedReader().use { it.readText() }
        val fragmentSrc = context.assets.open(fragmentAssetPath).bufferedReader().use { it.readText() }
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc, vertexAssetPath)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc, fragmentAssetPath)

        programId = GLES30.glCreateProgram()
        GLES30.glAttachShader(programId, vs)
        GLES30.glAttachShader(programId, fs)
        GLES30.glBindAttribLocation(programId, 0, "aPosition")
        GLES30.glBindAttribLocation(programId, 1, "aTexCoord")
        GLES30.glLinkProgram(programId)

        val status = IntArray(1)
        GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(programId)
            GLES30.glDeleteProgram(programId)
            throw RuntimeException("Shader link error ($fragmentAssetPath): $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
    }

    fun use() = GLES30.glUseProgram(programId)

    fun bindTexture(name: String, unit: Int, texId: Int, target: Int = GLES30.GL_TEXTURE_2D) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(target, texId)
        val loc = GLES30.glGetUniformLocation(programId, name)
        if (loc >= 0) GLES30.glUniform1i(loc, unit)
    }

    fun setFloat(name: String, v: Float) {
        val loc = GLES30.glGetUniformLocation(programId, name)
        if (loc >= 0) GLES30.glUniform1f(loc, v)
    }

    fun setInt(name: String, v: Int) {
        val loc = GLES30.glGetUniformLocation(programId, name)
        if (loc >= 0) GLES30.glUniform1i(loc, v)
    }

    fun setVec2(name: String, x: Float, y: Float) {
        val loc = GLES30.glGetUniformLocation(programId, name)
        if (loc >= 0) GLES30.glUniform2f(loc, x, y)
    }

    fun setMat4(name: String, m: FloatArray) {
        val loc = GLES30.glGetUniformLocation(programId, name)
        if (loc >= 0) GLES30.glUniformMatrix4fv(loc, 1, false, m, 0)
    }

    fun release() {
        if (programId != 0) GLES30.glDeleteProgram(programId)
    }

    companion object {
        private val QUAD = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)

        fun createQuadBuffer(): FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD); position(0) }

        fun drawQuad(buf: FloatBuffer) {
            buf.position(0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, buf)
            buf.position(2)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, buf)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
        }

        private fun compileShader(type: Int, src: String, name: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(s)
                GLES30.glDeleteShader(s)
                throw RuntimeException("Shader compile error ($name): $log")
            }
            return s
        }
    }
}
