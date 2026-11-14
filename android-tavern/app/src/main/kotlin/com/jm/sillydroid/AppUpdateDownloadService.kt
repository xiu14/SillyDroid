package com.jm.sillydroid

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.jm.sillydroid.core.model.notification.HostForegroundBehavior
import com.jm.sillydroid.core.model.notification.HostNotificationAction
import com.jm.sillydroid.core.model.notification.HostNotificationChannel
import com.jm.sillydroid.core.model.notification.HostNotificationKind
import com.jm.sillydroid.core.model.notification.HostNotificationProgress
import com.jm.sillydroid.core.model.notification.HostNotificationSpec
import com.jm.sillydroid.core.model.notification.HostNotificationTapSpec
import com.jm.sillydroid.core.model.update.AppDownloadState
import com.jm.sillydroid.core.model.update.AppDownloadStatus
import com.jm.sillydroid.domain.app.SillyDroidAppGraph
import com.jm.sillydroid.domain.app.SillyDroidAppGraphProvider
import com.jm.sillydroid.domain.update.AppUpdateAdbInstallResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppUpdateDownloadService : Service() {
    companion object {
        private const val LOG_TAG = "SillyDroidUpdate"
        private const val ACTION_START = "com.jm.sillydroid.action.UPDATE_DOWNLOAD_START"
        // 不能和 HostDownloadNotificationCoordinator 的 "app-update-download" 共用一个 key：
        // 通知服务按 key 记录实际发出的 tag/id，共用时两边互相覆盖，下载完会删错、残留一条「下载中」。
        private const val foregroundNotificationKey = "app-update-download-foreground"
        private const val foregroundNotificationId = 12038
        // pm install 一个 330MB 的包加 am start，X8 上一般二三十秒；留足余量再判定 adb 安装失败。
        private const val adbInstallGraceMillis = 120_000L

        fun createStartIntent(context: Context): Intent {
            return Intent(context, AppUpdateDownloadService::class.java).apply {
                action = ACTION_START
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val graph = (applicationContext as SillyDroidAppGraphProvider).sillyDroidAppGraph
        val release = graph.appUpdateRepository.cachedAvailableRelease()
        if (release == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        try {
            graph.hostNotificationService.postForeground(this, buildForegroundSpec("准备下载更新包。", null, true))
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Update download foreground start was not allowed.", error)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        serviceScope.launch {
            val notificationJob = launch {
                while (isActive) {
                    val state = graph.appUpdateRepository.cachedDownloadState()
                    if (state != null) {
                        graph.hostNotificationService.postForeground(
                            this@AppUpdateDownloadService,
                            buildForegroundSpec(
                                body = buildProgressBody(state.versionName, state.downloadedBytes, state.totalBytes),
                                progress = buildProgress(state.downloadedBytes, state.totalBytes),
                                ongoing = true
                            )
                        )
                    }
                    delay(1_000L)
                }
            }
            val finalState = graph.appUpdateRepository.startOrResumeDownload(release)
            notificationJob.cancel()
            if (finalState.verifiedReadyToInstall && installWithLocalAdb(graph, finalState)) {
                // 安装成功时进程已经被系统结束，走不到这里；还活着说明 pm install 没成功，退回系统安装器。
                graph.hostLogRepository.recordHostDiagnostic(
                    category = "app_update",
                    body = "event=adb_install_not_finished version=${finalState.versionName} " +
                        "waitedMs=$adbInstallGraceMillis log=/data/local/tmp/stdroid-adb-install.log"
                )
            }
            graph.hostDownloadNotificationCoordinator.refreshAppUpdateDownload(finalState)
            if (finalState.verifiedReadyToInstall) {
                graph.hostDownloadNotificationCoordinator.postAppUpdateReadyToInstall(
                    apkPath = graph.appUpdateRepository.downloadedApkPath(finalState),
                    canRequestPackageInstalls = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                        packageManager.canRequestPackageInstalls()
                )
            }
            if (finalState.status == AppDownloadStatus.FAILED || finalState.status == AppDownloadStatus.STALLED) {
                graph.hostDownloadNotificationCoordinator.postAppUpdateDownloadFailed(
                    versionName = finalState.versionName,
                    failureReason = finalState.failureReason
                )
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * 用本机 adb 直接安装（不走系统安装器的风险确认和密码）。交给 adb 后等一段时间：安装提交时系统会结束本进程，
     * 装完由 am start 重新打开 App。返回 true 表示已交给 adb 但等完了进程还在（安装没成功）；
     * 返回 false 表示 adb 不可用，调用方直接退回系统安装器。
     */
    private suspend fun installWithLocalAdb(graph: SillyDroidAppGraph, state: AppDownloadState): Boolean {
        graph.hostNotificationService.postForeground(
            this,
            buildForegroundSpec("“${state.versionName}”已下载，正在通过本机 adb 安装，完成后会自动重新打开。", null, true)
        )
        val result = graph.appUpdateRepository.installWithLocalAdb(state) {
            graph.hostNotificationService.postForeground(
                this,
                buildForegroundSpec("请在系统弹窗里允许 USB 调试（勾选「一律允许」），之后会自动安装更新。", null, true)
            )
        }
        return when (result) {
            AppUpdateAdbInstallResult.Started -> {
                graph.hostLogRepository.recordHostDiagnostic(
                    category = "app_update",
                    body = "event=adb_install_started version=${state.versionName}"
                )
                delay(adbInstallGraceMillis)
                true
            }

            is AppUpdateAdbInstallResult.Unavailable -> {
                graph.hostLogRepository.recordHostDiagnostic(
                    category = "app_update",
                    body = "event=adb_install_unavailable version=${state.versionName} reason=${result.reason.replace('\n', ' ')}"
                )
                false
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        // 下载结果（可安装 / 失败）由 HostDownloadNotificationCoordinator 另发通知；
        // 前台进度通知要一起移掉，不能 DETACH 留下一条停在原地的「下载中」。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        runCatching {
            (applicationContext as SillyDroidAppGraphProvider).sillyDroidAppGraph
                .hostNotificationService.remove(foregroundNotificationKey)
        }
        super.onDestroy()
    }

    private fun buildForegroundSpec(
        body: String,
        progress: HostNotificationProgress?,
        ongoing: Boolean
    ): HostNotificationSpec {
        return HostNotificationSpec(
            notificationKey = foregroundNotificationKey,
            kind = HostNotificationKind.APP_UPDATE_DOWNLOAD,
            channel = HostNotificationChannel.DOWNLOADS_INSTALL,
            title = "应用更新下载中",
            body = body,
            progress = progress ?: HostNotificationProgress.Indeterminate,
            ongoing = ongoing,
            autoCancel = false,
            tapSpec = HostNotificationTapSpec(HostNotificationAction.OPEN_MAIN),
            smallIconResId = android.R.drawable.stat_sys_download,
            foregroundBehavior = HostForegroundBehavior(
                serviceNotificationId = foregroundNotificationId,
                foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    null
                }
            )
        )
    }

    private fun buildProgress(downloadedBytes: Long, totalBytes: Long?): HostNotificationProgress {
        return if (totalBytes != null && totalBytes > 0L) {
            HostNotificationProgress.Determinate(
                current = downloadedBytes.coerceAtMost(totalBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                max = totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            )
        } else {
            HostNotificationProgress.Indeterminate
        }
    }

    private fun buildProgressBody(versionName: String, downloadedBytes: Long, totalBytes: Long?): String {
        val percent = if (totalBytes != null && totalBytes > 0L) {
            ((downloadedBytes.coerceAtMost(totalBytes) * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            null
        }
        return if (percent != null) {
            "正在下载更新“$versionName” ($percent%)。"
        } else {
            "正在下载更新“$versionName”。"
        }
    }
}
