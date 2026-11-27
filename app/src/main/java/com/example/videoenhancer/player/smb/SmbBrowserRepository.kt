package com.example.videoenhancer.player.smb

import android.net.Uri
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class SmbServerConfig(
    val host: String,
    val shareName: String,
    val username: String = "guest",
    val password: String = ""
)

data class SmbFileItem(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long
) {
    fun toPlaybackUri(config: SmbServerConfig): Uri {
        val authPart = when {
            config.password.isNotEmpty() -> "${Uri.encode(config.username)}:${Uri.encode(config.password)}@"
            config.username.isNotEmpty() -> "${Uri.encode(config.username)}@"
            else -> ""
        }
        val normalizedPath = relativePath.replace("\\", "/").trimStart('/')
        return Uri.parse("smb://$authPart${config.host}/${config.shareName}/$normalizedPath")
    }
}

class SmbBrowserRepository {
    companion object {
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "m2ts", "flv", "wmv"
        )
    }

    suspend fun listDirectory(config: SmbServerConfig, currentPath: String = ""): Result<List<SmbFileItem>> =
        withContext(Dispatchers.IO) {
            val client = SMBClient()
            try {
                val connection = client.connect(config.host)
                val session = connection.authenticate(
                    AuthenticationContext(config.username, config.password.toCharArray(), "")
                )
                val share = session.connectShare(config.shareName) as? DiskShare
                    ?: return@withContext Result.failure(IllegalStateException("共有フォルダにアクセスできません"))

                val smbQueryPath = currentPath.replace("/", "\\").trim('\\')
                val items = share.list(smbQueryPath).mapNotNull { info ->
                    val name = info.fileName
                    if (name == "." || name == ".." || name.startsWith("$")) return@mapNotNull null

                    val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                    if (!isDir && ext !in VIDEO_EXTENSIONS) return@mapNotNull null

                    val childPath = if (smbQueryPath.isEmpty()) name else "$smbQueryPath\\$name"
                    SmbFileItem(name, childPath, isDir, info.endOfFile)
                }.sortedWith(
                    compareBy<SmbFileItem> { !it.isDirectory }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                )

                share.close()
                session.close()
                connection.close()
                Result.success(items)
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
