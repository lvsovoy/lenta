package me.lesovoy.lenta.data.source

import android.content.Context
import android.content.SharedPreferences
import me.lesovoy.lenta.data.nextcloud.NextcloudPreferences
import org.json.JSONArray
import java.util.UUID

class StorageSourceManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val nextcloudPrefs = NextcloudPreferences(context)

    fun getAllSources(): List<StorageSource> {
        val sources = mutableListOf<StorageSource>()

        // 1. Always include Local Storage
        sources.add(
            StorageSource(
                id = StorageSource.LOCAL_SOURCE_ID,
                name = "Local Storage",
                type = StorageSourceType.LOCAL,
                isDefault = true
            )
        )

        // 2. Load custom sources from JSON
        val sourcesJson = prefs.getString(KEY_SOURCES_LIST, null)
        val loadedCustomSources = mutableListOf<StorageSource>()
        if (!sourcesJson.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(sourcesJson)
                for (i in 0 until jsonArray.length()) {
                    val itemObj = jsonArray.getJSONObject(i)
                    val source = StorageSource.fromJson(itemObj)
                    if (source.id != StorageSource.LOCAL_SOURCE_ID) {
                        loadedCustomSources.add(source)
                    }
                }
            } catch (_: Throwable) {
                // Fallback parsing from JSON string
                try {
                    val trimmed = sourcesJson.trim().removeSurrounding("[", "]").trim()
                    if (trimmed.isNotEmpty()) {
                        val items = trimmed.split("(?<=\\}),\\s*(?=\\{)".toRegex())
                        for (itemStr in items) {
                            val source = StorageSource.fromJsonString(itemStr)
                            if (source.id.isNotBlank() && source.id != StorageSource.LOCAL_SOURCE_ID) {
                                loadedCustomSources.add(source)
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        // 3. Ensure Nextcloud source is present and synced with NextcloudPreferences
        val existingNextcloud = loadedCustomSources.firstOrNull { it.id == StorageSource.NEXTCLOUD_SOURCE_ID || it.type == StorageSourceType.NEXTCLOUD }
        val nextcloudSource = if (existingNextcloud != null) {
            existingNextcloud.copy(
                serverUrl = if (nextcloudPrefs.serverUrl.isNotBlank()) nextcloudPrefs.serverUrl else existingNextcloud.serverUrl,
                username = if (nextcloudPrefs.username.isNotBlank()) nextcloudPrefs.username else existingNextcloud.username,
                password = if (nextcloudPrefs.password.isNotBlank()) nextcloudPrefs.password else existingNextcloud.password,
                showHidden = nextcloudPrefs.showHiddenFiles
            )
        } else {
            StorageSource(
                id = StorageSource.NEXTCLOUD_SOURCE_ID,
                name = "Nextcloud",
                type = StorageSourceType.NEXTCLOUD,
                serverUrl = nextcloudPrefs.serverUrl,
                username = nextcloudPrefs.username,
                password = nextcloudPrefs.password,
                showHidden = nextcloudPrefs.showHiddenFiles,
                isDefault = true
            )
        }

        sources.add(nextcloudSource)

        // Add remaining custom sources (WebDAV, Google Drive, OneDrive, SMB, FTP, etc.)
        for (custom in loadedCustomSources) {
            if (custom.id != StorageSource.NEXTCLOUD_SOURCE_ID && custom.id != nextcloudSource.id) {
                sources.add(custom)
            }
        }

        return sources
    }

    fun getSource(id: String): StorageSource? {
        return getAllSources().firstOrNull { it.id == id }
    }

    fun saveSource(source: StorageSource): StorageSource {
        val finalSource = if (source.id.isBlank()) {
            source.copy(id = "source_${UUID.randomUUID().toString().take(8)}")
        } else {
            source
        }

        if (finalSource.type == StorageSourceType.NEXTCLOUD || finalSource.id == StorageSource.NEXTCLOUD_SOURCE_ID) {
            nextcloudPrefs.serverUrl = finalSource.serverUrl
            nextcloudPrefs.username = finalSource.username
            nextcloudPrefs.password = finalSource.password
            nextcloudPrefs.showHiddenFiles = finalSource.showHidden
        }

        val all = getAllSources().toMutableList()
        val index = all.indexOfFirst { it.id == finalSource.id }
        if (index >= 0) {
            all[index] = finalSource
        } else {
            all.add(finalSource)
        }

        saveSourcesList(all.filter { it.id != StorageSource.LOCAL_SOURCE_ID })
        return finalSource
    }

    fun deleteSource(id: String) {
        if (id == StorageSource.LOCAL_SOURCE_ID) return // Cannot delete local
        if (id == StorageSource.NEXTCLOUD_SOURCE_ID) {
            nextcloudPrefs.serverUrl = ""
            nextcloudPrefs.username = ""
            nextcloudPrefs.password = ""
        }

        val all = getAllSources().filter { it.id != id && it.id != StorageSource.LOCAL_SOURCE_ID }
        saveSourcesList(all)
    }

    private fun saveSourcesList(sources: List<StorageSource>) {
        val jsonArrayStr = buildString {
            append("[")
            sources.forEachIndexed { index, source ->
                if (index > 0) append(",")
                append(source.toJsonString())
            }
            append("]")
        }
        prefs.edit().putString(KEY_SOURCES_LIST, jsonArrayStr).apply()
    }

    companion object {
        private const val PREFS_NAME = "lenta_storage_sources"
        private const val KEY_SOURCES_LIST = "configured_sources_json"
    }
}
