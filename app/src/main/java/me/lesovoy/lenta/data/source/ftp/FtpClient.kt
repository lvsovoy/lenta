package me.lesovoy.lenta.data.source.ftp

import android.content.Context
import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.source.RemoteFileClient
import me.lesovoy.lenta.data.source.StorageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class FtpClient(override val source: StorageSource) : RemoteFileClient {

    fun parseFtpHost(): String {
        var url = source.serverUrl.trim()
        if (url.startsWith("ftp://", ignoreCase = true)) {
            url = url.substring(6)
        } else if (url.startsWith("sftp://", ignoreCase = true)) {
            url = url.substring(7)
        }
        val hostPart = url.substringBefore('/').substringBefore(':')
        return hostPart.ifBlank { "localhost" }
    }

    fun parseFtpPort(): Int {
        if (source.port > 0) return source.port
        var url = source.serverUrl.trim()
        val isSftp = url.startsWith("sftp://", ignoreCase = true)
        if (url.startsWith("ftp://", ignoreCase = true)) {
            url = url.substring(6)
        } else if (isSftp) {
            url = url.substring(7)
        }
        val hostWithPort = url.substringBefore('/')
        if (hostWithPort.contains(':')) {
            return hostWithPort.substringAfter(':').toIntOrNull() ?: if (isSftp) 22 else 21
        }
        return if (isSftp) 22 else 21
    }

    override fun getAuthHeaders(): Map<String, String> = emptyMap()

    override fun getPreviewUrl(item: MediaItem): String? {
        return item.uriString.ifBlank { getStreamUrl(item) }
    }

    override fun getStreamUrl(item: MediaItem): String {
        val host = parseFtpHost()
        val port = parseFtpPort()
        val path = item.effectiveRemotePath.trim('/')
        val portPart = if (port != 21 && port != 22) ":$port" else ""
        val pathPart = if (path.isNotBlank()) "/$path" else ""
        return "ftp://$host$portPart$pathPart"
    }

    override suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!source.isConfigured()) {
            return@withContext Result.failure(IllegalStateException("FTP server host must not be empty"))
        }

        try {
            val host = parseFtpHost()
            val port = parseFtpPort()

            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 5000)
                Result.success(true)
            }
        } catch (e: Exception) {
            Result.failure(Exception("FTP connection to ${parseFtpHost()}:${parseFtpPort()} failed: ${e.message}"))
        }
    }

    override suspend fun listFolder(
        remotePath: String,
        showHidden: Boolean
    ): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        if (!source.isConfigured()) {
            return@withContext Result.failure(IllegalStateException("FTP source not configured"))
        }

        try {
            val items = mutableListOf<MediaItem>()
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun downloadToCache(context: Context, item: MediaItem): Result<File> = withContext(Dispatchers.IO) {
        val cacheSubdir = File(context.cacheDir, "remote_cache/${source.id}")
        if (!cacheSubdir.exists()) {
            cacheSubdir.mkdirs()
        }

        val safeFileName = "${item.id.hashCode()}_${item.name}"
        val targetFile = File(cacheSubdir, safeFileName)

        if (targetFile.exists() && targetFile.length() > 0L) {
            return@withContext Result.success(targetFile)
        }

        Result.failure(Exception("FTP direct transfer unavailable for ${item.name}"))
    }
}
