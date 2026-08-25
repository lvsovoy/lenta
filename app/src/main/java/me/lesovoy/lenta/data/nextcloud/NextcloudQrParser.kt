package me.lesovoy.lenta.data.nextcloud

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class NextcloudQrCredentials(
    val serverUrl: String,
    val username: String,
    val password: String
)

object NextcloudQrParser {

    private val ACTION_KEYWORDS = setOf("login", "onetime-login", "auth", "connect", "")
    private val KNOWN_KEYS = setOf(
        "server", "url", "host", "serverurl", "server_url", "server-url", "address",
        "user", "username", "loginname", "login_name", "login-name", "login", "account",
        "password", "pass", "token", "secret", "apppassword", "app_password", "app-password", "apptoken", "app_token"
    )

    fun parse(rawText: String?): NextcloudQrCredentials? {
        if (rawText.isNullOrBlank()) return null
        val text = rawText.trim()

        // 1. JSON format: {"server":"...", "user":"...", "password":"..."}
        if (text.startsWith("{") && text.endsWith("}")) {
            parseJson(text)?.let { return it }
        }

        // 2. Scheme format: nc://... or nextcloud://...
        if (text.startsWith("nc://", ignoreCase = true) || text.startsWith("nextcloud://", ignoreCase = true)) {
            parseNcScheme(text)?.let { return it }
        }

        // 3. HTTP / HTTPS format with userinfo or query params
        if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
            parseHttpUri(text)?.let { return it }
        }

        // 4. Key-Value format (server=...;user=...;password=... or server:...;user:...;password:...)
        parseKeyValue(text)?.let { return it }

        // 5. Generic user:password@server format
        parseUserPassAtServer(text)?.let { return it }

