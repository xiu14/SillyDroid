package com.jm.sillydroid.data.update

import com.jm.sillydroid.core.model.update.AppUpdateRequestConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FilesiteVersionParserTest {
    @Test
    fun parsesNewerFilesiteVersion() {
        val release = FilesiteVersionParser.parseLatestAvailableRelease(
            versionInfo = JSONObject(versionJson(versionCode = 3)),
            config = requestConfig(currentVersionCode = 2)
        )

        assertNotNull(release)
        requireNotNull(release)
        assertEquals("1.0.3", release.versionName)
        assertEquals("v1.0.3", release.releaseTag)
        assertEquals("stdroid-v1.0.3.apk", release.apkAssetName)
        assertEquals("https://files.biatch.party/dl/releases/stdroid-v1.0.3.apk", release.apkDownloadUrl)
        assertEquals(sha256, release.apkSha256)
        assertEquals("修复 xxx", release.releaseNotesMarkdown)
    }

    @Test
    fun ignoresSameOrOlderVersionCode() {
        assertNull(
            FilesiteVersionParser.parseLatestAvailableRelease(
                versionInfo = JSONObject(versionJson(versionCode = 2)),
                config = requestConfig(currentVersionCode = 2)
            )
        )
        assertNull(
            FilesiteVersionParser.parseLatestAvailableRelease(
                versionInfo = JSONObject(versionJson(versionCode = 1)),
                config = requestConfig(currentVersionCode = 2)
            )
        )
    }

    @Test
    fun ignoresInvalidSha256() {
        val release = FilesiteVersionParser.parseLatestAvailableRelease(
            versionInfo = JSONObject(versionJson(versionCode = 3, sha = "not-a-sha")),
            config = requestConfig(currentVersionCode = 2)
        )

        assertNull(release)
    }

    @Test
    fun treatsBlankChangelogAsNoNotes() {
        val release = FilesiteVersionParser.parseLatestAvailableRelease(
            versionInfo = JSONObject(versionJson(versionCode = 3, changelog = "  ")),
            config = requestConfig(currentVersionCode = 2)
        )

        assertNull(requireNotNull(release).releaseNotesMarkdown)
    }

    private fun requestConfig(currentVersionCode: Long): AppUpdateRequestConfig {
        return AppUpdateRequestConfig(
            latestReleaseMetadataUrl = "https://files.biatch.party/api/version/stdroid",
            buildType = "debug",
            currentVersionName = "1.0.2",
            currentVersionCode = currentVersionCode
        )
    }

    private fun versionJson(
        versionCode: Int,
        sha: String = sha256,
        changelog: String = "修复 xxx"
    ): String {
        return JSONObject()
            .put("app", "stdroid")
            .put("versionCode", versionCode)
            .put("versionName", "1.0.$versionCode")
            .put("apkKey", "releases/stdroid-v1.0.$versionCode.apk")
            .put("apkUrl", "https://files.biatch.party/dl/releases/stdroid-v1.0.$versionCode.apk")
            .put("size", 1024)
            .put("sha256", sha)
            .put("changelog", changelog)
            .put("updatedAt", "2026-10-09T00:00:00.000Z")
            .toString()
    }

    private companion object {
        private const val sha256 = "3f5c2a9e8b7d41c6a0e9f2b4d8c1a7e5f6b3d9c2e4a8f1b7c5d0e9a2f4b6c8d1"
    }
}
