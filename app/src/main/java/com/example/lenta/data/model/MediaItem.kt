package com.example.lenta.data.model

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
    val mimeType: String? = null
) : Serializable
