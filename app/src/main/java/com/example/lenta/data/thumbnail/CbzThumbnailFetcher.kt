package com.example.lenta.data.thumbnail

import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.example.lenta.data.cbz.CbzReader
import com.example.lenta.data.local.LocalMediaRepository
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.Locale

class CbzThumbnailFetcher(
    private val context: Context,
    private val file: File
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val coverFile = CbzReader.getComicCover(context, file) ?: return null
        val ext = coverFile.extension.lowercase(Locale.ROOT)
        val mimeType = when (ext) {
            "png", "apng" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "avif", "avis" -> "image/avif"
            "heic", "heif" -> "image/heic"
            "svg" -> "image/svg+xml"
            else -> "image/jpeg"
        }
        return SourceResult(
            source = ImageSource(file = coverFile.toOkioPath()),
            mimeType = mimeType,
            dataSource = DataSource.DISK
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            val ext = data.extension.lowercase(Locale.ROOT)
            if (ext in LocalMediaRepository.COMIC_EXTENSIONS) {
                return CbzThumbnailFetcher(context, data)
            }
            return null
        }
    }
}
