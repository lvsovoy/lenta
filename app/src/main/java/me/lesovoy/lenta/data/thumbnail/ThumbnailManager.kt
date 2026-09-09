package me.lesovoy.lenta.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import coil.Coil
import coil.request.ImageRequest
import me.lesovoy.lenta.data.audio.AudioMetadataHelper
import me.lesovoy.lenta.data.cbz.CbzReader
import me.lesovoy.lenta.data.document.DocumentPageReader
import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.model.MediaType
import me.lesovoy.lenta.data.nextcloud.NextcloudClient
import me.lesovoy.lenta.data.nextcloud.NextcloudPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

object ThumbnailManager {

    const val THUMBNAIL_DIR = "thumbnail_cache"
    const val CBZ_SUBDIR = "cbz"
    const val VIDEO_SUBDIR = "video"
    const val COIL_SUBDIR = "coil"

    fun getThumbnailDir(context: Context): File = getThumbnailDir(context.cacheDir)

    fun getThumbnailDir(cacheDir: File): File {
        val dir = File(cacheDir, THUMBNAIL_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getVideoThumbnailDir(cacheDir: File): File {
        val dir = File(cacheDir, "$THUMBNAIL_DIR/$VIDEO_SUBDIR")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getCbzThumbnailDir(cacheDir: File): File {
        val dir = File(cacheDir, "$THUMBNAIL_DIR/$CBZ_SUBDIR")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getVideoThumbnailCacheKey(item: MediaItem): String {
        return if (item.isRemote) {
            "vid_nc_${item.id.hashCode()}_${item.size}_${item.dateModified}.jpg"
        } else {
            val file = File(item.path)
            "vid_${file.name.hashCode()}_${file.length()}_${file.lastModified()}.jpg"
        }
    }

    fun getCbzCoverCacheKey(item: MediaItem): String {
        return if (item.isRemote) {
            "cover_nc_${item.id.hashCode()}_${item.size}_${item.dateModified}"
        } else {
            val file = File(item.path)
            "cover_${file.name.hashCode()}_${file.length()}_${file.lastModified()}"
        }
    }

    fun isThumbnailCached(context: Context, item: MediaItem): Boolean =
        isThumbnailCached(context.cacheDir, item)

    fun isThumbnailCached(cacheDir: File, item: MediaItem): Boolean {
        if (item.isDirectory) return true
        if (item.isRemote) {
            val cacheSubdir = File(cacheDir, "nextcloud_cache")
            val safeFileName = "${item.id.hashCode()}_${item.name}"
            val cachedFile = File(cacheSubdir, safeFileName)
            if (cachedFile.exists() && cachedFile.length() > 0L) return true

            val remoteCacheSubdir = File(cacheDir, "remote_cache/${item.sourceId ?: ""}")
            val remoteCachedFile = File(remoteCacheSubdir, safeFileName)
            if (remoteCachedFile.exists() && remoteCachedFile.length() > 0L) return true

            return when (item.type) {
                MediaType.VIDEO -> {
                    val cacheKey = getVideoThumbnailCacheKey(item)
                    val cachedThumb = File(getVideoThumbnailDir(cacheDir), cacheKey)
                    cachedThumb.exists() && cachedThumb.length() > 0L
                }
                MediaType.CBZ, MediaType.DOCUMENT, MediaType.EBOOK, MediaType.PRESENTATION -> {
                    val cacheKey = getCbzCoverCacheKey(item)
                    val thumbCacheDir = getCbzThumbnailDir(cacheDir)
                    val foundInCbz = thumbCacheDir.listFiles { _, name -> name.startsWith(cacheKey) }?.any { it.length() > 0 } == true
                    if (foundInCbz) true else {
                        val docThumbDir = File(cacheDir, "thumbnail_cache/doc")
                        docThumbDir.listFiles { _, name -> name.startsWith(cacheKey) }?.any { it.length() > 0 } == true
                    }
                }
                MediaType.AUDIO -> {
                    val audioThumbDir = File(cacheDir, "thumbnail_cache/audio")
                    audioThumbDir.listFiles { _, name -> name.contains(item.name.hashCode().toString()) }?.any { it.length() > 0 } == true
                }
                MediaType.IMAGE, MediaType.GIF -> {
                    true
                }
            }
        }

        val file = File(item.path)
        if (!file.exists() || file.length() == 0L) return false

        return when (item.type) {
            MediaType.VIDEO -> {
                val cacheKey = getVideoThumbnailCacheKey(item)
                val cachedThumb = File(getVideoThumbnailDir(cacheDir), cacheKey)
                cachedThumb.exists() && cachedThumb.length() > 0L
            }
            MediaType.CBZ -> {
                val cacheKey = getCbzCoverCacheKey(item)
                val thumbCacheDir = getCbzThumbnailDir(cacheDir)
                thumbCacheDir.listFiles { _, name -> name.startsWith(cacheKey) }?.any { it.length() > 0 } == true
            }
            MediaType.DOCUMENT, MediaType.EBOOK, MediaType.PRESENTATION -> {
                val cacheKey = "cover_${file.name.hashCode()}_${file.length()}_${file.lastModified()}"
                val thumbCacheDir = File(cacheDir, "thumbnail_cache/doc")
                thumbCacheDir.listFiles { _, name -> name.startsWith(cacheKey) }?.any { it.length() > 0 } == true
            }
            MediaType.AUDIO -> {
                val audioThumbDir = File(cacheDir, "thumbnail_cache/audio")
                audioThumbDir.listFiles { _, name -> name.contains(file.name.hashCode().toString()) }?.any { it.length() > 0 } == true
            }
            MediaType.IMAGE, MediaType.GIF -> {
                true
            }
        }
    }

    suspend fun getCbzCoverThumbnail(context: Context, comicFile: File): File? =
        CbzReader.getComicCover(context.cacheDir, comicFile)

    suspend fun getCbzCoverThumbnail(cacheDir: File, comicFile: File): File? =
        CbzReader.getComicCover(cacheDir, comicFile)

    suspend fun getCbzCoverThumbnail(
        context: Context,
        item: MediaItem,
        client: me.lesovoy.lenta.data.source.RemoteFileClient? = null
    ): File? = getCbzCoverThumbnail(context.cacheDir, item, client)

    suspend fun getCbzCoverThumbnail(
        cacheDir: File,
        item: MediaItem,
        client: me.lesovoy.lenta.data.source.RemoteFileClient? = null
    ): File? = withContext(Dispatchers.IO) {
        if (!item.isRemote) {
            val file = File(item.path)
            return@withContext getCbzCoverThumbnail(cacheDir, file)
        }

        val cacheKey = getCbzCoverCacheKey(item)
        val thumbCacheDir = getCbzThumbnailDir(cacheDir)
        val existingCover = thumbCacheDir.listFiles { _, name -> name.startsWith(cacheKey) }?.firstOrNull { it.length() > 0L }
        if (existingCover != null && existingCover.exists()) {
            return@withContext existingCover
        }

        // Check if full file is already downloaded in nextcloud_cache or remote_cache
        val nextcloudCacheDir = File(cacheDir, "nextcloud_cache")
        val downloadedFile = File(nextcloudCacheDir, "${item.id.hashCode()}_${item.name}")
        if (downloadedFile.exists() && downloadedFile.length() > 0L) {
            val cover = CbzReader.getComicCover(cacheDir, downloadedFile)
            if (cover != null && cover.exists()) {
                val ext = cover.extension
                val cachedTarget = File(thumbCacheDir, "$cacheKey.$ext")
                if (!cachedTarget.exists()) {
                    try { cover.copyTo(cachedTarget, overwrite = true) } catch (_: Throwable) {}
                }
                return@withContext if (cachedTarget.exists()) cachedTarget else cover
            }
        }

        val remoteCacheDir = File(cacheDir, "remote_cache/${item.sourceId ?: ""}")
        val remoteDownloadedFile = File(remoteCacheDir, "${item.id.hashCode()}_${item.name}")
        if (remoteDownloadedFile.exists() && remoteDownloadedFile.length() > 0L) {
            val cover = CbzReader.getComicCover(cacheDir, remoteDownloadedFile)
            if (cover != null && cover.exists()) {
                val ext = cover.extension
                val cachedTarget = File(thumbCacheDir, "$cacheKey.$ext")
                if (!cachedTarget.exists()) {
                    try { cover.copyTo(cachedTarget, overwrite = true) } catch (_: Throwable) {}
                }
                return@withContext if (cachedTarget.exists()) cachedTarget else cover
            }
        }

        if (client != null && item.uriString.isNotEmpty()) {
            try {
                val reqBuilder = okhttp3.Request.Builder().url(item.uriString)
                for ((k, v) in client.getAuthHeaders()) {
                    reqBuilder.header(k, v)
                }

                val okHttpClient = okhttp3.OkHttpClient()
                okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.byteStream()?.use { stream ->
                            val cover = CbzReader.extractCoverFromStream(cacheDir, cacheKey, stream)
                            if (cover != null && cover.exists()) {
                                return@withContext cover
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        null
    }

    suspend fun getDocumentCoverThumbnail(
        cacheDir: File,
        item: MediaItem,
        client: me.lesovoy.lenta.data.source.RemoteFileClient? = null
    ): File? = withContext(Dispatchers.IO) {
        if (!item.isRemote) {
            val file = File(item.path)
            return@withContext DocumentPageReader.getDocumentCover(cacheDir, file)
        }

        val cacheKey = "cover_${item.id.hashCode()}_${item.size}_${item.dateModified}"
        val thumbCacheDir = File(cacheDir, "thumbnail_cache/doc")
        thumbCacheDir.mkdirs()
        val existingCover = thumbCacheDir.listFiles { _, name -> name.startsWith(cacheKey) }?.firstOrNull { it.length() > 0L }
        if (existingCover != null && existingCover.exists()) {
            return@withContext existingCover
        }

        val nextcloudCacheDir = File(cacheDir, "nextcloud_cache")
        val downloadedFile = File(nextcloudCacheDir, "${item.id.hashCode()}_${item.name}")
        if (downloadedFile.exists() && downloadedFile.length() > 0L) {
            val cover = DocumentPageReader.getDocumentCover(cacheDir, downloadedFile)
            if (cover != null && cover.exists()) return@withContext cover
        }

        val remoteCacheDir = File(cacheDir, "remote_cache/${item.sourceId ?: ""}")
        val remoteDownloadedFile = File(remoteCacheDir, "${item.id.hashCode()}_${item.name}")
        if (remoteDownloadedFile.exists() && remoteDownloadedFile.length() > 0L) {
            val cover = DocumentPageReader.getDocumentCover(cacheDir, remoteDownloadedFile)
            if (cover != null && cover.exists()) return@withContext cover
        }

        null
    }

    suspend fun getVideoThumbnail(context: Context, videoFile: File): File? =
        getVideoThumbnail(context.cacheDir, videoFile)

    suspend fun getVideoThumbnail(
        context: Context,
        item: MediaItem,
        client: me.lesovoy.lenta.data.source.RemoteFileClient? = null
    ): File? = withContext(Dispatchers.IO) {
        if (item.isRemote) {
            getVideoThumbnail(context.cacheDir, item, client)
        } else {
            val file = File(item.path)
            if (file.exists() && file.length() > 0L) {
                getVideoThumbnail(context.cacheDir, file)
            } else if (item.uriString.isNotEmpty()) {
                getVideoThumbnailFromUri(context, Uri.parse(item.uriString), getVideoThumbnailCacheKey(item))
            } else {
                null
            }
        }
    }

    fun calculateColorDifference(bitmap: Bitmap): Double {
        if (bitmap.isRecycled) return 0.0
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0.0
        return calculateColorDifference(width, height) { x, y -> bitmap.getPixel(x, y) }
    }

    fun calculateColorDifference(width: Int, height: Int, getPixel: (x: Int, y: Int) -> Int): Double {
        if (width <= 0 || height <= 0) return 0.0

        val sampleGrid = 32
        val stepX = maxOf(1, width / sampleGrid)
        val stepY = maxOf(1, height / sampleGrid)

        var count = 0
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumSqR = 0L
        var sumSqG = 0L
        var sumSqB = 0L

        var minR = 255
        var maxR = 0
        var minG = 255
        var maxG = 0
        var minB = 255
        var maxB = 0

        for (y in 0 until height step stepY) {
            for (x in 0 until width step stepX) {
                val pixel = getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                count++
                sumR += r
                sumG += g
                sumB += b
                sumSqR += (r * r).toLong()
                sumSqG += (g * g).toLong()
                sumSqB += (b * b).toLong()

                if (r < minR) minR = r
                if (r > maxR) maxR = r
                if (g < minG) minG = g
                if (g > maxG) maxG = g
                if (b < minB) minB = b
                if (b > maxB) maxB = b
            }
        }

        if (count == 0) return 0.0

        val meanR = sumR.toDouble() / count
        val meanG = sumG.toDouble() / count
        val meanB = sumB.toDouble() / count

        val varR = (sumSqR.toDouble() / count) - (meanR * meanR)
        val varG = (sumSqG.toDouble() / count) - (meanG * meanG)
        val varB = (sumSqB.toDouble() / count) - (meanB * meanB)

        val stdDev = Math.sqrt(maxOf(0.0, varR) + maxOf(0.0, varG) + maxOf(0.0, varB))
        val maxRange = maxOf(maxR - minR, maxG - minG, maxB - minB)

        // Combine standard deviation and maxRange for a robust color difference score
        return stdDev + (maxRange * 0.1)
    }

    fun isSingleColor(differenceScore: Double): Boolean {
        // A low score indicates a uniform / single-colour frame (e.g. solid black, blank screen, uniform background)
        return differenceScore < 10.0
    }

    fun isSingleColorFrame(bitmap: Bitmap, differenceScore: Double = calculateColorDifference(bitmap)): Boolean {
        return isSingleColor(differenceScore)
    }

    fun getCandidateVideoFrameTimestamps(durationUs: Long): List<Long> {
        val initialOffsets = listOf(
            1_000_000L,  // 1s
            2_000_000L,  // 2s
            3_000_000L,  // 3s
            5_000_000L,  // 5s
            8_000_000L,  // 8s
            12_000_000L, // 12s
            20_000_000L, // 20s
            30_000_000L, // 30s
            60_000_000L  // 60s
        )

        val durationFractions = if (durationUs > 0L) {
            listOf(
                (durationUs * 0.05).toLong(),
                (durationUs * 0.10).toLong(),
                (durationUs * 0.20).toLong(),
                (durationUs * 0.30).toLong(),
                (durationUs * 0.50).toLong(),
                (durationUs * 0.70).toLong()
            )
        } else {
            emptyList()
        }

        val forwardTimestamps = (initialOffsets + durationFractions)
            .filter { ts -> ts > 0L && (durationUs <= 0L || ts < durationUs) }
            .distinct()
            .sorted()

        val fallbackTimestamps = listOf(500_000L, 0L).filter { durationUs <= 0L || it < durationUs }

        return (forwardTimestamps + fallbackTimestamps).distinct()
    }

    fun getCandidateVideoFrameTimestamps(retriever: MediaMetadataRetriever): List<Long> {
        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = durationStr?.toLongOrNull() ?: 0L
        val durationUs = durationMs * 1000L
        return getCandidateVideoFrameTimestamps(durationUs)
    }

    fun extractBestVideoFrame(retriever: MediaMetadataRetriever, maxDim: Int = 512): Bitmap? {
        val timestamps = getCandidateVideoFrameTimestamps(retriever)
        val syncOptions = listOf(
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            MediaMetadataRetriever.OPTION_CLOSEST,
            MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
            MediaMetadataRetriever.OPTION_NEXT_SYNC
        )

        var bestBitmap: Bitmap? = null
        var bestDifference = -1.0

        for (ts in timestamps) {
            var frame: Bitmap? = null
            for (opt in syncOptions) {
                try {
                    frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(ts, opt, maxDim, maxDim)
                    } else {
                        retriever.getFrameAtTime(ts, opt)
                    }
                    if (frame != null) break
                } catch (_: Throwable) {}
            }

            if (frame != null) {
                val diff = calculateColorDifference(frame)
                if (!isSingleColorFrame(frame, diff)) {
                    if (bestBitmap != null && bestBitmap != frame) {
                        try { bestBitmap.recycle() } catch (_: Throwable) {}
                    }
                    return frame
                }

                if (diff > bestDifference || bestBitmap == null) {
                    if (bestBitmap != null && bestBitmap != frame) {
                        try { bestBitmap.recycle() } catch (_: Throwable) {}
                    }
                    bestBitmap = frame
                    bestDifference = diff
                } else {
                    if (frame != bestBitmap) {
                        try { frame.recycle() } catch (_: Throwable) {}
                    }
                }
            }
        }

        if (bestBitmap != null) {
            return bestBitmap
        }

        return try {
            retriever.frameAtTime
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun getVideoThumbnail(
        cacheDir: File,
        item: MediaItem,
        client: me.lesovoy.lenta.data.source.RemoteFileClient? = null
    ): File? = withContext(Dispatchers.IO) {
        if (!item.isRemote) {
            val file = File(item.path)
            return@withContext getVideoThumbnail(cacheDir, file)
        }

        val cacheKey = getVideoThumbnailCacheKey(item)
        val videoThumbDir = getVideoThumbnailDir(cacheDir)
        val cachedThumb = File(videoThumbDir, cacheKey)
        if (cachedThumb.exists() && cachedThumb.length() > 0L) {
            return@withContext cachedThumb
        }

        // Check if full file is already downloaded in nextcloud_cache or remote_cache
        val nextcloudCacheDir = File(cacheDir, "nextcloud_cache")
        val downloadedFile = File(nextcloudCacheDir, "${item.id.hashCode()}_${item.name}")
        if (downloadedFile.exists() && downloadedFile.length() > 0L) {
            val thumb = getVideoThumbnail(cacheDir, downloadedFile)
            if (thumb != null && thumb.exists()) {
                if (!cachedThumb.exists()) {
                    try { thumb.copyTo(cachedThumb, overwrite = true) } catch (_: Throwable) {}
                }
                return@withContext if (cachedThumb.exists()) cachedThumb else thumb
            }
        }

        val remoteCacheDir = File(cacheDir, "remote_cache/${item.sourceId ?: ""}")
        val remoteDownloadedFile = File(remoteCacheDir, "${item.id.hashCode()}_${item.name}")
        if (remoteDownloadedFile.exists() && remoteDownloadedFile.length() > 0L) {
            val thumb = getVideoThumbnail(cacheDir, remoteDownloadedFile)
            if (thumb != null && thumb.exists()) {
                if (!cachedThumb.exists()) {
                    try { thumb.copyTo(cachedThumb, overwrite = true) } catch (_: Throwable) {}
                }
                return@withContext if (cachedThumb.exists()) cachedThumb else thumb
            }
        }

        val maxDim = 512
        var bitmap: Bitmap? = null

        val headers = client?.getAuthHeaders() ?: emptyMap()

        fun extractFrameWithRetriever(setSource: (MediaMetadataRetriever) -> Unit): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                setSource(retriever)
                extractBestVideoFrame(retriever, maxDim)
            } catch (_: Throwable) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {
                }
            }
        }

        if (item.uriString.isNotEmpty()) {
            bitmap = extractFrameWithRetriever { r ->
                if (headers.isNotEmpty()) {
                    r.setDataSource(item.uriString, headers)
                } else {
                    r.setDataSource(item.uriString)
                }
            }
        }

        if (bitmap != null) {
            try {
                val finalBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val bw = bitmap.width
                    val bh = bitmap.height
                    val (scaledW, scaledH) = if (bw >= bh) {
                        maxDim to (bh * maxDim / bw).coerceAtLeast(1)
                    } else {
                        (bw * maxDim / bh).coerceAtLeast(1) to maxDim
                    }
                    val scaled = try {
                        Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
                    } catch (_: Throwable) {
                        bitmap
                    }
                    if (scaled != bitmap) {
                        try { bitmap.recycle() } catch (_: Throwable) {}
                    }
                    scaled
                } else {
                    bitmap
                }

                val tempFile = File(videoThumbDir, "${cacheKey}_tmp_${System.nanoTime()}.jpg")
                try {
                    FileOutputStream(tempFile).use { out ->
                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    if (cachedThumb.exists() && cachedThumb.length() > 0L) {
                        tempFile.delete()
                        return@withContext cachedThumb
                    }
                    if (tempFile.exists() && tempFile.length() > 0L) {
                        if (!tempFile.renameTo(cachedThumb)) {
                            tempFile.copyTo(cachedThumb, overwrite = true)
                            tempFile.delete()
                        }
                    }
                    if (cachedThumb.exists() && cachedThumb.length() > 0L) {
                        return@withContext cachedThumb
                    }
                    if (tempFile.exists() && tempFile.length() > 0L) {
                        return@withContext tempFile
                    }
                } finally {
                    try {
                        if (!finalBitmap.isRecycled) {
                            finalBitmap.recycle()
                        }
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }

        null
    }

    suspend fun getVideoThumbnail(cacheDir: File, videoFile: File): File? = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() == 0L) return@withContext null

        val cacheKey = "vid_${videoFile.name.hashCode()}_${videoFile.length()}_${videoFile.lastModified()}.jpg"
        val videoThumbDir = getVideoThumbnailDir(cacheDir)

        val cachedThumb = File(videoThumbDir, cacheKey)
        if (cachedThumb.exists() && cachedThumb.length() > 0L) {
            return@withContext cachedThumb
        }

        val maxDim = 512
        var bitmap: Bitmap? = null

        // Extract frame using MediaMetadataRetriever
        fun extractFrameWithRetriever(setSource: (MediaMetadataRetriever) -> Unit): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                setSource(retriever)
                extractBestVideoFrame(retriever, maxDim)
            } catch (_: Throwable) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {
                }
            }
        }

        // 1. Try file path first
        bitmap = extractFrameWithRetriever { r ->
            r.setDataSource(videoFile.absolutePath)
        }

        // 2. Fallback to FileInputStream with file descriptor
        if (bitmap == null) {
            bitmap = try {
                FileInputStream(videoFile).use { fis ->
                    extractFrameWithRetriever { r ->
                        r.setDataSource(fis.fd, 0L, videoFile.length())
                    }
                }
            } catch (_: Throwable) {
                null
            }
        }

        // 3. Fallback to ThumbnailUtils on API 29+
        if (bitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                bitmap = android.media.ThumbnailUtils.createVideoThumbnail(
                    videoFile,
                    android.util.Size(maxDim, maxDim),
                    null
                )
            } catch (_: Throwable) {
            }
        }

        if (bitmap != null) {
            try {
                val finalBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val bw = bitmap.width
                    val bh = bitmap.height
                    val (scaledW, scaledH) = if (bw >= bh) {
                        maxDim to (bh * maxDim / bw).coerceAtLeast(1)
                    } else {
                        (bw * maxDim / bh).coerceAtLeast(1) to maxDim
                    }
                    val scaled = try {
                        Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
                    } catch (_: Throwable) {
                        bitmap
                    }
                    if (scaled != bitmap) {
                        try { bitmap.recycle() } catch (_: Throwable) {}
                    }
                    scaled
                } else {
                    bitmap
                }

                val tempFile = File(videoThumbDir, "${cacheKey}_tmp_${System.nanoTime()}.jpg")
                try {
                    FileOutputStream(tempFile).use { out ->
                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    if (cachedThumb.exists() && cachedThumb.length() > 0L) {
                        tempFile.delete()
                        return@withContext cachedThumb
                    }
                    if (tempFile.exists() && tempFile.length() > 0L) {
                        if (!tempFile.renameTo(cachedThumb)) {
                            tempFile.copyTo(cachedThumb, overwrite = true)
                            tempFile.delete()
                        }
                    }
                    if (cachedThumb.exists() && cachedThumb.length() > 0L) {
                        return@withContext cachedThumb
                    }
                    if (tempFile.exists() && tempFile.length() > 0L) {
                        return@withContext tempFile
                    }
                } finally {
                    try {
                        if (!finalBitmap.isRecycled) {
                            finalBitmap.recycle()
                        }
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }

        null
    }

    suspend fun getVideoThumbnailFromUri(
        context: Context,
        uri: Uri,
        customCacheKey: String? = null
    ): File? = withContext(Dispatchers.IO) {
        val cacheKey = customCacheKey ?: "vid_uri_${uri.toString().hashCode()}.jpg"
        val videoThumbDir = getVideoThumbnailDir(context.cacheDir)
        val cachedThumb = File(videoThumbDir, cacheKey)
        if (cachedThumb.exists() && cachedThumb.length() > 0L) {
            return@withContext cachedThumb
        }

        val maxDim = 512
        var bitmap: Bitmap? = null

        fun extractFrameWithRetriever(setSource: (MediaMetadataRetriever) -> Unit): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                setSource(retriever)
                extractBestVideoFrame(retriever, maxDim)
            } catch (_: Throwable) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {
                }
            }
        }

        bitmap = extractFrameWithRetriever { r ->
            r.setDataSource(context, uri)
        }

        if (bitmap == null) {
            bitmap = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    extractFrameWithRetriever { r ->
                        r.setDataSource(pfd.fileDescriptor)
                    }
                }
            } catch (_: Throwable) {
                null
            }
        }

        if (bitmap != null) {
            try {
                val finalBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val bw = bitmap.width
                    val bh = bitmap.height
                    val (scaledW, scaledH) = if (bw >= bh) {
                        maxDim to (bh * maxDim / bw).coerceAtLeast(1)
                    } else {
                        (bw * maxDim / bh).coerceAtLeast(1) to maxDim
                    }
                    val scaled = try {
                        Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
                    } catch (_: Throwable) {
                        bitmap
                    }
                    if (scaled != bitmap) {
                        try { bitmap.recycle() } catch (_: Throwable) {}
                    }
                    scaled
                } else {
                    bitmap
                }

                val tempFile = File(videoThumbDir, "${cacheKey}_tmp_${System.nanoTime()}.jpg")
                try {
                    FileOutputStream(tempFile).use { out ->
                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    if (cachedThumb.exists() && cachedThumb.length() > 0L) {
                        tempFile.delete()
                        return@withContext cachedThumb
                    }
                    if (tempFile.exists() && tempFile.length() > 0L) {
                        if (!tempFile.renameTo(cachedThumb)) {
                            tempFile.copyTo(cachedThumb, overwrite = true)
                            tempFile.delete()
                        }
                    }
                    if (cachedThumb.exists() && cachedThumb.length() > 0L) {
                        return@withContext cachedThumb
                    }
                    if (tempFile.exists() && tempFile.length() > 0L) {
                        return@withContext tempFile
                    }
                } finally {
                    try {
                        if (!finalBitmap.isRecycled) {
                            finalBitmap.recycle()
                        }
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }

        null
    }

    suspend fun generateThumbnail(
        context: Context,
        item: MediaItem,
        client: me.lesovoy.lenta.data.source.RemoteFileClient? = null
    ): File? = withContext(Dispatchers.IO) {
        if (item.isDirectory) return@withContext null
        val resolvedClient = client ?: if (item.sourceId != null) {
            me.lesovoy.lenta.data.source.SourceClientFactory.getClientForSourceId(context, item.sourceId)
        } else if (item.isRemote) {
            val prefs = NextcloudPreferences(context)
            NextcloudClient(prefs)
        } else {
            null
        }

        val imageLoader = try {
            Coil.imageLoader(context)
        } catch (_: Throwable) {
            null
        }

        when (item.type) {
            MediaType.VIDEO -> {
                val thumb = getVideoThumbnail(context, item, resolvedClient)
                if (thumb != null && thumb.exists() && imageLoader != null) {
                    try {
                        val request = ImageRequest.Builder(context)
                            .data(thumb)
                            .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                            .size(512, 512)
                            .build()
                        imageLoader.execute(request)
                    } catch (_: Throwable) {}
                }
                thumb
            }
            MediaType.CBZ -> {
                val cover = getCbzCoverThumbnail(context.cacheDir, item, resolvedClient)
                if (cover != null && cover.exists() && imageLoader != null) {
                    try {
                        val request = ImageRequest.Builder(context)
                            .data(cover)
                            .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                            .size(512, 512)
                            .build()
                        imageLoader.execute(request)
                    } catch (_: Throwable) {}
                }
                cover
            }
            MediaType.DOCUMENT, MediaType.EBOOK, MediaType.PRESENTATION -> {
                val cover = getDocumentCoverThumbnail(context.cacheDir, item, resolvedClient)
                if (cover != null && cover.exists() && imageLoader != null) {
                    try {
                        val request = ImageRequest.Builder(context)
                            .data(cover)
                            .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                            .size(512, 512)
                            .build()
                        imageLoader.execute(request)
                    } catch (_: Throwable) {}
                }
                cover
            }
            MediaType.AUDIO -> {
                val artFile = if (item.isRemote) {
                    val nextcloudCacheDir = File(context.cacheDir, "nextcloud_cache")
                    val downloadedFile = File(nextcloudCacheDir, "${item.id.hashCode()}_${item.name}")
                    val remoteCacheDir = File(context.cacheDir, "remote_cache/${item.sourceId ?: ""}")
                    val remoteDownloadedFile = File(remoteCacheDir, "${item.id.hashCode()}_${item.name}")
                    val targetFile = if (downloadedFile.exists() && downloadedFile.length() > 0L) {
                        downloadedFile
                    } else if (remoteDownloadedFile.exists() && remoteDownloadedFile.length() > 0L) {
                        remoteDownloadedFile
                    } else null

                    if (targetFile != null) {
                        AudioMetadataHelper.extractMetadata(context, targetFile).artworkFile
                    } else {
                        val audioThumbDir = File(context.cacheDir, "thumbnail_cache/audio")
                        audioThumbDir.listFiles { _, name -> name.contains(item.name.hashCode().toString()) }?.firstOrNull()
                    }
                } else if (item.uriString.startsWith("content://")) {
                    AudioMetadataHelper.extractMetadataFromUri(context, Uri.parse(item.uriString), item.name).artworkFile
                } else {
                    val file = File(item.path)
                    if (file.exists() && file.length() > 0L) {
                        AudioMetadataHelper.extractMetadata(context, file).artworkFile
                    } else {
                        null
                    }
                }
                if (artFile != null && artFile.exists() && imageLoader != null) {
                    try {
                        val request = ImageRequest.Builder(context)
                            .data(artFile)
                            .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                            .size(512, 512)
                            .build()
                        imageLoader.execute(request)
                    } catch (_: Throwable) {}
                }
                artFile
            }
            MediaType.IMAGE, MediaType.GIF -> {
                if (item.isRemote) {
                    if (imageLoader != null) {
                        val previewUrl = resolvedClient?.getPreviewUrl(item) ?: item.uriString
                        val request = ImageRequest.Builder(context)
                            .data(previewUrl)
                            .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                            .size(512, 512)
                            .build()
                        imageLoader.execute(request)
                    }
                    null
                } else {
                    val file = File(item.path)
                    if (file.exists() && file.length() > 0L) file else null
                }
            }
        }
    }

    suspend fun generateThumbnail(
        cacheDir: File,
        item: MediaItem,
        client: me.lesovoy.lenta.data.source.RemoteFileClient? = null
    ): File? = withContext(Dispatchers.IO) {
        if (item.isDirectory) return@withContext null
        when (item.type) {
            MediaType.VIDEO -> {
                if (item.isRemote) {
                    getVideoThumbnail(cacheDir, item, client)
                } else {
                    val file = File(item.path)
                    if (file.exists() && file.length() > 0L) {
                        getVideoThumbnail(cacheDir, file)
                    } else null
                }
            }
            MediaType.CBZ -> {
                getCbzCoverThumbnail(cacheDir, item, client)
            }
            MediaType.DOCUMENT, MediaType.EBOOK, MediaType.PRESENTATION -> {
                getDocumentCoverThumbnail(cacheDir, item, client)
            }
            MediaType.AUDIO -> {
                if (item.isRemote) {
                    val nextcloudCacheDir = File(cacheDir, "nextcloud_cache")
                    val downloadedFile = File(nextcloudCacheDir, "${item.id.hashCode()}_${item.name}")
                    val remoteCacheDir = File(cacheDir, "remote_cache/${item.sourceId ?: ""}")
                    val remoteDownloadedFile = File(remoteCacheDir, "${item.id.hashCode()}_${item.name}")
                    val targetFile = if (downloadedFile.exists() && downloadedFile.length() > 0L) {
                        downloadedFile
                    } else if (remoteDownloadedFile.exists() && remoteDownloadedFile.length() > 0L) {
                        remoteDownloadedFile
                    } else null

                    if (targetFile != null) {
                        AudioMetadataHelper.extractMetadata(cacheDir, targetFile).artworkFile
                    } else {
                        val audioThumbDir = File(cacheDir, "thumbnail_cache/audio")
                        audioThumbDir.listFiles { _, name -> name.contains(item.name.hashCode().toString()) }?.firstOrNull()
                    }
                } else {
                    val file = File(item.path)
                    if (file.exists() && file.length() > 0L) {
                        AudioMetadataHelper.extractMetadata(cacheDir, file).artworkFile
                    } else null
                }
            }
            MediaType.IMAGE, MediaType.GIF -> {
                val file = File(item.path)
                if (file.exists() && file.length() > 0L) file else null
            }
        }
    }

    /**
     * Pre-generates missing local thumbnails (videos and CBZ covers) on disk.
     */
    suspend fun pregenerateLocalThumbnails(
        cacheDir: File,
        items: List<MediaItem>,
        onThumbnailGenerated: ((item: MediaItem, file: File?) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        var processedCount = 0
        val mediaItems = items.filter { !it.isDirectory && !it.isNextcloud }
        for (item in mediaItems) {
            if (!coroutineContext.isActive) break
            val file = File(item.path)
            if (file.exists() && file.length() > 0L) {
                when (item.type) {
                    MediaType.VIDEO -> {
                        val thumb = getVideoThumbnail(cacheDir, file)
                        if (thumb != null && thumb.exists()) {
                            processedCount++
                            onThumbnailGenerated?.invoke(item, thumb)
                        }
                    }
                    MediaType.CBZ -> {
                        val cover = getCbzCoverThumbnail(cacheDir, file)
                        if (cover != null && cover.exists()) {
                            processedCount++
                            onThumbnailGenerated?.invoke(item, cover)
                        }
                    }
                    MediaType.DOCUMENT, MediaType.EBOOK, MediaType.PRESENTATION -> {
                        val cover = DocumentPageReader.getDocumentCover(cacheDir, file)
                        if (cover != null && cover.exists()) {
                            processedCount++
                            onThumbnailGenerated?.invoke(item, cover)
                        }
                    }
                    MediaType.AUDIO -> {
                        val meta = AudioMetadataHelper.extractMetadata(cacheDir.parentFile ?: cacheDir, file)
                        if (meta.artworkFile != null && meta.artworkFile.exists()) {
                            processedCount++
                            onThumbnailGenerated?.invoke(item, meta.artworkFile)
                        }
                    }
                    MediaType.IMAGE, MediaType.GIF -> {
                        processedCount++
                        onThumbnailGenerated?.invoke(item, file)
                    }
                }
            }
        }
        processedCount
    }

    /**
     * Automatically pre-generates and caches missing thumbnails for all media items in a folder.
     */
    suspend fun pregenerateThumbnails(
        context: Context,
        items: List<MediaItem>,
        onThumbnailGenerated: ((item: MediaItem) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val mediaItems = items.filter { !it.isDirectory }
        if (mediaItems.isEmpty()) return@withContext

        val imageLoader = try {
            Coil.imageLoader(context)
        } catch (_: Throwable) {
            null
        }

        val prefs = NextcloudPreferences(context)
        val nextcloudClient = NextcloudClient(prefs)

        for (item in mediaItems) {
            if (!coroutineContext.isActive) break

            try {
                if (item.isRemote) {
                    val client = if (item.sourceId != null) {
                        me.lesovoy.lenta.data.source.SourceClientFactory.getClientForSourceId(context, item.sourceId) ?: nextcloudClient
                    } else {
                        nextcloudClient
                    }

                    when (item.type) {
                        MediaType.VIDEO -> {
                            val thumb = getVideoThumbnail(context.cacheDir, item, client)
                            if (thumb != null && thumb.exists()) {
                                if (imageLoader != null) {
                                    val request = ImageRequest.Builder(context)
                                        .data(thumb)
                                        .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                                        .size(512, 512)
                                        .build()
                                    imageLoader.execute(request)
                                }
                                withContext(Dispatchers.Main) {
                                    onThumbnailGenerated?.invoke(item)
                                }
                            } else {
                                if (imageLoader != null) {
                                    val request = ImageRequest.Builder(context)
                                        .data(item.uriString)
                                        .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                                        .size(512, 512)
                                        .build()
                                    val result = imageLoader.execute(request)
                                    if (result is coil.request.SuccessResult) {
                                        withContext(Dispatchers.Main) {
                                            onThumbnailGenerated?.invoke(item)
                                        }
                                    }
                                }
                            }
                        }
                        MediaType.CBZ -> {
                            val cover = getCbzCoverThumbnail(context.cacheDir, item, client)
                            if (cover != null && cover.exists()) {
                                if (imageLoader != null) {
                                    val request = ImageRequest.Builder(context)
                                        .data(cover)
                                        .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                                        .size(512, 512)
                                        .build()
                                    imageLoader.execute(request)
                                }
                                withContext(Dispatchers.Main) {
                                    onThumbnailGenerated?.invoke(item)
                                }
                            }
                        }
                        MediaType.DOCUMENT, MediaType.EBOOK, MediaType.PRESENTATION -> {
                            val cover = getDocumentCoverThumbnail(context.cacheDir, item, client)
                            if (cover != null && cover.exists()) {
                                if (imageLoader != null) {
                                    val request = ImageRequest.Builder(context)
                                        .data(cover)
                                        .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                                        .size(512, 512)
                                        .build()
                                    imageLoader.execute(request)
                                }
                                withContext(Dispatchers.Main) {
                                    onThumbnailGenerated?.invoke(item)
                                }
                            }
                        }
                        MediaType.AUDIO -> {
                            val audioThumbDir = File(context.cacheDir, "thumbnail_cache/audio")
                            val cachedArt = audioThumbDir.listFiles { _, name -> name.contains(item.name.hashCode().toString()) }?.firstOrNull()
                            if (cachedArt != null && cachedArt.exists()) {
                                if (imageLoader != null) {
                                    val request = ImageRequest.Builder(context)
                                        .data(cachedArt)
                                        .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                                        .size(512, 512)
                                        .build()
                                    imageLoader.execute(request)
                                }
                                withContext(Dispatchers.Main) {
                                    onThumbnailGenerated?.invoke(item)
                                }
                            }
                        }
                        MediaType.IMAGE, MediaType.GIF -> {
                            if (imageLoader != null) {
                                val previewUrl = client.getPreviewUrl(item) ?: item.uriString
                                val request = ImageRequest.Builder(context)
                                    .data(previewUrl)
                                    .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                                    .size(512, 512)
                                    .build()
                                val result = imageLoader.execute(request)
                                if (result is coil.request.SuccessResult) {
                                    withContext(Dispatchers.Main) {
                                        onThumbnailGenerated?.invoke(item)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val file = File(item.path)
                    if (file.exists() && file.length() > 0L) {
                        when (item.type) {
                            MediaType.VIDEO -> {
                                val thumb = getVideoThumbnail(context.cacheDir, file)
                                if (thumb != null && thumb.exists()) {
                                    withContext(Dispatchers.Main) {
                                        onThumbnailGenerated?.invoke(item)
                                    }
                                }
                            }
                            MediaType.CBZ -> {
                                val cover = getCbzCoverThumbnail(context.cacheDir, file)
                                if (cover != null && cover.exists()) {
                                    withContext(Dispatchers.Main) {
                                        onThumbnailGenerated?.invoke(item)
                                    }
                                }
                            }
                            MediaType.DOCUMENT, MediaType.EBOOK, MediaType.PRESENTATION -> {
                                val cover = DocumentPageReader.getDocumentCover(context.cacheDir, file)
                                if (cover != null && cover.exists()) {
                                    withContext(Dispatchers.Main) {
                                        onThumbnailGenerated?.invoke(item)
                                    }
                                }
                            }
                            MediaType.AUDIO -> {
                                val meta = AudioMetadataHelper.extractMetadata(context, file)
                                if (meta.artworkFile != null && meta.artworkFile.exists()) {
                                    withContext(Dispatchers.Main) {
                                        onThumbnailGenerated?.invoke(item)
                                    }
                                }
                            }
                            MediaType.IMAGE, MediaType.GIF -> {
                                withContext(Dispatchers.Main) {
                                    onThumbnailGenerated?.invoke(item)
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Calculates the total size of all cached thumbnails and rendered page caches in bytes.
     */
    suspend fun getThumbnailCacheSizeBytes(context: Context): Long = getThumbnailCacheSizeBytes(context.cacheDir)

    suspend fun getThumbnailCacheSizeBytes(cacheDir: File): Long = withContext(Dispatchers.IO) {
        var totalSize = 0L
        val thumbDir = File(cacheDir, THUMBNAIL_DIR)
        if (thumbDir.exists()) {
            totalSize += calculateDirSize(thumbDir)
        }
        val cbzCacheDir = File(cacheDir, "cbz_cache")
        if (cbzCacheDir.exists()) {
            totalSize += calculateDirSize(cbzCacheDir)
        }
        val docCacheDir = File(cacheDir, "doc_cache")
        if (docCacheDir.exists()) {
            totalSize += calculateDirSize(docCacheDir)
        }
        val uriDocsCacheDir = File(cacheDir, "uri_docs_cache")
        if (uriDocsCacheDir.exists()) {
            totalSize += calculateDirSize(uriDocsCacheDir)
        }
        totalSize
    }

    /**
     * Calculates the total size of cached downloaded online/remote files in bytes.
     */
    suspend fun getOnlineFilesCacheSizeBytes(context: Context): Long = getOnlineFilesCacheSizeBytes(context.cacheDir)

    suspend fun getOnlineFilesCacheSizeBytes(cacheDir: File): Long = withContext(Dispatchers.IO) {
        var totalSize = 0L
        val nextcloudCacheDir = File(cacheDir, "nextcloud_cache")
        if (nextcloudCacheDir.exists()) {
            totalSize += calculateDirSize(nextcloudCacheDir)
        }
        val remoteCacheDir = File(cacheDir, "remote_cache")
        if (remoteCacheDir.exists()) {
            totalSize += calculateDirSize(remoteCacheDir)
        }
        totalSize
    }

    /**
     * Calculates the total size of all caches in bytes.
     */
    suspend fun getCacheSizeBytes(context: Context): Long = getCacheSizeBytes(context.cacheDir)

    suspend fun getCacheSizeBytes(cacheDir: File): Long = withContext(Dispatchers.IO) {
        getThumbnailCacheSizeBytes(cacheDir) + getOnlineFilesCacheSizeBytes(cacheDir)
    }

    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) {
                calculateDirSize(file)
            } else {
                file.length()
            }
        }
        return size
    }

    /**
     * Clears all thumbnail caches and rendered page caches from memory and disk.
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun clearThumbnailCache(context: Context) = withContext(Dispatchers.IO) {
        try {
            Coil.imageLoader(context).memoryCache?.clear()
            Coil.imageLoader(context).diskCache?.clear()
        } catch (_: Throwable) {
        }
        clearThumbnailCache(context.cacheDir)
    }

    suspend fun clearThumbnailCache(cacheDir: File) = withContext(Dispatchers.IO) {
        val thumbDir = File(cacheDir, THUMBNAIL_DIR)
        if (thumbDir.exists()) {
            thumbDir.deleteRecursively()
        }
        val cbzCacheDir = File(cacheDir, "cbz_cache")
        if (cbzCacheDir.exists()) {
            cbzCacheDir.deleteRecursively()
        }
        val docCacheDir = File(cacheDir, "doc_cache")
        if (docCacheDir.exists()) {
            docCacheDir.deleteRecursively()
        }
        val uriDocsCacheDir = File(cacheDir, "uri_docs_cache")
        if (uriDocsCacheDir.exists()) {
            uriDocsCacheDir.deleteRecursively()
        }
    }

    /**
     * Clears all downloaded online/remote file caches from disk.
     */
    suspend fun clearOnlineFilesCache(context: Context) = withContext(Dispatchers.IO) {
        clearOnlineFilesCache(context.cacheDir)
    }

    suspend fun clearOnlineFilesCache(cacheDir: File) = withContext(Dispatchers.IO) {
        val nextcloudCacheDir = File(cacheDir, "nextcloud_cache")
        if (nextcloudCacheDir.exists()) {
            nextcloudCacheDir.deleteRecursively()
        }
        val remoteCacheDir = File(cacheDir, "remote_cache")
        if (remoteCacheDir.exists()) {
            remoteCacheDir.deleteRecursively()
        }
    }

    /**
     * Clears all caches (thumbnails and online files) from memory and disk.
     */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        clearThumbnailCache(context)
        clearOnlineFilesCache(context)
    }

    suspend fun clearCache(cacheDir: File) = withContext(Dispatchers.IO) {
        clearThumbnailCache(cacheDir)
        clearOnlineFilesCache(cacheDir)
    }

    fun formatCacheSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }
}
