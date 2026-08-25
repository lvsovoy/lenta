package me.lesovoy.lenta.data.nextcloud

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

class NextcloudClient(val preferences: NextcloudPreferences) : RemoteFileClient {

    override val source: StorageSource
        get() = StorageSource(
            id = StorageSource.NEXTCLOUD_SOURCE_ID,
            name = "Nextcloud",
            type = StorageSourceType.NEXTCLOUD,
            serverUrl = preferences.serverUrl,
            username = preferences.username,
            password = preferences.password,
            showHidden = preferences.showHiddenFiles,
            isDefault = true
        )

    override fun getAuthHeaders(): Map<String, String> {
        return mapOf("Authorization" to getAuthHeader())
    }

    override fun getStreamUrl(item: MediaItem): String {
        return item.uriString
    }

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
        val user = try {
            java.net.URLEncoder.encode(preferences.username.trim(), "UTF-8").replace("+", "%20")
        } catch (_: Exception) {
            preferences.username.trim()
        }
        return "$base/remote.php/dav/files/$user"
    }

    fun getLegacyWebDavBaseUrl(): String {
        val base = normalizeServerUrl(preferences.serverUrl)
        return "$base/remote.php/webdav"
    }

    fun getAuthHeader(password: String = preferences.password): String {
        return Credentials.basic(preferences.username.trim(), password)
    }

    private fun getCandidatePasswords(): List<String> {
        val original = preferences.password
        val candidates = mutableListOf(original)
        if (original.contains("-") || original.contains(" ")) {
            val stripped = original.replace("-", "").replace(" ", "")
            if (stripped.isNotBlank() && !candidates.contains(stripped)) {
                candidates.add(stripped)
            }
        } else if (original.length == 25 && original.all { it.isLetterOrDigit() }) {
            val chunked = original.chunked(5).joinToString("-")
            if (!candidates.contains(chunked)) {
                candidates.add(chunked)
            }
        }
        return candidates
    }

    private fun getCandidateBaseUrls(): List<String> {
        val urls = mutableListOf<String>()
        val primary = getWebDavBaseUrl()
        urls.add(primary)
        val legacy = getLegacyWebDavBaseUrl()
        if (!urls.contains(legacy)) {
            urls.add(legacy)
        }
        val unencoded = "${normalizeServerUrl(preferences.serverUrl)}/remote.php/dav/files/${preferences.username.trim()}"
        if (!urls.contains(unencoded)) {
            urls.add(unencoded)
        }
        return urls
    }

    /**
     * Generates a preview thumbnail URL for a Nextcloud file.
     */
    override fun getPreviewUrl(item: MediaItem): String? {
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

    override suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!preferences.isConfigured()) {
            return@withContext Result.failure(IllegalStateException("Server URL, username and password must not be empty"))
        }

        val propfindXml = """<?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:resourcetype/>
              </d:prop>
            </d:propfind>
        """.trimIndent()

        var lastError: Exception? = null
        val passwords = getCandidatePasswords()
        val baseUrls = getCandidateBaseUrls()

        for (pass in passwords) {
            val authHeader = getAuthHeader(pass)
            for (baseUrl in baseUrls) {
                val url = baseUrl.trimEnd('/') + "/"
                try {
                    val request = Request.Builder()
                        .url(url)
                        .method("PROPFIND", propfindXml.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                        .header("Authorization", authHeader)
                        .header("Depth", "0")
                        .build()

                    val (isSuccess, code, message) = httpClient.newCall(request).execute().use { response ->
                        Triple(response.isSuccessful || response.code == 207, response.code, response.message)
                    }

                    if (isSuccess) {
                        if (pass != preferences.password) {
                            preferences.password = pass
                        }
                        return@withContext Result.success(true)
                    } else if (code == 401) {
                        lastError = Exception("Authentication failed: invalid username or password (401)")
                    } else {
                        lastError = Exception("Server returned HTTP $code: $message")
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }

        Result.failure(lastError ?: Exception("Authentication failed: invalid username or password (401)"))
    }

    override suspend fun listFolder(
        remotePath: String,
        showHidden: Boolean
    ): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        if (!preferences.isConfigured()) {
            return@withContext Result.failure(IllegalStateException("Nextcloud not configured"))
        }

        val cleanPath = remotePath.trim('/').let { if (it.isEmpty()) "" else "/$it" }
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

        var lastError: Exception? = null
        val passwords = getCandidatePasswords()
        val baseUrls = getCandidateBaseUrls()

        for (pass in passwords) {
            val authHeader = getAuthHeader(pass)
            for (baseUrl in baseUrls) {
                val url = "${baseUrl.trimEnd('/')}$cleanPath/"
                try {
                    val request = Request.Builder()
                        .url(url)
                        .method("PROPFIND", propfindXml.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                        .header("Authorization", authHeader)
                        .header("Depth", "1")
                        .build()

                    val (isSuccess, code, message, xmlBody) = httpClient.newCall(request).execute().use { response ->
                        val body = if (response.isSuccessful || response.code == 207) response.body?.string().orEmpty() else ""
                        Quad(response.isSuccessful || response.code == 207, response.code, response.message, body)
                    }

                    if (isSuccess) {
                        if (pass != preferences.password) {
                            preferences.password = pass
                        }
                        val items = parseWebDavResponse(xmlBody, cleanPath, showHidden)
                        return@withContext Result.success(items)
                    } else if (code == 401) {
                        lastError = Exception("HTTP 401: Unauthorized")
                    } else {
                        lastError = Exception("HTTP $code: $message")
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }

        Result.failure(lastError ?: Exception("Nextcloud folder listing failed"))
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

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

        val username = preferences.username.trim()
        val encodedUsername = try {
            java.net.URLEncoder.encode(username, "UTF-8").replace("+", "%20")
        } catch (_: Exception) {
            username
        }
        val davPrefixes = listOf(
            "/remote.php/dav/files/$username",
            "/remote.php/dav/files/$encodedUsername",
            "/remote.php/webdav",
            "/remote.php/dav"
        )
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
            var matchedRelPath: String? = null
            for (prefix in davPrefixes) {
                if (decodedHref.contains(prefix)) {
                    matchedRelPath = decodedHref.substringAfter(prefix)
                    break
                }
            }
            val relPath = (matchedRelPath ?: decodedHref).trim('/')

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
                                mimeType = "inode/directory",
                                sourceId = StorageSource.NEXTCLOUD_SOURCE_ID,
                                sourceType = StorageSourceType.NEXTCLOUD,
                                remotePath = relPath
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
                                    mimeType = contentType,
                                    sourceId = StorageSource.NEXTCLOUD_SOURCE_ID,
                                    sourceType = StorageSourceType.NEXTCLOUD,
                                    remotePath = relPath
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
    override suspend fun downloadToCache(context: Context, item: MediaItem): Result<File> = withContext(Dispatchers.IO) {
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
