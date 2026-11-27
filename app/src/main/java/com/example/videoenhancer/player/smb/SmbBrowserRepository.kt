package com.example.videoenhancer.player.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.Locale

data class SmbBrowserEntry(
    val name: String,
    val isDirectory: Boolean,
    val relativePath: String,
    val sizeBytes: Long = 0L
)

data class SmbAuthConfig(
    val isAnonymous: Boolean = true,
    val username: String = "匿名",
    val password: String = "",
    val domain: String = "",
    val shareName: String = "video"
) {
    fun shouldUseAnonymous(): Boolean {
        return isAnonymous ||
            username.isBlank() ||
            username == "匿名" ||
            username.equals("guest", ignoreCase = true) ||
            username.equals("anonymous", ignoreCase = true)
    }
}

class SmbBrowserRepository {

    private val videoExtensions = setOf(
        ".mp4", ".mkv", ".avi", ".mov", ".m4v", ".ts", ".m2ts", ".webm", ".flv", ".wmv"
    )

    fun isVideoFile(fileName: String): Boolean {
        val lower = fileName.lowercase(Locale.US)
        return videoExtensions.any { lower.endsWith(it) }
    }

    suspend fun listEntries(
        host: String,
        authConfig: SmbAuthConfig,
        subPath: String = ""
    ): Result<List<SmbBrowserEntry>> = withContext(Dispatchers.IO) {
        val client = SMBClient()
        try {
            client.connect(host).use { connection ->
                val authContext = if (authConfig.shouldUseAnonymous()) {
                    AuthenticationContext.anonymous()
                } else {
                    AuthenticationContext(
                        authConfig.username,
                        authConfig.password.toCharArray(),
                        authConfig.domain
                    )
                }

                val session = connection.authenticate(authContext)
                session.use { sess ->
                    val share = sess.connectShare(authConfig.shareName) as DiskShare
                    share.use { diskShare ->
                        val normalized = subPath.trim('/')
                        val entries = diskShare.list(normalized)
                            .filter { info ->
                                val name = info.fileName
                                name != "." && name != ".."
                            }
                            .mapNotNull { info ->
                                val name = info.fileName
                                val isDir = (info.fileAttributes and 0x10L) != 0L
                                val nextPath = if (normalized.isBlank()) name else "$normalized/$name"

                                if (isDir) {
                                    SmbBrowserEntry(
                                        name = name,
                                        isDirectory = true,
                                        relativePath = nextPath,
                                        sizeBytes = 0L
                                    )
                                } else if (isVideoFile(name)) {
                                    val size = info.endOfFile
                                    SmbBrowserEntry(
                                        name = name,
                                        isDirectory = false,
                                        relativePath = nextPath,
                                        sizeBytes = size
                                    )
                                } else {
                                    null
                                }
                            }
                            .sortedWith(
                                compareBy<SmbBrowserEntry> { !it.isDirectory }
                                    .thenBy { it.name.lowercase(Locale.US) }
                            )

                        Result.success(entries)
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun getFileSize(
        host: String,
        authConfig: SmbAuthConfig,
        relativePath: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        val client = SMBClient()
        try {
            client.connect(host).use { connection ->
                val authContext = if (authConfig.shouldUseAnonymous()) {
                    AuthenticationContext.anonymous()
                } else {
                    AuthenticationContext(
                        authConfig.username,
                        authConfig.password.toCharArray(),
                        authConfig.domain
                    )
                }

                val session = connection.authenticate(authContext)
                session.use { sess ->
                    val share = sess.connectShare(authConfig.shareName) as DiskShare
                    share.use { diskShare ->
                        val smbPath = relativePath.trim('/').replace('/', '\\')
                        val file = diskShare.openFile(
                            smbPath,
                            EnumSet.of(AccessMask.GENERIC_READ),
                            null,
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            null
                        )
                        file.use { handle ->
                            val endOfFile = handle.fileInformation.standardInformation.endOfFile
                            Result.success(endOfFile)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }
}
