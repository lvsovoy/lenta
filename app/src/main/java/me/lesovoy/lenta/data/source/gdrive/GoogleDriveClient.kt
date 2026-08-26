package me.lesovoy.lenta.data.source.gdrive

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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class GoogleDriveClient(override val source: StorageSource) : RemoteFileClient {

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val apiBaseUrl: String
        get() = OAuthConfig.google.apiBaseUrl.ifBlank { "https://www.googleapis.com/drive/v3" }

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
        return "$apiBaseUrl/files/${item.id}?alt=media"
    }

    override suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!source.isConfigured()) {
            return@withContext Result.failure(IllegalStateException("Google Drive token or credentials missing"))
        }

        try {
            val urlBuilder = "$apiBaseUrl/files".toHttpUrlOrNull()?.newBuilder()
                ?: return@withContext Result.failure(IllegalStateException("Invalid Google Drive API URL"))

            urlBuilder.addQueryParameter("pageSize", "1")
            urlBuilder.addQueryParameter("fields", "files(id, name)")

            val reqBuilder = Request.Builder().url(urlBuilder.build())
            for ((k, v) in getAuthHeaders()) {
                reqBuilder.header(k, v)
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else if (response.code == 401 || response.code == 403) {
                    Result.failure(Exception("Google Drive authentication failed (HTTP ${response.code}): invalid token or insufficient scopes"))
                } else {
                    Result.failure(Exception("Google Drive error HTTP ${response.code}: ${response.message}"))
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
            return@withContext Result.failure(IllegalStateException("Google Drive source not configured"))
        }

        try {
            val parentId = if (remotePath.isBlank() || remotePath == "/" || remotePath == "root") "root" else remotePath
            val query = "'$parentId' in parents and trashed = false"

            val urlBuilder = "$apiBaseUrl/files".toHttpUrlOrNull()?.newBuilder()
                ?: return@withContext Result.failure(IllegalStateException("Invalid URL"))

            urlBuilder.addQueryParameter("q", query)
            urlBuilder.addQueryParameter("pageSize", "100")
            urlBuilder.addQueryParameter("fields", "files(id, name, mimeType, size, modifiedTime, thumbnailLink, webContentLink)")

            val reqBuilder = Request.Builder().url(urlBuilder.build())
            for ((k, v) in getAuthHeaders()) {
                reqBuilder.header(k, v)
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }

                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val filesArray = json.optJSONArray("files") ?: org.json.JSONArray()
                val items = mutableListOf<MediaItem>()

                for (i in 0 until filesArray.length()) {
                    val fileObj = filesArray.getJSONObject(i)
                    val id = fileObj.getString("id")
                    val name = fileObj.getString("name")
                    val mimeType = fileObj.optString("mimeType", "")
                    val size = fileObj.optLong("size", 0L)
                    val modifiedTimeStr = fileObj.optString("modifiedTime", "")
                    val modifiedTime = parseIsoDate(modifiedTimeStr)
                    val thumbnailLink = fileObj.optString("thumbnailLink", "")

                    if (showHidden || !name.startsWith(".")) {
                        val isFolder = (mimeType == "application/vnd.google-apps.folder")
                        if (isFolder) {
                            items.add(
                                MediaItem(
                                    id = id,
                                    name = name,
                                    path = id,
                                    uriString = thumbnailLink,
                                    type = MediaType.IMAGE,
                                    size = size,
                                    dateModified = modifiedTime,
                                    isDirectory = true,
                                    isNextcloud = false,
                                    mimeType = mimeType,
                                    sourceId = source.id,
                                    sourceType = StorageSourceType.GOOGLE_DRIVE,
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
                                        uriString = thumbnailLink.ifEmpty { "$apiBaseUrl/files/$id?alt=media" },
                                        type = mediaType,
                                        size = size,
                                        dateModified = modifiedTime,
                                        isDirectory = false,
                                        isNextcloud = false,
                                        mimeType = mimeType,
                                        sourceId = source.id,
                                        sourceType = StorageSourceType.GOOGLE_DRIVE,
                                        remotePath = id
                                    )
                                )
                            }
                        }
                    }
                }

                // Sort directories first, then alphabetical
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
            lower.contains("presentation") || lower.contains("powerpoint") || lower.contains("google-apps.presentation") -> MediaType.PRESENTATION
            lower.contains("epub") || lower.contains("fb2") || lower.contains("fictionbook") || lower.contains("mobipocket") -> MediaType.EBOOK
            lower.startsWith("text/") && (lower.contains("plain") || lower.contains("markdown")) -> MediaType.EBOOK
            lower.contains("comic") || lower.contains("cbz") || lower.contains("cbr") -> MediaType.CBZ
            lower.contains("document") || lower.contains("word") || lower.contains("pdf") || lower.contains("spreadsheet") || lower.contains("excel") || lower.contains("google-apps.document") || lower.contains("google-apps.spreadsheet") -> MediaType.DOCUMENT
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
