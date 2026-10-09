package com.jm.sillydroid.data.update

import com.jm.sillydroid.core.model.update.AppUpdateRequestConfig
import com.jm.sillydroid.core.model.update.AvailableAppRelease
import java.util.Locale
import org.json.JSONObject

internal object FilesiteVersionParser {
    /**
     * 解析 filesite `GET /api/version/<app>` 返回的版本信息；
     * 只按 versionCode 判断新旧，与 Android 覆盖安装的判定保持一致。
     */
    fun parseLatestAvailableRelease(
        versionInfo: JSONObject,
        config: AppUpdateRequestConfig
    ): AvailableAppRelease? {
        val versionCode = versionInfo.optLong("versionCode", 0L)
        val versionName = versionInfo.optString("versionName").trim()
        val apkKey = versionInfo.optString("apkKey").trim()
        val apkDownloadUrl = versionInfo.optString("apkUrl").trim()
        val apkSha256 = versionInfo.optString("sha256").trim().lowercase(Locale.US)
        val apkAssetName = apkKey.substringAfterLast('/')
        if (
            versionCode <= config.currentVersionCode ||
            versionName.isBlank() ||
            apkAssetName.isBlank() ||
            apkDownloadUrl.isBlank() ||
            !apkSha256.matches(sha256Pattern)
        ) {
            return null
        }

        val releaseTag = "v$versionName"
        return AvailableAppRelease(
            releaseTag = releaseTag,
            releaseTitle = releaseTag,
            versionName = versionName,
            hostVersion = versionName,
            releaseNotesMarkdown = (versionInfo.opt("changelog") as? String)?.trim()?.ifBlank { null },
            apkAssetName = apkAssetName,
            apkDownloadUrl = apkDownloadUrl,
            apkSha256 = apkSha256
        )
    }

    private val sha256Pattern = Regex("[0-9a-f]{64}")
}
