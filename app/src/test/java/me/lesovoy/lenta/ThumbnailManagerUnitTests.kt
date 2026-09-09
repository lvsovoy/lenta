package me.lesovoy.lenta

import me.lesovoy.lenta.data.cbz.CbzReader
import me.lesovoy.lenta.data.local.LocalMediaRepository
import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.model.MediaType
import me.lesovoy.lenta.data.nextcloud.NextcloudClient
import me.lesovoy.lenta.data.nextcloud.NextcloudPreferences
import me.lesovoy.lenta.data.thumbnail.ThumbnailManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThumbnailManagerUnitTests {

    @Test
    fun testCbzCoverExtractionAndCaching() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "thumb_test_${System.currentTimeMillis()}")
        val cacheDir = File(tempDir, "cache")
        tempDir.mkdirs()
        cacheDir.mkdirs()

        try {
            // Create a mock CBZ file with 3 images in random order in the ZIP
            val cbzFile = File(tempDir, "sample_comic.cbz")
            ZipOutputStream(FileOutputStream(cbzFile)).use { zip ->
                val entries = listOf(
                    "Chapter 1/page_02.jpg" to "page 2 content",
                    "Chapter 1/page_10.jpg" to "page 10 content",
                    "Chapter 1/page_01.jpg" to "page 1 cover content",
                    "notes.txt" to "not an image"
                )
                for ((name, content) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }

            // Extract cover thumbnail
            val cover1 = CbzReader.getComicCover(cacheDir, cbzFile)
            assertNotNull(cover1)
            assertTrue(cover1!!.exists())
            assertEquals("page 1 cover content", cover1.readText())

            // Second call should return the cached file directly from disk without rebuilding
            val cover2 = CbzReader.getComicCover(cacheDir, cbzFile)
            assertNotNull(cover2)
            assertEquals(cover1.absolutePath, cover2!!.absolutePath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testVideoThumbnailDiskCachingHit() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "video_thumb_test_${System.currentTimeMillis()}")
        val cacheDir = File(tempDir, "cache")
        tempDir.mkdirs()
        cacheDir.mkdirs()

        try {
            val videoFile = File(tempDir, "sample_video.mp4")
            videoFile.writeText("fake video data bytes")

            val cacheKey = "vid_${videoFile.name.hashCode()}_${videoFile.length()}_${videoFile.lastModified()}.jpg"
            val videoThumbDir = File(cacheDir, "${ThumbnailManager.THUMBNAIL_DIR}/${ThumbnailManager.VIDEO_SUBDIR}")
            videoThumbDir.mkdirs()
            val preCachedFile = File(videoThumbDir, cacheKey)
            preCachedFile.writeText("pre-cached thumbnail jpeg data")

            // Test on-disk cache hit
            val result = ThumbnailManager.getVideoThumbnail(cacheDir, videoFile)
            assertNotNull(result)
            assertEquals(preCachedFile.absolutePath, result!!.absolutePath)
            assertEquals("pre-cached thumbnail jpeg data", result.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testCacheSizeCalculationAcrossDirectories() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "calc_test_${System.currentTimeMillis()}")
        val cacheDir = File(tempDir, "cache")
        tempDir.mkdirs()
        cacheDir.mkdirs()

        try {
            val thumbSubDir = File(cacheDir, "thumbnail_cache/video")
            thumbSubDir.mkdirs()
            File(thumbSubDir, "v1.jpg").writeBytes(ByteArray(1024)) // 1 KB

            val cbzSubDir = File(cacheDir, "thumbnail_cache/cbz")
            cbzSubDir.mkdirs()
            File(cbzSubDir, "c1.jpg").writeBytes(ByteArray(2048)) // 2 KB

            val coilSubDir = File(cacheDir, "thumbnail_cache/coil")
            coilSubDir.mkdirs()
            File(coilSubDir, "coil1.cache").writeBytes(ByteArray(4096)) // 4 KB

            val cbzCacheDir = File(cacheDir, "cbz_cache")
            cbzCacheDir.mkdirs()
            File(cbzCacheDir, "page1.png").writeBytes(ByteArray(1024)) // 1 KB

            val docCacheDir = File(cacheDir, "doc_cache/doc1")
            docCacheDir.mkdirs()
            File(docCacheDir, "page1.png").writeBytes(ByteArray(512)) // 0.5 KB

            val uriDocsDir = File(cacheDir, "uri_docs_cache")
            uriDocsDir.mkdirs()
            File(uriDocsDir, "temp.pdf").writeBytes(ByteArray(512)) // 0.5 KB

            // Online files
            val nextcloudCacheDir = File(cacheDir, "nextcloud_cache")
            nextcloudCacheDir.mkdirs()
            File(nextcloudCacheDir, "sample_video.mp4").writeBytes(ByteArray(16384)) // 16 KB

            val remoteCacheDir = File(cacheDir, "remote_cache/gdrive_1")
            remoteCacheDir.mkdirs()
            File(remoteCacheDir, "sample_doc.pdf").writeBytes(ByteArray(8192)) // 8 KB

            val thumbnailBytes = ThumbnailManager.getThumbnailCacheSizeBytes(cacheDir)
            assertEquals(9216L, thumbnailBytes) // 1024 + 2048 + 4096 + 1024 + 512 + 512 = 9216 B = 9.0 KB
            assertEquals("9.0 KB", ThumbnailManager.formatCacheSize(thumbnailBytes))

            val onlineBytes = ThumbnailManager.getOnlineFilesCacheSizeBytes(cacheDir)
            assertEquals(24576L, onlineBytes) // 16384 + 8192 = 24576 B = 24.0 KB
            assertEquals("24.0 KB", ThumbnailManager.formatCacheSize(onlineBytes))

            val totalBytes = ThumbnailManager.getCacheSizeBytes(cacheDir)
            assertEquals(33792L, totalBytes) // 9216 + 24576 = 33792 B = 33.0 KB
            assertEquals("33.0 KB", ThumbnailManager.formatCacheSize(totalBytes))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testClearSeparateCaches() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "clear_sep_test_${System.currentTimeMillis()}")
        val cacheDir = File(tempDir, "cache")
        tempDir.mkdirs()
        cacheDir.mkdirs()

        try {
            val thumbSubDir = File(cacheDir, "thumbnail_cache/video")
            thumbSubDir.mkdirs()
            val thumbFile = File(thumbSubDir, "v1.jpg")
            thumbFile.writeBytes(ByteArray(1024))

            val cbzCacheDir = File(cacheDir, "cbz_cache")
            cbzCacheDir.mkdirs()
            val cbzFile = File(cbzCacheDir, "page1.png")
            cbzFile.writeBytes(ByteArray(1024))

            val nextcloudCacheDir = File(cacheDir, "nextcloud_cache")
            nextcloudCacheDir.mkdirs()
            val ncFile = File(nextcloudCacheDir, "video.mp4")
            ncFile.writeBytes(ByteArray(4096))

            val remoteCacheDir = File(cacheDir, "remote_cache/src1")
            remoteCacheDir.mkdirs()
            val remoteFile = File(remoteCacheDir, "photo.jpg")
            remoteFile.writeBytes(ByteArray(2048))

            // Clear only thumbnail cache
            ThumbnailManager.clearThumbnailCache(cacheDir)
            assertFalse(File(cacheDir, "thumbnail_cache").exists())
            assertFalse(File(cacheDir, "cbz_cache").exists())
            assertTrue(ncFile.exists())
            assertTrue(remoteFile.exists())
            assertEquals(0L, ThumbnailManager.getThumbnailCacheSizeBytes(cacheDir))
            assertEquals(6144L, ThumbnailManager.getOnlineFilesCacheSizeBytes(cacheDir))

            // Clear only online files cache
            ThumbnailManager.clearOnlineFilesCache(cacheDir)
            assertFalse(File(cacheDir, "nextcloud_cache").exists())
            assertFalse(File(cacheDir, "remote_cache").exists())
            assertEquals(0L, ThumbnailManager.getOnlineFilesCacheSizeBytes(cacheDir))
            assertEquals(0L, ThumbnailManager.getCacheSizeBytes(cacheDir))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFormatCacheSize() {
        assertEquals("0 B", ThumbnailManager.formatCacheSize(0L))
        assertEquals("500 B", ThumbnailManager.formatCacheSize(500L))
        assertEquals("1.5 KB", ThumbnailManager.formatCacheSize(1536L))
        assertEquals("10.0 MB", ThumbnailManager.formatCacheSize(10L * 1024 * 1024))
        assertEquals("2.5 GB", ThumbnailManager.formatCacheSize((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testNextcloudPreviewUrlGeneration() {
        val prefs = NextcloudPreferences().apply {
            serverUrl = "https://cloud.example.com"
            username = "alice"
            password = "secretpassword"
        }
        val client = NextcloudClient(prefs)

        val item = MediaItem(
            id = "photos/summer vacation/beach.jpg",
            name = "beach.jpg",
            path = "photos/summer vacation/beach.jpg",
            uriString = "https://cloud.example.com/remote.php/dav/files/alice/photos/summer%20vacation/beach.jpg",
            type = MediaType.IMAGE,
            isNextcloud = true,
            nextcloudPath = "photos/summer vacation/beach.jpg"
        )

        val previewUrl = client.getPreviewUrl(item)
        assertNotNull(previewUrl)
        assertEquals(
            "https://cloud.example.com/index.php/core/preview.png?file=photos%2Fsummer%20vacation%2Fbeach.jpg&x=512&y=512&a=true",
            previewUrl
        )
    }

    @Test
    fun testNextcloudPreviewUrlWhenNotConfigured() {
        val emptyPrefs = NextcloudPreferences()
        val client = NextcloudClient(emptyPrefs)

        val item = MediaItem(
            id = "test.jpg",
            name = "test.jpg",
            path = "test.jpg",
            uriString = "https://example.com/test.jpg",
            type = MediaType.IMAGE,
            isNextcloud = true
        )

        val previewUrl = client.getPreviewUrl(item)
        assertNull(previewUrl)
    }

    @Test
    fun testPregenerateLocalThumbnailsAndIsCached() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "pregen_test_${System.currentTimeMillis()}")
        val cacheDir = File(tempDir, "cache")
        tempDir.mkdirs()
        cacheDir.mkdirs()

        try {
            // Create a mock CBZ file
            val cbzFile = File(tempDir, "comic1.cbz")
            ZipOutputStream(FileOutputStream(cbzFile)).use { zip ->
                zip.putNextEntry(ZipEntry("cover.jpg"))
                zip.write("cbz cover data".toByteArray())
                zip.closeEntry()
            }

            // Create a mock video file with pre-cached thumbnail
            val videoFile = File(tempDir, "video1.mp4")
            videoFile.writeText("fake video data")
            val vidCacheKey = "vid_${videoFile.name.hashCode()}_${videoFile.length()}_${videoFile.lastModified()}.jpg"
            val videoThumbDir = File(cacheDir, "thumbnail_cache/video")
            videoThumbDir.mkdirs()
            val videoThumbFile = File(videoThumbDir, vidCacheKey)
            videoThumbFile.writeText("cached video frame jpeg")

            // Create an image file
            val imageFile = File(tempDir, "photo1.jpg")
            imageFile.writeText("fake photo data")

            // Create directory item
            val dirItem = MediaItem(
                id = tempDir.absolutePath,
                name = tempDir.name,
                path = tempDir.absolutePath,
                uriString = tempDir.toURI().toString(),
                type = MediaType.IMAGE,
                isDirectory = true
            )
            val cbzItem = MediaItem(
                id = cbzFile.absolutePath,
                name = cbzFile.name,
                path = cbzFile.absolutePath,
                uriString = cbzFile.toURI().toString(),
                type = MediaType.CBZ
            )
            val videoItem = MediaItem(
                id = videoFile.absolutePath,
                name = videoFile.name,
                path = videoFile.absolutePath,
                uriString = videoFile.toURI().toString(),
                type = MediaType.VIDEO
            )
            val imageItem = MediaItem(
                id = imageFile.absolutePath,
                name = imageFile.name,
                path = imageFile.absolutePath,
                uriString = imageFile.toURI().toString(),
                type = MediaType.IMAGE
            )

            val items = listOf(dirItem, cbzItem, videoItem, imageItem)

            // Before pregeneration, CBZ cover is not cached yet
            val isCbzCachedBefore = ThumbnailManager.isThumbnailCached(cacheDir, cbzItem)
            org.junit.Assert.assertFalse(isCbzCachedBefore)

            // Video is already cached
            val isVideoCachedBefore = ThumbnailManager.isThumbnailCached(cacheDir, videoItem)
            assertTrue(isVideoCachedBefore)

            // Run automated pre-generation for the folder
            val generated = ThumbnailManager.pregenerateLocalThumbnails(cacheDir, items)
            assertEquals(3, generated) // cbz, video, image processed

            // After pregeneration, CBZ cover must be cached on disk
            val isCbzCachedAfter = ThumbnailManager.isThumbnailCached(cacheDir, cbzItem)
            assertTrue(isCbzCachedAfter)

            // Verify CBZ cover file exists on disk
            val coverKey = "cover_${cbzFile.name.hashCode()}_${cbzFile.length()}_${cbzFile.lastModified()}"
            val cbzThumbDir = File(cacheDir, "thumbnail_cache/cbz")
            val cachedCoverFile = cbzThumbDir.listFiles { _, name -> name.startsWith(coverKey) }?.firstOrNull()
            assertNotNull(cachedCoverFile)
            assertTrue(cachedCoverFile!!.exists())
            assertEquals("cbz cover data", cachedCoverFile.readText())

            // Modifying the CBZ file should make the old cache key obsolete and trigger new thumbnail generation
            Thread.sleep(10)
            cbzFile.setLastModified(System.currentTimeMillis() + 5000L)
            val isCbzCachedAfterMod = ThumbnailManager.isThumbnailCached(cacheDir, cbzItem)
            org.junit.Assert.assertFalse(isCbzCachedAfterMod)

            // Running pregeneration again generates thumbnail for the modified file
            val newCbzItem = MediaItem(
                id = cbzFile.absolutePath,
                name = cbzFile.name,
                path = cbzFile.absolutePath,
                uriString = cbzFile.toURI().toString(),
                type = MediaType.CBZ
            )
            ThumbnailManager.pregenerateLocalThumbnails(cacheDir, listOf(newCbzItem))
            assertTrue(ThumbnailManager.isThumbnailCached(cacheDir, newCbzItem))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLargeVideoCacheHitAndInvalidation() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "large_vid_test_${System.currentTimeMillis()}")
        val cacheDir = File(tempDir, "cache")
        tempDir.mkdirs()
        cacheDir.mkdirs()

        try {
            // Mock a 4K large video file (e.g. 4GB represented by file length metadata)
            val largeVideo = File(tempDir, "4k_recording_huge.mp4")
            largeVideo.writeText("simulated 4k video header and frames")

            val item = MediaItem(
                id = largeVideo.absolutePath,
                name = largeVideo.name,
                path = largeVideo.absolutePath,
                uriString = largeVideo.toURI().toString(),
                type = MediaType.VIDEO,
                size = 4L * 1024 * 1024 * 1024 // 4 GB
            )

            // Initially not cached
            org.junit.Assert.assertFalse(ThumbnailManager.isThumbnailCached(cacheDir, item))

            // Pre-create the disk-cached thumbnail
            val cacheKey = "vid_${largeVideo.name.hashCode()}_${largeVideo.length()}_${largeVideo.lastModified()}.jpg"
            val videoThumbDir = File(cacheDir, "thumbnail_cache/video")
            videoThumbDir.mkdirs()
            val thumbFile = File(videoThumbDir, cacheKey)
            thumbFile.writeText("scaled 512x288 jpeg")

            // Cache hit check
            assertTrue(ThumbnailManager.isThumbnailCached(cacheDir, item))

            val retrieved = ThumbnailManager.getVideoThumbnail(cacheDir, largeVideo)
            assertNotNull(retrieved)
            assertEquals(thumbFile.absolutePath, retrieved!!.absolutePath)
            assertEquals("scaled 512x288 jpeg", retrieved.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFirstMediaInFolderForFolderThumbnail() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "folder_thumb_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val emptySubdir = File(tempDir, "EmptyFolder")
            emptySubdir.mkdirs()
            assertNull(LocalMediaRepository.getFirstMediaFileInDirectory(emptySubdir))

            val mediaSubdir = File(tempDir, "CameraRoll")
            mediaSubdir.mkdirs()
            File(mediaSubdir, "binary_file.dat").writeText("hello")
            assertNull(LocalMediaRepository.getFirstMediaFileInDirectory(mediaSubdir))

            val photo = File(mediaSubdir, "photo_01.jpg")
            photo.writeText("photo jpeg")
            val firstMedia = LocalMediaRepository.getFirstMediaFileInDirectory(mediaSubdir)
            assertNotNull(firstMedia)
            assertEquals("photo_01.jpg", firstMedia!!.name)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testCbzStreamingCoverExtractionWithSubfolders() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "cbz_subfolder_test_${System.currentTimeMillis()}")
        val cacheDir = File(tempDir, "cache")
        tempDir.mkdirs()
        cacheDir.mkdirs()

        try {
            val cbzFile = File(tempDir, "manga_vol_01.cbz")
            ZipOutputStream(FileOutputStream(cbzFile)).use { zip ->
                val entries = listOf(
                    "Manga/Chapter 02/page_01.jpg" to "ch 2 page 1",
                    "Manga/Chapter 01/page_02.jpg" to "ch 1 page 2",
                    "Manga/Chapter 01/page_01.png" to "first page of ch 1 - cover",
                    "__MACOSX/._page_00.jpg" to "macos metadata",
                    ".hidden/dummy.jpg" to "hidden file"
                )
                for ((name, content) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }

            val cover = CbzReader.getComicCover(cacheDir, cbzFile)
            assertNotNull(cover)
            assertTrue(cover!!.exists())
            assertEquals("first page of ch 1 - cover", cover.readText())
            assertEquals("png", cover.extension)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testNextcloudVideoThumbnailCachingHit() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "nc_vid_test_${System.currentTimeMillis()}")
        val cacheDir = File(tempDir, "cache")
        tempDir.mkdirs()
        cacheDir.mkdirs()

        try {
            val item = MediaItem(
                id = "Videos/holiday.mp4",
                name = "holiday.mp4",
                path = "Videos/holiday.mp4",
                uriString = "https://cloud.example.com/remote.php/dav/files/user/Videos/holiday.mp4",
                type = MediaType.VIDEO,
                size = 10485760L,
                dateModified = 1700000000000L,
                isNextcloud = true,
                nextcloudPath = "Videos/holiday.mp4"
            )

            // Initially not cached
            org.junit.Assert.assertFalse(ThumbnailManager.isThumbnailCached(cacheDir, item))

            // Pre-create disk cache thumbnail
            val cacheKey = ThumbnailManager.getVideoThumbnailCacheKey(item)
            val videoThumbDir = ThumbnailManager.getVideoThumbnailDir(cacheDir)
            val thumbFile = File(videoThumbDir, cacheKey)
            thumbFile.writeText("pre-cached nextcloud video thumbnail")

            // Cache hit
            assertTrue(ThumbnailManager.isThumbnailCached(cacheDir, item))

            val retrieved = ThumbnailManager.getVideoThumbnail(cacheDir, item)
            assertNotNull(retrieved)
            assertEquals(thumbFile.absolutePath, retrieved!!.absolutePath)
            assertEquals("pre-cached nextcloud video thumbnail", retrieved.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testNextcloudCbzCoverCachingHit() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "nc_cbz_test_${System.currentTimeMillis()}")
        val cacheDir = File(tempDir, "cache")
        tempDir.mkdirs()
        cacheDir.mkdirs()

        try {
            val item = MediaItem(
                id = "Comics/comic.cbz",
                name = "comic.cbz",
                path = "Comics/comic.cbz",
                uriString = "https://cloud.example.com/remote.php/dav/files/user/Comics/comic.cbz",
                type = MediaType.CBZ,
                size = 5242880L,
                dateModified = 1700000000000L,
                isNextcloud = true,
                nextcloudPath = "Comics/comic.cbz"
            )

            // Initially not cached
            org.junit.Assert.assertFalse(ThumbnailManager.isThumbnailCached(cacheDir, item))

            // Pre-create disk cache cover
            val cacheKey = ThumbnailManager.getCbzCoverCacheKey(item)
            val cbzThumbDir = ThumbnailManager.getCbzThumbnailDir(cacheDir)
            val coverFile = File(cbzThumbDir, "${cacheKey}.jpg")
            coverFile.writeText("pre-cached nextcloud cbz cover")

            // Cache hit
            assertTrue(ThumbnailManager.isThumbnailCached(cacheDir, item))

            val retrieved = ThumbnailManager.getCbzCoverThumbnail(cacheDir, item)
            assertNotNull(retrieved)
            assertEquals(coverFile.absolutePath, retrieved!!.absolutePath)
            assertEquals("pre-cached nextcloud cbz cover", retrieved.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testNextcloudDownloadedCacheResolution() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "nc_downloaded_test_${System.currentTimeMillis()}")
        val cacheDir = File(tempDir, "cache")
        val ncCacheDir = File(cacheDir, "nextcloud_cache")
        tempDir.mkdirs()
        cacheDir.mkdirs()
        ncCacheDir.mkdirs()

        try {
            // Create a downloaded CBZ file in nextcloud_cache
            val cbzItem = MediaItem(
                id = "Comics/issue1.cbz",
                name = "issue1.cbz",
                path = "Comics/issue1.cbz",
                uriString = "https://cloud.example.com/remote.php/dav/files/user/Comics/issue1.cbz",
                type = MediaType.CBZ,
                size = 1000L,
                isNextcloud = true
            )
            val downloadedCbz = File(ncCacheDir, "${cbzItem.id.hashCode()}_${cbzItem.name}")
            ZipOutputStream(FileOutputStream(downloadedCbz)).use { zip ->
                zip.putNextEntry(ZipEntry("cover.jpg"))
                zip.write("downloaded cbz cover data".toByteArray())
                zip.closeEntry()
            }

            assertTrue(ThumbnailManager.isThumbnailCached(cacheDir, cbzItem))

            val cover = ThumbnailManager.getCbzCoverThumbnail(cacheDir, cbzItem)
            assertNotNull(cover)
            assertTrue(cover!!.exists())
            assertEquals("downloaded cbz cover data", cover.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSingleColorFrameDetection() {
        // Pure black frame (0, 0, 0)
        val blackScore = ThumbnailManager.calculateColorDifference(100, 100) { _, _ -> 0xFF000000.toInt() }
        assertEquals(0.0, blackScore, 0.001)
        assertTrue(ThumbnailManager.isSingleColor(blackScore))

        // Pure white frame (255, 255, 255)
        val whiteScore = ThumbnailManager.calculateColorDifference(100, 100) { _, _ -> 0xFFFFFFFF.toInt() }
        assertEquals(0.0, whiteScore, 0.001)
        assertTrue(ThumbnailManager.isSingleColor(whiteScore))

        // Solid green frame (0, 255, 0)
        val greenScore = ThumbnailManager.calculateColorDifference(100, 100) { _, _ -> 0xFF00FF00.toInt() }
        assertEquals(0.0, greenScore, 0.001)
        assertTrue(ThumbnailManager.isSingleColor(greenScore))

        // Minor compression noise on black (pixels varying between 0 and 3)
        val noiseScore = ThumbnailManager.calculateColorDifference(100, 100) { x, y ->
            val noise = (x + y) % 4
            (0xFF shl 24) or (noise shl 16) or (noise shl 8) or noise
        }
        assertTrue("Noise score should be recognized as single color, but was: $noiseScore", noiseScore < 10.0)
        assertTrue(ThumbnailManager.isSingleColor(noiseScore))

        // Colorful frame with high contrast (half red, half blue)
        val colorfulScore = ThumbnailManager.calculateColorDifference(100, 100) { x, _ ->
            if (x < 50) 0xFFFF0000.toInt() else 0xFF0000FF.toInt()
        }
        assertTrue("Colorful score should be large (> 20.0), was: $colorfulScore", colorfulScore > 20.0)
        org.junit.Assert.assertFalse(ThumbnailManager.isSingleColor(colorfulScore))
    }

    @Test
    fun testCandidateVideoFrameTimestampsProgressiveAdvancement() {
        // Known duration of 60 seconds (60,000,000 us)
        val durationUs = 60_000_000L
        val timestamps = ThumbnailManager.getCandidateVideoFrameTimestamps(durationUs)

        // Must start with early timestamps and advance strictly forward
        assertTrue("Timestamps list must not be empty", timestamps.isNotEmpty())
        assertTrue("Initial timestamp should be around 1-3s", timestamps.contains(1_000_000L))

        // Ensure all timestamps are within duration
        for (ts in timestamps) {
            assertTrue("Timestamp $ts must be >= 0", ts >= 0L)
            assertTrue("Timestamp $ts must be < duration $durationUs", ts < durationUs)
        }

        // Ensure offsets advance forward
        val forwardPart = timestamps.takeWhile { it >= 1_000_000L }
        for (i in 0 until forwardPart.size - 1) {
            assertTrue("Timestamps should advance forward in time", forwardPart[i] < forwardPart[i + 1])
        }
    }

    @Test
    fun testCandidateVideoFrameTimestampsWithUnknownDuration() {
        val timestamps = ThumbnailManager.getCandidateVideoFrameTimestamps(0L)
        assertTrue("Timestamps must not be empty", timestamps.isNotEmpty())
        assertTrue("Must include 1s initial offset", timestamps.contains(1_000_000L))
        assertTrue("Must include 2s offset", timestamps.contains(2_000_000L))
        assertTrue("Must include 5s offset", timestamps.contains(5_000_000L))
    }

    @Test
    fun testGenerateThumbnailForLocalCbz() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "gen_thumb_local_cbz_${System.currentTimeMillis()}")
        val cacheDir = File(tempDir, "cache")
        tempDir.mkdirs()
        cacheDir.mkdirs()

        try {
            val cbzFile = File(tempDir, "comic.cbz")
            ZipOutputStream(FileOutputStream(cbzFile)).use { zip ->
                zip.putNextEntry(ZipEntry("01_cover.jpg"))
                zip.write("cover data content".toByteArray())
                zip.closeEntry()
            }

            val item = MediaItem(
                id = "local_cbz_1",
                name = "comic.cbz",
                path = cbzFile.absolutePath,
                uriString = "",
                type = MediaType.CBZ,
                size = cbzFile.length(),
                dateModified = cbzFile.lastModified()
            )

            val generatedThumb = ThumbnailManager.generateThumbnail(cacheDir, item)
            assertNotNull(generatedThumb)
            assertTrue(generatedThumb!!.exists())
            assertTrue(generatedThumb.length() > 0L)
            assertEquals("cover data content", generatedThumb.readText())

            // Verify it is now recognized as cached
            assertTrue(ThumbnailManager.isThumbnailCached(cacheDir, item))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testGenerateThumbnailForRemoteDownloadedCbz() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "gen_thumb_remote_cbz_${System.currentTimeMillis()}")
        val cacheDir = File(tempDir, "cache")
        val ncCacheDir = File(cacheDir, "nextcloud_cache")
        tempDir.mkdirs()
        cacheDir.mkdirs()
        ncCacheDir.mkdirs()

        try {
            val item = MediaItem(
                id = "remote_cbz_1",
                name = "issue_remote.cbz",
                path = "Comics/issue_remote.cbz",
                uriString = "https://cloud.example.com/remote.php/dav/files/user/Comics/issue_remote.cbz",
                type = MediaType.CBZ,
                size = 5000L,
                dateModified = 12345678L,
                isNextcloud = true
            )

            val downloadedFile = File(ncCacheDir, "${item.id.hashCode()}_${item.name}")
            ZipOutputStream(FileOutputStream(downloadedFile)).use { zip ->
                zip.putNextEntry(ZipEntry("front.jpg"))
                zip.write("remote comic front cover".toByteArray())
                zip.closeEntry()
            }

            val generatedThumb = ThumbnailManager.generateThumbnail(cacheDir, item)
            assertNotNull(generatedThumb)
            assertTrue(generatedThumb!!.exists())
            assertTrue(generatedThumb.length() > 0L)
            assertEquals("remote comic front cover", generatedThumb.readText())

            // Verify it was copied to the expected remote cache key
            val remCacheKey = ThumbnailManager.getCbzCoverCacheKey(item)
            val expectedCachedCover = File(ThumbnailManager.getCbzThumbnailDir(cacheDir), "${remCacheKey}.jpg")
            assertTrue(expectedCachedCover.exists())
            assertEquals("remote comic front cover", expectedCachedCover.readText())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testGenerateThumbnailForLocalImage() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "gen_thumb_image_${System.currentTimeMillis()}")
        val cacheDir = File(tempDir, "cache")
        tempDir.mkdirs()
        cacheDir.mkdirs()

        try {
            val imgFile = File(tempDir, "photo.jpg")
            imgFile.writeText("sample photo bytes")

            val item = MediaItem(
                id = "local_img_1",
                name = "photo.jpg",
                path = imgFile.absolutePath,
                uriString = "",
                type = MediaType.IMAGE,
                size = imgFile.length(),
                dateModified = imgFile.lastModified()
            )

            val generatedThumb = ThumbnailManager.generateThumbnail(cacheDir, item)
            assertNotNull(generatedThumb)
            assertTrue(generatedThumb!!.exists())
            assertEquals(imgFile.absolutePath, generatedThumb.absolutePath)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
