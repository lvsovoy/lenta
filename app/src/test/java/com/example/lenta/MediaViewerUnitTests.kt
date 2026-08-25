package com.example.lenta

import com.example.lenta.data.cbz.CbzReader
import com.example.lenta.data.local.LocalMediaRepository
import com.example.lenta.data.model.MediaType
import com.example.lenta.ui.viewer.TapZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertNull(LocalMediaRepository.getMediaTypeFromExtension("pdf"))
        assertNull(LocalMediaRepository.getMediaTypeFromExtension("txt"))
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
}
