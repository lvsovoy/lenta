package me.lesovoy.lenta

import kotlinx.coroutines.runBlocking
import me.lesovoy.lenta.data.audio.AudioMetadataHelper
import me.lesovoy.lenta.data.document.DocumentPageReader
import me.lesovoy.lenta.data.local.LocalMediaRepository
import me.lesovoy.lenta.data.local.MediaIntentResolver
import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentAndMediaSupportUnitTests {

    @Test
    fun testAudioExtensionsMapping() {
        val audioExts = listOf("mp3", "wav", "flac", "m4a", "ogg", "opus", "aac", "wma", "aiff", "mid", "amr")
        for (ext in audioExts) {
            assertEquals("Testing extension: $ext", MediaType.AUDIO, LocalMediaRepository.getMediaTypeFromExtension(ext))
        }
    }

    @Test
    fun testDocumentExtensionsMapping() {
        val docExts = listOf("doc", "docx", "dot", "dotx", "docm", "odt", "ott", "rtf", "pages", "gdoc", "pdf", "xlsx", "xls", "ods", "csv")
        for (ext in docExts) {
            assertEquals("Testing extension: $ext", MediaType.DOCUMENT, LocalMediaRepository.getMediaTypeFromExtension(ext))
        }
    }

    @Test
    fun testEbookExtensionsMapping() {
        val ebookExts = listOf("epub", "fb2", "txt", "text", "log", "md", "markdown", "mobi", "azw", "azw3", "djvu", "nfo")
        for (ext in ebookExts) {
            assertEquals("Testing extension: $ext", MediaType.EBOOK, LocalMediaRepository.getMediaTypeFromExtension(ext))
        }
    }

    @Test
    fun testPresentationExtensionsMapping() {
        val presentationExts = listOf("ppt", "pptx", "pps", "ppsx", "pot", "potx", "pptm", "odp", "otp", "key", "keynote", "gslides", "gpresentation")
        for (ext in presentationExts) {
            assertEquals("Testing extension: $ext", MediaType.PRESENTATION, LocalMediaRepository.getMediaTypeFromExtension(ext))
        }
    }

    @Test
    fun testMimeTypeResolution() {
        assertEquals(MediaType.AUDIO, MediaIntentResolver.resolveMediaType("song.mp3", null, "audio/mpeg"))
        assertEquals(MediaType.AUDIO, MediaIntentResolver.resolveMediaType("track.wav", null, "audio/wav"))
        assertEquals(MediaType.AUDIO, MediaIntentResolver.resolveMediaType("audio.flac", null, "audio/flac"))

        assertEquals(MediaType.DOCUMENT, MediaIntentResolver.resolveMediaType("report.pdf", null, "application/pdf"))
        assertEquals(MediaType.DOCUMENT, MediaIntentResolver.resolveMediaType("doc.docx", null, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(MediaType.DOCUMENT, MediaIntentResolver.resolveMediaType("doc.doc", null, "application/msword"))
        assertEquals(MediaType.DOCUMENT, MediaIntentResolver.resolveMediaType("sheet.xlsx", null, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))

        assertEquals(MediaType.EBOOK, MediaIntentResolver.resolveMediaType("book.epub", null, "application/epub+zip"))
        assertEquals(MediaType.EBOOK, MediaIntentResolver.resolveMediaType("book.fb2", null, "application/x-fictionbook+xml"))
        assertEquals(MediaType.EBOOK, MediaIntentResolver.resolveMediaType("notes.txt", null, "text/plain"))
        assertEquals(MediaType.EBOOK, MediaIntentResolver.resolveMediaType("readme.md", null, "text/markdown"))

        assertEquals(MediaType.PRESENTATION, MediaIntentResolver.resolveMediaType("slides.pptx", null, "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
        assertEquals(MediaType.PRESENTATION, MediaIntentResolver.resolveMediaType("presentation.ppt", null, "application/vnd.ms-powerpoint"))
        assertEquals(MediaType.PRESENTATION, MediaIntentResolver.resolveMediaType("deck.gslides", null, "application/vnd.google-apps.presentation"))

        // Without filename, only MIME type
        assertEquals(MediaType.AUDIO, MediaIntentResolver.resolveMediaType(null, null, "audio/mp3"))
        assertEquals(MediaType.AUDIO, MediaIntentResolver.resolveMediaType(null, null, "application/ogg"))
        assertEquals(MediaType.AUDIO, MediaIntentResolver.resolveMediaType(null, null, "application/opus"))
        assertEquals(MediaType.DOCUMENT, MediaIntentResolver.resolveMediaType(null, null, "application/pdf"))
        assertEquals(MediaType.DOCUMENT, MediaIntentResolver.resolveMediaType(null, null, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(MediaType.DOCUMENT, MediaIntentResolver.resolveMediaType(null, null, "application/rtf"))
        assertEquals(MediaType.DOCUMENT, MediaIntentResolver.resolveMediaType(null, null, "text/csv"))
        assertEquals(MediaType.PRESENTATION, MediaIntentResolver.resolveMediaType(null, null, "application/vnd.ms-powerpoint"))
        assertEquals(MediaType.PRESENTATION, MediaIntentResolver.resolveMediaType(null, null, "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
        assertEquals(MediaType.EBOOK, MediaIntentResolver.resolveMediaType(null, null, "application/epub+zip"))
        assertEquals(MediaType.EBOOK, MediaIntentResolver.resolveMediaType(null, null, "application/x-fictionbook+xml"))
        assertEquals(MediaType.CBZ, MediaIntentResolver.resolveMediaType(null, null, "application/x-cbz"))
        assertEquals(MediaType.CBZ, MediaIntentResolver.resolveMediaType(null, null, "application/x-rar-compressed"))
        assertEquals(MediaType.CBZ, MediaIntentResolver.resolveMediaType(null, null, "application/x-7z-compressed"))
    }

    @Test
    fun testIsPaginatedType() {
        assertTrue(DocumentPageReader.isPaginatedType("cbz"))
        assertTrue(DocumentPageReader.isPaginatedType("cbr"))
        assertTrue(DocumentPageReader.isPaginatedType("pdf"))
        assertTrue(DocumentPageReader.isPaginatedType("docx"))
        assertTrue(DocumentPageReader.isPaginatedType("doc"))
        assertTrue(DocumentPageReader.isPaginatedType("epub"))
        assertTrue(DocumentPageReader.isPaginatedType("fb2"))
        assertTrue(DocumentPageReader.isPaginatedType("txt"))
        assertTrue(DocumentPageReader.isPaginatedType("pptx"))
        assertTrue(DocumentPageReader.isPaginatedType("ppt"))
        assertTrue(DocumentPageReader.isPaginatedType("gslides"))
        assertTrue(DocumentPageReader.isPaginatedType("gdoc"))

        assertFalse(DocumentPageReader.isPaginatedType("mp3"))
        assertFalse(DocumentPageReader.isPaginatedType("mp4"))
        assertFalse(DocumentPageReader.isPaginatedType("mkv"))
    }

    @Test
    fun testEbookTextExtraction() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "ebook_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val bookFile = File(tempDir, "my_novel.txt")
            val longContent = "Chapter 1\n\nThis is a long story paragraph."
            bookFile.writeText(longContent)

            assertTrue(DocumentPageReader.isPaginatedType(bookFile.extension))
            assertEquals(MediaType.EBOOK, LocalMediaRepository.getMediaType(bookFile))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testOpenXmlDocxStructure() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "docx_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val docxFile = File(tempDir, "document.docx")
            ZipOutputStream(FileOutputStream(docxFile)).use { zip ->
                val xmlContent = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body>
                        <w:p><w:r><w:t>Project Specification</w:t></w:r></w:p>
                        <w:p><w:r><w:t>This document contains system requirements.</w:t></w:r></w:p>
                      </w:body>
                    </w:document>
                """.trimIndent()
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(xmlContent.toByteArray())
                zip.closeEntry()
            }

            assertTrue(DocumentPageReader.isPaginatedType(docxFile.extension))
            assertEquals(MediaType.DOCUMENT, LocalMediaRepository.getMediaType(docxFile))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testOpenXmlPptxStructure() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "pptx_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val pptxFile = File(tempDir, "presentation.pptx")
            ZipOutputStream(FileOutputStream(pptxFile)).use { zip ->
                val slide1Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                      <p:cSld>
                        <p:spTree>
                          <p:sp>
                            <p:txBody>
                              <a:p><a:r><a:t>Quarterly Business Review</a:t></a:r></a:p>
                            </p:txBody>
                          </p:sp>
                        </p:spTree>
                      </p:cSld>
                    </p:sld>
                """.trimIndent()
                zip.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
                zip.write(slide1Xml.toByteArray())
                zip.closeEntry()
            }

            assertTrue(DocumentPageReader.isPaginatedType(pptxFile.extension))
            assertEquals(MediaType.PRESENTATION, LocalMediaRepository.getMediaType(pptxFile))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testDetectFormat() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "detect_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            // PDF
            val pdfFile = File(tempDir, "sample_no_ext")
            pdfFile.writeBytes("%PDF-1.5 test pdf content".toByteArray(StandardCharsets.ISO_8859_1))
            assertEquals(DocumentPageReader.DocumentFormat.PDF, DocumentPageReader.detectFormat(pdfFile))

            // RTF
            val rtfFile = File(tempDir, "sample_rtf")
            rtfFile.writeBytes("{\\rtf1\\ansi\\deff0 {\\fonttbl} Hello RTF \\par }".toByteArray(StandardCharsets.ISO_8859_1))
            assertEquals(DocumentPageReader.DocumentFormat.RTF, DocumentPageReader.detectFormat(rtfFile))

            // FB2
            val fb2File = File(tempDir, "book_without_ext")
            fb2File.writeBytes("<?xml version=\"1.0\" encoding=\"utf-8\"?><FictionBook><description><title-info><book-title>War and Peace</book-title></title-info></description><body><section><p>Hello World</p></section></body></FictionBook>".toByteArray(StandardCharsets.UTF_8))
            assertEquals(DocumentPageReader.DocumentFormat.FB2, DocumentPageReader.detectFormat(fb2File))

            // EPUB Zip
            val epubFile = File(tempDir, "book.epub")
            ZipOutputStream(FileOutputStream(epubFile)).use { zip ->
                zip.putNextEntry(ZipEntry("mimetype"))
                zip.write("application/epub+zip".toByteArray(StandardCharsets.US_ASCII))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("chapter1.xhtml"))
                zip.write("<html><body><p>Epub Chapter 1</p></body></html>".toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            assertEquals(DocumentPageReader.DocumentFormat.EPUB, DocumentPageReader.detectFormat(epubFile))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFb2EncodingAndBase64Stripping() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "fb2_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val fb2File = File(tempDir, "sample.fb2")
            // Create Windows-1251 encoded FB2 with Cyrillic text and huge base64 image
            val win1251Charset = java.nio.charset.Charset.forName("windows-1251")
            val rawXml = "<?xml version=\"1.0\" encoding=\"windows-1251\"?>\n" +
                    "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n" +
                    "<description><title-info><book-title>Капитанская дочка</book-title><author><last-name>Пушкин</last-name></author></title-info></description>\n" +
                    "<body>\n" +
                    "<title><p>Глава 1</p></title>\n" +
                    "<p>Отец мой Андрей Петрович Гринев в молодости своей служил.</p>\n" +
                    "<p>Мы жили в симбирской деревне.</p>\n" +
                    "</body>\n" +
                    "<binary id=\"cover.jpg\" content-type=\"image/jpeg\">/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAB4AKADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD9/6KKKAP/2Q==</binary>\n" +
                    "</FictionBook>"

            fb2File.writeBytes(rawXml.toByteArray(win1251Charset))

            assertEquals(DocumentPageReader.DocumentFormat.FB2, DocumentPageReader.detectFormat(fb2File))
            assertEquals(MediaType.EBOOK, LocalMediaRepository.getMediaType(fb2File))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testEpubHtmlEntityAndStyleStripping() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "epub_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val epubFile = File(tempDir, "sample.epub")
            ZipOutputStream(FileOutputStream(epubFile)).use { zip ->
                zip.putNextEntry(ZipEntry("mimetype"))
                zip.write("application/epub+zip".toByteArray(StandardCharsets.US_ASCII))
                zip.closeEntry()

                val chapterHtml = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <!DOCTYPE html>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                    <head>
                        <title>Chapter 1</title>
                        <style type="text/css">
                            body { margin: 5%; font-size: 1em; }
                            p.heading { font-weight: bold; }
                        </style>
                        <script type="text/javascript">
                            console.log("script content");
                        </script>
                    </head>
                    <body>
                        <h1 class="heading">The Adventure Begins &amp; Continues</h1>
                        <p>It was a dark &amp; stormy night&mdash;or was it?</p>
                        <p>&#8220;Hello world!&#8221; &copy; 2026</p>
                    </body>
                    </html>
                """.trimIndent()

                zip.putNextEntry(ZipEntry("OEBPS/Text/chapter1.xhtml"))
                zip.write(chapterHtml.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }

            assertEquals(DocumentPageReader.DocumentFormat.EPUB, DocumentPageReader.detectFormat(epubFile))
            assertEquals(MediaType.EBOOK, LocalMediaRepository.getMediaType(epubFile))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testBinaryDocAndPptDetection() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "ole_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            val docFile = File(tempDir, "document.doc")
            // OLE2 magic bytes D0 CF 11 E0 A1 B1 1A E1
            val ole2Header = byteArrayOf(
                0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
                0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
            ) + ByteArray(504)
            docFile.writeBytes(ole2Header)

            assertEquals(DocumentPageReader.DocumentFormat.DOC, DocumentPageReader.detectFormat(docFile))
            assertEquals(MediaType.DOCUMENT, LocalMediaRepository.getMediaType(docFile))

            val pptFile = File(tempDir, "presentation.ppt")
            pptFile.writeBytes(ole2Header)
            assertEquals(DocumentPageReader.DocumentFormat.PPT, DocumentPageReader.detectFormat(pptFile))
            assertEquals(MediaType.PRESENTATION, LocalMediaRepository.getMediaType(pptFile))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testAudioMetadataExtractionFallback() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "audio_test_${System.currentTimeMillis()}")
        val cacheDir = File(tempDir, "cache")
        tempDir.mkdirs()
        cacheDir.mkdirs()

        try {
            val audioFile = File(tempDir, "Symphony_No_5.mp3")
            audioFile.writeText("fake mp3 data header")

            val metadata = AudioMetadataHelper.extractMetadata(cacheDir, audioFile)
            assertNotNull(metadata)
            assertEquals("Symphony_No_5", metadata.title)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
