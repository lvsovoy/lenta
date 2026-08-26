package me.lesovoy.lenta.data.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lesovoy.lenta.data.cbz.CbzReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object DocumentPageReader {

    private const val PAGE_WIDTH = 1200
    private const val PAGE_HEIGHT = 1600

    private const val SLIDE_WIDTH = 1600
    private const val SLIDE_HEIGHT = 900

    enum class DocumentFormat {
        PDF,
        EPUB,
        FB2,
        DOCX,
        DOC,
        PPTX,
        PPT,
        ODT,
        ODP,
        RTF,
        COMIC,
        TEXT,
        BINARY_UNSUPPORTED
    }

    val COMIC_EXTENSIONS = setOf("cbz", "zip", "cbr", "cb7", "cbt", "cba", "rar", "7z", "tar", "gz")
    val DOC_EXTENSIONS = setOf(
        "doc", "docx", "dot", "dotx", "docm", "dotm", "odt", "ott", "rtf", "pages", "gdoc",
        "wpd", "wps", "oxps", "xps", "pdf", "xls", "xlsx", "xlsm", "xlt", "xltx", "ods", "ots",
        "csv", "tsv", "numbers", "gsheet"
    )
    val EBOOK_EXTENSIONS = setOf(
        "epub", "fb2", "txt", "text", "log", "md", "markdown", "mobi", "azw", "azw3", "prc",
        "lit", "djvu", "djv", "nfo", "chm"
    )
    val PRESENTATION_EXTENSIONS = setOf(
        "ppt", "pptx", "pps", "ppsx", "pot", "potx", "pptm", "potm", "ppsm", "odp", "otp",
        "key", "keynote", "gslides", "gpresentation"
    )

    /**
     * Checks if the given file or extension should be handled as a paginated document/presentation/ebook/comic.
     */
    fun isPaginatedType(ext: String): Boolean {
        val lower = ext.lowercase(Locale.ROOT)
        return lower in CbzReader.IMAGE_EXTENSIONS ||
                lower in COMIC_EXTENSIONS ||
                lower in DOC_EXTENSIONS ||
                lower in EBOOK_EXTENSIONS ||
                lower in PRESENTATION_EXTENSIONS ||
                lower == "pdf"
    }

    /**
     * Inspects magic header bytes and file extension to accurately detect the document format.
     */
    fun detectFormat(file: File): DocumentFormat {
        if (!file.exists() || file.length() == 0L) return DocumentFormat.BINARY_UNSUPPORTED

        val ext = file.extension.lowercase(Locale.ROOT)

        // Read first 512 bytes for magic number identification
        val header = ByteArray(512)
        var bytesRead = 0
        try {
            FileInputStream(file).use { input ->
                bytesRead = input.read(header)
            }
        } catch (_: Throwable) {
        }

        if (bytesRead >= 4) {
            // 1. PDF: %PDF-
            if (header[0] == 0x25.toByte() && header[1] == 0x50.toByte() && header[2] == 0x44.toByte() && header[3] == 0x46.toByte()) {
                return DocumentFormat.PDF
            }

            // 2. RTF: {\rtf
            if (header[0] == 0x7B.toByte() && header[1] == 0x5C.toByte() && header[2] == 0x72.toByte() && header[3] == 0x74.toByte()) {
                return DocumentFormat.RTF
            }

            // 3. OLE2 Compound Document (DOC / PPT / XLS): D0 CF 11 E0 A1 B1 1A E1
            if (header[0] == 0xD0.toByte() && header[1] == 0xCF.toByte() && header[2] == 0x11.toByte() && header[3] == 0xE0.toByte()) {
                if (ext in PRESENTATION_EXTENSIONS || ext == "ppt" || ext == "pps" || ext == "pot") {
                    return DocumentFormat.PPT
                }
                val streams = try { Ole2Parser.listStreamNames(file) } catch (_: Throwable) { emptyList() }
                return when {
                    streams.any { it.contains("PowerPoint", ignoreCase = true) } -> DocumentFormat.PPT
                    streams.any { it.contains("WordDocument", ignoreCase = true) } -> DocumentFormat.DOC
                    ext in PRESENTATION_EXTENSIONS -> DocumentFormat.PPT
                    else -> DocumentFormat.DOC
                }
            }

            // 4. ZIP Containers (EPUB, DOCX, PPTX, ODT, ODP, CBZ)
            if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() && (header[2] == 0x03.toByte() || header[2] == 0x05.toByte())) {
                try {
                    ZipFile(file).use { zip ->
                        val entries = zip.entries().asSequence().map { it.name }.toSet()
                        if (entries.any { it.startsWith("word/") || it == "word/document.xml" } || ext in setOf("docx", "dotx", "docm")) {
                            return DocumentFormat.DOCX
                        }
                        if (entries.any { it.startsWith("ppt/") || it == "ppt/presentation.xml" } || ext in setOf("pptx", "ppsx", "potx")) {
                            return DocumentFormat.PPTX
                        }
                        if (entries.any { it.endsWith(".xhtml") || it.endsWith(".opf") || it == "META-INF/container.xml" } || ext == "epub") {
                            return DocumentFormat.EPUB
                        }
                        if (entries.any { it.endsWith(".fb2") } || ext.contains("fb2")) {
                            return DocumentFormat.FB2
                        }
                        val mimeEntry = zip.getEntry("mimetype")
                        if (mimeEntry != null) {
                            val mime = zip.getInputStream(mimeEntry).bufferedReader(StandardCharsets.US_ASCII).use { it.readText() }
                            if (mime.contains("epub")) return DocumentFormat.EPUB
                            if (mime.contains("opendocument.text")) return DocumentFormat.ODT
                            if (mime.contains("opendocument.presentation")) return DocumentFormat.ODP
                        }
                        if (entries.any { it == "content.xml" }) {
                            return if (ext in PRESENTATION_EXTENSIONS || ext == "odp") DocumentFormat.ODP else DocumentFormat.ODT
                        }
                        if (entries.any { CbzReader.isImageFile(it) }) {
                            return DocumentFormat.COMIC
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            // 5. XML / FB2: <?xml or <FictionBook
            val headerStr = String(header, 0, bytesRead.coerceAtMost(512), StandardCharsets.ISO_8859_1)
            if (headerStr.contains("<FictionBook", ignoreCase = true) || (headerStr.contains("<?xml", ignoreCase = true) && ext == "fb2")) {
                return DocumentFormat.FB2
            }
        }

        // Extension-based detection fallback
        return when {
            ext == "pdf" -> DocumentFormat.PDF
            ext == "epub" || ext == "mobi" || ext == "azw" || ext == "azw3" -> DocumentFormat.EPUB
            ext == "fb2" -> DocumentFormat.FB2
            ext in setOf("docx", "dotx", "docm") -> DocumentFormat.DOCX
            ext in setOf("doc", "dot") -> DocumentFormat.DOC
            ext in setOf("pptx", "ppsx", "potx", "pptm") -> DocumentFormat.PPTX
            ext in setOf("ppt", "pps", "pot") -> DocumentFormat.PPT
            ext in setOf("odt", "ott") -> DocumentFormat.ODT
            ext in setOf("odp", "otp") -> DocumentFormat.ODP
            ext == "rtf" -> DocumentFormat.RTF
            ext in COMIC_EXTENSIONS -> DocumentFormat.COMIC
            ext in setOf("txt", "text", "log", "md", "markdown", "csv", "tsv", "nfo") -> DocumentFormat.TEXT
            ext in PRESENTATION_EXTENSIONS -> DocumentFormat.PPTX
            ext in DOC_EXTENSIONS -> DocumentFormat.DOCX
            ext in EBOOK_EXTENSIONS -> DocumentFormat.EPUB
            isBinaryFile(file, header, bytesRead) -> DocumentFormat.BINARY_UNSUPPORTED
            else -> DocumentFormat.TEXT
        }
    }

    private fun isBinaryFile(file: File, sample: ByteArray, length: Int): Boolean {
        if (length == 0) return false
        var nonPrintable = 0
        for (i in 0 until length) {
            val b = sample[i].toInt() and 0xFF
            if (b == 0) return true // Null byte indicates binary format
            if (b < 0x09 || (b in 0x0E..0x1F) || b == 0x7F) {
                nonPrintable++
            }
        }
        return (nonPrintable.toDouble() / length.toDouble()) > 0.15
    }

    suspend fun getDocumentPages(context: Context, file: File): List<File> = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext emptyList()

        val format = detectFormat(file)

        when (format) {
            DocumentFormat.COMIC -> {
                val comicPages = CbzReader.getComicPages(context, file)
                if (comicPages.isNotEmpty()) return@withContext comicPages
            }
            DocumentFormat.PDF -> {
                val pdfPages = renderPdfPages(context.cacheDir, file)
                if (pdfPages.isNotEmpty()) return@withContext pdfPages
            }
            DocumentFormat.PPTX, DocumentFormat.PPT, DocumentFormat.ODP -> {
                val presentationPages = renderPresentationPages(context.cacheDir, file, format)
                if (presentationPages.isNotEmpty()) return@withContext presentationPages
            }
            DocumentFormat.EPUB, DocumentFormat.FB2 -> {
                val ebookPages = renderEbookPages(context.cacheDir, file, format)
                if (ebookPages.isNotEmpty()) return@withContext ebookPages
            }
            DocumentFormat.DOCX, DocumentFormat.DOC, DocumentFormat.ODT, DocumentFormat.RTF -> {
                val docPages = renderDocumentPages(context.cacheDir, file, format)
                if (docPages.isNotEmpty()) return@withContext docPages
            }
            DocumentFormat.TEXT -> {
                return@withContext renderGenericTextPages(context.cacheDir, file)
            }
            DocumentFormat.BINARY_UNSUPPORTED -> {
                return@withContext renderUnsupportedBinaryCard(context.cacheDir, file)
            }
        }

        // Final safe fallback
        renderGenericTextPages(context.cacheDir, file)
    }

    suspend fun getDocumentPagesFromUri(
        context: Context,
        uri: Uri,
        itemId: String,
        fileName: String? = null,
        mimeType: String? = null
    ): List<File> = withContext(Dispatchers.IO) {
        val cacheSubdir = File(context.cacheDir, "uri_docs_cache")
        cacheSubdir.mkdirs()

        var resolvedExt = ""
        var resolvedName = fileName ?: ""

        if (resolvedName.isEmpty()) {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            resolvedName = cursor.getString(nameIndex) ?: ""
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        if (resolvedName.isNotEmpty() && resolvedName.contains(".")) {
            resolvedExt = resolvedName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        }

        if (resolvedExt.isEmpty() && uri.lastPathSegment != null && uri.lastPathSegment!!.contains(".")) {
            resolvedExt = uri.lastPathSegment!!.substringAfterLast('.', "").lowercase(Locale.ROOT)
        }

        if (resolvedExt.isEmpty()) {
            val type = mimeType ?: try { context.contentResolver.getType(uri) } catch (_: Throwable) { null }
            if (type != null) {
                resolvedExt = when {
                    type.contains("pdf") -> "pdf"
                    type.contains("epub") -> "epub"
                    type.contains("fictionbook") || type.contains("fb2") -> "fb2"
                    type.contains("wordprocessingml") || type.contains("docx") -> "docx"
                    type.contains("msword") || type.contains("doc") -> "doc"
                    type.contains("presentationml") || type.contains("pptx") -> "pptx"
                    type.contains("powerpoint") || type.contains("ppt") -> "ppt"
                    type.contains("opendocument.text") || type.contains("odt") -> "odt"
                    type.contains("opendocument.presentation") || type.contains("odp") -> "odp"
                    type.contains("rtf") -> "rtf"
                    type.contains("cbz") || type.contains("zip") -> "cbz"
                    type.contains("plain") || type.contains("markdown") -> "txt"
                    else -> ""
                }
            }
        }

        val safeSuffix = if (resolvedExt.isNotEmpty()) ".$resolvedExt" else ""
        val safeName = "doc_uri_${itemId.hashCode()}_${System.currentTimeMillis()}$safeSuffix"
        val tempFile = File(cacheSubdir, safeName)

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0L) {
                return@withContext getDocumentPages(context, tempFile)
            }
        } catch (_: Throwable) {
        }
        emptyList()
    }

    suspend fun getDocumentCover(context: Context, file: File): File? =
        getDocumentCover(context.cacheDir, file)

    suspend fun getDocumentCover(cacheDir: File, file: File): File? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext null

        val format = detectFormat(file)
        val cacheKey = "cover_${file.name.hashCode()}_${file.length()}_${file.lastModified()}"
        val thumbCacheDir = File(cacheDir, "thumbnail_cache/doc")
        thumbCacheDir.mkdirs()

        val existing = thumbCacheDir.listFiles { _, name -> name.startsWith(cacheKey) }?.firstOrNull()
        if (existing != null && existing.length() > 0L) {
            return@withContext existing
        }

        if (format == DocumentFormat.COMIC) {
            val comicCover = CbzReader.getComicCover(cacheDir, file)
            if (comicCover != null && comicCover.exists()) return@withContext comicCover
        }

        val pages = try {
            val extCacheDir = File(cacheDir, "doc_cache/${file.name.hashCode()}_${file.length()}")
            if (extCacheDir.exists() && extCacheDir.isDirectory) {
                val list = extCacheDir.listFiles { _, n -> n.endsWith(".png") || n.endsWith(".jpg") }
                if (!list.isNullOrEmpty()) {
                    list.sortedWith { f1, f2 -> CbzReader.naturalComparator.compare(f1.name, f2.name) }
                } else {
                    renderFirstPageOnly(cacheDir, file, format)
                }
            } else {
                renderFirstPageOnly(cacheDir, file, format)
            }
        } catch (_: Throwable) {
            emptyList()
        }

        val first = pages.firstOrNull()
        if (first != null && first.exists() && first.length() > 0L) {
            val coverOut = File(thumbCacheDir, "$cacheKey.jpg")
            try {
                first.copyTo(coverOut, overwrite = true)
                return@withContext coverOut
            } catch (_: Throwable) {
                return@withContext first
            }
        }

        null
    }

    private suspend fun renderFirstPageOnly(cacheDir: File, file: File, format: DocumentFormat): List<File> {
        return when (format) {
            DocumentFormat.PDF -> renderPdfPages(cacheDir, file, maxPages = 1)
            DocumentFormat.PPTX, DocumentFormat.PPT, DocumentFormat.ODP -> renderPresentationPages(cacheDir, file, format, maxPages = 1)
            DocumentFormat.EPUB, DocumentFormat.FB2 -> renderEbookPages(cacheDir, file, format, maxPages = 1)
            DocumentFormat.DOCX, DocumentFormat.DOC, DocumentFormat.ODT, DocumentFormat.RTF -> renderDocumentPages(cacheDir, file, format, maxPages = 1)
            DocumentFormat.COMIC -> CbzReader.getComicPages(cacheDir, file).take(1)
            DocumentFormat.BINARY_UNSUPPORTED -> renderUnsupportedBinaryCard(cacheDir, file)
            else -> renderGenericTextPages(cacheDir, file, maxPages = 1)
        }
    }

    // ==========================================
    // PDF RENDERING
    // ==========================================
    private fun renderPdfPages(cacheDir: File, pdfFile: File, maxPages: Int = Int.MAX_VALUE): List<File> {
        val pagesDir = File(cacheDir, "doc_cache/pdf_${pdfFile.name.hashCode()}_${pdfFile.length()}_${pdfFile.lastModified()}")
        pagesDir.mkdirs()

        val existing = pagesDir.listFiles { _, name -> name.endsWith(".jpg") || name.endsWith(".png") }
        if (!existing.isNullOrEmpty()) {
            return existing.sortedWith { f1, f2 -> CbzReader.naturalComparator.compare(f1.name, f2.name) }
        }

        val result = mutableListOf<File>()
        try {
            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val total = minOf(renderer.pageCount, maxPages)

            for (i in 0 until total) {
                val page = renderer.openPage(i)
                val scale = 2f
                val w = (page.width * scale).toInt().coerceAtLeast(600)
                val h = (page.height * scale).toInt().coerceAtLeast(800)

                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val outFile = File(pagesDir, "page_%04d.jpg".format(i + 1))
                FileOutputStream(outFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                bitmap.recycle()
                if (outFile.exists() && outFile.length() > 0L) {
                    result.add(outFile)
                }
            }
            renderer.close()
            pfd.close()
        } catch (_: Throwable) {
        }
        return result
    }

    // ==========================================
    // PRESENTATIONS (PPTX / PPT / ODP / SLIDES)
    // ==========================================
    private fun renderPresentationPages(
        cacheDir: File,
        file: File,
        format: DocumentFormat = DocumentFormat.PPTX,
        maxPages: Int = Int.MAX_VALUE
    ): List<File> {
        val pagesDir = File(cacheDir, "doc_cache/ppt_${file.name.hashCode()}_${file.length()}_${file.lastModified()}")
        pagesDir.mkdirs()

        val existing = pagesDir.listFiles { _, name -> name.endsWith(".jpg") || name.endsWith(".png") }
        if (!existing.isNullOrEmpty()) {
            return existing.sortedWith { f1, f2 -> CbzReader.naturalComparator.compare(f1.name, f2.name) }
        }

        val slides = extractPresentationSlides(file, format)
        if (slides.isEmpty()) {
            return renderFallbackSlides(pagesDir, file)
        }

        val result = mutableListOf<File>()
        val count = minOf(slides.size, maxPages)
        for (i in 0 until count) {
            val slide = slides[i]
            val bitmap = Bitmap.createBitmap(SLIDE_WIDTH, SLIDE_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            drawSlidePage(canvas, slide, i + 1, slides.size, file.nameWithoutExtension)

            val outFile = File(pagesDir, "slide_%04d.jpg".format(i + 1))
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()
            if (outFile.exists() && outFile.length() > 0L) {
                result.add(outFile)
            }
        }
        return result
    }

    private data class SlideData(
        val title: String,
        val bullets: List<String>
    )

    private fun extractPresentationSlides(file: File, format: DocumentFormat): List<SlideData> {
        return when (format) {
            DocumentFormat.PPTX -> extractPptxSlides(file)
            DocumentFormat.PPT -> extractPptBinarySlides(file)
            DocumentFormat.ODP -> extractOdpSlides(file)
            else -> {
                val ext = file.extension.lowercase(Locale.ROOT)
                if (ext in setOf("pptx", "ppsx", "potx")) extractPptxSlides(file)
                else if (ext in setOf("ppt", "pps", "pot")) extractPptBinarySlides(file)
                else if (ext in setOf("odp", "otp")) extractOdpSlides(file)
                else extractPptxSlides(file).ifEmpty { extractPptBinarySlides(file) }
            }
        }
    }

    private fun extractPptxSlides(file: File): List<SlideData> {
        val slides = mutableListOf<SlideData>()
        try {
            ZipFile(file).use { zip ->
                val slideEntries = zip.entries().asSequence()
                    .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
                    .sortedWith { e1, e2 -> CbzReader.naturalComparator.compare(e1.name, e2.name) }
                    .toList()

                for (entry in slideEntries) {
                    val xml = zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    val slide = parseSlideXml(xml)
                    if (slide != null) {
                        slides.add(slide)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return slides
    }

    private fun extractOdpSlides(file: File): List<SlideData> {
        val slides = mutableListOf<SlideData>()
        try {
            ZipFile(file).use { zip ->
                val contentEntry = zip.getEntry("content.xml") ?: return emptyList()
                val xml = zip.getInputStream(contentEntry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val pageRegex = Regex("<draw:page[^>]*>(.*?)</draw:page>", RegexOption.DOT_MATCHES_ALL)
                for (match in pageRegex.findAll(xml)) {
                    val pageXml = match.groupValues[1]
                    val textList = mutableListOf<String>()
                    val pRegex = Regex("<text:p[^>]*>(.*?)</text:p>", RegexOption.DOT_MATCHES_ALL)
                    for (pMatch in pRegex.findAll(pageXml)) {
                        val clean = unescapeXml(pMatch.groupValues[1].replace(Regex("<[^>]+>"), "")).trim()
                        if (clean.isNotEmpty()) {
                            textList.add(clean)
                        }
                    }
                    if (textList.isNotEmpty()) {
                        slides.add(SlideData(textList.first(), textList.drop(1)))
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return slides
    }

    /**
     * Extracts slides and text records from binary PowerPoint 97-2003 (.ppt) OLE2 files.
     */
    private fun extractPptBinarySlides(file: File): List<SlideData> {
        val slides = mutableListOf<SlideData>()
        try {
            val pptBytes = Ole2Parser.extractStream(file, "PowerPoint Document")
                ?: file.readBytes()

            val textRuns = mutableListOf<String>()
            val buffer = ByteBuffer.wrap(pptBytes).order(ByteOrder.LITTLE_ENDIAN)

            var pos = 0
            while (pos + 8 <= pptBytes.size) {
                buffer.position(pos)
                val verAndInstance = buffer.short.toInt() and 0xFFFF
                val recType = buffer.short.toInt() and 0xFFFF
                val recLen = buffer.int

                if (recLen < 0 || pos + 8 + recLen > pptBytes.size) {
                    pos++
                    continue
                }

                val dataOffset = pos + 8

                // Slide container marker (0x03E8 / 0x03EE / 0x03F8)
                if (recType == 0x03E8 || recType == 0x03EE || recType == 0x03F8) {
                    if (textRuns.isNotEmpty()) {
                        slides.add(SlideData(textRuns.first(), textRuns.drop(1)))
                        textRuns.clear()
                    }
                }

                // TextCharsAtom (0x0FBA) - UTF-16LE text
                if (recType == 0x0FBA && recLen >= 2) {
                    val text = String(pptBytes, dataOffset, recLen, StandardCharsets.UTF_16LE).trim()
                    val cleaned = cleanBinaryText(text)
                    if (cleaned.isNotBlank()) {
                        textRuns.add(cleaned)
                    }
                }

                // TextBytesAtom (0x0FBB) - 8-bit text (ISO-8859-1 / Windows-1252 / CP1251)
                if (recType == 0x0FBB && recLen >= 1) {
                    val text = String(pptBytes, dataOffset, recLen, StandardCharsets.ISO_8859_1).trim()
                    val cleaned = cleanBinaryText(text)
                    if (cleaned.isNotBlank()) {
                        textRuns.add(cleaned)
                    }
                }

                // CString (0x0F9E)
                if (recType == 0x0F9E && recLen >= 2) {
                    val text = String(pptBytes, dataOffset, recLen, StandardCharsets.UTF_16LE).trim()
                    val cleaned = cleanBinaryText(text)
                    if (cleaned.isNotBlank() && !cleaned.startsWith("Default") && !cleaned.startsWith("Times")) {
                        textRuns.add(cleaned)
                    }
                }

                pos += 8 + recLen
            }

            if (textRuns.isNotEmpty()) {
                slides.add(SlideData(textRuns.first(), textRuns.drop(1)))
            }
        } catch (_: Throwable) {
        }

        // If structured slide records weren't found, extract clean printable string runs
        if (slides.isEmpty()) {
            val textBlocks = extractPrintableTextRuns(file)
            if (textBlocks.isNotEmpty()) {
                for (i in textBlocks.indices step 4) {
                    val title = textBlocks[i].take(80)
                    val bullets = textBlocks.drop(i + 1).take(3)
                    slides.add(SlideData(title, bullets))
                }
            }
        }

        return slides
    }

    private fun parseSlideXml(xml: String): SlideData? {
        val textList = mutableListOf<String>()
        val regex = Regex("<a:t[^>]*>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)
        for (match in regex.findAll(xml)) {
            val text = unescapeXml(match.groupValues[1]).trim()
            if (text.isNotEmpty()) {
                textList.add(text)
            }
        }
        if (textList.isEmpty()) return null
        val title = textList.first()
        val bullets = textList.drop(1)
        return SlideData(title, bullets)
    }

    private fun drawSlidePage(
        canvas: Canvas,
        slide: SlideData,
        slideNum: Int,
        totalSlides: Int,
        docTitle: String
    ) {
        canvas.drawColor(Color.parseColor("#1E1E2E"))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Accent top banner
        paint.color = Color.parseColor("#313244")
        canvas.drawRect(0f, 0f, SLIDE_WIDTH.toFloat(), 120f, paint)

        // Accent line
        paint.color = Color.parseColor("#89B4FA")
        canvas.drawRect(0f, 116f, SLIDE_WIDTH.toFloat(), 120f, paint)

        // Slide Header / Doc Title
        paint.color = Color.parseColor("#A6ADC8")
        paint.textSize = 28f
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(docTitle.take(60), 60f, 75f, paint)

        // Slide Number
        val numText = "$slideNum / $totalSlides"
        val numWidth = paint.measureText(numText)
        canvas.drawText(numText, SLIDE_WIDTH - 60f - numWidth, 75f, paint)

        // Slide Title
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CDD6F4")
            textSize = 52f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val contentWidth = SLIDE_WIDTH - 160
        val titleLayout = StaticLayout.Builder.obtain(
            slide.title,
            0,
            slide.title.length,
            titlePaint,
            contentWidth
        ).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.1f)
            .build()

        canvas.save()
        canvas.translate(80f, 180f)
        titleLayout.draw(canvas)
        canvas.restore()

        // Bullets / Content
        var currentY = 180f + titleLayout.height + 40f
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#BAC2DE")
            textSize = 34f
            typeface = Typeface.DEFAULT
        }

        val bulletDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#89B4FA")
        }

        for (bullet in slide.bullets) {
            if (currentY > SLIDE_HEIGHT - 100) break

            canvas.drawCircle(100f, currentY + 16f, 8f, bulletDotPaint)

            val bulletLayout = StaticLayout.Builder.obtain(
                bullet,
                0,
                bullet.length,
                bodyPaint,
                contentWidth - 60
            ).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.2f)
                .build()

            canvas.save()
            canvas.translate(130f, currentY)
            bulletLayout.draw(canvas)
            canvas.restore()

            currentY += bulletLayout.height + 28f
        }
    }

    private fun renderFallbackSlides(pagesDir: File, file: File): List<File> {
        val result = mutableListOf<File>()
        val bitmap = Bitmap.createBitmap(SLIDE_WIDTH, SLIDE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val slide = SlideData(
            title = file.nameWithoutExtension,
            bullets = listOf("Presentation: ${file.extension.uppercase(Locale.ROOT)}", "File size: ${file.length() / 1024} KB")
        )
        drawSlidePage(canvas, slide, 1, 1, file.nameWithoutExtension)

        val outFile = File(pagesDir, "slide_0001.jpg")
        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()
        if (outFile.exists()) result.add(outFile)
        return result
    }

    // ==========================================
    // EBOOKS (EPUB, FB2, MOBI, TXT, MD)
    // ==========================================
    private fun renderEbookPages(
        cacheDir: File,
        file: File,
        format: DocumentFormat = DocumentFormat.EPUB,
        maxPages: Int = Int.MAX_VALUE
    ): List<File> {
        val pagesDir = File(cacheDir, "doc_cache/ebook_${file.name.hashCode()}_${file.length()}_${file.lastModified()}")
        pagesDir.mkdirs()

        val existing = pagesDir.listFiles { _, name -> name.endsWith(".jpg") || name.endsWith(".png") }
        if (!existing.isNullOrEmpty()) {
            return existing.sortedWith { f1, f2 -> CbzReader.naturalComparator.compare(f1.name, f2.name) }
        }

        val textBlocks = extractEbookText(file, format)
        if (textBlocks.isEmpty()) {
            // Check if EPUB contains comic/illustrated images
            if (format == DocumentFormat.EPUB) {
                val imagePages = extractEpubImagesToPages(pagesDir, file, maxPages)
                if (imagePages.isNotEmpty()) return imagePages
            }
        }
        return renderTextIntoPages(pagesDir, file.nameWithoutExtension, textBlocks, maxPages, isEbook = true)
    }

    private fun extractEbookText(file: File, format: DocumentFormat): List<String> {
        return when (format) {
            DocumentFormat.EPUB -> extractEpubText(file)
            DocumentFormat.FB2 -> extractFb2Text(file)
            else -> {
                val ext = file.extension.lowercase(Locale.ROOT)
                if (ext == "epub" || ext == "mobi") extractEpubText(file)
                else if (ext == "fb2") extractFb2Text(file)
                else extractEpubText(file).ifEmpty { extractFb2Text(file) }
            }
        }
    }

    private fun extractEpubText(file: File): List<String> {
        val blocks = mutableListOf<String>()
        try {
            ZipFile(file).use { zip ->
                val xhtmlEntries = zip.entries().asSequence()
                    .filter {
                        val name = it.name.lowercase(Locale.ROOT)
                        (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xml")) &&
                                !name.endsWith("toc.ncx") && !name.endsWith("content.opf") && !name.endsWith("container.xml")
                    }
                    .sortedWith { e1, e2 -> CbzReader.naturalComparator.compare(e1.name, e2.name) }
                    .toList()

                for (entry in xhtmlEntries) {
                    val raw = zip.getInputStream(entry).use { readStreamWithAutoCharset(it) }
                    val plain = stripHtmlTags(raw)
                    val paragraphs = plain.split("\n\n").map { it.trim() }.filter { it.isNotBlank() }
                    blocks.addAll(paragraphs)
                }
            }
        } catch (_: Throwable) {
        }
        return blocks
    }

    private fun extractEpubImagesToPages(pagesDir: File, file: File, maxPages: Int): List<File> {
        val result = mutableListOf<File>()
        try {
            ZipFile(file).use { zip ->
                val imageEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory && CbzReader.isImageFile(it.name) }
                    .sortedWith { e1, e2 -> CbzReader.naturalComparator.compare(e1.name, e2.name) }
                    .take(maxPages)
                    .toList()

                for ((idx, entry) in imageEntries.withIndex()) {
                    val ext = entry.name.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
                    val outFile = File(pagesDir, "page_%04d.$ext".format(idx + 1))
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (outFile.exists() && outFile.length() > 0L) {
                        result.add(outFile)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return result
    }

    /**
     * Extracts text from FB2 / FictionBook XML with robust charset detection and base64 stripping.
     */
    private fun extractFb2Text(file: File): List<String> {
        val blocks = mutableListOf<String>()
        try {
            var xmlContent: String? = null

            // Handle .fb2.zip
            if (file.extension.equals("zip", ignoreCase = true) || file.name.contains(".fb2.zip", ignoreCase = true)) {
                try {
                    ZipFile(file).use { zip ->
                        val fb2Entry = zip.entries().asSequence().firstOrNull { it.name.endsWith(".fb2", ignoreCase = true) }
                        if (fb2Entry != null) {
                            xmlContent = zip.getInputStream(fb2Entry).use { readStreamWithAutoCharset(it) }
                        }
                    }
                } catch (_: Throwable) {}
            }

            if (xmlContent == null) {
                xmlContent = readStreamWithAutoCharset(FileInputStream(file))
            }

            val xml = xmlContent ?: return emptyList()

            // 1. Strip massive base64 image blobs (<binary>...</binary>)
            val cleanXml = xml.replace(Regex("<binary[^>]*>.*?</binary>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")

            // 2. Extract title / book description if present
            val titleMatch = Regex("<book-title[^>]*>(.*?)</book-title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(cleanXml)
            if (titleMatch != null) {
                val title = unescapeXml(stripHtmlTags(titleMatch.groupValues[1]))
                if (title.isNotBlank()) {
                    blocks.add(title)
                }
            }

            // 3. Extract text paragraphs, headings, verses, and annotations
            val tagRegex = Regex("<(p|v|title|subtitle|text-author|annotation|epigraph)[^>]*>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            for (match in tagRegex.findAll(cleanXml)) {
                val inner = match.groupValues[2]
                val plain = unescapeXml(stripHtmlTags(inner)).trim()
                if (plain.isNotBlank()) {
                    blocks.add(plain)
                }
            }

            // Fallback: strip XML tags from cleanXml if no paragraphs were matched
            if (blocks.isEmpty()) {
                val plain = unescapeXml(stripHtmlTags(cleanXml))
                val paras = plain.split("\n\n").map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("<?xml") }
                blocks.addAll(paras)
            }
        } catch (_: Throwable) {
        }
        return blocks
    }

    // ==========================================
    // DOCUMENTS (DOCX, DOC, ODT, RTF, GDOC, CSV)
    // ==========================================
    private fun renderDocumentPages(
        cacheDir: File,
        file: File,
        format: DocumentFormat = DocumentFormat.DOCX,
        maxPages: Int = Int.MAX_VALUE
    ): List<File> {
        val pagesDir = File(cacheDir, "doc_cache/doc_${file.name.hashCode()}_${file.length()}_${file.lastModified()}")
        pagesDir.mkdirs()

        val existing = pagesDir.listFiles { _, name -> name.endsWith(".jpg") || name.endsWith(".png") }
        if (!existing.isNullOrEmpty()) {
            return existing.sortedWith { f1, f2 -> CbzReader.naturalComparator.compare(f1.name, f2.name) }
        }

        val textBlocks = extractDocumentText(file, format)
        return renderTextIntoPages(pagesDir, file.nameWithoutExtension, textBlocks, maxPages, isEbook = false)
    }

    private fun extractDocumentText(file: File, format: DocumentFormat): List<String> {
        return when (format) {
            DocumentFormat.DOCX -> extractDocxText(file)
            DocumentFormat.DOC -> extractDocBinaryText(file)
            DocumentFormat.ODT -> extractOdtText(file)
            DocumentFormat.RTF -> extractRtfText(file)
            else -> {
                val ext = file.extension.lowercase(Locale.ROOT)
                if (ext in setOf("docx", "dotx", "docm")) extractDocxText(file)
                else if (ext in setOf("doc", "dot")) extractDocBinaryText(file)
                else if (ext in setOf("odt", "ott")) extractOdtText(file)
                else if (ext == "rtf") extractRtfText(file)
                else extractDocxText(file).ifEmpty { extractDocBinaryText(file) }
            }
        }
    }

    private fun extractDocxText(file: File): List<String> {
        val blocks = mutableListOf<String>()
        try {
            ZipFile(file).use { zip ->
                val docEntry = zip.getEntry("word/document.xml")
                if (docEntry != null) {
                    val xml = zip.getInputStream(docEntry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    val pRegex = Regex("<w:p[^>]*>(.*?)</w:p>", RegexOption.DOT_MATCHES_ALL)
                    val tRegex = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
                    for (pMatch in pRegex.findAll(xml)) {
                        val pXml = pMatch.groupValues[1]
                        val pText = tRegex.findAll(pXml).map { unescapeXml(it.groupValues[1]) }.joinToString("").trim()
                        if (pText.isNotEmpty()) {
                            blocks.add(pText)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return blocks
    }

    private fun extractOdtText(file: File): List<String> {
        val blocks = mutableListOf<String>()
        try {
            ZipFile(file).use { zip ->
                val contentEntry = zip.getEntry("content.xml")
                if (contentEntry != null) {
                    val xml = zip.getInputStream(contentEntry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    val pRegex = Regex("<(text:p|text:h)[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
                    for (pMatch in pRegex.findAll(xml)) {
                        val plain = unescapeXml(stripHtmlTags(pMatch.groupValues[2])).trim()
                        if (plain.isNotEmpty()) {
                            blocks.add(plain)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return blocks
    }

    private fun extractRtfText(file: File): List<String> {
        val blocks = mutableListOf<String>()
        try {
            val rtf = file.readText(StandardCharsets.ISO_8859_1)
            // Strip RTF control words: \word, {\fonttbl...}, \par
            val clean = rtf
                .replace(Regex("\\{\\\\fonttbl.*?\\}", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("\\{\\\\colortbl.*?\\}", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("\\{\\\\stylesheet.*?\\}", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("\\{\\\\info.*?\\}", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("\\\\par[d]?", RegexOption.IGNORE_CASE), "\n\n")
                .replace(Regex("\\\\[a-z0-9\\-]+ ?", RegexOption.IGNORE_CASE), "")
                .replace(Regex("[{}]"), "")
                .trim()

            val paras = clean.split("\n\n").map { it.trim() }.filter { it.isNotBlank() }
            blocks.addAll(paras)
        } catch (_: Throwable) {
        }
        return blocks
    }

    /**
     * Extracts readable text from binary Microsoft Word 97-2003 (.doc) files.
     */
    private fun extractDocBinaryText(file: File): List<String> {
        val blocks = mutableListOf<String>()
        try {
            val docBytes = Ole2Parser.extractStream(file, "WordDocument")
                ?: file.readBytes()

            // In Word 97-2003 .doc format, the WordDocument stream begins with a FIB (File Information Block).
            // Magic numbers: 0xA5EC or 0xA5DC
            val textBlocks = extractWordStreamParagraphs(docBytes)
            if (textBlocks.isNotEmpty()) {
                blocks.addAll(textBlocks)
            }
        } catch (_: Throwable) {
        }

        if (blocks.isEmpty()) {
            blocks.addAll(extractPrintableTextRuns(file))
        }

        return blocks
    }

    private fun extractWordStreamParagraphs(bytes: ByteArray): List<String> {
        val paragraphs = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0

        while (i < bytes.size) {
            // Check for UTF-16LE 2-byte char vs 8-bit char
            if (i + 1 < bytes.size && bytes[i + 1].toInt() == 0 && (bytes[i].toInt() and 0xFF) in 32..126) {
                // ASCII character in UTF-16LE
                val c = (bytes[i].toInt() and 0xFF).toChar()
                sb.append(c)
                i += 2
            } else if (i + 1 < bytes.size && bytes[i].toInt() == 0x0D && bytes[i + 1].toInt() == 0) {
                // \r in UTF-16LE = paragraph break
                val p = cleanBinaryText(sb.toString())
                if (p.isNotBlank()) paragraphs.add(p)
                sb.setLength(0)
                i += 2
            } else {
                val b = bytes[i].toInt() and 0xFF
                if (b == 0x0D || b == 0x0A) {
                    val p = cleanBinaryText(sb.toString())
                    if (p.isNotBlank()) paragraphs.add(p)
                    sb.setLength(0)
                } else if (b in 32..126 || b in 160..255) {
                    sb.append(b.toChar())
                } else if (b < 32 && sb.length > 3) {
                    val p = cleanBinaryText(sb.toString())
                    if (p.isNotBlank()) paragraphs.add(p)
                    sb.setLength(0)
                }
                i++
            }
        }

        if (sb.isNotEmpty()) {
            val p = cleanBinaryText(sb.toString())
            if (p.isNotBlank()) paragraphs.add(p)
        }

        return paragraphs.filter { it.length >= 3 }
    }

    private fun extractPrintableTextRuns(file: File): List<String> {
        val result = mutableListOf<String>()
        try {
            val bytes = file.readBytes()
            val sb = StringBuilder()
            var printableCount = 0

            for (b in bytes) {
                val u = b.toInt() and 0xFF
                if (u in 32..126 || u in 160..255) {
                    sb.append(u.toChar())
                    printableCount++
                } else if (u == 0x0A || u == 0x0D) {
                    if (printableCount >= 4) {
                        val str = cleanBinaryText(sb.toString())
                        if (str.isNotBlank()) result.add(str)
                    }
                    sb.setLength(0)
                    printableCount = 0
                } else {
                    if (printableCount >= 6) {
                        val str = cleanBinaryText(sb.toString())
                        if (str.isNotBlank()) result.add(str)
                    }
                    sb.setLength(0)
                    printableCount = 0
                }
            }

            if (printableCount >= 4) {
                val str = cleanBinaryText(sb.toString())
                if (str.isNotBlank()) result.add(str)
            }
        } catch (_: Throwable) {
        }
        return result
    }

    private fun cleanBinaryText(text: String): String {
        return text
            .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]"), "")
            .trim()
    }

    private fun renderGenericTextPages(cacheDir: File, file: File, maxPages: Int = Int.MAX_VALUE): List<File> {
        val pagesDir = File(cacheDir, "doc_cache/txt_${file.name.hashCode()}_${file.length()}_${file.lastModified()}")
        pagesDir.mkdirs()

        val existing = pagesDir.listFiles { _, name -> name.endsWith(".jpg") || name.endsWith(".png") }
        if (!existing.isNullOrEmpty()) {
            return existing.sortedWith { f1, f2 -> CbzReader.naturalComparator.compare(f1.name, f2.name) }
        }

        val text = try {
            readStreamWithAutoCharset(FileInputStream(file))
        } catch (_: Throwable) {
            ""
        }

        // Safety check: if text contains high density of non-printable noise, render info card instead
        if (text.count { it < ' ' && it != '\n' && it != '\r' && it != '\t' } > 50) {
            return renderUnsupportedBinaryCard(cacheDir, file)
        }

        val paragraphs = text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        return renderTextIntoPages(pagesDir, file.nameWithoutExtension, paragraphs, maxPages, isEbook = false)
    }

    private fun renderUnsupportedBinaryCard(cacheDir: File, file: File): List<File> {
        val pagesDir = File(cacheDir, "doc_cache/card_${file.name.hashCode()}_${file.length()}")
        pagesDir.mkdirs()

        val outFile = File(pagesDir, "page_0001.jpg")
        if (outFile.exists() && outFile.length() > 0L) return listOf(outFile)

        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#F5F5F7"))

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1B1F")
            textSize = 48f
            typeface = Typeface.DEFAULT_BOLD
        }
        val infoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#49454F")
            textSize = 32f
            typeface = Typeface.DEFAULT
        }

        canvas.drawText(file.nameWithoutExtension.take(40), 100f, 300f, titlePaint)
        canvas.drawText("Format: ${file.extension.uppercase(Locale.ROOT).ifEmpty { "Binary Document" }}", 100f, 380f, infoPaint)
        canvas.drawText("File Size: ${file.length() / 1024} KB", 100f, 440f, infoPaint)

        FileOutputStream(outFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return listOf(outFile)
    }

    private fun renderTextIntoPages(
        pagesDir: File,
        docTitle: String,
        paragraphs: List<String>,
        maxPages: Int,
        isEbook: Boolean
    ): List<File> {
        val result = mutableListOf<File>()
        if (paragraphs.isEmpty()) {
            val emptyBitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(emptyBitmap)
            canvas.drawColor(if (isEbook) Color.parseColor("#FBF0D9") else Color.WHITE)
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 36f
            }
            canvas.drawText(docTitle.take(45), 100f, 200f, p)
            val outFile = File(pagesDir, "page_0001.jpg")
            FileOutputStream(outFile).use { emptyBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            emptyBitmap.recycle()
            if (outFile.exists()) result.add(outFile)
            return result
        }

        val marginHorizontal = 100
        val marginTop = 140
        val marginBottom = 120
        val contentWidth = PAGE_WIDTH - 2 * marginHorizontal
        val availableHeight = PAGE_HEIGHT - marginTop - marginBottom

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isEbook) Color.parseColor("#1C1B1F") else Color.parseColor("#212121")
            textSize = if (isEbook) 32f else 28f
            typeface = if (isEbook) Typeface.SERIF else Typeface.DEFAULT
        }

        val pagesParagraphs = mutableListOf<List<StaticLayout>>()
        var currentPageLayouts = mutableListOf<StaticLayout>()
        var currentHeight = 0

        for (para in paragraphs) {
            val cleanPara = cleanBinaryText(para)
            if (cleanPara.isBlank()) continue

            val layout = StaticLayout.Builder.obtain(
                cleanPara,
                0,
                cleanPara.length,
                textPaint,
                contentWidth
            ).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(if (isEbook) 10f else 6f, 1.15f)
                .build()

            if (currentHeight + layout.height + 30 > availableHeight && currentPageLayouts.isNotEmpty()) {
                pagesParagraphs.add(currentPageLayouts)
                currentPageLayouts = mutableListOf()
                currentHeight = 0
            }

            currentPageLayouts.add(layout)
            currentHeight += layout.height + 30
        }

        if (currentPageLayouts.isNotEmpty()) {
            pagesParagraphs.add(currentPageLayouts)
        }

        val totalPages = pagesParagraphs.size
        val count = minOf(totalPages, maxPages)

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#757575")
            textSize = 24f
            typeface = Typeface.DEFAULT
        }

        for (pageIdx in 0 until count) {
            val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            canvas.drawColor(if (isEbook) Color.parseColor("#FBF0D9") else Color.parseColor("#FFFFFF"))

            // Header: Document Title
            canvas.drawText(docTitle.take(45), marginHorizontal.toFloat(), 80f, headerPaint)

            // Header line
            val linePaint = Paint().apply {
                color = Color.parseColor("#E0E0E0")
                strokeWidth = 2f
            }
            canvas.drawLine(
                marginHorizontal.toFloat(),
                100f,
                (PAGE_WIDTH - marginHorizontal).toFloat(),
                100f,
                linePaint
            )

            // Content
            val layouts = pagesParagraphs[pageIdx]
            var yOffset = marginTop.toFloat()
            for (layout in layouts) {
                canvas.save()
                canvas.translate(marginHorizontal.toFloat(), yOffset)
                layout.draw(canvas)
                canvas.restore()
                yOffset += layout.height + 30f
            }

            // Footer: Page number
            val pageNumStr = "${pageIdx + 1} / $totalPages"
            val pageNumWidth = headerPaint.measureText(pageNumStr)
            canvas.drawText(
                pageNumStr,
                (PAGE_WIDTH - marginHorizontal - pageNumWidth),
                (PAGE_HEIGHT - 50).toFloat(),
                headerPaint
            )

            val outFile = File(pagesDir, "page_%04d.jpg".format(pageIdx + 1))
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()
            if (outFile.exists() && outFile.length() > 0L) {
                result.add(outFile)
            }
        }

        return result
    }

    private fun stripHtmlTags(html: String): String {
        return html
            .replace(Regex("<head[^>]*>.*?</head>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<div[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<h[1-6][^>]*>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("</li>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .let { unescapeXml(it) }
            .trim()
    }

    private fun unescapeXml(text: String): String {
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&lsquo;", "‘")
            .replace("&rsquo;", "’")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace(Regex("&#(\\d+);")) { match ->
                try {
                    match.groupValues[1].toInt().toChar().toString()
                } catch (_: Throwable) {
                    match.value
                }
            }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
                try {
                    match.groupValues[1].toInt(16).toChar().toString()
                } catch (_: Throwable) {
                    match.value
                }
            }
    }

    private fun readStreamWithAutoCharset(stream: InputStream): String {
        val bytes = stream.readBytes()
        if (bytes.isEmpty()) return ""

        // Check for XML encoding declaration
        val sample = String(bytes, 0, minOf(bytes.size, 1024), StandardCharsets.ISO_8859_1)
        val encMatch = Regex("encoding=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(sample)
        if (encMatch != null) {
            val encName = encMatch.groupValues[1]
            try {
                val charset = Charset.forName(encName)
                return String(bytes, charset)
            } catch (_: Throwable) {}
        }

        // Try UTF-8 first
        try {
            val utf8 = String(bytes, StandardCharsets.UTF_8)
            if (!utf8.contains('\uFFFD')) {
                return utf8
            }
        } catch (_: Throwable) {}

        // Fallback to Windows-1251 (common for FB2/DOC) then ISO-8859-1
        return try {
            String(bytes, Charset.forName("windows-1251"))
        } catch (_: Throwable) {
            String(bytes, StandardCharsets.ISO_8859_1)
        }
    }

    // ==========================================
    // OLE2 COMPOUND DOCUMENT PARSER
    // ==========================================
    private object Ole2Parser {
        fun listStreamNames(file: File): List<String> {
            val names = mutableListOf<String>()
            try {
                val bytes = file.readBytes()
                if (bytes.size < 512) return emptyList()
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val uSectorShift = buf.getShort(30).toInt()
                val sectorSize = 1 shl uSectorShift
                val sectDirStart = buf.getInt(48)

                if (sectDirStart < 0) return emptyList()
                val dirOffset = (sectDirStart + 1) * sectorSize
                if (dirOffset + 512 <= bytes.size) {
                    for (entryIdx in 0 until (sectorSize / 128)) {
                        val entryOffset = dirOffset + entryIdx * 128
                        val nameBytes = bytes.copyOfRange(entryOffset, entryOffset + 64)
                        val name = String(nameBytes, StandardCharsets.UTF_16LE).trim { it <= ' ' || it == '\u0000' }
                        if (name.isNotBlank()) {
                            names.add(name)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            return names
        }

        fun extractStream(file: File, targetStreamName: String): ByteArray? {
            try {
                val bytes = file.readBytes()
                if (bytes.size < 512) return null
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

                val magic = buf.getLong(0)
                if (magic != -0x1E10BEEF1F303120L && (bytes[0] != 0xD0.toByte() || bytes[1] != 0xCF.toByte())) {
                    return null
                }

                val uSectorShift = buf.getShort(30).toInt()
                val sectorSize = 1 shl uSectorShift
                val sectDirStart = buf.getInt(48)

                if (sectDirStart < 0) return null

                // Read Directory entries
                val dirOffset = (sectDirStart + 1) * sectorSize
                if (dirOffset >= bytes.size) return null

                for (entryIdx in 0 until (sectorSize / 128)) {
                    val entryOffset = dirOffset + entryIdx * 128
                    if (entryOffset + 128 > bytes.size) break

                    val nameBytes = bytes.copyOfRange(entryOffset, entryOffset + 64)
                    val name = String(nameBytes, StandardCharsets.UTF_16LE).trim { it <= ' ' || it == '\u0000' }

                    if (name.equals(targetStreamName, ignoreCase = true)) {
                        val sectStart = buf.getInt(entryOffset + 116)
                        val streamSize = buf.getLong(entryOffset + 120).toInt()

                        if (sectStart >= 0 && streamSize > 0) {
                            val streamOffset = (sectStart + 1) * sectorSize
                            val endOffset = minOf(bytes.size, streamOffset + streamSize)
                            if (streamOffset < bytes.size && endOffset > streamOffset) {
                                return bytes.copyOfRange(streamOffset, endOffset)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            return null
        }
    }
}
