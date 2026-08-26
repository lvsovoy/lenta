package me.lesovoy.lenta.data.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class AudioMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkFile: File?
)

object AudioMetadataHelper {

    suspend fun extractMetadata(context: Context, audioFile: File): AudioMetadata =
        extractMetadata(context.cacheDir, audioFile)

    suspend fun extractMetadata(cacheDir: File, audioFile: File): AudioMetadata = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext AudioMetadata(
                title = audioFile.nameWithoutExtension.ifEmpty { "Audio" },
                artist = "Unknown Artist",
                album = "Unknown Album",
                durationMs = 0L,
                artworkFile = null
            )
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audioFile.absolutePath)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()
                ?.ifEmpty { null }
                ?: audioFile.nameWithoutExtension.ifEmpty { "Audio" }

            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim()
                ?.ifEmpty { null }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim()?.ifEmpty { null }
                ?: "Unknown Artist"

            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.trim()
                ?.ifEmpty { null }
                ?: "Unknown Album"

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            val picBytes = retriever.embeddedPicture
            var artworkFile: File? = null
            if (picBytes != null && picBytes.isNotEmpty()) {
                val thumbCacheDir = File(cacheDir, "thumbnail_cache/audio")
                thumbCacheDir.mkdirs()
                val cacheKey = "art_${audioFile.name.hashCode()}_${audioFile.length()}_${audioFile.lastModified()}.jpg"
                val artFile = File(thumbCacheDir, cacheKey)
                if (!artFile.exists() || artFile.length() == 0L) {
                    try {
                        FileOutputStream(artFile).use { fos ->
                            fos.write(picBytes)
                        }
                    } catch (_: Throwable) {
                    }
                }
                if (artFile.exists() && artFile.length() > 0L) {
                    artworkFile = artFile
                }
            }

            AudioMetadata(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                artworkFile = artworkFile
            )
        } catch (_: Throwable) {
            AudioMetadata(
                title = audioFile.nameWithoutExtension.ifEmpty { "Audio" },
                artist = "Unknown Artist",
                album = "Unknown Album",
                durationMs = 0L,
                artworkFile = null
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    suspend fun extractMetadataFromUri(context: Context, uri: Uri, displayName: String): AudioMetadata = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val fallbackTitle = displayName.substringBeforeLast('.').ifEmpty { "Audio" }
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()
                ?.ifEmpty { null }
                ?: fallbackTitle

            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim()
                ?.ifEmpty { null }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim()?.ifEmpty { null }
                ?: "Unknown Artist"

            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.trim()
                ?.ifEmpty { null }
                ?: "Unknown Album"

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            val picBytes = retriever.embeddedPicture
            var artworkFile: File? = null
            if (picBytes != null && picBytes.isNotEmpty()) {
                val cacheDir = File(context.cacheDir, "thumbnail_cache/audio")
                cacheDir.mkdirs()
                val cacheKey = "art_uri_${uri.toString().hashCode()}.jpg"
                val artFile = File(cacheDir, cacheKey)
                if (!artFile.exists() || artFile.length() == 0L) {
                    try {
                        FileOutputStream(artFile).use { fos ->
                            fos.write(picBytes)
                        }
                    } catch (_: Throwable) {
                    }
                }
                if (artFile.exists() && artFile.length() > 0L) {
                    artworkFile = artFile
                }
            }

            AudioMetadata(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                artworkFile = artworkFile
            )
        } catch (_: Throwable) {
            val fallbackTitle = displayName.substringBeforeLast('.').ifEmpty { "Audio" }
            AudioMetadata(
                title = fallbackTitle,
                artist = "Unknown Artist",
                album = "Unknown Album",
                durationMs = 0L,
                artworkFile = null
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }
}
