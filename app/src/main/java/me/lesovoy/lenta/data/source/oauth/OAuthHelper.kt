package me.lesovoy.lenta.data.source.oauth

import me.lesovoy.lenta.data.source.StorageSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class OAuthAuthResult(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long = 0,
    val userEmail: String? = null,
    val error: String? = null,
    val rawUrl: String = ""
)

object OAuthHelper {

    val GOOGLE_DEFAULT_CLIENT_ID: String get() = OAuthConfig.google.clientId
    val GOOGLE_REDIRECT_URI: String get() = OAuthConfig.google.redirectUri
    val GOOGLE_SCOPES: String get() = OAuthConfig.google.scopes

    val ONEDRIVE_DEFAULT_CLIENT_ID: String get() = OAuthConfig.oneDrive.clientId
    val ONEDRIVE_REDIRECT_URI: String get() = OAuthConfig.oneDrive.redirectUri
    val ONEDRIVE_SCOPES: String get() = OAuthConfig.oneDrive.scopes

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun buildAuthUrl(
        type: StorageSourceType,
        customClientId: String? = null,
        customRedirectUri: String? = null
    ): String {
        val config = OAuthConfig.getConfigForType(type) ?: return ""
        val clientId = customClientId?.ifBlank { null } ?: config.clientId
        val redirectUri = customRedirectUri?.ifBlank { null } ?: config.redirectUri
        val encodedRedirect = URLEncoder.encode(redirectUri, "UTF-8")
        val encodedScopes = URLEncoder.encode(config.scopes, "UTF-8")
        val encodedClientId = URLEncoder.encode(clientId, "UTF-8")

        val promptParam = if (config.prompt.isNotBlank()) "&prompt=${config.prompt}" else ""
        return "${config.authEndpoint}" +
                "?client_id=$encodedClientId" +
                "&redirect_uri=$encodedRedirect" +
                "&response_type=${config.responseType}" +
                "&scope=$encodedScopes" +
                promptParam
    }

    fun isRedirectUrl(type: StorageSourceType, url: String): Boolean {
        if (url.isBlank()) return false
        val cleanUrl = url.lowercase()

        if (cleanUrl.startsWith("me.lesovoy.lenta://") ||
            cleanUrl.startsWith("me.lesovoy.lenta:/") ||
            cleanUrl.startsWith("lenta://") ||
            cleanUrl.startsWith("msauth://me.lesovoy.lenta")
        ) {
            return true
        }

        if (cleanUrl.contains("#access_token=") || cleanUrl.contains("?access_token=") || cleanUrl.contains("&access_token=")) {
            return true
        }

        if (cleanUrl.contains("error=access_denied") || cleanUrl.contains("error=")) {
            return true
        }

        val config = OAuthConfig.getConfigForType(type)
        if (config != null) {
            for (prefix in config.redirectUrlPrefixes) {
                if (cleanUrl.startsWith(prefix.lowercase())) {
                    return true
                }
            }
            if (cleanUrl.contains("oauth2callback")) {
                return true
            }
        }

        return false
    }

    fun parseOAuthResult(url: String): OAuthAuthResult? {
        if (url.isBlank()) return null

        val params = mutableMapOf<String, String>()

        // Extract hash fragment parameters (#access_token=...&...)
        val fragment = url.substringAfter('#', "")
        if (fragment.isNotEmpty()) {
            parseQueryString(fragment, params)
        }

        // Extract query parameters (?access_token=...&...)
        val query = url.substringBefore('#').substringAfter('?', "")
        if (query.isNotEmpty()) {
            parseQueryString(query, params)
        }

        val error = params["error"]?.let { err ->
            val desc = params["error_description"]
            if (desc != null) "$err: $desc" else err
        }

        val accessToken = params["access_token"]
        if (accessToken != null && accessToken.isNotBlank()) {
            val tokenType = params["token_type"] ?: "Bearer"
            val expiresIn = params["expires_in"]?.toLongOrNull() ?: 0L
            return OAuthAuthResult(
                accessToken = accessToken,
                tokenType = tokenType,
                expiresIn = expiresIn,
                error = null,
                rawUrl = url
            )
        }

        if (error != null) {
            return OAuthAuthResult(
                accessToken = "",
                error = error,
                rawUrl = url
            )
        }

        return null
    }

    private fun parseQueryString(queryString: String, outMap: MutableMap<String, String>) {
        val pairs = queryString.split("&")
        for (pair in pairs) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                val key = try { URLDecoder.decode(parts[0], "UTF-8") } catch (_: Exception) { parts[0] }
                val value = try { URLDecoder.decode(parts[1], "UTF-8") } catch (_: Exception) { parts[1] }
                if (key.isNotBlank()) {
                    outMap[key] = value
                }
            }
        }
    }

    suspend fun fetchUserProfile(type: StorageSourceType, accessToken: String): Result<String> = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Access token is blank"))
        }

        try {
            val endpoint = OAuthConfig.getConfigForType(type)?.userInfoEndpoint
                ?: return@withContext Result.failure(IllegalArgumentException("Unsupported OAuth type: $type"))

            val authHeader = if (accessToken.startsWith("Bearer ", ignoreCase = true)) accessToken else "Bearer $accessToken"
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", authHeader)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }

                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)

                val accountName = when (type) {
                    StorageSourceType.GOOGLE_DRIVE -> {
                        json.optString("email").ifBlank {
                            json.optString("name").ifBlank { "Google Drive User" }
                        }
                    }
                    StorageSourceType.ONEDRIVE -> {
                        json.optString("userPrincipalName").ifBlank {
                            json.optString("mail").ifBlank {
                                json.optString("displayName").ifBlank { "OneDrive User" }
                            }
                        }
                    }
                    else -> "Cloud User"
                }

                Result.success(accountName)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
