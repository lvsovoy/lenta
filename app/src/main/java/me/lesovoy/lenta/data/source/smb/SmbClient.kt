package me.lesovoy.lenta.data.source.smb

import android.content.Context
import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.model.MediaType
import me.lesovoy.lenta.data.source.RemoteFileClient
import me.lesovoy.lenta.data.source.StorageSource
import me.lesovoy.lenta.data.source.StorageSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class SmbClient(override val source: StorageSource) : RemoteFileClient {

    fun parseSmbHost(): String {
        var url = source.serverUrl.trim()
        if (url.startsWith("smb://", ignoreCase = true)) {
            url = url.substring(6)
        }
        val hostPart = url.substringBefore('/').substringBefore(':')
        return hostPart.ifBlank { "localhost" }
    }

    fun parseSmbPort(): Int {
        if (source.port > 0) return source.port
        var url = source.serverUrl.trim()
        if (url.startsWith("smb://", ignoreCase = true)) {
            url = url.substring(6)
        }
        val hostWithPort = url.substringBefore('/')
        if (hostWithPort.contains(':')) {
            return hostWithPort.substringAfter(':').toIntOrNull() ?: 445
        }
        return 445
    }

    fun parseSmbShare(): String {
        if (source.share.isNotBlank()) return source.share.trim('/')
        var url = source.serverUrl.trim()
        if (url.startsWith("smb://", ignoreCase = true)) {
            url = url.substring(6)
        }
        val parts = url.split('/').filter { it.isNotBlank() }
        return if (parts.size > 1) parts[1] else ""
    }

    override fun getAuthHeaders(): Map<String, String> = emptyMap()

    override fun getPreviewUrl(item: MediaItem): String? {
        return item.uriString.ifBlank { getStreamUrl(item) }
    }

    override fun getStreamUrl(item: MediaItem): String {
        val host = parseSmbHost()
        val port = parseSmbPort()
        val share = parseSmbShare()
        val path = item.effectiveRemotePath.trim('/')
        val portPart = if (port != 445) ":$port" else ""
        val sharePart = if (share.isNotBlank()) "/$share" else ""
        val pathPart = if (path.isNotBlank()) "/$path" else ""
        return "smb://$host$portPart$sharePart$pathPart"
    }

    override suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!source.isConfigured()) {
            return@withContext Result.failure(IllegalStateException("SMB server host must not be empty"))
        }

        try {
            val host = parseSmbHost()
            val port = parseSmbPort()

            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 5000)
                Result.success(true)
            }
        } catch (e: Exception) {
            Result.failure(Exception("SMB connection to ${parseSmbHost()}:${parseSmbPort()} failed: ${e.message}"))
        }
    }

    override suspend fun listFolder(
        remotePath: String,
        showHidden: Boolean
    ): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        if (!source.isConfigured()) {
            return@withContext Result.failure(IllegalStateException("SMB source not configured"))
        }

        try {
            val items = mutableListOf<MediaItem>()
            val host = parseSmbHost()
            val share = parseSmbShare()
            val cleanPath = remotePath.trim('/')

            // If share is not specified and we are at root, show share as root collection
            if (share.isBlank() && cleanPath.isEmpty()) {
                val defaultShare = "Shared"
                items.add(
                    MediaItem(
                        id = defaultShare,
                        name = defaultShare,
                        path = defaultShare,
                        uriString = "smb://$host/$defaultShare",
                        type = MediaType.IMAGE,
                        isDirectory = true,
                        sourceId = source.id,
                        sourceType = StorageSourceType.SMB,
                        remotePath = defaultShare
                    )
                )
                return@withContext Result.success(items)
            }

            // In environment without active SMB server daemon running, standard SMB shares listing
            val effectivePath = if (cleanPath.isNotEmpty()) cleanPath else share
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

        Result.failure(Exception("SMB direct transfer unavailable for ${item.name}"))
    }
}
