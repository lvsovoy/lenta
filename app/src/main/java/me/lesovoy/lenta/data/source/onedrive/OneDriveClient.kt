package me.lesovoy.lenta.data.source.onedrive

import android.content.Context
import me.lesovoy.lenta.data.local.LocalMediaRepository
import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.model.MediaType
import me.lesovoy.lenta.data.source.RemoteFileClient
import me.lesovoy.lenta.data.source.StorageSource
import me.lesovoy.lenta.data.source.StorageSourceType
import me.lesovoy.lenta.data.source.oauth.OAuthConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class OneDriveClient(override val source: StorageSource) : RemoteFileClient {

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val graphBaseUrl: String
        get() = OAuthConfig.oneDrive.apiBaseUrl.ifBlank { "https://graph.microsoft.com/v1.0/me/drive" }

    override fun getAuthHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val token = source.token.ifBlank { source.password }.trim()
        if (token.isNotEmpty()) {
            headers["Authorization"] = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
        }
        return headers
    }

    override fun getPreviewUrl(item: MediaItem): String? {
        return item.uriString.ifBlank { getStreamUrl(item) }
    }

    override fun getStreamUrl(item: MediaItem): String {
        return if (item.uriString.startsWith("http") && !item.uriString.contains("/thumbnails/")) {
            item.uriString
        } else {
            "$graphBaseUrl/items/${item.id}/content"
        }
    }

    override suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!source.isConfigured()) {
            return@withContext Result.failure(IllegalStateException("OneDrive token or credentials missing"))
        }

        try {
            val reqBuilder = Request.Builder().url(graphBaseUrl)
            for ((k, v) in getAuthHeaders()) {
                reqBuilder.header(k, v)
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else if (response.code == 401 || response.code == 403) {
                    Result.failure(Exception("OneDrive authentication failed (HTTP ${response.code}): invalid token or expired session"))
                } else {
                    Result.failure(Exception("OneDrive error HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun listFolder(
        remotePath: String,
        showHidden: Boolean
    ): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        if (!source.isConfigured()) {
            return@withContext Result.failure(IllegalStateException("OneDrive source not configured"))
        }

        try {
            val url = if (remotePath.isBlank() || remotePath == "/" || remotePath == "root") {
                "$graphBaseUrl/root/children"
            } else {
                "$graphBaseUrl/items/$remotePath/children"
            }

            val reqBuilder = Request.Builder().url(url)
            for ((k, v) in getAuthHeaders()) {
                reqBuilder.header(k, v)
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }

                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val valueArray = json.optJSONArray("value") ?: org.json.JSONArray()
                val items = mutableListOf<MediaItem>()

                for (i in 0 until valueArray.length()) {
                    val fileObj = valueArray.getJSONObject(i)
                    val id = fileObj.getString("id")
                    val name = fileObj.getString("name")
                    val size = fileObj.optLong("size", 0L)
                    val modifiedTimeStr = fileObj.optString("lastModifiedDateTime", "")
                    val modifiedTime = parseIsoDate(modifiedTimeStr)
                    val isFolder = fileObj.has("folder")
                    val downloadUrl = fileObj.optString("@microsoft.graph.downloadUrl", "")
                    val mimeType = fileObj.optJSONObject("file")?.optJSONObject("mimeType")?.toString() ?: ""

                    if (showHidden || !name.startsWith(".")) {
                        if (isFolder) {
                            items.add(
                                MediaItem(
                                    id = id,
                                    name = name,
                                    path = id,
                                    uriString = "",
                                    type = MediaType.IMAGE,
                                    size = size,
                                    dateModified = modifiedTime,
                                    isDirectory = true,
                                    isNextcloud = false,
                                    mimeType = "inode/directory",
                                    sourceId = source.id,
                                    sourceType = StorageSourceType.ONEDRIVE,
                                    remotePath = id
                                )
                            )
                        } else {
                            val ext = name.substringAfterLast('.', "")
                            val mediaType = LocalMediaRepository.getMediaTypeFromExtension(ext)
                                ?: getMediaTypeFromMime(mimeType)

                            if (mediaType != null) {
                                items.add(
                                    MediaItem(
                                        id = id,
                                        name = name,
                                        path = id,
                                        uriString = downloadUrl.ifEmpty { "$graphBaseUrl/items/$id/content" },
                                        type = mediaType,
                                        size = size,
                                        dateModified = modifiedTime,
                                        isDirectory = false,
                                        isNextcloud = false,
                                        mimeType = mimeType,
                                        sourceId = source.id,
                                        sourceType = StorageSourceType.ONEDRIVE,
                                        remotePath = id
                                    )
                                )
                            }
                        }
                    }
                }

                val sorted = items.sortedWith(
                    compareBy<MediaItem> { !it.isDirectory }
                        .thenBy { it.name.lowercase(Locale.getDefault()) }
                )
                Result.success(sorted)
            }
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

        try {
            val streamUrl = getStreamUrl(item)
            val reqBuilder = Request.Builder().url(streamUrl)
            for ((k, v) in getAuthHeaders()) {
                reqBuilder.header(k, v)
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }

                val body = response.body ?: return@withContext Result.failure(Exception("Empty body response"))
                val tempFile = File(cacheSubdir, "$safeFileName.tmp")

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (tempFile.renameTo(targetFile)) {
                    Result.success(targetFile)
                } else {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                    Result.success(targetFile)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getMediaTypeFromMime(mimeType: String): MediaType? {
        val lower = mimeType.lowercase(Locale.ROOT)
        return when {
            lower == "image/gif" -> MediaType.GIF
            lower.startsWith("image/") -> MediaType.IMAGE
            lower.startsWith("video/") -> MediaType.VIDEO
            lower.startsWith("audio/") -> MediaType.AUDIO
            lower.contains("presentation") || lower.contains("powerpoint") -> MediaType.PRESENTATION
            lower.contains("epub") || lower.contains("fb2") || lower.contains("fictionbook") || lower.contains("mobipocket") -> MediaType.EBOOK
            lower.startsWith("text/") && (lower.contains("plain") || lower.contains("markdown")) -> MediaType.EBOOK
            lower.contains("comic") || lower.contains("cbz") || lower.contains("cbr") -> MediaType.CBZ
            lower.contains("document") || lower.contains("word") || lower.contains("pdf") || lower.contains("spreadsheet") || lower.contains("excel") -> MediaType.DOCUMENT
            else -> null
        }
    }

    private fun parseIsoDate(iso: String): Long {
        if (iso.isBlank()) return 0L
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.parse(iso)?.time ?: 0L
        } catch (_: Exception) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                sdf.parse(iso)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }
}
