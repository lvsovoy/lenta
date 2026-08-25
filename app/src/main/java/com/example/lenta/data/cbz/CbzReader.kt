package com.example.lenta.data.cbz

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

object CbzReader {

    val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "jpe", "jif", "jfif", "jfi",
        "png", "apng",
        "webp",
        "gif",
        "bmp", "dib",
        "avif", "avis",
        "heic", "heif",
        "jxl",
        "tif", "tiff",
        "svg"
    )

    private val NUM_PATTERN = Pattern.compile("(\\d+)")

    /**
     * Natural sort comparator for comic pages and folder hierarchies (e.g. page1.jpg, page2.jpg, page10.jpg)
     */
    val naturalComparator = Comparator<String> { s1, s2 ->
        val norm1 = s1.replace('\\', '/')
        val norm2 = s2.replace('\\', '/')
        val matcher1 = NUM_PATTERN.matcher(norm1)
        val matcher2 = NUM_PATTERN.matcher(norm2)

        var pos1 = 0
        var pos2 = 0

        while (pos1 < norm1.length && pos2 < norm2.length) {
            val found1 = matcher1.find(pos1)
            val found2 = matcher2.find(pos2)

            if (found1 && found2 && matcher1.start() == pos1 && matcher2.start() == pos2) {
                val numStr1 = matcher1.group()
                val numStr2 = matcher2.group()
                val num1 = numStr1.toLongOrNull()
                val num2 = numStr2.toLongOrNull()

                if (num1 != null && num2 != null) {
                    val diff = num1.compareTo(num2)
                    if (diff != 0) return@Comparator diff
                } else {
                    val trimmed1 = numStr1.trimStart('0')
                    val trimmed2 = numStr2.trimStart('0')
                    val lenDiff = trimmed1.length.compareTo(trimmed2.length)
                    if (lenDiff != 0) return@Comparator lenDiff
                    val strDiff = trimmed1.compareTo(trimmed2)
                    if (strDiff != 0) return@Comparator strDiff
                }
                pos1 = matcher1.end()
                pos2 = matcher2.end()
            } else {
                val c1 = norm1[pos1]
                val c2 = norm2[pos2]
                val diff = c1.lowercaseChar().compareTo(c2.lowercaseChar())
                if (diff != 0) return@Comparator diff
                pos1++
                pos2++
            }
        }
        norm1.length.compareTo(norm2.length)
    }

    fun isImageFile(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        if (normalized.endsWith("/")) return false
        val segments = normalized.split('/')
        // Skip macOS metadata (__MACOSX, ._*) and hidden dot-files
        if (segments.any { it.startsWith(".") || it.equals("__MACOSX", ignoreCase = true) }) {
            return false
        }
        val fileName = segments.last()
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in IMAGE_EXTENSIONS
    }

    /**
     * Extracts pages from a CBZ/ZIP file to a cache directory and returns a list of page Files.
     */
    suspend fun getComicPages(context: Context, comicFile: File): List<File> =
        getComicPages(context.cacheDir, comicFile)

    /**
     * Extracts ONLY the first page (cover) from a CBZ/ZIP file to thumbnail cache.
     * Returns the cached File, or null if no image found or error.
     */
    suspend fun getComicCover(context: Context, comicFile: File): File? =
        getComicCover(context.cacheDir, comicFile)

    suspend fun getComicCover(cacheDir: File, comicFile: File): File? = withContext(Dispatchers.IO) {
        if (!comicFile.exists() || comicFile.length() == 0L) return@withContext null

        val cacheKey = "cover_${comicFile.name.hashCode()}_${comicFile.length()}_${comicFile.lastModified()}"
        val thumbCacheDir = File(cacheDir, "thumbnail_cache/cbz")
        thumbCacheDir.mkdirs()

        val existingCover = thumbCacheDir.listFiles { _, name -> name.startsWith(cacheKey) }?.firstOrNull()
        if (existingCover != null && existingCover.length() > 0) {
            return@withContext existingCover
        }

        // Also check if comic was already extracted in cbz_cache
        val fullCacheKey = "comic_${comicFile.name.hashCode()}_${comicFile.length()}_${comicFile.lastModified()}"
        val fullComicDir = File(cacheDir, "cbz_cache/$fullCacheKey")
        if (fullComicDir.exists() && fullComicDir.isDirectory) {
            val firstPage = fullComicDir.listFiles { _, name -> isImageFile(name) }
                ?.minWithOrNull { f1, f2 -> naturalComparator.compare(f1.name, f2.name) }
            if (firstPage != null && firstPage.length() > 0) {
                return@withContext firstPage
            }
        }

        val charsets = listOf(
            StandardCharsets.UTF_8,
            Charset.forName("Cp437"),
            Charset.forName("ISO-8859-1"),
            Charset.forName("Shift_JIS")
        )

        // 1. Try random-access ZipFile first
        for (charset in charsets) {
            try {
                ZipFile(comicFile, charset).use { zip ->
                    val imageEntries = mutableListOf<ZipEntry>()
                    val entriesEnum = zip.entries()
                    while (entriesEnum.hasMoreElements()) {
                        try {
                            val entry = entriesEnum.nextElement()
                            if (!entry.isDirectory && isImageFile(entry.name)) {
                                imageEntries.add(entry)
                            }
                        } catch (_: Throwable) {
                        }
                    }

                    val firstEntry = imageEntries.minWithOrNull { e1, e2 ->
                        naturalComparator.compare(e1.name, e2.name)
                    }

                    if (firstEntry != null) {
                        val ext = firstEntry.name.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
                        val outFile = File(thumbCacheDir, "${cacheKey}.$ext")
                        val tempFile = File(thumbCacheDir, "${cacheKey}_tmp_${System.nanoTime()}.$ext")
                        zip.getInputStream(firstEntry).use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (tempFile.exists() && tempFile.length() > 0L) {
                            if (!tempFile.renameTo(outFile)) {
                                tempFile.copyTo(outFile, overwrite = true)
                                tempFile.delete()
                            }
                        }
                        if (outFile.exists() && outFile.length() > 0L) {
                            return@withContext outFile
                        }
                        if (tempFile.exists() && tempFile.length() > 0L) {
                            return@withContext tempFile
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 2. Try streaming ZipInputStream to find the first image without full extraction
        for (charset in charsets) {
            try {
                comicFile.inputStream().use { input ->
                    val cover = extractCoverFromStream(cacheDir, cacheKey, input, charset)
                    if (cover != null && cover.exists()) {
                        return@withContext cover
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 3. Fallback: extract pages
        try {
            val pages = getComicPages(cacheDir, comicFile)
            if (pages.isNotEmpty()) {
                return@withContext pages.first()
            }
        } catch (_: Throwable) {
        }

        null
    }

    /**
     * Extracts ONLY the first page (cover) from an input stream (e.g. from local file or network stream)
     * into the CBZ thumbnail cache.
     */
    suspend fun extractCoverFromStream(
        cacheDir: File,
        cacheKey: String,
        inputStream: InputStream,
        charset: Charset = StandardCharsets.UTF_8
    ): File? = withContext(Dispatchers.IO) {
        val thumbCacheDir = File(cacheDir, "thumbnail_cache/cbz")
        thumbCacheDir.mkdirs()

        val existingCover = thumbCacheDir.listFiles { _, name -> name.startsWith(cacheKey) }?.firstOrNull()
        if (existingCover != null && existingCover.length() > 0) {
            return@withContext existingCover
        }

        try {
            val zipInput = ZipInputStream(inputStream, charset)
            var entry: ZipEntry?
            var bestEntryName: String? = null
            var coverBytes: ByteArray? = null

            while (true) {
                entry = try { zipInput.nextEntry } catch (_: Throwable) { null }
                if (entry == null) break

                if (!entry.isDirectory && isImageFile(entry.name)) {
                    val bytes = zipInput.readBytes()
                    if (bytes.isNotEmpty()) {
                        if (bestEntryName == null || naturalComparator.compare(entry.name, bestEntryName) < 0) {
                            bestEntryName = entry.name
                            coverBytes = bytes
                        }
                    }
                }
                try { zipInput.closeEntry() } catch (_: Throwable) {}
            }

            if (bestEntryName != null && coverBytes != null && coverBytes.isNotEmpty()) {
                val ext = bestEntryName.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
                val outFile = File(thumbCacheDir, "${cacheKey}.$ext")
                val tempFile = File(thumbCacheDir, "${cacheKey}_tmp_${System.nanoTime()}.$ext")
                FileOutputStream(tempFile).use { it.write(coverBytes) }
                if (tempFile.exists() && tempFile.length() > 0L) {
                    if (!tempFile.renameTo(outFile)) {
                        tempFile.copyTo(outFile, overwrite = true)
                        tempFile.delete()
                    }
                }
                if (outFile.exists() && outFile.length() > 0L) {
                    return@withContext outFile
                }
                if (tempFile.exists() && tempFile.length() > 0L) {
                    return@withContext tempFile
                }
            }
        } catch (_: Throwable) {
        }
        null
    }

    suspend fun getComicPages(cacheDir: File, comicFile: File): List<File> = withContext(Dispatchers.IO) {
        val cacheKey = "comic_${comicFile.name.hashCode()}_${comicFile.length()}_${comicFile.lastModified()}"
        val comicCacheDir = File(cacheDir, "cbz_cache/$cacheKey")
        val markerFile = File(comicCacheDir, ".complete")

        if (comicCacheDir.exists() && comicCacheDir.isDirectory && markerFile.exists()) {
            val count = markerFile.readText().trim().toIntOrNull() ?: 0
            val existingPages = comicCacheDir.listFiles { _, name -> isImageFile(name) }
                ?.sortedWith { f1, f2 -> naturalComparator.compare(f1.name, f2.name) }
            if (existingPages != null && existingPages.size == count && count > 0) {
                return@withContext existingPages
            }
        }

        // Clean any corrupted/incomplete cache
        if (comicCacheDir.exists()) {
            comicCacheDir.deleteRecursively()
        }

        val stagingDir = File(cacheDir, "cbz_cache/${cacheKey}_tmp_${System.currentTimeMillis()}")
        stagingDir.mkdirs()

        val pages = extractZipToDir(comicFile, stagingDir)

        if (pages.isNotEmpty()) {
            File(stagingDir, ".complete").writeText(pages.size.toString())

            if (stagingDir.renameTo(comicCacheDir)) {
                return@withContext comicCacheDir.listFiles { _, name -> isImageFile(name) }
                    ?.sortedWith { f1, f2 -> naturalComparator.compare(f1.name, f2.name) }
                    ?: pages
            } else {
                comicCacheDir.mkdirs()
                stagingDir.listFiles()?.forEach { file ->
                    file.renameTo(File(comicCacheDir, file.name))
                }
                stagingDir.deleteRecursively()
                return@withContext comicCacheDir.listFiles { _, name -> isImageFile(name) }
                    ?.sortedWith { f1, f2 -> naturalComparator.compare(f1.name, f2.name) }
                    ?: pages
            }
        } else {
            stagingDir.deleteRecursively()
            return@withContext emptyList()
        }
    }

    private fun extractZipToDir(comicFile: File, outDir: File): List<File> {
        val charsets = listOf(
            StandardCharsets.UTF_8,
            Charset.forName("Cp437"),
            Charset.forName("ISO-8859-1"),
            Charset.forName("Shift_JIS")
        )

        for (charset in charsets) {
            try {
                ZipFile(comicFile, charset).use { zip ->
                    val entries = zip.entries().toList()
                        .filter { !it.isDirectory && isImageFile(it.name) }
                        .sortedWith { e1, e2 -> naturalComparator.compare(e1.name, e2.name) }

                    if (entries.isNotEmpty()) {
                        val extractedFiles = mutableListOf<File>()
                        entries.forEachIndexed { index, entry ->
                            val ext = entry.name.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
                            val outFile = File(outDir, String.format(Locale.US, "page_%04d.%s", index + 1, ext))
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(outFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            extractedFiles.add(outFile)
                        }
                        return extractedFiles
                    }
                }
            } catch (_: Throwable) {
                // Try next charset or fallback
            }
        }

        // Fallback to ZipInputStream with different charsets
        for (charset in charsets) {
            try {
                comicFile.inputStream().use { input ->
                    val files = extractFromInputStream(input, outDir, charset)
                    if (files.isNotEmpty()) {
                        return files
                    }
                }
            } catch (_: Throwable) {
                // Continue
            }
        }

        return emptyList()
    }

    /**
     * Extract pages from Uri
     */
    suspend fun getComicPagesFromUri(context: Context, uri: Uri, id: String): List<File> = withContext(Dispatchers.IO) {
        val cacheKey = "comic_uri_${id.hashCode()}"
        val comicCacheDir = File(context.cacheDir, "cbz_cache/$cacheKey")
        val markerFile = File(comicCacheDir, ".complete")

        if (comicCacheDir.exists() && comicCacheDir.isDirectory && markerFile.exists()) {
            val count = markerFile.readText().trim().toIntOrNull() ?: 0
            val existingPages = comicCacheDir.listFiles { _, name -> isImageFile(name) }
                ?.sortedWith { f1, f2 -> naturalComparator.compare(f1.name, f2.name) }
            if (existingPages != null && existingPages.size == count && count > 0) {
                return@withContext existingPages
            }
        }

        if (comicCacheDir.exists()) {
            comicCacheDir.deleteRecursively()
        }

        val stagingDir = File(context.cacheDir, "cbz_cache/${cacheKey}_tmp_${System.currentTimeMillis()}")
        stagingDir.mkdirs()

        val pages = mutableListOf<File>()
        val charsets = listOf(
            StandardCharsets.UTF_8,
            Charset.forName("Cp437"),
            Charset.forName("ISO-8859-1")
        )

        for (charset in charsets) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val extracted = extractFromInputStream(input, stagingDir, charset)
                    if (extracted.isNotEmpty()) {
                        pages.addAll(extracted)
                        break
                    }
                }
            } catch (_: Throwable) {
            }
        }

        if (pages.isNotEmpty()) {
            File(stagingDir, ".complete").writeText(pages.size.toString())
            if (stagingDir.renameTo(comicCacheDir)) {
                return@withContext comicCacheDir.listFiles { _, name -> isImageFile(name) }
                    ?.sortedWith { f1, f2 -> naturalComparator.compare(f1.name, f2.name) }
                    ?: pages
            } else {
                comicCacheDir.mkdirs()
                stagingDir.listFiles()?.forEach { file ->
                    file.renameTo(File(comicCacheDir, file.name))
                }
                stagingDir.deleteRecursively()
                return@withContext comicCacheDir.listFiles { _, name -> isImageFile(name) }
                    ?.sortedWith { f1, f2 -> naturalComparator.compare(f1.name, f2.name) }
                    ?: pages
            }
        } else {
            stagingDir.deleteRecursively()
            return@withContext emptyList()
        }
    }

    private fun extractFromInputStream(input: InputStream, outDir: File, charset: Charset = StandardCharsets.UTF_8): List<File> {
        val pages = mutableListOf<Pair<String, File>>()
        val zipInput = ZipInputStream(input, charset)
        var entry: ZipEntry? = try { zipInput.nextEntry } catch (_: Throwable) { null }
        var index = 0

        while (entry != null) {
            if (!entry.isDirectory && isImageFile(entry.name)) {
                val originalName = entry.name
                val ext = originalName.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
                val tempFile = File(outDir, "temp_${index}.$ext")
                try {
                    FileOutputStream(tempFile).use { out ->
                        zipInput.copyTo(out)
                    }
                    pages.add(originalName to tempFile)
                    index++
                } catch (_: Throwable) {
                    tempFile.delete()
                }
            }
            try {
                zipInput.closeEntry()
                entry = zipInput.nextEntry
            } catch (_: Throwable) {
                break
            }
        }

        if (pages.isEmpty()) return emptyList()

        // Sort naturally and rename cleanly
        pages.sortWith { (name1, _), (name2, _) -> naturalComparator.compare(name1, name2) }
        val finalFiles = mutableListOf<File>()

        pages.forEachIndexed { pageIdx, (_, tempFile) ->
            val ext = tempFile.name.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
            val targetFile = File(outDir, String.format(Locale.US, "page_%04d.%s", pageIdx + 1, ext))
            if (tempFile.renameTo(targetFile)) {
                finalFiles.add(targetFile)
            } else {
                finalFiles.add(tempFile)
            }
        }

        return finalFiles
    }
}
