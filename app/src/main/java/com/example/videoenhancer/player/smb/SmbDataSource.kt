package com.example.videoenhancer.player.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet

@UnstableApi
class SmbDataSourceFactory(
    private val authConfig: SmbAuthConfig
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return SmbDataSource(authConfig)
    }
}

@UnstableApi
class SmbDataSource(
    private val authConfig: SmbAuthConfig
) : BaseDataSource(/* isNetwork = */ true) {

    private var currentUri: Uri? = null
    private var smbClient: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null
    private var smbFile: File? = null
    private var inputStream: InputStream? = null

    private var readPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri

        try {
            val uri = dataSpec.uri
            val host = uri.host ?: throw IOException("SMBホスト名が指定されていません: $uri")
            val segments = uri.pathSegments.filter { it.isNotBlank() }
            if (segments.isEmpty()) {
                throw IOException("SMB共有名・ファイルパスが指定されていません: $uri")
            }

            val shareName = segments.first().ifBlank { authConfig.shareName }
            val filePath = segments.drop(1).joinToString("\\")
            if (filePath.isBlank()) {
                throw IOException("SMBファイルパスが空です: $uri")
            }

            val client = SMBClient()
            val conn = client.connect(host)
            val authContext = if (authConfig.shouldUseAnonymous()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(
                    authConfig.username,
                    authConfig.password.toCharArray(),
                    authConfig.domain
                )
            }

            val sess = conn.authenticate(authContext)
            val share = sess.connectShare(shareName) as DiskShare
            val handle = share.openFile(
                filePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )

            val totalSize = handle.fileInformation.standardInformation.endOfFile
            val startPos = dataSpec.position
            if (startPos > totalSize) {
                throw IOException("シーク位置がファイルサイズを超えています ($startPos > $totalSize)")
            }

            val stream = handle.inputStream
            var skipped = 0L
            while (skipped < startPos) {
                val n = stream.skip(startPos - skipped)
                if (n <= 0L) {
                    val tempBuf = ByteArray(minOf(8192L, startPos - skipped).toInt())
                    val r = stream.read(tempBuf, 0, tempBuf.size)
                    if (r <= 0) break
                    skipped += r.toLong()
                } else {
                    skipped += n
                }
            }

            smbClient = client
            connection = conn
            session = sess
            diskShare = share
            smbFile = handle
            inputStream = stream
            readPosition = skipped

            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length.coerceAtMost(totalSize - skipped)
            } else {
                totalSize - skipped
            }

            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: Exception) {
            closeQuietly()
            throw IOException("SMBファイルのオープンに失敗しました: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val stream = inputStream ?: throw IOException("SMBストリームが開かれていません")
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        if (toRead <= 0) return C.RESULT_END_OF_INPUT

        return try {
            val readBytes = stream.read(buffer, offset, toRead)
            if (readBytes <= 0) {
                bytesRemaining = 0L
                C.RESULT_END_OF_INPUT
            } else {
                readPosition += readBytes.toLong()
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                    bytesRemaining -= readBytes.toLong()
                }
                bytesTransferred(readBytes)
                readBytes
            }
        } catch (e: Exception) {
            throw IOException("SMBファイルの読み込み中にエラーが発生しました: ${e.message}", e)
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        val wasOpened = opened
        opened = false
        closeQuietly()
        if (wasOpened) {
            transferEnded()
        }
    }

    private fun closeQuietly() {
        try { inputStream?.close() } catch (_: Exception) {}
        inputStream = null
        try { smbFile?.close() } catch (_: Exception) {}
        smbFile = null
        try { diskShare?.close() } catch (_: Exception) {}
        diskShare = null
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { connection?.close() } catch (_: Exception) {}
        connection = null
        try { smbClient?.close() } catch (_: Exception) {}
        smbClient = null
        readPosition = 0L
        bytesRemaining = 0L
    }
}
