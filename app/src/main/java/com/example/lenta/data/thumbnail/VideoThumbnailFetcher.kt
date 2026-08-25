package com.example.lenta.data.thumbnail

import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.example.lenta.data.local.LocalMediaRepository
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.Locale

class VideoThumbnailFetcher(
    private val context: Context,
    private val file: File
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val thumbFile = ThumbnailManager.getVideoThumbnail(context, file) ?: return null
        return SourceResult(
            source = ImageSource(file = thumbFile.toOkioPath()),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            val ext = data.extension.lowercase(Locale.ROOT)
            if (ext in LocalMediaRepository.VIDEO_EXTENSIONS) {
                return VideoThumbnailFetcher(context, data)
            }
            return null
        }
    }
}
