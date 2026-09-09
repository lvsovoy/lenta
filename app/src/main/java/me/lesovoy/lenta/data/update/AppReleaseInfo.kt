package me.lesovoy.lenta.data.update

/**
 * Metadata and assets describing an app release from GitHub.
 */
data class AppReleaseInfo(
    val tagName: String,
    val releaseName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val apkFileName: String?,
    val apkFileSize: Long,
    val releasePageUrl: String,
    val commitMessages: List<String> = emptyList(),
    val publishedAt: String? = null
)

/**
 * Result representing the outcome of checking for app updates.
 */
sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val releaseInfo: AppReleaseInfo,
        val currentVersion: String
    ) : UpdateCheckResult()

    data class UpToDate(
        val currentVersion: String,
        val latestRelease: AppReleaseInfo? = null
    ) : UpdateCheckResult()

    data class Error(
        val message: String,
        val throwable: Throwable? = null
    ) : UpdateCheckResult()
}
