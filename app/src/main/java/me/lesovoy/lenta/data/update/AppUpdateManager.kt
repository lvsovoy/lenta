package me.lesovoy.lenta.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lesovoy.lenta.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages checking for newer app releases on GitHub and initiating update downloads.
 */
class AppUpdateManager(
    private val context: Context? = null,
    private val okHttpClient: OkHttpClient = defaultHttpClient,
    private val repoOwner: String = GITHUB_OWNER,
    private val repoName: String = GITHUB_REPO,
    private val currentVersionOverride: String? = null
) {

    companion object {
        const val GITHUB_OWNER = "lvsovoy"
        const val GITHUB_REPO = "lenta"

        private val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Compares a remote tag name (e.g. "v1.0.1", "1.1.0") against the current version string.
         * Returns true if remote is strictly newer than current.
         */
        fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
            val remoteSegments = parseVersionSegments(remoteTag)
            val currentSegments = parseVersionSegments(currentVersion)

            val maxLen = maxOf(remoteSegments.size, currentSegments.size)
            for (i in 0 until maxLen) {
                val r = remoteSegments.getOrElse(i) { 0 }
                val c = currentSegments.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }

        /**
         * Parses version string into numeric integer segments, stripping prefixes like 'v' and suffix metadata.
         */
        fun parseVersionSegments(versionStr: String): List<Int> {
            val cleaned = versionStr.trim().removePrefix("v").removePrefix("V")
            // Take part before any hyphen or plus if present
            val mainVersion = cleaned.split("-", "+", "_").firstOrNull() ?: ""
            if (mainVersion.isBlank()) return listOf(0)

            val parts = mainVersion.split(".")
            val list = mutableListOf<Int>()
            for (part in parts) {
                val digitsOnly = part.filter { it.isDigit() }
                val num = digitsOnly.toIntOrNull() ?: 0
                list.add(num)
            }
            while (list.size > 1 && list.last() == 0) {
                list.removeAt(list.size - 1)
            }
            return if (list.isEmpty()) listOf(0) else list
        }

        /**
         * Formats raw commit messages or bullet items into a clean changelog string.
         */
        fun formatChangelog(releaseNotes: String, commitMessages: List<String>): String {
            val sb = StringBuilder()
            val cleanNotes = releaseNotes.trim()

            if (commitMessages.isNotEmpty()) {
                for (msg in commitMessages) {
                    val firstLine = msg.lines().firstOrNull { it.isNotBlank() }?.trim() ?: continue
                    if (firstLine.isNotBlank()) {
                        sb.append("• ").append(firstLine).append("\n")
                    }
                }
            } else if (cleanNotes.isNotBlank()) {
                sb.append(cleanNotes)
            }

            return sb.toString().trim()
        }
    }

    val currentVersion: String
        get() = currentVersionOverride ?: try {
            val ctx = context
            if (ctx != null) {
                val pInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                pInfo.versionName ?: "1.0"
            } else {
                "1.0"
            }
        } catch (_: Exception) {
            "1.0"
        }

    /**
     * Checks if a newer release is available on GitHub.
     */
    suspend fun checkForUpdates(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val apiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Lenta-Android-App")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                if (response.code == 404) {
                    return@withContext UpdateCheckResult.UpToDate(currentVersion)
                }
                return@withContext UpdateCheckResult.Error("GitHub API HTTP ${response.code}: ${response.message}")
            }

            val responseBody = response.body?.string()
                ?: return@withContext UpdateCheckResult.Error("Empty response from GitHub API")

            val release = parseReleaseJson(responseBody)
            val isNewer = isNewerVersion(release.tagName, currentVersion)

            if (isNewer) {
                val commits = fetchCommitMessages(currentVersion, release.tagName)
                val enrichedRelease = release.copy(commitMessages = commits)
                UpdateCheckResult.UpdateAvailable(enrichedRelease, currentVersion)
            } else {
                UpdateCheckResult.UpToDate(currentVersion, release)
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "Failed to check for updates", e)
        }
    }

    /**
     * Fetches commit messages between current version tag and target release tag.
     */
    suspend fun fetchCommitMessages(fromVersion: String, toTag: String): List<String> = withContext(Dispatchers.IO) {
        val commitList = mutableListOf<String>()
        val fromTags = listOf(
            fromVersion,
            "v$fromVersion",
            fromVersion.removePrefix("v")
        ).distinct()

        var fetched = false
        for (fromTagCandidate in fromTags) {
            try {
                val compareUrl = "https://api.github.com/repos/$repoOwner/$repoName/compare/$fromTagCandidate...$toTag"
                val req = Request.Builder()
                    .url(compareUrl)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "Lenta-Android-App")
                    .build()

                val resp = okHttpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (body != null) {
                        val obj = JSONObject(body)
                        val commitsArray = obj.optJSONArray("commits")
                        if (commitsArray != null) {
                            for (i in 0 until commitsArray.length()) {
                                val cObj = commitsArray.optJSONObject(i)
                                val commitObj = cObj?.optJSONObject("commit")
                                val message = commitObj?.optString("message")
                                if (!message.isNullOrBlank()) {
                                    commitList.add(message)
                                }
                            }
                            fetched = true
                            break
                        }
                    }
                }
            } catch (_: Exception) {
                // Continue to fallback
            }
        }

        if (!fetched || commitList.isEmpty()) {
            // Fallback to latest commits for the target tag
            try {
                val commitsUrl = "https://api.github.com/repos/$repoOwner/$repoName/commits?sha=$toTag&per_page=15"
                val req = Request.Builder()
                    .url(commitsUrl)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "Lenta-Android-App")
                    .build()

                val resp = okHttpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (body != null) {
                        val arr = JSONArray(body)
                        for (i in 0 until arr.length()) {
                            val cObj = arr.optJSONObject(i)
                            val commitObj = cObj?.optJSONObject("commit")
                            val message = commitObj?.optString("message")
                            if (!message.isNullOrBlank()) {
                                commitList.add(message)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore fallback error
            }
        }

        commitList
    }

    /**
     * Parses GitHub release JSON into an AppReleaseInfo instance.
     */
    fun parseReleaseJson(jsonString: String): AppReleaseInfo {
        try {
            val json = JSONObject(jsonString)
            val tagName = json.optString("tag_name", "")
            val releaseName = json.optString("name", tagName)
            val releaseNotes = json.optString("body", "")
            val htmlUrl = json.optString("html_url", "https://github.com/$repoOwner/$repoName/releases")
            val publishedAt = if (json.has("published_at")) json.optString("published_at") else null

            var apkDownloadUrl: String? = null
            var apkFileName: String? = null
            var apkFileSize: Long = 0L

            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkDownloadUrl = if (asset.has("browser_download_url")) asset.optString("browser_download_url") else null
                        apkFileName = name
                        apkFileSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (tagName.isNotBlank()) {
                return AppReleaseInfo(
                    tagName = tagName,
                    releaseName = releaseName,
                    releaseNotes = releaseNotes,
                    apkDownloadUrl = apkDownloadUrl,
                    apkFileName = apkFileName,
                    apkFileSize = apkFileSize,
                    releasePageUrl = htmlUrl,
                    publishedAt = publishedAt
                )
            }
        } catch (_: Throwable) {
            // Fall back to regex parsing (e.g. in JVM unit tests)
        }

        // Regex fallback parser
        fun extractString(key: String, source: String = jsonString): String {
            val pattern = Regex("\"$key\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"")
            val match = pattern.find(source)
            return match?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                ?.replace("\\n", "\n")
                ?.replace("\\r", "\r")
                ?.replace("\\t", "\t")
                ?: ""
        }

        fun extractLong(key: String, source: String = jsonString): Long {
            val pattern = Regex("\"$key\"\\s*:\\s*(\\d+)")
            val match = pattern.find(source)
            return match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        }

        val tagName = extractString("tag_name")
        val releaseName = extractString("name").ifBlank { tagName }
        val releaseNotes = extractString("body")
        val htmlUrl = extractString("html_url").ifBlank { "https://github.com/$repoOwner/$repoName/releases" }
        val publishedAt = extractString("published_at").takeIf { it.isNotBlank() }

        var apkDownloadUrl: String? = null
        var apkFileName: String? = null
        var apkFileSize: Long = 0L

        val assetPattern = Regex("\\{[^{}]*\"name\"\\s*:\\s*\"([^\"]+\\.apk)\"[^{}]*\\}", RegexOption.IGNORE_CASE)
        val assetMatch = assetPattern.find(jsonString)
        if (assetMatch != null) {
            val assetBlock = assetMatch.value
            apkFileName = assetMatch.groupValues[1]
            apkDownloadUrl = extractString("browser_download_url", assetBlock).takeIf { it.isNotBlank() }
            apkFileSize = extractLong("size", assetBlock)
        }

        return AppReleaseInfo(
            tagName = tagName,
            releaseName = releaseName,
            releaseNotes = releaseNotes,
            apkDownloadUrl = apkDownloadUrl,
            apkFileName = apkFileName,
            apkFileSize = apkFileSize,
            releasePageUrl = htmlUrl,
            publishedAt = publishedAt
        )
    }

    /**
     * Initiates the download of the APK update via Android's DownloadManager and launches view intent.
     */
    fun downloadUpdate(releaseInfo: AppReleaseInfo) {
        val ctx = context ?: return
        val downloadUrl = releaseInfo.apkDownloadUrl ?: releaseInfo.releasePageUrl
        val fileName = releaseInfo.apkFileName ?: "Lenta-${releaseInfo.tagName}.apk"

        try {
            if (releaseInfo.apkDownloadUrl != null) {
                val downloadManager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                if (downloadManager != null) {
                    val request = DownloadManager.Request(Uri.parse(releaseInfo.apkDownloadUrl)).apply {
                        setTitle(ctx.getString(R.string.app_name) + " " + releaseInfo.tagName)
                        setDescription(ctx.getString(R.string.update_downloading, releaseInfo.tagName))
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setMimeType("application/vnd.android.package-archive")
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    }
                    downloadManager.enqueue(request)
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.update_downloading, releaseInfo.tagName),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // Also launch browser / action view so the user can immediately download or view the release
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseInfo.releasePageUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(fallbackIntent)
            } catch (_: Exception) {
                Toast.makeText(ctx, "Could not open download URL", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
