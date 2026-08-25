package me.lesovoy.lenta.data.source

import android.content.Context
import me.lesovoy.lenta.data.nextcloud.NextcloudClient
import me.lesovoy.lenta.data.nextcloud.NextcloudPreferences
import me.lesovoy.lenta.data.source.ftp.FtpClient
import me.lesovoy.lenta.data.source.gdrive.GoogleDriveClient
import me.lesovoy.lenta.data.source.onedrive.OneDriveClient
import me.lesovoy.lenta.data.source.smb.SmbClient
import me.lesovoy.lenta.data.source.webdav.WebDavClient

object SourceClientFactory {

    fun getClient(context: Context, source: StorageSource): RemoteFileClient {
        return when (source.type) {
            StorageSourceType.NEXTCLOUD -> {
                val prefs = NextcloudPreferences(context)
                if (source.serverUrl.isNotBlank()) {
                    prefs.serverUrl = source.serverUrl
                    prefs.username = source.username
                    prefs.password = source.password
                    prefs.showHiddenFiles = source.showHidden
                }
                NextcloudClient(prefs)
            }
            StorageSourceType.WEBDAV -> WebDavClient(source)
            StorageSourceType.GOOGLE_DRIVE -> GoogleDriveClient(source)
            StorageSourceType.ONEDRIVE -> OneDriveClient(source)
            StorageSourceType.SMB -> SmbClient(source)
            StorageSourceType.FTP -> FtpClient(source)
            StorageSourceType.LOCAL -> WebDavClient(source)
        }
    }

    fun getClientForSourceId(context: Context, sourceId: String): RemoteFileClient? {
        val manager = StorageSourceManager(context)
        val source = manager.getSource(sourceId) ?: return null
        return getClient(context, source)
    }
}
