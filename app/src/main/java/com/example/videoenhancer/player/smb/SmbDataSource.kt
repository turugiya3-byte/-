package com.example.videoenhancer.player.smb

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
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
import java.util.EnumSet

@OptIn(UnstableApi::class)
class SmbDataSource : BaseDataSource(true) {

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var smbFile: File? = null

    private var currentUri: Uri? = null
    private var readPosition: Long = 0
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri
        val uri = dataSpec.uri
        val host = uri.host ?: throw IOException("SMB host is missing")
        val port = if (uri.port != -1) uri.port else 445

        val userInfo = uri.userInfo?.split(":")
        val username = userInfo?.getOrNull(0) ?: "guest"
        val password = userInfo?.getOrNull(1) ?: ""

        val pathSegments = uri.pathSegments
        if (pathSegments.isEmpty()) throw IOException("SMB share name is missing")
        val shareName = pathSegments[0]
        val filePath = pathSegments.drop(1).joinToString("\\")

        try {
            client = SMBClient()
            connection = client!!.connect(host, port)
            session = connection!!.authenticate(AuthenticationContext(username, password.toCharArray(), ""))
            share = session!!.connectShare(shareName) as DiskShare

            smbFile = share!!.openFile(
                filePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )

            val fileLength = smbFile!!.fileInformation.standardInformation.endOfFile
            readPosition = dataSpec.position
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                fileLength - readPosition
            }

            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: Exception) {
            close()
            throw IOException("Failed to open SMB file: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()
        val bytesRead = smbFile?.read(buffer, readPosition, offset, bytesToRead) ?: -1
        if (bytesRead <= 0) return C.RESULT_END_OF_INPUT

        readPosition += bytesRead
        bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        try {
            smbFile?.close()
            share?.close()
            session?.close()
            connection?.close()
            client?.close()
        } catch (_: Exception) {
        } finally {
            smbFile = null
            share = null
            session = null
            connection = null
            client = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
