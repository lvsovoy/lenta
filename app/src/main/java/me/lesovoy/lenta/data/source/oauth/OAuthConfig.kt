package me.lesovoy.lenta.data.source.oauth

import me.lesovoy.lenta.data.source.StorageSourceType

/**
 * Developer Application Configuration for OAuth and Web Login storage sources.
 *
 * Developers can modify credentials (Client IDs, Client Secrets if applicable),
 * redirect URIs, OAuth scopes, authorization endpoints, API base URLs,
 * and web login settings directly in this configuration file for development,
 * testing, or custom OAuth client setups.
 */
data class OAuthProviderConfig(
    val clientId: String,
    val redirectUri: String,
    val scopes: String,
    val authEndpoint: String,
    val userInfoEndpoint: String,
    val apiBaseUrl: String = "",
    val responseType: String = "token",
    val prompt: String = "select_account",
    val redirectUrlPrefixes: List<String> = emptyList()
)

data class WebLoginConfig(
    val userAgent: String = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
    val connectTimeoutSeconds: Long = 15L,
    val readTimeoutSeconds: Long = 15L
)

object OAuthConfig {

    /**
     * Default Google Drive OAuth2 Developer Configuration.
     */
    val DEFAULT_GOOGLE = OAuthProviderConfig(
        clientId = "767261370439-u6iul4fgpeqj8daudasn680g22h54jgd.apps.googleusercontent.com",
        redirectUri = "me.lesovoy.lenta://oauth2callback",
        scopes = "https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/drive.file https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile",
        authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
        userInfoEndpoint = "https://www.googleapis.com/oauth2/v3/userinfo",
        apiBaseUrl = "https://www.googleapis.com/drive/v3",
        responseType = "token",
        prompt = "select_account",
        redirectUrlPrefixes = listOf(
            "me.lesovoy.lenta://oauth2callback",
            "me.lesovoy.lenta:/oauth2callback",
            "lenta://oauth2callback",
            "lenta://oauth/callback",
            "https://oauth.pstmn.io/v1/callback",
            "http://localhost",
            "https://localhost"
        )
    )

    /**
     * Default Microsoft OneDrive OAuth2 Developer Configuration.
     */
    val DEFAULT_ONEDRIVE = OAuthProviderConfig(
        clientId = "169ecc20-4c23-45e2-89d6-c71a539f86fd",
        redirectUri = "me.lesovoy.lenta://oauth2callback",
        scopes = "Files.Read Files.Read.All User.Read offline_access openid profile email",
        authEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        userInfoEndpoint = "https://graph.microsoft.com/v1.0/me",
        apiBaseUrl = "https://graph.microsoft.com/v1.0/me/drive",
        responseType = "token",
        prompt = "select_account",
        redirectUrlPrefixes = listOf(
            "me.lesovoy.lenta://oauth2callback",
            "me.lesovoy.lenta:/oauth2callback",
            "lenta://oauth2callback",
            "lenta://oauth/callback",
            "msauth://me.lesovoy.lenta",
            "https://login.microsoftonline.com/common/oauth2/nativeclient",
            "http://localhost",
            "https://localhost"
        )
    )

    /**
     * Default Web Login Dialog Configuration.
     */
    val DEFAULT_WEB_LOGIN = WebLoginConfig()

    /**
     * Active Google Drive OAuth configuration (can be updated or overridden by developers).
     */
    var google: OAuthProviderConfig = DEFAULT_GOOGLE

    /**
     * Active Microsoft OneDrive OAuth configuration (can be updated or overridden by developers).
     */
    var oneDrive: OAuthProviderConfig = DEFAULT_ONEDRIVE

    /**
     * Active Web Login configuration (can be updated or overridden by developers).
     */
    var webLogin: WebLoginConfig = DEFAULT_WEB_LOGIN

    /**
     * Returns the OAuth configuration for a given storage source type.
     */
    fun getConfigForType(type: StorageSourceType): OAuthProviderConfig? {
        return when (type) {
            StorageSourceType.GOOGLE_DRIVE -> google
            StorageSourceType.ONEDRIVE -> oneDrive
            else -> null
        }
    }

    /**
     * Resets active configurations back to their default values.
     */
    fun resetToDefaults() {
        google = DEFAULT_GOOGLE
        oneDrive = DEFAULT_ONEDRIVE
        webLogin = DEFAULT_WEB_LOGIN
    }
}
