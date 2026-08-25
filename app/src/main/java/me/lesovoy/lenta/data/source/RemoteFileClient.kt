package me.lesovoy.lenta.data.source

import android.content.Context
import me.lesovoy.lenta.data.model.MediaItem
import java.io.File

interface RemoteFileClient {
    val source: StorageSource

    suspend fun testConnection(): Result<Boolean>

    suspend fun listFolder(
        remotePath: String = "",
        showHidden: Boolean = source.showHidden
    ): Result<List<MediaItem>>

    suspend fun downloadToCache(
        context: Context,
        item: MediaItem
    ): Result<File>

    fun getPreviewUrl(item: MediaItem): String?

    fun getStreamUrl(item: MediaItem): String

    fun getAuthHeaders(): Map<String, String>
}
