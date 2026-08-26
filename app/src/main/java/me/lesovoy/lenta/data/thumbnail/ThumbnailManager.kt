package me.lesovoy.lenta.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
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
    ): File? = getVideoThumbnail(context.cacheDir, item, client)

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
                val timestamps = listOf(1_000_000L, 0L, -1L, 500_000L, 2_000_000L)
                val syncOptions = listOf(
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
                    MediaMetadataRetriever.OPTION_NEXT_SYNC
                )

                var found: Bitmap? = null
                for (opt in syncOptions) {
                    for (ts in timestamps) {
                        try {
                            found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                retriever.getScaledFrameAtTime(ts, opt, maxDim, maxDim)
                            } else {
                                retriever.getFrameAtTime(ts, opt)
                            }
                            if (found != null) return found
                        } catch (_: Throwable) {
                        }
                    }
                }
                try {
                    found = retriever.frameAtTime
                } catch (_: Throwable) {
                }
                found
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
                val timestamps = listOf(1_000_000L, 0L, -1L, 500_000L, 2_000_000L)
                val syncOptions = listOf(
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
                    MediaMetadataRetriever.OPTION_NEXT_SYNC
                )

                var found: Bitmap? = null
                for (opt in syncOptions) {
                    for (ts in timestamps) {
                        try {
                            found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                retriever.getScaledFrameAtTime(ts, opt, maxDim, maxDim)
                            } else {
                                retriever.getFrameAtTime(ts, opt)
                            }
                            if (found != null) return found
                        } catch (_: Throwable) {
                        }
                    }
                }
                try {
                    found = retriever.frameAtTime
                } catch (_: Throwable) {
                }
                found
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
                                    if (imageLoader != null) {
                                        val request = ImageRequest.Builder(context)
                                            .data(thumb)
                                            .size(512, 512)
                                            .build()
                                        imageLoader.execute(request)
                                    }
                                    withContext(Dispatchers.Main) {
                                        onThumbnailGenerated?.invoke(item)
                                    }
                                }
                            }
                            MediaType.CBZ -> {
                                val cover = getCbzCoverThumbnail(context.cacheDir, file)
                                if (cover != null && cover.exists()) {
                                    if (imageLoader != null) {
                                        val request = ImageRequest.Builder(context)
                                            .data(cover)
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
                                val cover = DocumentPageReader.getDocumentCover(context.cacheDir, file)
                                if (cover != null && cover.exists()) {
                                    if (imageLoader != null) {
                                        val request = ImageRequest.Builder(context)
                                            .data(cover)
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
                                val meta = AudioMetadataHelper.extractMetadata(context, file)
                                if (meta.artworkFile != null && meta.artworkFile.exists()) {
                                    if (imageLoader != null) {
                                        val request = ImageRequest.Builder(context)
                                            .data(meta.artworkFile)
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
                                    val request = ImageRequest.Builder(context)
                                        .data(file)
                                        .size(512, 512)
                                        .build()
                                    imageLoader.execute(request)
                                }
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
     * Calculates the total size of all cached thumbnails in bytes.
     */
    suspend fun getCacheSizeBytes(context: Context): Long = getCacheSizeBytes(context.cacheDir)

    suspend fun getCacheSizeBytes(cacheDir: File): Long = withContext(Dispatchers.IO) {
        var totalSize = 0L
        val thumbDir = File(cacheDir, THUMBNAIL_DIR)
        if (thumbDir.exists()) {
            totalSize += calculateDirSize(thumbDir)
        }
        val cbzCacheDir = File(cacheDir, "cbz_cache")
        if (cbzCacheDir.exists()) {
            totalSize += calculateDirSize(cbzCacheDir)
        }
        val nextcloudCacheDir = File(cacheDir, "nextcloud_cache")
        if (nextcloudCacheDir.exists()) {
            totalSize += calculateDirSize(nextcloudCacheDir)
        }
        totalSize
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
     * Clears all thumbnail caches from memory and disk.
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        try {
            Coil.imageLoader(context).memoryCache?.clear()
            Coil.imageLoader(context).diskCache?.clear()
        } catch (_: Throwable) {
        }

        val thumbDir = File(context.cacheDir, THUMBNAIL_DIR)
        if (thumbDir.exists()) {
            thumbDir.deleteRecursively()
        }
        val cbzCacheDir = File(context.cacheDir, "cbz_cache")
        if (cbzCacheDir.exists()) {
            cbzCacheDir.deleteRecursively()
        }
        val nextcloudCacheDir = File(context.cacheDir, "nextcloud_cache")
        if (nextcloudCacheDir.exists()) {
            nextcloudCacheDir.deleteRecursively()
        }
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
