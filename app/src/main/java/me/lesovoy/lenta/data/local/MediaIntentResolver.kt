package me.lesovoy.lenta.data.local

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.OpenableColumns
import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.model.MediaType
import me.lesovoy.lenta.ui.viewer.MediaViewerActivity
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale

object MediaIntentResolver {

    /**
     * Resolves the MediaType from display name, URI path, and MIME type.
     */
    fun resolveMediaType(displayName: String?, uriPath: String?, mimeType: String?): MediaType {
        // 1. Try extension from display name
        val nameExt = displayName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: ""
        val typeFromName = LocalMediaRepository.getMediaTypeFromExtension(nameExt)
        if (typeFromName != null) return typeFromName

        // 2. Try extension from URI path
        val pathExt = uriPath?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: ""
        val typeFromPath = LocalMediaRepository.getMediaTypeFromExtension(pathExt)
        if (typeFromPath != null) return typeFromPath

        // 3. Try MIME type
        if (!mimeType.isNullOrEmpty() && mimeType != "*/*" && mimeType != "application/octet-stream") {
            val lowerMime = mimeType.lowercase(Locale.ROOT)
            when {
                lowerMime == "image/gif" -> return MediaType.GIF
                lowerMime.startsWith("audio/") -> return MediaType.AUDIO
                lowerMime.startsWith("video/") -> return MediaType.VIDEO
                lowerMime.startsWith("image/") -> return MediaType.IMAGE
                lowerMime.contains("presentation") || lowerMime.contains("powerpoint") || lowerMime.contains("slides") || lowerMime.contains("keynote") -> return MediaType.PRESENTATION
                lowerMime.contains("epub") || lowerMime.contains("fb2") || lowerMime.contains("fictionbook") ||
                        lowerMime.contains("mobipocket") || lowerMime.contains("amazon.mobi") || lowerMime.contains("djvu") || lowerMime.contains("chm") -> return MediaType.EBOOK
                lowerMime.startsWith("text/") && (lowerMime.contains("plain") || lowerMime.contains("markdown")) -> return MediaType.EBOOK
                lowerMime in setOf(
                    "application/x-cbz",
                    "application/vnd.comicbook+zip",
                    "application/x-cbr",
                    "application/vnd.comicbook-rar",
                    "application/x-cb7",
                    "application/x-cbt",
                    "application/x-rar-compressed",
                    "application/vnd.rar",
                    "application/x-7z-compressed",
                    "application/x-tar",
                    "application/gzip",
                    "application/x-gzip"
                ) -> return MediaType.CBZ
                lowerMime.contains("word") || lowerMime.contains("document") || lowerMime.contains("pdf") ||
                        lowerMime.contains("spreadsheet") || lowerMime.contains("excel") || lowerMime.contains("rtf") ||
                        lowerMime.contains("opendocument") || lowerMime.contains("xps") || lowerMime.contains("pages") ||
                        lowerMime.contains("csv") || lowerMime.contains("tab-separated") -> return MediaType.DOCUMENT
                lowerMime in setOf(
                    "application/zip",
                    "application/x-zip-compressed"
                ) -> return MediaType.CBZ
                lowerMime in setOf(
                    "application/ogg",
                    "application/x-ogg",
                    "application/opus",
                    "application/x-flac"
                ) -> return MediaType.AUDIO
            }
        }

        // Default fallback: IMAGE
        return MediaType.IMAGE
    }

    /**
     * Resolves MediaItems and starting position from an incoming Intent.
     */
    fun resolveMediaFromIntent(context: Context, intent: Intent): Pair<List<MediaItem>, Int> {
        // 1. Check if intent already has EXTRA_MEDIA_ITEMS
        if (intent.hasExtra(MediaViewerActivity.EXTRA_MEDIA_ITEMS)) {
            @Suppress("UNCHECKED_CAST", "DEPRECATION")
            val items: List<MediaItem> = if (Build.VERSION.SDK_INT >= 33) {
                intent.getSerializableExtra(
                    MediaViewerActivity.EXTRA_MEDIA_ITEMS,
                    ArrayList::class.java
                ) as? ArrayList<MediaItem> ?: emptyList()
            } else {
                intent.getSerializableExtra(
                    MediaViewerActivity.EXTRA_MEDIA_ITEMS
                ) as? ArrayList<MediaItem> ?: emptyList()
            }

            val startPos = intent.getIntExtra(MediaViewerActivity.EXTRA_START_POSITION, 0)
                .coerceIn(0, (items.size - 1).coerceAtLeast(0))
            if (items.isNotEmpty()) {
                return Pair(items, startPos)
            }
        }

        // 2. Check if intent has data URI (ACTION_VIEW, Open With, etc.)
        val uri = intent.data ?: return Pair(emptyList(), 0)

        var displayName: String? = null
        var fileSize = 0L
        var resolvedMimeType: String? = intent.type

        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx != -1) {
                            displayName = cursor.getString(nameIdx)
                        }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx != -1) {
                            fileSize = cursor.getLong(sizeIdx)
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            if (resolvedMimeType.isNullOrEmpty() || resolvedMimeType == "*/*" || resolvedMimeType == "application/octet-stream") {
                try {
                    resolvedMimeType = context.contentResolver.getType(uri)
                } catch (_: Throwable) {
                }
            }
        }

        if (displayName.isNullOrEmpty()) {
            displayName = uri.lastPathSegment?.substringAfterLast('/')
                ?: (if (uri.scheme == "file") File(uri.path ?: "").name else "media_file")
        }

        val mediaType = resolveMediaType(displayName, uri.path, resolvedMimeType)

        // 3. Check if URI is a local file on disk to load directory siblings
        val directPath = when {
            uri.scheme == "file" -> uri.path
            uri.path?.startsWith("/storage") == true -> uri.path
            uri.path?.startsWith("/sdcard") == true -> uri.path
            else -> null
        }

        if (!directPath.isNullOrEmpty()) {
            val localFile = File(directPath)
            if (localFile.exists() && localFile.isFile) {
                val parent = localFile.parentFile
                if (parent != null && parent.exists() && parent.canRead()) {
                    try {
                        val repository = LocalMediaRepository(context)
                        val siblings = runBlocking {
                            repository.getMediaItemsInDirectory(parent, showHidden = false)
                        }
                        if (siblings.isNotEmpty()) {
                            val foundIndex = siblings.indexOfFirst { it.path == localFile.absolutePath }
                            if (foundIndex != -1) {
                                return Pair(siblings, foundIndex)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        val singleItem = MediaItem(
            id = uri.toString(),
            name = displayName ?: "media_file",
            path = if (uri.scheme == "file") (uri.path ?: "") else (directPath ?: ""),
            uriString = uri.toString(),
            type = mediaType,
            size = fileSize,
            dateModified = System.currentTimeMillis(),
            isDirectory = false,
            mimeType = resolvedMimeType
        )

        return Pair(listOf(singleItem), 0)
    }
}
