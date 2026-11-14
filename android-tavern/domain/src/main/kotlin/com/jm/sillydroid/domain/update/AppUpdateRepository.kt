package com.jm.sillydroid.domain.update

import com.jm.sillydroid.core.model.update.AppDownloadRecord
import com.jm.sillydroid.core.model.update.AppDownloadState
import com.jm.sillydroid.core.model.update.AppUpdateRequestConfig
import com.jm.sillydroid.core.model.update.AvailableAppRelease

interface AppUpdateRepository {
    fun cachedAvailableRelease(): AvailableAppRelease?
    fun cachedDownloadState(): AppDownloadState?
    var checkErrorMessage: String?
    fun cacheAvailableRelease(release: AvailableAppRelease?)
    suspend fun fetchLatestAvailableRelease(config: AppUpdateRequestConfig): AvailableAppRelease?
    suspend fun startDownload(release: AvailableAppRelease): AppDownloadState
    suspend fun startOrResumeDownload(release: AvailableAppRelease): AppDownloadState
    fun inspectDownloadState(release: AvailableAppRelease, verifyCompleteApk: Boolean = true): AppDownloadRecord
    fun updateDownloadProgress(record: AppDownloadRecord): AppDownloadState?
    fun cleanupDownloadCache(currentRelease: AvailableAppRelease?): AppDownloadCacheCleanupResult
    fun queryDownloadRecord(downloadId: Long): AppDownloadRecord
    fun verifyDownloadedApk(downloadState: AppDownloadState): Boolean
    fun markDownloadVerified(downloadState: AppDownloadState): AppDownloadState
    fun downloadedApkPath(downloadState: AppDownloadState): String
    fun clearAvailableRelease()
    fun clearDownloadState(removeDownload: Boolean)

    /**
     * 5.2: 进程内去重闸门。
     * 当 [MainActivity] 与 [BootstrapSettingsActivity] 同时订阅
     * `DOWNLOAD_COMPLETE` 广播并各自调用安装 Intent 时，
     * 首次调用返回 true，同一 downloadId 在去重窗口内的后续调用返回 false。
     */
    fun claimInstallerLaunch(downloadId: Long): Boolean

    /**
     * 通过本机 adb（127.0.0.1:5555，shell 身份）安装已校验的更新包，不经过系统安装器的风险确认。
     * 阻塞调用，要在 IO 线程执行；第一次会在手机上弹「允许 USB 调试」，[onAuthorizationRequested] 在弹框前回调。
     * 返回 [AppUpdateAdbInstallResult.Started] 时安装已在后台开始，App 随后会被系统结束并自动重新打开。
     */
    fun installWithLocalAdb(
        downloadState: AppDownloadState,
        onAuthorizationRequested: () -> Unit = {}
    ): AppUpdateAdbInstallResult = AppUpdateAdbInstallResult.Unavailable("当前实现不支持 adb 安装")
}

sealed interface AppUpdateAdbInstallResult {
    object Started : AppUpdateAdbInstallResult

    data class Unavailable(val reason: String) : AppUpdateAdbInstallResult
}

interface AppUpdateStateRepository {
    var availableRelease: AvailableAppRelease?
    var downloadState: AppDownloadState?
    var checkErrorMessage: String?
}

data class AppDownloadCacheCleanupResult(
    val scannedFileCount: Int,
    val deletedFileCount: Int,
    val freedBytes: Long
)