        return null
    }

    private fun parseJson(text: String): NextcloudQrCredentials? {
        val map = extractJsonFields(text)
        return extractCredentials(map)
    }

    private fun extractJsonFields(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
        for (match in regex.findAll(text)) {
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2]
            result[key] = value
        }
        return result
    }

    private fun parseNcScheme(text: String): NextcloudQrCredentials? {
        val withoutScheme = if (text.startsWith("nc://", ignoreCase = true)) {
            text.substring(5)
        } else {
            text.substring(12)
        }.trim()

        // 1. Check if query parameters: nc://onetime-login?user=...&password=...&server=... or nc://cloud.com?user=...
        if (withoutScheme.contains("?")) {
            val hostPart = withoutScheme.substringBefore("?")
            val queryPart = withoutScheme.substringAfter("?")
            val map = parseKeyValueParams(queryPart).toMutableMap()
            parseQueryString(queryPart).forEach { (k, v) -> map.putIfAbsent(k, v) }

            extractCredentials(map, defaultServer = hostPart)?.let { return it }
        }

        // 2. Check if path format: nc://onetime-login/user:kleso&password:...&server:... or nc://login/...
        if (withoutScheme.contains("/")) {
            val hostOrAction = withoutScheme.substringBefore("/")
            val paramsPart = withoutScheme.substringAfter("/")
            val map = parseKeyValueParams(paramsPart)

            extractCredentials(map, defaultServer = hostOrAction)?.let { return it }
        }

        // 3. Check key-value params directly in withoutScheme
        val map = parseKeyValueParams(withoutScheme)
        extractCredentials(map)?.let { return it }

        // 4. Standard Nextcloud QR format: nc://user:password@server_url
        return parseUserPassAtServer(withoutScheme)
    }

    private fun parseHttpUri(text: String): NextcloudQrCredentials? {
        // e.g. https://user:password@cloud.example.com/remote.php/dav/files/user
        val scheme = if (text.startsWith("https://", ignoreCase = true)) "https://" else "http://"
        val remainder = text.substring(scheme.length)

        if (remainder.contains("@")) {
            val creds = parseUserPassAtServer(remainder)
            if (creds != null) {
                val fullUrl = if (!creds.serverUrl.startsWith("http://") && !creds.serverUrl.startsWith("https://")) {
                    scheme + creds.serverUrl
                } else {
                    creds.serverUrl
                }
                return creds.copy(serverUrl = cleanServerUrl(fullUrl))
            }
        }

        if (remainder.contains("?")) {
            val queryMap = parseQueryString(remainder.substringAfter("?"))
            val host = remainder.substringBefore("?")
            extractCredentials(queryMap, defaultServer = scheme + host)?.let { return it }
        }

        return null
    }

    private fun parseUserPassAtServer(text: String): NextcloudQrCredentials? {
        // e.g. "admin:xxxx-xxxx-xxxx-xxxx@https://cloud.example.com" or "user:pass@host:port/path"
        if (!text.contains("@")) return null

        val atIndex = if (text.contains("@https://", ignoreCase = true)) {
            text.indexOf("@https://", ignoreCase = true)
        } else if (text.contains("@http://", ignoreCase = true)) {
            text.indexOf("@http://", ignoreCase = true)
        } else {
            text.lastIndexOf("@")
        }

        if (atIndex <= 0 || atIndex >= text.length - 1) return null

        val userPassPart = text.substring(0, atIndex)
        val serverPart = text.substring(atIndex + 1)

        if (!userPassPart.contains(":")) return null

        val colonIndex = userPassPart.indexOf(":")
        val username = decode(userPassPart.substring(0, colonIndex).trim())
        val password = decode(userPassPart.substring(colonIndex + 1).trim())
        val serverUrl = cleanServerUrl(decode(serverPart.trim()))

        if (username.isBlank() || password.isBlank() || serverUrl.isBlank()) return null

        return NextcloudQrCredentials(serverUrl, username, password)
    }

    private fun parseKeyValue(text: String): NextcloudQrCredentials? {
        val map = parseKeyValueParams(text)
        return extractCredentials(map)
    }

    private fun extractCredentials(map: Map<String, String>, defaultServer: String? = null): NextcloudQrCredentials? {
        val serverCandidate = map["server"]
            ?: map["url"]
            ?: map["host"]
            ?: map["serverurl"]
            ?: map["server_url"]
            ?: map["server-url"]
            ?: map["address"]
            ?: defaultServer

        val user = map["user"]
            ?: map["username"]
            ?: map["loginname"]
            ?: map["login_name"]
            ?: map["login-name"]
            ?: map["login"]
            ?: map["account"]

        val pass = map["password"]
            ?: map["pass"]
            ?: map["token"]
            ?: map["secret"]
            ?: map["apppassword"]
            ?: map["app_password"]
            ?: map["app-password"]
            ?: map["apptoken"]
            ?: map["app_token"]

        if (serverCandidate.isNullOrBlank() || user.isNullOrBlank() || pass.isNullOrBlank()) {
            return null
        }

        val serverTrimmed = serverCandidate.trim()
        if (serverTrimmed.lowercase() in ACTION_KEYWORDS) {
            return null
        }

        return NextcloudQrCredentials(
            cleanServerUrl(decode(serverTrimmed)),
            decode(user.trim()),
            decode(pass.trim())
        )
    }

    private fun parseQueryString(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pairs = query.split("&")
        for (pair in pairs) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim().substringAfterLast("/").lowercase()
                result[key] = parts[1].trim()
            }
        }
        return result
    }

    private fun parseKeyValueParams(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val delimiters = charArrayOf('&', ';', '\n', '\r', ',')
        val lines = text.split(*delimiters)
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.contains("=")) {
                val rawKey = trimmed.substringBefore("=").trim()
                val cleanKey = rawKey.substringAfterLast("/").trim().lowercase()
                val value = trimmed.substringAfter("=").trim()
                if (cleanKey.isNotEmpty()) {
                    result[cleanKey] = value
                }
            } else if (trimmed.contains(":")) {
                val rawKey = trimmed.substringBefore(":").trim()
                val cleanKey = rawKey.substringAfterLast("/").trim().lowercase()
                if (cleanKey in KNOWN_KEYS) {
                    val value = trimmed.substringAfter(":").trim()
                    result[cleanKey] = value
                }
            }
        }
        return result
    }

    fun cleanServerUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            // Keep existing scheme
        } else {
            url = "https://$url"
        }

        // Remove trailing slashes and common webdav endpoints
        url = url.trimEnd('/')
        val endingsToRemove = listOf(
            "/remote.php/dav/files",
            "/remote.php/dav",
            "/remote.php/webdav",
            "/remote.php",
            "/index.php/apps/files",
            "/index.php"
        )
        for (ending in endingsToRemove) {
            if (url.endsWith(ending, ignoreCase = true)) {
                url = url.substring(0, url.length - ending.length).trimEnd('/')
            }
        }

        // Also check if remote.php/dav/files/username is in the path
        val davFilesPattern = Regex("/remote\\.php/dav/files/[^/]+/?$", RegexOption.IGNORE_CASE)
        url = url.replace(davFilesPattern, "").trimEnd('/')

        return url
    }

    private fun decode(value: String): String {
        return try {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }
}
