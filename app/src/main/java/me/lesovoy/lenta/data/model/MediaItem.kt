package me.lesovoy.lenta.data.model

import me.lesovoy.lenta.data.source.StorageSourceType
import java.io.Serializable

enum class MediaType {
    IMAGE,
    VIDEO,
    GIF,
    CBZ
}

data class MediaItem(
    val id: String,
    val name: String,
    val path: String,
    val uriString: String,
    val type: MediaType,
    val size: Long = 0L,
    val dateModified: Long = 0L,
    val isDirectory: Boolean = false,
    val isNextcloud: Boolean = false,
    val nextcloudPath: String? = null,
    val mimeType: String? = null,
    val sourceId: String? = null,
    val sourceType: StorageSourceType? = null,
    val remotePath: String? = null
) : Serializable {
    val isRemote: Boolean
        get() = isNextcloud || (sourceType != null && sourceType != StorageSourceType.LOCAL) || sourceId != null

    val effectiveRemotePath: String
        get() = remotePath ?: nextcloudPath ?: path
}
