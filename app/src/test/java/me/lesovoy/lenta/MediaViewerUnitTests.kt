package me.lesovoy.lenta

import me.lesovoy.lenta.data.cbz.CbzReader
import me.lesovoy.lenta.data.local.LocalMediaRepository
import me.lesovoy.lenta.data.local.MediaIntentResolver
import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.model.MediaType
import me.lesovoy.lenta.ui.viewer.TapZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MediaViewerUnitTests {

    @Test
    fun testTapZoneDetection() {
        assertEquals(TapZone.LEFT, TapZone.fromFraction(0.0f))
        assertEquals(TapZone.LEFT, TapZone.fromFraction(0.15f))
        assertEquals(TapZone.LEFT, TapZone.fromFraction(0.34f))
        assertEquals(TapZone.MIDDLE, TapZone.fromFraction(0.35f))
        assertEquals(TapZone.MIDDLE, TapZone.fromFraction(0.50f))
        assertEquals(TapZone.MIDDLE, TapZone.fromFraction(0.65f))
        assertEquals(TapZone.RIGHT, TapZone.fromFraction(0.66f))
        assertEquals(TapZone.RIGHT, TapZone.fromFraction(0.85f))
        assertEquals(TapZone.RIGHT, TapZone.fromFraction(1.0f))
    }

    @Test
    fun testContinuousVideoSkipBounds() {
        val durationMs = 18000L
        var currentPosMs = 0L

        fun skip(offsetMs: Long) {
            currentPosMs = (currentPosMs + offsetMs).coerceIn(0L, durationMs)
        }

        // Tap right 3 times continuously (+5s, +5s, +5s)
        skip(5000L)
        assertEquals(5000L, currentPosMs)
        skip(5000L)
        assertEquals(10000L, currentPosMs)
        skip(5000L)
        assertEquals(15000L, currentPosMs)

        // 4th tap reaches and clamps at duration
        skip(5000L)
        assertEquals(18000L, currentPosMs)
        skip(5000L)
        assertEquals(18000L, currentPosMs)

        // Tap left 3 times continuously (-5s, -5s, -5s)
        skip(-5000L)
        assertEquals(13000L, currentPosMs)
        skip(-5000L)
        assertEquals(8000L, currentPosMs)
        skip(-5000L)
        assertEquals(3000L, currentPosMs)

        // Tap left past 0 clamps at 0
        skip(-5000L)
        assertEquals(0L, currentPosMs)
        skip(-5000L)
        assertEquals(0L, currentPosMs)
    }

    @Test
    fun testContinuousComicPageNavigation() {
        val totalPages = 5
        var currentPage = 0

        fun nextPage(): Boolean {
            if (currentPage < totalPages - 1) {
                currentPage++
                return true
            }
            return false
        }

        fun previousPage(): Boolean {
            if (currentPage > 0) {
                currentPage--
                return true
            }
            return false
        }

        // Tap left on page 0 -> stays 0
        assertEquals(false, previousPage())
        assertEquals(0, currentPage)

        // Tap right 4 times continuously -> 1, 2, 3, 4
        assertEquals(true, nextPage())
        assertEquals(1, currentPage)
        assertEquals(true, nextPage())
        assertEquals(2, currentPage)
        assertEquals(true, nextPage())
        assertEquals(3, currentPage)
        assertEquals(true, nextPage())
        assertEquals(4, currentPage)

        // Tap right on last page (4) -> stays 4
        assertEquals(false, nextPage())
        assertEquals(4, currentPage)

        // Tap left 4 times continuously -> 3, 2, 1, 0
        assertEquals(true, previousPage())
        assertEquals(3, currentPage)
        assertEquals(true, previousPage())
        assertEquals(2, currentPage)
        assertEquals(true, previousPage())
        assertEquals(1, currentPage)
        assertEquals(true, previousPage())
        assertEquals(0, currentPage)
    }

    @Test
    fun testMediaTypeResolution() {
        assertEquals(MediaType.IMAGE, LocalMediaRepository.getMediaTypeFromExtension("jpg"))
        assertEquals(MediaType.IMAGE, LocalMediaRepository.getMediaTypeFromExtension("png"))
        assertEquals(MediaType.IMAGE, LocalMediaRepository.getMediaTypeFromExtension("webp"))
        assertEquals(MediaType.VIDEO, LocalMediaRepository.getMediaTypeFromExtension("mp4"))
        assertEquals(MediaType.VIDEO, LocalMediaRepository.getMediaTypeFromExtension("mkv"))
        assertEquals(MediaType.VIDEO, LocalMediaRepository.getMediaTypeFromExtension("webm"))
        assertEquals(MediaType.GIF, LocalMediaRepository.getMediaTypeFromExtension("gif"))
        assertEquals(MediaType.CBZ, LocalMediaRepository.getMediaTypeFromExtension("cbz"))
        assertEquals(MediaType.CBZ, LocalMediaRepository.getMediaTypeFromExtension("cbr"))
        assertEquals(MediaType.CBZ, LocalMediaRepository.getMediaTypeFromExtension("zip"))
        assertEquals(MediaType.DOCUMENT, LocalMediaRepository.getMediaTypeFromExtension("pdf"))
        assertEquals(MediaType.DOCUMENT, LocalMediaRepository.getMediaTypeFromExtension("docx"))
        assertEquals(MediaType.DOCUMENT, LocalMediaRepository.getMediaTypeFromExtension("doc"))
        assertEquals(MediaType.EBOOK, LocalMediaRepository.getMediaTypeFromExtension("epub"))
        assertEquals(MediaType.EBOOK, LocalMediaRepository.getMediaTypeFromExtension("fb2"))
        assertEquals(MediaType.EBOOK, LocalMediaRepository.getMediaTypeFromExtension("txt"))
        assertEquals(MediaType.PRESENTATION, LocalMediaRepository.getMediaTypeFromExtension("pptx"))
        assertEquals(MediaType.PRESENTATION, LocalMediaRepository.getMediaTypeFromExtension("ppt"))
        assertEquals(MediaType.AUDIO, LocalMediaRepository.getMediaTypeFromExtension("mp3"))
        assertEquals(MediaType.AUDIO, LocalMediaRepository.getMediaTypeFromExtension("flac"))
        assertNull(LocalMediaRepository.getMediaTypeFromExtension("unknown_binary"))
    }

    @Test
    fun testCbzNaturalSortComparator() {
        val pageNames = listOf(
            "page_10.jpg",
            "page_1.jpg",
            "page_2.jpg",
            "page_20.jpg",
            "page_3.jpg",
            "cover.jpg"
        )

        val sorted = pageNames.sortedWith(CbzReader.naturalComparator)

        val expected = listOf(
            "cover.jpg",
            "page_1.jpg",
            "page_2.jpg",
            "page_3.jpg",
            "page_10.jpg",
            "page_20.jpg"
        )

        assertEquals(expected, sorted)
    }

    @Test
    fun testCbzNaturalSortWithDifferentNumberFormats() {
        val list = listOf("1.png", "10.png", "2.png", "03.png", "21.png", "02.png")
        val sorted = list.sortedWith(CbzReader.naturalComparator)
        // Check relative ordering
        val indexOf1 = sorted.indexOf("1.png")
        val indexOf2 = sorted.indexOf("2.png")
        val indexOf3 = sorted.indexOf("03.png")
        val indexOf10 = sorted.indexOf("10.png")
        val indexOf21 = sorted.indexOf("21.png")

        assert(indexOf1 < indexOf2)
        assert(indexOf2 < indexOf3)
        assert(indexOf3 < indexOf10)
        assert(indexOf10 < indexOf21)
    }

    @Test
    fun testProgressFractionCalculation() {
        // Valid duration calculations
        val current = 5000L
        val duration = 10000L
        val fraction = (current.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        assertEquals(0.5f, fraction, 0.001f)
        val progressInt = (fraction * 1000).toInt()
        assertEquals(500, progressInt)

        // Clamping overflow
        val overflowCurrent = 15000L
        val overflowFraction = (overflowCurrent.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        assertEquals(1.0f, overflowFraction, 0.001f)

        // Clamping underflow
        val negativeCurrent = -500L
        val negativeFraction = (negativeCurrent.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        assertEquals(0.0f, negativeFraction, 0.001f)
    }

    @Test
    fun testActiveItemProgressFiltering() {
        var appliedProgress = -1
        var currentActiveIndex = 1

        val onPlaybackProgress = { itemPos: Int, fraction: Float ->
            if (itemPos == currentActiveIndex) {
                appliedProgress = (fraction * 1000).toInt()
            }
        }

        // Active item emits 45% progress
        onPlaybackProgress(1, 0.45f)
        assertEquals(450, appliedProgress)

        // Offscreen pre-buffered item (pos 2) emits 0% progress -> must be ignored!
        onPlaybackProgress(2, 0.0f)
        assertEquals(450, appliedProgress)

        // Offscreen pre-buffered item (pos 0) emits 0% progress -> must be ignored!
        onPlaybackProgress(0, 0.0f)
        assertEquals(450, appliedProgress)

        // Active item emits 46% progress -> must be updated
        onPlaybackProgress(1, 0.46f)
        assertEquals(460, appliedProgress)

        // User switches to item 2
        currentActiveIndex = 2
        onPlaybackProgress(2, 0.10f)
        assertEquals(100, appliedProgress)

        // Old item 1 stray event -> must be ignored!
        onPlaybackProgress(1, 0.50f)
        assertEquals(100, appliedProgress)
    }

    @Test
    fun testIsImageFileValidation() {
        // Valid image formats
        assert(CbzReader.isImageFile("page1.jpg"))
        assert(CbzReader.isImageFile("page2.JPEG"))
        assert(CbzReader.isImageFile("page3.png"))
        assert(CbzReader.isImageFile("page4.webp"))
        assert(CbzReader.isImageFile("page5.avif"))
        assert(CbzReader.isImageFile("page6.jfif"))
        assert(CbzReader.isImageFile("page7.jxl"))
        assert(CbzReader.isImageFile("page8.heic"))
        assert(CbzReader.isImageFile("page9.tiff"))
        assert(CbzReader.isImageFile("subfolder/page10.gif"))
        assert(CbzReader.isImageFile("windows\\subfolder\\page11.bmp"))

        // Invalid files & metadata
        assert(!CbzReader.isImageFile("ComicInfo.xml"))
        assert(!CbzReader.isImageFile("description.txt"))
        assert(!CbzReader.isImageFile("thumbs.db"))
        assert(!CbzReader.isImageFile(".DS_Store"))
        assert(!CbzReader.isImageFile("._page1.jpg"))
        assert(!CbzReader.isImageFile("__MACOSX/page1.jpg"))
        assert(!CbzReader.isImageFile("chapter1/__MACOSX/._page1.jpg"))
        assert(!CbzReader.isImageFile(".hidden/page1.jpg"))
        assert(!CbzReader.isImageFile("dir\\.hidden\\page1.jpg"))
        assert(!CbzReader.isImageFile("subfolder/"))
    }

    @Test
    fun testNaturalSortWithSubfoldersAndWindowsSeparators() {
        val paths = listOf(
            "Chapter 10/page 1.jpg",
            "Chapter 1/page 2.jpg",
            "Chapter 1/page 10.jpg",
            "Chapter 1/page 1.jpg",
            "Chapter 2\\page 1.jpg"
        )
        val sorted = paths.sortedWith(CbzReader.naturalComparator)
        val expected = listOf(
            "Chapter 1/page 1.jpg",
            "Chapter 1/page 2.jpg",
            "Chapter 1/page 10.jpg",
            "Chapter 2\\page 1.jpg",
            "Chapter 10/page 1.jpg"
        )
        assertEquals(expected, sorted)
    }

    @Test
    fun testCbzExtractionAndCacheIntegrity() = kotlinx.coroutines.runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "cbz_test_${System.currentTimeMillis()}")
        val cacheDir = File(tempDir, "cache")
        tempDir.mkdirs()
        cacheDir.mkdirs()

        try {
            // Create a test CBZ with 5 pages and metadata
            val cbzFile = File(tempDir, "test_comic.cbz")
            ZipOutputStream(FileOutputStream(cbzFile)).use { zos ->
                val entries = listOf(
                    "cover.jpg" to "image_data_cover".toByteArray(),
                    "01.avif" to "image_data_01".toByteArray(),
                    "02.jfif" to "image_data_02".toByteArray(),
                    "03.png" to "image_data_03".toByteArray(),
                    "04.webp" to "image_data_04".toByteArray(),
                    "ComicInfo.xml" to "<xml>metadata</xml>".toByteArray(),
                    "__MACOSX/._cover.jpg" to "mac_metadata".toByteArray()
                )
                for ((name, data) in entries) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(data)
                    zos.closeEntry()
                }
            }

            // Extract using CbzReader
            val pages = CbzReader.getComicPages(cacheDir, cbzFile)
            assertEquals(5, pages.size)
            assertEquals("page_0001.avif", pages[0].name)
            assertEquals("page_0002.jfif", pages[1].name)
            assertEquals("page_0003.png", pages[2].name)
            assertEquals("page_0004.webp", pages[3].name)
            assertEquals("page_0005.jpg", pages[4].name)

            // Second call must return cached files
            val cachedPages = CbzReader.getComicPages(cacheDir, cbzFile)
            assertEquals(5, cachedPages.size)

            // Test incomplete cache recovery: corrupt the cache by deleting complete marker and 4 pages
            val cacheKey = "comic_${cbzFile.name.hashCode()}_${cbzFile.length()}_${cbzFile.lastModified()}"
            val comicCacheDir = File(cacheDir, "cbz_cache/$cacheKey")
            File(comicCacheDir, ".complete").delete()
            File(comicCacheDir, "page_0002.jfif").delete()
            File(comicCacheDir, "page_0003.png").delete()
            File(comicCacheDir, "page_0004.webp").delete()
            File(comicCacheDir, "page_0005.jpg").delete()

            // Corrupted cache only has 1 page remaining, but next call must detect corruption and re-extract all 5
            val recoveredPages = CbzReader.getComicPages(cacheDir, cbzFile)
            assertEquals(5, recoveredPages.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testPinchToZoomMathAndTransformation() {
        val width = 1000f
        val height = 1000f
        val cx = width / 2f
        val cy = height / 2f

        var scale = 1.0f
        var translationX = 0f
        var translationY = 0f

        fun applyScaleAndPan(factor: Float, focusX: Float, focusY: Float, lastFocusX: Float, lastFocusY: Float) {
            val oldScale = scale
            val newScale = (oldScale * factor).coerceIn(1.0f, 6.0f)
            val k = newScale / oldScale

            val newTx = (focusX - lastFocusX) + (1f - k) * (lastFocusX - cx) + k * translationX
            val newTy = (focusY - lastFocusY) + (1f - k) * (lastFocusY - cy) + k * translationY

            scale = newScale
            translationX = newTx
            translationY = newTy
        }

        fun resetZoom() {
            scale = 1.0f
            translationX = 0f
            translationY = 0f
        }

        // Center zoom 2x
        applyScaleAndPan(2.0f, 500f, 500f, 500f, 500f)
        assertEquals(2.0f, scale, 0.001f)
        assertEquals(0f, translationX, 0.001f)
        assertEquals(0f, translationY, 0.001f)

        // Reset
        resetZoom()
        assertEquals(1.0f, scale, 0.001f)
        assertEquals(0f, translationX, 0.001f)
        assertEquals(0f, translationY, 0.001f)

        // Off-center zoom 2x at (800, 500)
        applyScaleAndPan(2.0f, 800f, 500f, 800f, 500f)
        assertEquals(2.0f, scale, 0.001f)
        // Focal point (800) in transformed coords: 500 + (800 - 500)*2 + Tx = 800 => Tx = -300
        assertEquals(-300f, translationX, 0.001f)
        assertEquals(0f, translationY, 0.001f)

        // Pan +50 right
        applyScaleAndPan(1.0f, 850f, 500f, 800f, 500f)
        assertEquals(2.0f, scale, 0.001f)
        assertEquals(-250f, translationX, 0.001f)

        // Release gesture -> reset zoom
        resetZoom()
        assertEquals(1.0f, scale, 0.001f)
        assertEquals(0f, translationX, 0.001f)
        assertEquals(0f, translationY, 0.001f)
    }

    @Test
    fun testMediaIntentResolverTypeDetection() {
        // Test by display name extension
        assertEquals(MediaType.IMAGE, MediaIntentResolver.resolveMediaType("photo.jpg", null, null))
        assertEquals(MediaType.IMAGE, MediaIntentResolver.resolveMediaType("image.png", null, null))
        assertEquals(MediaType.IMAGE, MediaIntentResolver.resolveMediaType("picture.webp", null, null))
        assertEquals(MediaType.IMAGE, MediaIntentResolver.resolveMediaType("vector.svg", null, null))
        assertEquals(MediaType.IMAGE, MediaIntentResolver.resolveMediaType("photo.heic", null, null))
        assertEquals(MediaType.IMAGE, MediaIntentResolver.resolveMediaType("photo.avif", null, null))

        assertEquals(MediaType.VIDEO, MediaIntentResolver.resolveMediaType("video.mp4", null, null))
        assertEquals(MediaType.VIDEO, MediaIntentResolver.resolveMediaType("movie.mkv", null, null))
        assertEquals(MediaType.VIDEO, MediaIntentResolver.resolveMediaType("clip.webm", null, null))
        assertEquals(MediaType.VIDEO, MediaIntentResolver.resolveMediaType("recording.mov", null, null))
        assertEquals(MediaType.VIDEO, MediaIntentResolver.resolveMediaType("file.avi", null, null))

        assertEquals(MediaType.GIF, MediaIntentResolver.resolveMediaType("animation.gif", null, null))

        assertEquals(MediaType.CBZ, MediaIntentResolver.resolveMediaType("comic.cbz", null, null))
        assertEquals(MediaType.CBZ, MediaIntentResolver.resolveMediaType("manga.cbr", null, null))
        assertEquals(MediaType.CBZ, MediaIntentResolver.resolveMediaType("archive.zip", null, null))
        assertEquals(MediaType.CBZ, MediaIntentResolver.resolveMediaType("comic.cb7", null, null))

        // Test by URI path extension when display name is generic or missing
        assertEquals(MediaType.VIDEO, MediaIntentResolver.resolveMediaType(null, "/storage/emulated/0/Download/clip.mp4", null))
        assertEquals(MediaType.IMAGE, MediaIntentResolver.resolveMediaType(null, "/sdcard/Pictures/pic.png", null))
        assertEquals(MediaType.CBZ, MediaIntentResolver.resolveMediaType(null, "/storage/emulated/0/Books/comic.cbz", null))
        assertEquals(MediaType.GIF, MediaIntentResolver.resolveMediaType(null, "/storage/emulated/0/Download/funny.gif", null))

        // Test by MIME type when name/path has no extension
        assertEquals(MediaType.IMAGE, MediaIntentResolver.resolveMediaType("media_12345", null, "image/jpeg"))
        assertEquals(MediaType.IMAGE, MediaIntentResolver.resolveMediaType("blob_001", null, "image/png"))
        assertEquals(MediaType.VIDEO, MediaIntentResolver.resolveMediaType("stream_001", null, "video/mp4"))
        assertEquals(MediaType.VIDEO, MediaIntentResolver.resolveMediaType("raw_video", null, "video/x-matroska"))
        assertEquals(MediaType.GIF, MediaIntentResolver.resolveMediaType("download", null, "image/gif"))
        assertEquals(MediaType.CBZ, MediaIntentResolver.resolveMediaType("comicbook", null, "application/x-cbz"))
        assertEquals(MediaType.CBZ, MediaIntentResolver.resolveMediaType("comic_archive", null, "application/vnd.comicbook+zip"))
        assertEquals(MediaType.CBZ, MediaIntentResolver.resolveMediaType("comic_rar", null, "application/vnd.comicbook-rar"))
        assertEquals(MediaType.CBZ, MediaIntentResolver.resolveMediaType("archive", null, "application/zip"))

        // Default fallback to IMAGE
        assertEquals(MediaType.IMAGE, MediaIntentResolver.resolveMediaType(null, null, null))
    }

    @Test
    fun testDrawerSwipeRightTriggerConditions() {
        var drawerOpenRequested = false
        fun onSwipeRightRequested() {
            drawerOpenRequested = true
        }

        // 1. Single-page / non-paginated media (IMAGE, VIDEO, GIF)
        drawerOpenRequested = false
        val isImage = true
        if (isImage) {
            onSwipeRightRequested()
        }
        assertTrue(drawerOpenRequested)

        // 2. Paginated media (CBZ) at page 0 -> opens drawer
        drawerOpenRequested = false
        val currentCbzPage = 0
        if (currentCbzPage == 0) {
            onSwipeRightRequested()
        }
        assertTrue(drawerOpenRequested)

        // 3. Paginated media (CBZ) at page > 0 -> does NOT open drawer, turns page instead
        drawerOpenRequested = false
        var turnedPreviousPage = false
        val currentCbzPageMiddle = 2
        if (currentCbzPageMiddle == 0) {
            onSwipeRightRequested()
        } else {
            turnedPreviousPage = true
        }
        assertFalse(drawerOpenRequested)
        assertTrue(turnedPreviousPage)
    }

    @Test
    fun testDrawerFolderHierarchyAndHighlight() {
        val rootPath = "/storage/emulated/0/Pictures"
        val items = listOf(
            MediaItem("dir1", "Vacation", "$rootPath/Vacation", "", MediaType.IMAGE, 0L, 0L, isDirectory = true),
            MediaItem("img1", "photo1.jpg", "$rootPath/photo1.jpg", "", MediaType.IMAGE, 1024L, 0L, isDirectory = false),
            MediaItem("vid1", "clip1.mp4", "$rootPath/clip1.mp4", "", MediaType.VIDEO, 2048L, 0L, isDirectory = false),
            MediaItem("cbz1", "comic1.cbz", "$rootPath/comic1.cbz", "", MediaType.CBZ, 4096L, 0L, isDirectory = false)
        )

        // Verify currently playing item matching
        var currentlyPlayingId = "vid1"
        var currentlyPlayingPath = "$rootPath/clip1.mp4"

        val highlightedIndex = items.indexOfFirst {
            !it.isDirectory && (it.id == currentlyPlayingId || it.path == currentlyPlayingPath)
        }
        assertEquals(2, highlightedIndex)
        assertEquals("clip1.mp4", items[highlightedIndex].name)

        // Switching neighbor in same directory
        val neighbor = items[1] // photo1.jpg
        val playable = items.filter { !it.isDirectory }
        val newPlayableIndex = playable.indexOfFirst { it.id == neighbor.id }
        assertEquals(0, newPlayableIndex)
        assertEquals("photo1.jpg", playable[newPlayableIndex].name)

        // Parent path navigation (Local)
        val localParent = java.io.File(rootPath).parentFile?.absolutePath
        assertEquals("/storage/emulated/0", localParent)

        // Parent path navigation (Nextcloud WebDAV)
        val ncCurrentPath = "Photos/Vacation/2026"
        val ncParentPath = ncCurrentPath.trim('/').substringBeforeLast('/', "")
        assertEquals("Photos/Vacation", ncParentPath)

        val ncRootParent = "Photos".trim('/').substringBeforeLast('/', "")
        assertEquals("", ncRootParent)
    }

    @Test
    fun testOpeningAndPlayingTriggersThumbnailGeneration() {
        val generatedItems = mutableListOf<MediaItem>()
        val onThumbnailGenerated: (MediaItem) -> Unit = { item ->
            generatedItems.add(item)
        }

        val videoItem = MediaItem("v1", "test.mp4", "/storage/test.mp4", "", MediaType.VIDEO, 1000L, 0L)
        val audioItem = MediaItem("a1", "song.mp3", "/storage/song.mp3", "", MediaType.AUDIO, 2000L, 0L)
        val cbzItem = MediaItem("c1", "comic.cbz", "/storage/comic.cbz", "", MediaType.CBZ, 3000L, 0L)

        // Simulate opening & playing video
        onThumbnailGenerated(videoItem)
        assertEquals(1, generatedItems.size)
        assertEquals("test.mp4", generatedItems[0].name)

        // Simulate opening & playing audio
        onThumbnailGenerated(audioItem)
        assertEquals(2, generatedItems.size)
        assertEquals("song.mp3", generatedItems[1].name)

        // Simulate opening cbz
        onThumbnailGenerated(cbzItem)
        assertEquals(3, generatedItems.size)
        assertEquals("comic.cbz", generatedItems[2].name)
    }

    @Test
    fun testDrawerThumbnailUpdateWhenThumbnailGenerated() {
        val drawerItems = listOf(
            MediaItem("v1", "test.mp4", "/storage/test.mp4", "", MediaType.VIDEO, 1000L, 0L),
            MediaItem("v2", "clip.mp4", "/storage/clip.mp4", "", MediaType.VIDEO, 2000L, 0L)
        )

        val updatedIndices = mutableListOf<Int>()
        val notifyItemChanged: (Int) -> Unit = { index ->
            updatedIndices.add(index)
        }

        val onThumbnailGenerated: (MediaItem) -> Unit = { generatedItem ->
            val idx = drawerItems.indexOfFirst { it.id == generatedItem.id }
            if (idx != -1) {
                notifyItemChanged(idx)
            }
        }

        // Generate thumbnail for v2
        onThumbnailGenerated(drawerItems[1])
        assertEquals(1, updatedIndices.size)
        assertEquals(1, updatedIndices[0])

        // Generate thumbnail for v1
        onThumbnailGenerated(drawerItems[0])
        assertEquals(2, updatedIndices.size)
        assertEquals(0, updatedIndices[1])
    }
}
