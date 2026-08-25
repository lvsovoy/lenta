package me.lesovoy.lenta.data.source

import org.json.JSONObject
import java.io.Serializable

data class StorageSource(
    val id: String,
    val name: String,
    val type: StorageSourceType,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val domain: String = "",
    val share: String = "",
    val port: Int = 0,
    val rootPath: String = "",
    val showHidden: Boolean = false,
    val isDefault: Boolean = false
) : Serializable {

    fun isConfigured(): Boolean {
        return when (type) {
            StorageSourceType.LOCAL -> true
            StorageSourceType.NEXTCLOUD -> serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
            StorageSourceType.WEBDAV -> serverUrl.isNotBlank()
            StorageSourceType.GOOGLE_DRIVE -> token.isNotBlank() || serverUrl.isNotBlank() || password.isNotBlank()
            StorageSourceType.ONEDRIVE -> token.isNotBlank() || serverUrl.isNotBlank()
            StorageSourceType.SMB -> serverUrl.isNotBlank() || domain.isNotBlank()
            StorageSourceType.FTP -> serverUrl.isNotBlank()
        }
    }

    fun getDisplaySubtitle(): String {
        return when (type) {
            StorageSourceType.LOCAL -> "Photos, videos, and comics stored on device"
            StorageSourceType.NEXTCLOUD -> if (isConfigured()) "Connected ($serverUrl)" else "Not configured • Tap to set up"
            StorageSourceType.WEBDAV -> if (isConfigured()) "Connected ($serverUrl)" else "Not configured"
            StorageSourceType.GOOGLE_DRIVE -> if (isConfigured()) "Connected (Google Drive${if (username.isNotBlank()) " • $username" else ""})" else "Not configured • Sign in with Google"
            StorageSourceType.ONEDRIVE -> if (isConfigured()) "Connected (OneDrive${if (username.isNotBlank()) " • $username" else ""})" else "Not configured • Sign in with Microsoft"
            StorageSourceType.SMB -> if (isConfigured()) "Connected ($serverUrl${if (share.isNotBlank()) "/$share" else ""})" else "Not configured"
            StorageSourceType.FTP -> if (isConfigured()) "Connected ($serverUrl)" else "Not configured"
        }
    }

    fun toJsonString(): String {
        return buildString {
            append("{")
            append("\"id\":\"").append(escapeJson(id)).append("\",")
            append("\"name\":\"").append(escapeJson(name)).append("\",")
            append("\"type\":\"").append(type.name).append("\",")
            append("\"serverUrl\":\"").append(escapeJson(serverUrl)).append("\",")
            append("\"username\":\"").append(escapeJson(username)).append("\",")
            append("\"password\":\"").append(escapeJson(password)).append("\",")
            append("\"token\":\"").append(escapeJson(token)).append("\",")
            append("\"domain\":\"").append(escapeJson(domain)).append("\",")
            append("\"share\":\"").append(escapeJson(share)).append("\",")
            append("\"port\":").append(port).append(",")
            append("\"rootPath\":\"").append(escapeJson(rootPath)).append("\",")
            append("\"showHidden\":").append(showHidden).append(",")
            append("\"isDefault\":").append(isDefault)
            append("}")
        }
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        try {
            json.put("id", id)
            json.put("name", name)
            json.put("type", type.name)
            json.put("serverUrl", serverUrl)
            json.put("username", username)
            json.put("password", password)
            json.put("token", token)
            json.put("domain", domain)
            json.put("share", share)
            json.put("port", port)
            json.put("rootPath", rootPath)
            json.put("showHidden", showHidden)
            json.put("isDefault", isDefault)
        } catch (_: Throwable) {}
        return json
    }

    companion object {
        const val LOCAL_SOURCE_ID = "source_local"
        const val NEXTCLOUD_SOURCE_ID = "source_nextcloud"

        fun fromJsonString(jsonStr: String): StorageSource {
            fun extractString(key: String): String {
                val pattern = Regex("\"$key\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"")
                val match = pattern.find(jsonStr)
                return match?.groupValues?.get(1)
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
                    ?.replace("\\n", "\n")
                    ?.replace("\\r", "\r")
                    ?.replace("\\t", "\t")
                    ?: ""
            }

            fun extractInt(key: String): Int {
                val pattern = Regex("\"$key\"\\s*:\\s*(\\d+)")
                val match = pattern.find(jsonStr)
                return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }

            fun extractBoolean(key: String): Boolean {
                val pattern = Regex("\"$key\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
                val match = pattern.find(jsonStr)
                return match?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: false
            }

            return StorageSource(
                id = extractString("id"),
                name = extractString("name").ifBlank { "Source" },
                type = StorageSourceType.fromString(extractString("type")),
                serverUrl = extractString("serverUrl"),
                username = extractString("username"),
                password = extractString("password"),
                token = extractString("token"),
                domain = extractString("domain"),
                share = extractString("share"),
                port = extractInt("port"),
                rootPath = extractString("rootPath"),
                showHidden = extractBoolean("showHidden"),
                isDefault = extractBoolean("isDefault")
            )
        }

        fun fromJson(json: JSONObject): StorageSource {
            return try {
                StorageSource(
                    id = json.optString("id", ""),
                    name = json.optString("name", "Source"),
                    type = StorageSourceType.fromString(json.optString("type", "WEBDAV")),
                    serverUrl = json.optString("serverUrl", ""),
                    username = json.optString("username", ""),
                    password = json.optString("password", ""),
                    token = json.optString("token", ""),
                    domain = json.optString("domain", ""),
                    share = json.optString("share", ""),
                    port = json.optInt("port", 0),
                    rootPath = json.optString("rootPath", ""),
                    showHidden = json.optBoolean("showHidden", false),
                    isDefault = json.optBoolean("isDefault", false)
                )
            } catch (_: Throwable) {
                fromJsonString(json.toString())
            }
        }
    }
}
