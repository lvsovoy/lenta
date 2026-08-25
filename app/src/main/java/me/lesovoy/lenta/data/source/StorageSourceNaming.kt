package me.lesovoy.lenta.data.source

object StorageSourceNaming {

    /**
     * Extracts the host or domain name from a URL or connection string.
     * Strips protocol schemes (https://, smb://, ftp://, etc.), paths, query strings,
     * and embedded user credentials.
     */
    fun extractHostOrDomain(serverUrl: String): String {
        val clean = serverUrl.trim()
        if (clean.isBlank()) return ""
        val withoutScheme = clean.replace(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "")
        val hostAndPort = withoutScheme.substringBefore('/').substringBefore('?')
        val hostOnly = if (hostAndPort.contains('@')) hostAndPort.substringAfterLast('@') else hostAndPort
        return hostOnly.trim()
    }

    /**
     * Generates an intuitive display name for a storage source based on its type
     * and account, domain, host, or share name.
     *
     * Examples:
     * - Nextcloud (john@cloud.example.com)
     * - Google Drive (user@gmail.com)
     * - OneDrive (user@outlook.com)
     * - WebDAV (alice@nas.local)
     * - SMB (admin@192.168.1.100) or SMB (192.168.1.100/Media)
     * - FTP (guest@ftp.example.com)
     */
    fun generateDisplayName(
        type: StorageSourceType,
        serverUrl: String = "",
        username: String = "",
        domain: String = "",
        share: String = ""
    ): String {
        val cleanUser = username.trim()
        val extractedHost = extractHostOrDomain(serverUrl)
        val cleanDomain = domain.trim()
        val cleanShare = share.trim()

        val domainOrHost = when {
            extractedHost.isNotBlank() -> extractedHost
            cleanDomain.isNotBlank() -> cleanDomain
            else -> ""
        }

        val typePrefix = when (type) {
            StorageSourceType.LOCAL -> "Local Storage"
            StorageSourceType.NEXTCLOUD -> "Nextcloud"
            StorageSourceType.WEBDAV -> "WebDAV"
            StorageSourceType.GOOGLE_DRIVE -> "Google Drive"
            StorageSourceType.ONEDRIVE -> "OneDrive"
            StorageSourceType.SMB -> "SMB"
            StorageSourceType.FTP -> "FTP"
        }

        val details = when {
            cleanUser.isNotBlank() && domainOrHost.isNotBlank() -> "$cleanUser@$domainOrHost"
            cleanUser.isNotBlank() -> cleanUser
            domainOrHost.isNotBlank() && cleanShare.isNotBlank() -> "$domainOrHost/$cleanShare"
            domainOrHost.isNotBlank() -> domainOrHost
            cleanShare.isNotBlank() -> cleanShare
            else -> ""
        }

        return if (details.isNotBlank()) {
            "$typePrefix ($details)"
        } else {
            type.displayName
        }
    }
}
