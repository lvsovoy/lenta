package me.lesovoy.lenta

import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.model.MediaType
import me.lesovoy.lenta.data.source.StorageSource
import me.lesovoy.lenta.data.source.StorageSourceNaming
import me.lesovoy.lenta.data.source.StorageSourceType
import me.lesovoy.lenta.data.source.ftp.FtpClient
import me.lesovoy.lenta.data.source.gdrive.GoogleDriveClient
import me.lesovoy.lenta.data.source.oauth.OAuthConfig
import me.lesovoy.lenta.data.source.oauth.OAuthHelper
import me.lesovoy.lenta.data.source.oauth.OAuthProviderConfig
import me.lesovoy.lenta.data.source.onedrive.OneDriveClient
import me.lesovoy.lenta.data.source.smb.SmbClient
import me.lesovoy.lenta.data.source.webdav.WebDavClient
import me.lesovoy.lenta.data.thumbnail.ThumbnailManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageSourcesUnitTests {

    @Test
    fun testStorageSourceSerialization() {
        val source = StorageSource(
            id = "source_webdav_1",
            name = "My WebDAV",
            type = StorageSourceType.WEBDAV,
            serverUrl = "https://dav.example.com",
            username = "user1",
            password = "pwd",
            token = "tok123",
            domain = "DOMAIN",
            share = "ShareName",
            port = 8080,
            rootPath = "remote/files",
            showHidden = true,
            isDefault = false
        )

        val jsonStr = source.toJsonString()
        val deserialized = StorageSource.fromJsonString(jsonStr)

        assertEquals(source.id, deserialized.id)
        assertEquals(source.name, deserialized.name)
        assertEquals(source.type, deserialized.type)
        assertEquals(source.serverUrl, deserialized.serverUrl)
        assertEquals(source.username, deserialized.username)
        assertEquals(source.password, deserialized.password)
        assertEquals(source.token, deserialized.token)
        assertEquals(source.domain, deserialized.domain)
        assertEquals(source.share, deserialized.share)
        assertEquals(source.port, deserialized.port)
        assertEquals(source.rootPath, deserialized.rootPath)
        assertEquals(source.showHidden, deserialized.showHidden)
        assertEquals(source.isDefault, deserialized.isDefault)
    }

    @Test
    fun testStorageSourceIsConfigured() {
        val local = StorageSource(id = "local", name = "Local", type = StorageSourceType.LOCAL)
        assertTrue(local.isConfigured())

        val webdavUnconfigured = StorageSource(id = "dav", name = "Dav", type = StorageSourceType.WEBDAV, serverUrl = "")
        assertFalse(webdavUnconfigured.isConfigured())

        val webdavConfigured = StorageSource(id = "dav", name = "Dav", type = StorageSourceType.WEBDAV, serverUrl = "https://dav.example.com")
        assertTrue(webdavConfigured.isConfigured())

        val gdrive = StorageSource(id = "gd", name = "GDrive", type = StorageSourceType.GOOGLE_DRIVE, token = "ya29.token")
        assertTrue(gdrive.isConfigured())

        val onedrive = StorageSource(id = "od", name = "OneDrive", type = StorageSourceType.ONEDRIVE, token = "EwBA...")
        assertTrue(onedrive.isConfigured())

        val smb = StorageSource(id = "smb", name = "SMB", type = StorageSourceType.SMB, serverUrl = "192.168.1.50")
        assertTrue(smb.isConfigured())

        val ftp = StorageSource(id = "ftp", name = "FTP", type = StorageSourceType.FTP, serverUrl = "ftp.example.com")
        assertTrue(ftp.isConfigured())
    }

    @Test
    fun testWebDavClientUrlNormalizationAndAuth() {
        val source = StorageSource(
            id = "dav1",
            name = "WebDav",
            type = StorageSourceType.WEBDAV,
            serverUrl = "dav.example.com/webdav/",
            username = "alice",
            password = "secretpassword"
        )

        val client = WebDavClient(source)
        assertEquals("https://dav.example.com/webdav", client.getWebDavBaseUrl())

        val authHeaders = client.getAuthHeaders()
        assertTrue(authHeaders.containsKey("Authorization"))
        assertTrue(authHeaders["Authorization"]!!.startsWith("Basic "))
    }

    @Test
    fun testWebDavClientResponseParsing() {
        val source = StorageSource(
            id = "dav1",
            name = "WebDav",
            type = StorageSourceType.WEBDAV,
            serverUrl = "https://dav.example.com"
        )

        val sampleXml = """<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/remote/files/</d:href>
                <d:propstat>
                  <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote/files/Videos/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype>
                    <d:displayname>Videos</d:displayname>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote/files/movie.mp4</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getcontentlength>10485760</d:getcontentlength>
                    <d:getcontenttype>video/mp4</d:getcontenttype>
                    <d:displayname>movie.mp4</d:displayname>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/remote/files/comic.cbz</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getcontentlength>5242880</d:getcontentlength>
                    <d:getcontenttype>application/x-cbz</d:getcontenttype>
                    <d:displayname>comic.cbz</d:displayname>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()

        val client = WebDavClient(source)
        val items = client.parseWebDavResponse(sampleXml, "remote/files")

        assertEquals(3, items.size)
        val folder = items[0]
        assertTrue(folder.isDirectory)
        assertEquals("Videos", folder.name)

        val video = items.first { it.name == "movie.mp4" }
        assertEquals(MediaType.VIDEO, video.type)
        assertEquals(10485760L, video.size)
        assertTrue(video.isRemote)
        assertEquals("dav1", video.sourceId)

        val cbz = items.first { it.name == "comic.cbz" }
        assertEquals(MediaType.CBZ, cbz.type)
        assertTrue(cbz.isRemote)
    }

    @Test
    fun testGoogleDriveClientAuthAndEndpoints() {
        val source = StorageSource(
            id = "gd1",
            name = "Google Drive",
            type = StorageSourceType.GOOGLE_DRIVE,
            token = "ya29.sample_oauth_token"
        )

        val client = GoogleDriveClient(source)
        val authHeaders = client.getAuthHeaders()
        assertEquals("Bearer ya29.sample_oauth_token", authHeaders["Authorization"])

        val item = MediaItem(
            id = "1AbCdEf_file_id",
            name = "holiday.png",
            path = "1AbCdEf_file_id",
            uriString = "https://lh3.googleusercontent.com/thumbnail",
            type = MediaType.IMAGE,
            sourceId = "gd1",
            sourceType = StorageSourceType.GOOGLE_DRIVE
        )

        assertEquals("https://www.googleapis.com/drive/v3/files/1AbCdEf_file_id?alt=media", client.getStreamUrl(item))
        assertEquals("https://lh3.googleusercontent.com/thumbnail", client.getPreviewUrl(item))
    }

    @Test
    fun testOneDriveClientAuthAndEndpoints() {
        val source = StorageSource(
            id = "od1",
            name = "OneDrive",
            type = StorageSourceType.ONEDRIVE,
            token = "Bearer test_bearer_token"
        )

        val client = OneDriveClient(source)
        val authHeaders = client.getAuthHeaders()
        assertEquals("Bearer test_bearer_token", authHeaders["Authorization"])

        val item = MediaItem(
            id = "01ABCDEF!123",
            name = "sample.jpg",
            path = "01ABCDEF!123",
            uriString = "https://public.sn.files.1drv.com/download_url",
            type = MediaType.IMAGE,
            sourceId = "od1",
            sourceType = StorageSourceType.ONEDRIVE
        )

        assertEquals("https://public.sn.files.1drv.com/download_url", client.getStreamUrl(item))
    }

    @Test
    fun testSmbClientHostPortShareParsing() {
        val source = StorageSource(
            id = "smb1",
            name = "Home NAS",
            type = StorageSourceType.SMB,
            serverUrl = "smb://192.168.1.100:4455/MediaFolder",
            share = "SharedMedia"
        )

        val client = SmbClient(source)
        assertEquals("192.168.1.100", client.parseSmbHost())
        assertEquals(4455, client.parseSmbPort())
        assertEquals("SharedMedia", client.parseSmbShare())

        val item = MediaItem(
            id = "Videos/home_video.mp4",
            name = "home_video.mp4",
            path = "Videos/home_video.mp4",
            uriString = "",
            type = MediaType.VIDEO,
            sourceId = "smb1",
            sourceType = StorageSourceType.SMB,
            remotePath = "Videos/home_video.mp4"
        )

        assertEquals("smb://192.168.1.100:4455/SharedMedia/Videos/home_video.mp4", client.getStreamUrl(item))
    }

    @Test
    fun testFtpClientHostPortParsing() {
        val source = StorageSource(
            id = "ftp1",
            name = "Office FTP",
            type = StorageSourceType.FTP,
            serverUrl = "ftp://ftp.example.com:2121/files",
            username = "ftpuser",
            password = "ftppassword"
        )

        val client = FtpClient(source)
        assertEquals("ftp.example.com", client.parseFtpHost())
        assertEquals(2121, client.parseFtpPort())

        val item = MediaItem(
            id = "photos/summer.jpg",
            name = "summer.jpg",
            path = "photos/summer.jpg",
            uriString = "",
            type = MediaType.IMAGE,
            sourceId = "ftp1",
            sourceType = StorageSourceType.FTP,
            remotePath = "photos/summer.jpg"
        )

        assertEquals("ftp://ftp.example.com:2121/photos/summer.jpg", client.getStreamUrl(item))
    }

    @Test
    fun testThumbnailManagerRemoteCacheKeyGeneration() {
        val remoteItem = MediaItem(
            id = "remote_item_999",
            name = "video.mp4",
            path = "remote/video.mp4",
            uriString = "https://dav.example.com/video.mp4",
            type = MediaType.VIDEO,
            size = 9876543L,
            dateModified = 1600000000000L,
            sourceId = "webdav_source",
            sourceType = StorageSourceType.WEBDAV
        )

        assertTrue(remoteItem.isRemote)
        val key = ThumbnailManager.getVideoThumbnailCacheKey(remoteItem)
        assertTrue(key.contains("vid_nc_") || key.contains("vid_rem_"))
        assertTrue(key.contains("9876543"))

        val remoteCbz = MediaItem(
            id = "remote_comic_111",
            name = "comic.cbz",
            path = "remote/comic.cbz",
            uriString = "https://dav.example.com/comic.cbz",
            type = MediaType.CBZ,
            size = 456789L,
            dateModified = 1700000000000L,
            sourceId = "gdrive_source",
            sourceType = StorageSourceType.GOOGLE_DRIVE
        )

        assertTrue(remoteCbz.isRemote)
        val cbzKey = ThumbnailManager.getCbzCoverCacheKey(remoteCbz)
        assertTrue(cbzKey.contains("cover_nc_") || cbzKey.contains("cover_rem_"))
        assertTrue(cbzKey.contains("456789"))
    }

    @Test
    fun testOAuthHelperAuthUrlBuilding() {
        val googleUrl = OAuthHelper.buildAuthUrl(StorageSourceType.GOOGLE_DRIVE)
        assertTrue(googleUrl.startsWith("https://accounts.google.com/o/oauth2/v2/auth"))
        assertTrue(googleUrl.contains("response_type=token"))
        assertTrue(googleUrl.contains("client_id="))
        assertTrue(googleUrl.contains("redirect_uri="))
        assertTrue(googleUrl.contains("drive.readonly"))

        val onedriveUrl = OAuthHelper.buildAuthUrl(StorageSourceType.ONEDRIVE)
        assertTrue(onedriveUrl.startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"))
        assertTrue(onedriveUrl.contains("response_type=token"))
        assertTrue(onedriveUrl.contains("client_id="))
        assertTrue(onedriveUrl.contains("redirect_uri="))
        assertTrue(onedriveUrl.contains("Files.Read"))
    }

    @Test
    fun testOAuthHelperRedirectDetection() {
        assertTrue(OAuthHelper.isRedirectUrl(StorageSourceType.GOOGLE_DRIVE, "me.lesovoy.lenta://oauth2callback#access_token=ya29.sample&token_type=Bearer"))
        assertTrue(OAuthHelper.isRedirectUrl(StorageSourceType.GOOGLE_DRIVE, "lenta://oauth2callback#access_token=ya29.sample"))
        assertTrue(OAuthHelper.isRedirectUrl(StorageSourceType.GOOGLE_DRIVE, "lenta://oauth/callback#access_token=ya29.sample"))
        assertTrue(OAuthHelper.isRedirectUrl(StorageSourceType.GOOGLE_DRIVE, "https://oauth.pstmn.io/v1/callback#access_token=ya29.sample&token_type=Bearer"))
        assertTrue(OAuthHelper.isRedirectUrl(StorageSourceType.GOOGLE_DRIVE, "http://localhost/oauth2callback#access_token=ya29.sample"))
        assertTrue(OAuthHelper.isRedirectUrl(StorageSourceType.ONEDRIVE, "me.lesovoy.lenta://oauth2callback#access_token=EwBA..."))
        assertTrue(OAuthHelper.isRedirectUrl(StorageSourceType.ONEDRIVE, "https://login.microsoftonline.com/common/oauth2/nativeclient#access_token=EwBA..."))
        assertTrue(OAuthHelper.isRedirectUrl(StorageSourceType.ONEDRIVE, "https://login.microsoftonline.com/common/oauth2/nativeclient?error=access_denied"))

        assertFalse(OAuthHelper.isRedirectUrl(StorageSourceType.GOOGLE_DRIVE, "https://accounts.google.com/signin/v2/identifier"))
        assertFalse(OAuthHelper.isRedirectUrl(StorageSourceType.ONEDRIVE, "https://login.microsoftonline.com/common/login"))
    }

    @Test
    fun testOAuthHelperTokenParsing() {
        val googleCallback = "me.lesovoy.lenta://oauth2callback#access_token=ya29.a0ARrdaM8SampleToken&token_type=Bearer&expires_in=3599&scope=drive.readonly"
        val googleResult = OAuthHelper.parseOAuthResult(googleCallback)
        assertNotNull(googleResult)
        assertEquals("ya29.a0ARrdaM8SampleToken", googleResult?.accessToken)
        assertEquals("Bearer", googleResult?.tokenType)
        assertEquals(3599L, googleResult?.expiresIn)
        assertNull(googleResult?.error)

        val onedriveCallback = "me.lesovoy.lenta://oauth2callback#access_token=EwBA1234Token&token_type=Bearer&expires_in=3600"
        val onedriveResult = OAuthHelper.parseOAuthResult(onedriveCallback)
        assertNotNull(onedriveResult)
        assertEquals("EwBA1234Token", onedriveResult?.accessToken)
        assertEquals("Bearer", onedriveResult?.tokenType)
        assertEquals(3600L, onedriveResult?.expiresIn)
        assertNull(onedriveResult?.error)

        val errorCallback = "me.lesovoy.lenta://oauth2callback?error=access_denied&error_description=The+user+denied+the+request"
        val errorResult = OAuthHelper.parseOAuthResult(errorCallback)
        assertNotNull(errorResult)
        assertEquals("", errorResult?.accessToken)
        assertTrue(errorResult?.error?.contains("access_denied") == true)
    }

    @Test
    fun testStorageSourceDisplaySubtitles() {
        val unauthGD = StorageSource(id = "gd", name = "Google Drive", type = StorageSourceType.GOOGLE_DRIVE)
        assertEquals("Not configured • Sign in with Google", unauthGD.getDisplaySubtitle())

        val authGD = StorageSource(id = "gd", name = "Google Drive", type = StorageSourceType.GOOGLE_DRIVE, token = "ya29.token", username = "alice@gmail.com")
        assertEquals("Connected (Google Drive • alice@gmail.com)", authGD.getDisplaySubtitle())

        val unauthOD = StorageSource(id = "od", name = "OneDrive", type = StorageSourceType.ONEDRIVE)
        assertEquals("Not configured • Sign in with Microsoft", unauthOD.getDisplaySubtitle())

        val authOD = StorageSource(id = "od", name = "OneDrive", type = StorageSourceType.ONEDRIVE, token = "EwBA.token", username = "bob@outlook.com")
        assertEquals("Connected (OneDrive • bob@outlook.com)", authOD.getDisplaySubtitle())
    }

    @Test
    fun testStorageSourceNaming() {
        // Extract host or domain
        assertEquals("cloud.example.com", StorageSourceNaming.extractHostOrDomain("https://cloud.example.com/remote.php/dav"))
        assertEquals("192.168.1.100", StorageSourceNaming.extractHostOrDomain("smb://192.168.1.100/share"))
        assertEquals("nas.local:5005", StorageSourceNaming.extractHostOrDomain("http://nas.local:5005/webdav"))
        assertEquals("ftp.server.org", StorageSourceNaming.extractHostOrDomain("ftp.server.org"))
        assertEquals("box.com", StorageSourceNaming.extractHostOrDomain("https://user:pass@box.com/dav"))

        // Nextcloud display name generation
        assertEquals(
            "Nextcloud (john@cloud.example.com)",
            StorageSourceNaming.generateDisplayName(
                type = StorageSourceType.NEXTCLOUD,
                serverUrl = "https://cloud.example.com/remote.php/dav",
                username = "john"
            )
        )
        assertEquals(
            "Nextcloud (cloud.example.com)",
            StorageSourceNaming.generateDisplayName(
                type = StorageSourceType.NEXTCLOUD,
                serverUrl = "https://cloud.example.com"
            )
        )

        // Google Drive display name generation
        assertEquals(
            "Google Drive (user@gmail.com)",
            StorageSourceNaming.generateDisplayName(
                type = StorageSourceType.GOOGLE_DRIVE,
                username = "user@gmail.com"
            )
        )
        assertEquals(
            "Google Drive",
            StorageSourceNaming.generateDisplayName(
                type = StorageSourceType.GOOGLE_DRIVE
            )
        )

        // OneDrive display name generation
        assertEquals(
            "OneDrive (user@outlook.com)",
            StorageSourceNaming.generateDisplayName(
                type = StorageSourceType.ONEDRIVE,
                username = "user@outlook.com"
            )
        )

        // SMB display name generation
        assertEquals(
            "SMB (admin@192.168.1.100)",
            StorageSourceNaming.generateDisplayName(
                type = StorageSourceType.SMB,
                serverUrl = "192.168.1.100",
                username = "admin"
            )
        )
        assertEquals(
            "SMB (192.168.1.100/Media)",
            StorageSourceNaming.generateDisplayName(
                type = StorageSourceType.SMB,
                serverUrl = "192.168.1.100",
                share = "Media"
            )
        )
        assertEquals(
            "SMB (admin@WORKGROUP)",
            StorageSourceNaming.generateDisplayName(
                type = StorageSourceType.SMB,
                domain = "WORKGROUP",
                username = "admin"
            )
        )

        // WebDAV display name generation
        assertEquals(
            "WebDAV (alice@nas.local:5005)",
            StorageSourceNaming.generateDisplayName(
                type = StorageSourceType.WEBDAV,
                serverUrl = "http://nas.local:5005/webdav",
                username = "alice"
            )
        )

        // FTP display name generation
        assertEquals(
            "FTP (guest@ftp.example.com)",
            StorageSourceNaming.generateDisplayName(
                type = StorageSourceType.FTP,
                serverUrl = "ftp.example.com",
                username = "guest"
            )
        )
    }

    @Test
    fun testOAuthConfigDefaultsAndOverrides() {
        // Test default configurations
        assertEquals("71813476288-0l2l8k1g7351vhj9q8oem6b3j7e3o8u1.apps.googleusercontent.com", OAuthConfig.DEFAULT_GOOGLE.clientId)
        assertEquals("me.lesovoy.lenta://oauth2callback", OAuthConfig.DEFAULT_GOOGLE.redirectUri)
        assertTrue(OAuthConfig.DEFAULT_GOOGLE.scopes.contains("drive.readonly"))
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", OAuthConfig.DEFAULT_GOOGLE.authEndpoint)
        assertEquals("https://www.googleapis.com/oauth2/v3/userinfo", OAuthConfig.DEFAULT_GOOGLE.userInfoEndpoint)
        assertEquals("https://www.googleapis.com/drive/v3", OAuthConfig.DEFAULT_GOOGLE.apiBaseUrl)

        assertEquals("d3590ed6-52b3-4102-aeff-aad2292ab01c", OAuthConfig.DEFAULT_ONEDRIVE.clientId)
        assertEquals("me.lesovoy.lenta://oauth2callback", OAuthConfig.DEFAULT_ONEDRIVE.redirectUri)
        assertTrue(OAuthConfig.DEFAULT_ONEDRIVE.scopes.contains("Files.Read"))
        assertEquals("https://login.microsoftonline.com/common/oauth2/v2.0/authorize", OAuthConfig.DEFAULT_ONEDRIVE.authEndpoint)
        assertEquals("https://graph.microsoft.com/v1.0/me", OAuthConfig.DEFAULT_ONEDRIVE.userInfoEndpoint)
        assertEquals("https://graph.microsoft.com/v1.0/me/drive", OAuthConfig.DEFAULT_ONEDRIVE.apiBaseUrl)

        // Test getConfigForType
        assertEquals(OAuthConfig.google, OAuthConfig.getConfigForType(StorageSourceType.GOOGLE_DRIVE))
        assertEquals(OAuthConfig.oneDrive, OAuthConfig.getConfigForType(StorageSourceType.ONEDRIVE))
        assertNull(OAuthConfig.getConfigForType(StorageSourceType.WEBDAV))

        // Test developer configuration override
        val customGoogleConfig = OAuthProviderConfig(
            clientId = "custom-dev-client-id.apps.googleusercontent.com",
            redirectUri = "https://custom.callback/oauth2",
            scopes = "https://www.googleapis.com/auth/drive.readonly",
            authEndpoint = "https://custom.google.auth/oauth2",
            userInfoEndpoint = "https://custom.google.auth/userinfo",
            apiBaseUrl = "https://custom.google.auth/api"
        )
        OAuthConfig.google = customGoogleConfig

        assertEquals("custom-dev-client-id.apps.googleusercontent.com", OAuthHelper.GOOGLE_DEFAULT_CLIENT_ID)
        val authUrl = OAuthHelper.buildAuthUrl(StorageSourceType.GOOGLE_DRIVE)
        assertTrue(authUrl.startsWith("https://custom.google.auth/oauth2"))
        assertTrue(authUrl.contains("client_id=custom-dev-client-id.apps.googleusercontent.com"))
        assertTrue(authUrl.contains("redirect_uri=https%3A%2F%2Fcustom.callback%2Foauth2"))

        // Reset to defaults
        OAuthConfig.resetToDefaults()
        assertEquals(OAuthConfig.DEFAULT_GOOGLE.clientId, OAuthHelper.GOOGLE_DEFAULT_CLIENT_ID)
        assertEquals(OAuthConfig.DEFAULT_GOOGLE, OAuthConfig.google)
        assertEquals(OAuthConfig.DEFAULT_ONEDRIVE, OAuthConfig.oneDrive)
    }

    @Test
    fun testWebLoginConfigDefaults() {
        assertNotNull(OAuthConfig.webLogin)
        assertTrue(OAuthConfig.webLogin.userAgent.contains("Mozilla/5.0"))
        assertTrue(OAuthConfig.webLogin.userAgent.contains("Chrome/"))
        assertEquals(15L, OAuthConfig.webLogin.connectTimeoutSeconds)
        assertEquals(15L, OAuthConfig.webLogin.readTimeoutSeconds)
    }
}
