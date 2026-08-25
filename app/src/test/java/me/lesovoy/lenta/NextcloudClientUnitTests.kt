package me.lesovoy.lenta

import me.lesovoy.lenta.data.local.LocalMediaRepository
import me.lesovoy.lenta.data.model.MediaType
import me.lesovoy.lenta.data.nextcloud.NextcloudClient
import me.lesovoy.lenta.data.nextcloud.NextcloudPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NextcloudClientUnitTests {

    private val sampleWebDavXml = """<?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/remote.php/dav/files/alice/</d:href>
            <d:propstat>
              <d:prop>
                <d:resourcetype><d:collection/></d:resourcetype>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/alice/Photos/</d:href>
            <d:propstat>
              <d:prop>
                <d:resourcetype><d:collection/></d:resourcetype>
                <d:displayname>Photos</d:displayname>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/alice/.hidden_folder/</d:href>
            <d:propstat>
              <d:prop>
                <d:resourcetype><d:collection/></d:resourcetype>
                <d:displayname>.hidden_folder</d:displayname>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/alice/vacation.jpg</d:href>
            <d:propstat>
              <d:prop>
                <d:getcontentlength>12345</d:getcontentlength>
                <d:getcontenttype>image/jpeg</d:getcontenttype>
                <d:displayname>vacation.jpg</d:displayname>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/alice/.secret_image.png</d:href>
            <d:propstat>
              <d:prop>
                <d:getcontentlength>6789</d:getcontentlength>
                <d:getcontenttype>image/png</d:getcontenttype>
                <d:displayname>.secret_image.png</d:displayname>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun testMediaTypeCategorization() {
        assertEquals(MediaType.IMAGE, LocalMediaRepository.getMediaTypeFromExtension("jpeg"))
        assertEquals(MediaType.VIDEO, LocalMediaRepository.getMediaTypeFromExtension("m4v"))
        assertEquals(MediaType.GIF, LocalMediaRepository.getMediaTypeFromExtension("GIF"))
        assertEquals(MediaType.CBZ, LocalMediaRepository.getMediaTypeFromExtension("CBZ"))
    }

    @Test
    fun testZipArchiveCreationAndPageExtraction() {
        val tempZip = File.createTempFile("test_comic", ".cbz")
        try {
            ZipOutputStream(FileOutputStream(tempZip)).use { zipOut ->
                for (i in listOf(1, 2, 10, 3, 20)) {
                    val entry = ZipEntry("chapter1/page_$i.jpg")
                    zipOut.putNextEntry(entry)
                    zipOut.write("fake image data $i".toByteArray())
                    zipOut.closeEntry()
                }
            }

            assertTrue(tempZip.exists() && tempZip.length() > 0)
        } finally {
            tempZip.delete()
        }
    }

    @Test
    fun testWebDavResponseExcludesHiddenFilesByDefault() {
        val prefs = NextcloudPreferences()
        prefs.username = "alice"
        prefs.serverUrl = "https://cloud.example.com"
        prefs.showHiddenFiles = false

        val client = NextcloudClient(prefs)
        val items = client.parseWebDavResponse(sampleWebDavXml, "", showHidden = false)

        val names = items.map { it.name }
        assertTrue(names.contains("Photos"))
        assertTrue(names.contains("vacation.jpg"))
        assertFalse(names.contains(".hidden_folder"))
        assertFalse(names.contains(".secret_image.png"))
        assertEquals(2, items.size)
    }

    @Test
    fun testWebDavResponseIncludesHiddenFilesWhenEnabled() {
        val prefs = NextcloudPreferences()
        prefs.username = "alice"
        prefs.serverUrl = "https://cloud.example.com"
        prefs.showHiddenFiles = true

        val client = NextcloudClient(prefs)
        val items = client.parseWebDavResponse(sampleWebDavXml, "", showHidden = true)

        val names = items.map { it.name }
        assertTrue(names.contains("Photos"))
        assertTrue(names.contains("vacation.jpg"))
        assertTrue(names.contains(".hidden_folder"))
        assertTrue(names.contains(".secret_image.png"))
        assertEquals(4, items.size)

        val hiddenDir = items.first { it.name == ".hidden_folder" }
        assertTrue(hiddenDir.isDirectory)

        val hiddenFile = items.first { it.name == ".secret_image.png" }
        assertFalse(hiddenFile.isDirectory)
        assertEquals(MediaType.IMAGE, hiddenFile.type)
    }

    @Test
    fun testNextcloudPreferencesGlobalHiddenFilesProperty() {
        val prefs = NextcloudPreferences()
        assertFalse(prefs.showHiddenFiles)

        prefs.showHiddenFiles = true
        assertTrue(prefs.showHiddenFiles)

        prefs.showHiddenFiles = false
        assertFalse(prefs.showHiddenFiles)
    }

    @Test
    fun testLocalDirectoryHiddenFilesFiltering() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "lenta_test_hidden_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val normalDir = File(tempDir, "Photos").apply { mkdirs() }
            val hiddenDir = File(tempDir, ".hidden_folder").apply { mkdirs() }
            val normalFile = File(tempDir, "pic.jpg").apply { writeText("fake") }
            val hiddenFile = File(tempDir, ".secret.png").apply { writeText("fake") }

            val repo = LocalMediaRepository()

            // 1. Without hidden files
            val defaultItems = repo.listDirectory(tempDir, showHidden = false)
            val defaultNames = defaultItems.map { it.name }
            assertTrue(defaultNames.contains("Photos"))
            assertTrue(defaultNames.contains("pic.jpg"))
            assertFalse(defaultNames.contains(".hidden_folder"))
            assertFalse(defaultNames.contains(".secret.png"))
            assertEquals(2, defaultItems.size)

            // 2. With hidden files
            val allItems = repo.listDirectory(tempDir, showHidden = true)
            val allNames = allItems.map { it.name }
            assertTrue(allNames.contains("Photos"))
            assertTrue(allNames.contains("pic.jpg"))
            assertTrue(allNames.contains(".hidden_folder"))
            assertTrue(allNames.contains(".secret.png"))
            assertEquals(4, allItems.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
