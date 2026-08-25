package com.example.lenta.data.nextcloud

import android.content.Context
import com.example.lenta.data.local.LocalMediaRepository
import com.example.lenta.data.model.MediaItem
import com.example.lenta.data.model.MediaType
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

class NextcloudClient(val preferences: NextcloudPreferences) {

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

    fun getWebDavBaseUrl(): String {
        val base = normalizeServerUrl(preferences.serverUrl)
        val user = preferences.username.trim()
        return "$base/remote.php/dav/files/$user"
    }

    fun getAuthHeader(): String {
        return Credentials.basic(preferences.username.trim(), preferences.password)
    }

    /**
     * Generates a preview thumbnail URL for a Nextcloud file.
     */
    fun getPreviewUrl(item: MediaItem): String? {
        if (!preferences.isConfigured()) return null
        val base = normalizeServerUrl(preferences.serverUrl)
        val path = item.nextcloudPath ?: item.path
        val cleanPath = path.trim('/')
        return if (cleanPath.isNotEmpty()) {
            try {
                val encoded = java.net.URLEncoder.encode(cleanPath, "UTF-8").replace("+", "%20")
                "$base/index.php/core/preview.png?file=$encoded&x=512&y=512&a=true"
            } catch (_: Throwable) {
                null
            }
        } else null
    }

    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!preferences.isConfigured()) {
            return@withContext Result.failure(IllegalStateException("Server URL, username and password must not be empty"))
        }

        try {
            val url = getWebDavBaseUrl()
            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", null)
                .header("Authorization", getAuthHeader())
                .header("Depth", "0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 207) {
                    Result.success(true)
                } else if (response.code == 401) {
                    Result.failure(Exception("Authentication failed: invalid username or password (401)"))
                } else {
                    Result.failure(Exception("Server returned HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listFolder(
        remotePath: String = "",
        showHidden: Boolean = preferences.showHiddenFiles
    ): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        if (!preferences.isConfigured()) {
            return@withContext Result.failure(IllegalStateException("Nextcloud not configured"))
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

            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", propfindXml.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Authorization", getAuthHeader())
                .header("Depth", "1")
                .build()

            httpClient.newCall(request).execute().use { response ->
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

    fun parseWebDavResponse(
        xml: String,
        currentRemotePath: String,
        showHidden: Boolean = preferences.showHiddenFiles
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

        val davPrefix = "/remote.php/dav/files/${preferences.username.trim()}"
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
            val relPath = if (decodedHref.contains(davPrefix)) {
                decodedHref.substringAfter(davPrefix)
            } else {
                decodedHref
            }.trim('/')

            // Ignore the current directory itself
            if (relPath != currentClean && decodedHref.isNotEmpty()) {
                val name = if (displayName.isNotBlank()) {
                    displayName
                } else {
                    relPath.substringAfterLast('/')
                }

                if (name.isNotEmpty() && (showHidden || !name.startsWith("."))) {
                    val fullFileUrl = if (href.startsWith("http")) {
                        href
                    } else {
                        val base = normalizeServerUrl(preferences.serverUrl)
                        val pathPart = if (href.startsWith("/")) href else "/$href"
                        "$base$pathPart"
                    }

                    if (isCollection) {
                        items.add(
                            MediaItem(
                                id = relPath,
                                name = name,
                                path = relPath,
                                uriString = fullFileUrl,
                                type = MediaType.IMAGE,
                                size = contentLength,
                                dateModified = lastModified,
                                isDirectory = true,
                                isNextcloud = true,
                                nextcloudPath = relPath,
                                mimeType = "inode/directory"
                            )
                        )
                    } else {
                        val ext = name.substringAfterLast('.', "")
                        val mediaType = LocalMediaRepository.getMediaTypeFromExtension(ext)
                        if (mediaType != null) {
                            items.add(
                                MediaItem(
                                    id = relPath,
                                    name = name,
                                    path = relPath,
                                    uriString = fullFileUrl,
                                    type = mediaType,
                                    size = contentLength,
                                    dateModified = lastModified,
                                    isDirectory = false,
                                    isNextcloud = true,
                                    nextcloudPath = relPath,
                                    mimeType = contentType
                                )
                            )
                        }
                    }
                }
            }
        }

        val dirs = items.filter { it.isDirectory }.sortedBy { it.name.lowercase(Locale.ROOT) }
        val files = items.filter { !it.isDirectory }.sortedBy { it.name.lowercase(Locale.ROOT) }
        return dirs + files
    }

    private fun getChildText(parent: Element, tagName: String): String {
        val nodes = parent.getElementsByTagName(tagName)
        if (nodes.length > 0) {
            return nodes.item(0).textContent?.trim() ?: ""
        }
        return ""
    }

    private fun parseHttpDate(dateStr: String): Long {
        return try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            format.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Downloads a Nextcloud media file to local cache for streaming/reading (e.g. for CBZ, Video, Image)
     */
    suspend fun downloadToCache(context: Context, item: MediaItem): Result<File> = withContext(Dispatchers.IO) {
        val cacheSubdir = File(context.cacheDir, "nextcloud_cache")
        cacheSubdir.mkdirs()
        val safeFileName = "${item.id.hashCode()}_${item.name}"
        val localFile = File(cacheSubdir, safeFileName)

        if (localFile.exists() && localFile.length() > 0 && (item.size <= 0 || localFile.length() == item.size)) {
            return@withContext Result.success(localFile)
        }

        try {
            val request = Request.Builder()
                .url(item.uriString)
                .header("Authorization", getAuthHeader())
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }

                val body = response.body ?: return@withContext Result.failure(Exception("Empty body"))
                val tempFile = File(cacheSubdir, "$safeFileName.tmp")

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (tempFile.renameTo(localFile)) {
                    Result.success(localFile)
                } else {
                    Result.success(tempFile)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
