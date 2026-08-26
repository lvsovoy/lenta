package me.lesovoy.lenta.data.local

import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class LocalMediaRepository(private val context: Context? = null) {

    companion object {
        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "jpe", "jif", "jfif", "jfi",
            "png", "apng", "webp", "bmp", "dib",
            "heic", "heif", "avif", "avis", "jxl", "tif", "tiff", "svg"
        )
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "ts", "m4v", "flv", "wmv", "ogv", "vob")
        val GIF_EXTENSIONS = setOf("gif")
        val COMIC_EXTENSIONS = setOf("cbz", "zip", "cbr", "cb7", "cbt", "cba", "rar", "7z", "tar", "gz")
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "wav", "wave", "ogg", "oga", "flac", "m4a", "aac", "opus", "wma",
            "aiff", "aif", "mid", "midi", "amr", "alac", "ape", "ac3", "dts", "mka", "ra", "ram"
        )
        val DOCUMENT_EXTENSIONS = setOf(
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

        fun getMediaType(file: File): MediaType? {
            return getMediaTypeFromExtension(file.extension)
        }

        fun getMediaTypeFromExtension(ext: String): MediaType? {
            val lower = ext.lowercase(Locale.ROOT)
            return when {
                lower in GIF_EXTENSIONS -> MediaType.GIF
                lower in VIDEO_EXTENSIONS -> MediaType.VIDEO
                lower in COMIC_EXTENSIONS -> MediaType.CBZ
                lower in IMAGE_EXTENSIONS -> MediaType.IMAGE
                lower in AUDIO_EXTENSIONS -> MediaType.AUDIO
                lower in PRESENTATION_EXTENSIONS -> MediaType.PRESENTATION
                lower in EBOOK_EXTENSIONS -> MediaType.EBOOK
                lower in DOCUMENT_EXTENSIONS -> MediaType.DOCUMENT
                else -> null
            }
        }

        fun getFirstMediaFileInDirectory(directory: File, showHidden: Boolean = false): File? {
            val files = directory.listFiles() ?: return null
            for (file in files) {
                if (!showHidden && file.name.startsWith(".")) continue
                if (!file.isDirectory && getMediaType(file) != null) {
                    return file
                }
            }
            return null
        }
    }

    data class StorageLocation(
        val name: String,
        val path: String,
        val file: File
    )

    fun getCommonStorageLocations(): List<StorageLocation> {
        val locations = mutableListOf<StorageLocation>()

        val internalStorage = Environment.getExternalStorageDirectory()
        if (internalStorage != null && internalStorage.exists()) {
            locations.add(StorageLocation("Internal Storage", internalStorage.absolutePath, internalStorage))
        }

        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        if (dcim != null && dcim.exists()) {
            locations.add(StorageLocation("DCIM", dcim.absolutePath, dcim))
        }

        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        if (pictures != null && pictures.exists()) {
            locations.add(StorageLocation("Pictures", pictures.absolutePath, pictures))
        }

        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        if (movies != null && movies.exists()) {
            locations.add(StorageLocation("Movies", movies.absolutePath, movies))
        }

        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloads != null && downloads.exists()) {
            locations.add(StorageLocation("Downloads", downloads.absolutePath, downloads))
        }

        return locations
    }

    suspend fun listDirectory(directory: File, showHidden: Boolean = false): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        if (!directory.exists() || !directory.canRead()) {
            return@withContext items
        }

        val files = directory.listFiles() ?: return@withContext items

        val directories = mutableListOf<MediaItem>()
        val mediaFiles = mutableListOf<MediaItem>()

        for (file in files) {
            if (!showHidden && file.name.startsWith(".")) continue

            if (file.isDirectory) {
                // Count media items inside if possible or just show directory
                directories.add(
                    MediaItem(
                        id = file.absolutePath,
                        name = file.name,
                        path = file.absolutePath,
                        uriString = file.toURI().toString(),
                        type = MediaType.IMAGE,
                        size = file.length(),
                        dateModified = file.lastModified(),
                        isDirectory = true
                    )
                )
            } else {
                val mediaType = getMediaType(file)
                if (mediaType != null) {
                    val mime = try {
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
                    } catch (_: Throwable) {
                        null
                    }
                    mediaFiles.add(
                        MediaItem(
                            id = file.absolutePath,
                            name = file.name,
                            path = file.absolutePath,
                            uriString = file.toURI().toString(),
                            type = mediaType,
                            size = file.length(),
                            dateModified = file.lastModified(),
                            isDirectory = false,
                            mimeType = mime
                        )
                    )
                }
            }
        }

        directories.sortBy { it.name.lowercase(Locale.ROOT) }
        mediaFiles.sortBy { it.name.lowercase(Locale.ROOT) }

        items.addAll(directories)
        items.addAll(mediaFiles)
        items
    }

    /**
     * Get only playable media items in a directory (for feeding into the viewer pager)
     */
    suspend fun getMediaItemsInDirectory(directory: File, showHidden: Boolean = false): List<MediaItem> = withContext(Dispatchers.IO) {
        val allItems = listDirectory(directory, showHidden)
        allItems.filter { !it.isDirectory }
    }
}
