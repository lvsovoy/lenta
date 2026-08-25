package me.lesovoy.lenta.data.source.webdav

import android.content.Context
import me.lesovoy.lenta.data.local.LocalMediaRepository
import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.model.MediaType
import me.lesovoy.lenta.data.source.RemoteFileClient
import me.lesovoy.lenta.data.source.StorageSource
import me.lesovoy.lenta.data.source.StorageSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

open class WebDavClient(override val source: StorageSource) : RemoteFileClient {

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun normalizeServerUrl(url: String): String {
        var clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://$clean"
        }
        return clean.trimEnd('/')
    }

    open fun getWebDavBaseUrl(): String {
        val base = normalizeServerUrl(source.serverUrl)
        val root = source.rootPath.trim('/')
        return if (root.isNotEmpty()) "$base/$root" else base
    }

    override fun getAuthHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (source.token.isNotBlank()) {
            headers["Authorization"] = "Bearer ${source.token.trim()}"
        } else if (source.username.isNotBlank() || source.password.isNotBlank()) {
            headers["Authorization"] = Credentials.basic(source.username.trim(), source.password)
        }
        return headers
    }

    override fun getPreviewUrl(item: MediaItem): String? {
        return item.uriString.ifBlank { getStreamUrl(item) }
    }

    override fun getStreamUrl(item: MediaItem): String {
        val path = item.effectiveRemotePath.trim('/')
        val base = getWebDavBaseUrl()
        return if (path.isEmpty()) base else "$base/$path"
    }

    override suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!source.isConfigured()) {
            return@withContext Result.failure(IllegalStateException("Server URL must not be empty"))
        }

        try {
            val url = getWebDavBaseUrl().trimEnd('/') + "/"
            val propfindXml = """<?xml version="1.0" encoding="utf-8" ?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:resourcetype/>
                  </d:prop>
                </d:propfind>
            """.trimIndent()

            val reqBuilder = Request.Builder()
                .url(url)
                .method("PROPFIND", propfindXml.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", "0")

            for ((k, v) in getAuthHeaders()) {
                reqBuilder.header(k, v)
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (response.isSuccessful || response.code == 207) {
                    Result.success(true)
                } else if (response.code == 401) {
                    Result.failure(Exception("Authentication failed: invalid username/password or token (401)"))
                } else {
                    Result.failure(Exception("Server returned HTTP ${response.code}: ${response.message}"))
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
            return@withContext Result.failure(IllegalStateException("Source not configured"))
        }

        try {
            val cleanPath = remotePath.trim('/').let { if (it.isEmpty()) "" else "/$it" }
            val url = "${getWebDavBaseUrl()}$cleanPath/"

            val propfindXml = """<?xml version="1.0" encoding="utf-8" ?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:getlastmodified/>
                    <d:getcontentlength/>
                    <d:getcontenttype/>
                    <d:resourcetype/>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>
            """.trimIndent()

            val reqBuilder = Request.Builder()
                .url(url)
                .method("PROPFIND", propfindXml.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", "1")

            for ((k, v) in getAuthHeaders()) {
                reqBuilder.header(k, v)
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 207) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }

                val xmlBody = response.body?.string() ?: ""
                val items = parseWebDavResponse(xmlBody, cleanPath, showHidden)
                Result.success(items)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    open fun parseWebDavResponse(
        xml: String,
        currentRemotePath: String,
        showHidden: Boolean = source.showHidden
    ): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        if (xml.isBlank()) return items

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(InputSource(StringReader(xml)))
        val responseNodes = doc.getElementsByTagName("d:response")
        val responses = if (responseNodes.length > 0) responseNodes else doc.getElementsByTagName("response")

        val currentClean = currentRemotePath.trim('/')

        for (i in 0 until responses.length) {
            val responseElem = responses.item(i) as? Element ?: continue

            val href = getChildText(responseElem, "d:href").ifEmpty { getChildText(responseElem, "href") }
            val displayName = getChildText(responseElem, "d:displayname").ifEmpty { getChildText(responseElem, "displayname") }
            val contentLength = (getChildText(responseElem, "d:getcontentlength").ifEmpty { getChildText(responseElem, "getcontentlength") }).toLongOrNull() ?: 0L
            val contentType = getChildText(responseElem, "d:getcontenttype").ifEmpty { getChildText(responseElem, "getcontenttype") }
            val lastModifiedStr = getChildText(responseElem, "d:getlastmodified").ifEmpty { getChildText(responseElem, "getlastmodified") }
            val lastModified = parseHttpDate(lastModifiedStr)

            val isCollection = responseElem.getElementsByTagName("d:collection").length > 0 ||
                    responseElem.getElementsByTagName("collection").length > 0

            val decodedHref = try { URLDecoder.decode(href, "UTF-8") } catch (e: Exception) { href }
            val relPath = decodedHref.trim('/')

            // Compute clean relative path from current path or href
            val itemName = if (displayName.isNotBlank()) {
                displayName
            } else {
                relPath.substringAfterLast('/')
            }

            if (itemName.isNotEmpty() && relPath != currentClean && decodedHref.isNotEmpty()) {
                if (showHidden || !itemName.startsWith(".")) {
                    val fullFileUrl = if (href.startsWith("http")) {
                        href
                    } else {
                        val base = normalizeServerUrl(source.serverUrl)
                        val pathPart = if (href.startsWith("/")) href else "/$href"
                        "$base$pathPart"
                    }

                    if (isCollection) {
                        items.add(
                            MediaItem(
                                id = relPath,
                                name = itemName,
                                path = relPath,
                                uriString = fullFileUrl,
                                type = MediaType.IMAGE,
                                size = contentLength,
                                dateModified = lastModified,
                                isDirectory = true,
                                isNextcloud = (source.type == StorageSourceType.NEXTCLOUD),
                                nextcloudPath = relPath,
                                mimeType = "inode/directory",
                                sourceId = source.id,
                                sourceType = source.type,
                                remotePath = relPath
                            )
                        )
                    } else {
                        val ext = itemName.substringAfterLast('.', "")
                        val mediaType = LocalMediaRepository.getMediaTypeFromExtension(ext)
                        if (mediaType != null) {
                            items.add(
                                MediaItem(
                                    id = relPath,
                                    name = itemName,
                                    path = relPath,
                                    uriString = fullFileUrl,
                                    type = mediaType,
                                    size = contentLength,
                                    dateModified = lastModified,
                                    isDirectory = false,
                                    isNextcloud = (source.type == StorageSourceType.NEXTCLOUD),
                                    nextcloudPath = relPath,
                                    mimeType = contentType,
                                    sourceId = source.id,
                                    sourceType = source.type,
                                    remotePath = relPath
                                )
                            )
                        }
                    }
                }
            }
        }

        // Sort: directories first, then alphabetically
        return items.sortedWith(
            compareBy<MediaItem> { !it.isDirectory }
                .thenBy { it.name.lowercase(Locale.getDefault()) }
        )
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

    protected fun getChildText(parent: Element, tagName: String): String {
        val list = parent.getElementsByTagName(tagName)
        if (list.length > 0) {
            return list.item(0).textContent?.trim() ?: ""
        }
        return ""
    }

    protected fun parseHttpDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        val formats = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        )
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                val date = sdf.parse(dateStr)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return 0L
    }
}
