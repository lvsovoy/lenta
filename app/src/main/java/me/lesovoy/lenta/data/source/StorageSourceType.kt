package me.lesovoy.lenta.data.source

enum class StorageSourceType(val displayName: String, val defaultPort: Int = 0) {
    LOCAL("Local Storage"),
    NEXTCLOUD("Nextcloud", 443),
    WEBDAV("WebDAV", 443),
    GOOGLE_DRIVE("Google Drive", 443),
    ONEDRIVE("OneDrive", 443),
    SMB("SMB / Windows Share", 445),
    FTP("FTP / SFTP", 21);

    companion object {
        fun fromString(value: String): StorageSourceType {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: WEBDAV
        }
    }
}
