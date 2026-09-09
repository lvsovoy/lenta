package me.lesovoy.lenta

import me.lesovoy.lenta.data.nextcloud.NextcloudPreferences
import me.lesovoy.lenta.data.update.AppReleaseInfo
import me.lesovoy.lenta.data.update.AppUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateUnitTests {

    private val sampleReleaseJson = """
        {
          "tag_name": "v1.0.1",
          "name": "Lenta 1.0.1",
          "body": "### Changes\n- Fixed video playback buffering\n- Added changelog view",
          "html_url": "https://github.com/lvsovoy/lenta/releases/tag/v1.0.1",
          "published_at": "2026-09-09T14:00:00Z",
          "assets": [
            {
              "name": "app-release.apk",
              "browser_download_url": "https://github.com/lvsovoy/lenta/releases/download/v1.0.1/app-release.apk",
              "size": 21500000
            },
            {
              "name": "checksums.txt",
              "browser_download_url": "https://github.com/lvsovoy/lenta/releases/download/v1.0.1/checksums.txt",
              "size": 128
            }
          ]
        }
    """.trimIndent()

    private val sampleReleaseWithoutApkJson = """
        {
          "tag_name": "v1.0.0",
          "name": "v1.0.0",
          "body": "Initial release",
          "html_url": "https://github.com/lvsovoy/lenta/releases/tag/v1.0.0",
          "assets": []
        }
    """.trimIndent()

    @Test
    fun testSemanticVersionComparison() {
        // Newer versions
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.1", "1.0"))
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(AppUpdateManager.isNewerVersion("v1.1", "1.0"))
        assertTrue(AppUpdateManager.isNewerVersion("1.1.0", "1.0.5"))
        assertTrue(AppUpdateManager.isNewerVersion("v2.0.0", "1.9.9"))
        assertTrue(AppUpdateManager.isNewerVersion("2.0.1", "2.0.0"))
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.1-rc1", "1.0.0"))

        // Equal or older versions
        assertFalse(AppUpdateManager.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(AppUpdateManager.isNewerVersion("1.0", "1.0.0"))
        assertFalse(AppUpdateManager.isNewerVersion("v1.0.0", "1.0"))
        assertFalse(AppUpdateManager.isNewerVersion("v0.9.9", "1.0.0"))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.0", "1.0.1"))
        assertFalse(AppUpdateManager.isNewerVersion("v1.0.0", "2.0.0"))
    }

    @Test
    fun testVersionSegmentsParsing() {
        assertEquals(listOf(1, 2, 3), AppUpdateManager.parseVersionSegments("v1.2.3"))
        assertEquals(listOf(1), AppUpdateManager.parseVersionSegments("1.0.0"))
        assertEquals(listOf(1, 1), AppUpdateManager.parseVersionSegments("v1.1.0"))
        assertEquals(listOf(2), AppUpdateManager.parseVersionSegments("2"))
        assertEquals(listOf(0), AppUpdateManager.parseVersionSegments(""))
    }

    @Test
    fun testReleaseJsonParsingWithApkAsset() {
        val manager = AppUpdateManager(
            context = null,
            currentVersionOverride = "1.0"
        )
        val release = manager.parseReleaseJson(sampleReleaseJson)

        assertEquals("v1.0.1", release.tagName)
        assertEquals("Lenta 1.0.1", release.releaseName)
        assertEquals("### Changes\n- Fixed video playback buffering\n- Added changelog view", release.releaseNotes)
        assertEquals("https://github.com/lvsovoy/lenta/releases/tag/v1.0.1", release.releasePageUrl)
        assertEquals("https://github.com/lvsovoy/lenta/releases/download/v1.0.1/app-release.apk", release.apkDownloadUrl)
        assertEquals("app-release.apk", release.apkFileName)
        assertEquals(21500000L, release.apkFileSize)
    }

    @Test
    fun testReleaseJsonParsingWithoutApkAsset() {
        val manager = AppUpdateManager(
            context = null,
            currentVersionOverride = "1.0"
        )
        val release = manager.parseReleaseJson(sampleReleaseWithoutApkJson)

        assertEquals("v1.0.0", release.tagName)
        assertNull(release.apkDownloadUrl)
        assertNull(release.apkFileName)
        assertEquals(0L, release.apkFileSize)
        assertEquals("https://github.com/lvsovoy/lenta/releases/tag/v1.0.0", release.releasePageUrl)
    }

    @Test
    fun testFormatChangelogWithCommitMessages() {
        val commits = listOf(
            "Fix video player surface release on stop\n\nDetailed explanation",
            "Update thumbnail cache eviction",
            "Add landscape grid column adaptations"
        )
        val changelog = AppUpdateManager.formatChangelog("Release notes here", commits)

        assertTrue(changelog.contains("• Fix video player surface release on stop"))
        assertTrue(changelog.contains("• Update thumbnail cache eviction"))
        assertTrue(changelog.contains("• Add landscape grid column adaptations"))
        assertFalse(changelog.contains("Detailed explanation"))
    }

    @Test
    fun testFormatChangelogWithoutCommitsUsesReleaseNotes() {
        val releaseNotes = "### Features\n* Added Nextcloud sync\n* Added CBZ support"
        val changelog = AppUpdateManager.formatChangelog(releaseNotes, emptyList())

        assertEquals(releaseNotes, changelog)
    }

    @Test
    fun testUpdatePreferences() {
        val prefs = NextcloudPreferences()
        assertTrue(prefs.checkUpdatesOnStartup)

        prefs.checkUpdatesOnStartup = false
        assertFalse(prefs.checkUpdatesOnStartup)

        prefs.checkUpdatesOnStartup = true
        assertTrue(prefs.checkUpdatesOnStartup)

        assertEquals(0L, prefs.lastUpdateCheckTimestamp)
        prefs.lastUpdateCheckTimestamp = 123456789L
        assertEquals(123456789L, prefs.lastUpdateCheckTimestamp)
    }

    @Test
    fun testUpdateCheckResultModels() {
        val release = AppReleaseInfo(
            tagName = "v1.2.0",
            releaseName = "v1.2.0",
            releaseNotes = "Notes",
            apkDownloadUrl = "https://example.com/app.apk",
            apkFileName = "app.apk",
            apkFileSize = 1000L,
            releasePageUrl = "https://github.com/lvsovoy/lenta/releases/tag/v1.2.0",
            commitMessages = listOf("feat: new UI", "fix: player crash")
        )

        val updateAvailable = me.lesovoy.lenta.data.update.UpdateCheckResult.UpdateAvailable(release, "1.0")
        assertEquals("1.0", updateAvailable.currentVersion)
        assertEquals("v1.2.0", updateAvailable.releaseInfo.tagName)
        assertEquals(2, updateAvailable.releaseInfo.commitMessages.size)

        val upToDate = me.lesovoy.lenta.data.update.UpdateCheckResult.UpToDate("1.2.0", release)
        assertEquals("1.2.0", upToDate.currentVersion)
        assertNotNull(upToDate.latestRelease)

        val error = me.lesovoy.lenta.data.update.UpdateCheckResult.Error("Network failure")
        assertEquals("Network failure", error.message)
    }
}
