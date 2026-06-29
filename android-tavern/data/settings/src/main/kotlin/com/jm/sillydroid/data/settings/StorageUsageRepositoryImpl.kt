package com.jm.sillydroid.data.settings

import android.content.Context
import com.jm.sillydroid.domain.storage.StorageCleanupAction
import com.jm.sillydroid.domain.storage.StorageCleanupResult
import com.jm.sillydroid.domain.storage.StorageUsageItem
import com.jm.sillydroid.domain.storage.StorageUsageItemId
import com.jm.sillydroid.domain.storage.StorageUsageRepository
import com.jm.sillydroid.domain.storage.StorageUsageSnapshot
import java.io.File

class StorageUsageRepositoryImpl(context: Context) : StorageUsageRepository {
    private val appContext = context.applicationContext

    override fun snapshot(): StorageUsageSnapshot {
        val paths = StoragePaths.from(appContext)
        val items = listOf(
            StorageUsageItem(
                id = StorageUsageItemId.APP_PRIVATE_DATA,
                sizeBytes = safeSizeOf(paths.appDataDir),
                path = paths.appDataDir.absolutePath
            ),
            StorageUsageItem(
                id = StorageUsageItemId.TAVERN_USER_DATA,
                sizeBytes = safeSizeOf(paths.tavernDataDir),
                path = paths.tavernDataDir.absolutePath
            ),
            StorageUsageItem(
                id = StorageUsageItemId.CHAT_HISTORY,
                sizeBytes = safeSizeOf(paths.chatDirs),
                path = paths.chatDirs.joinToString(separator = "\n") { it.absolutePath }
            ),
            StorageUsageItem(
                id = StorageUsageItemId.CHARACTERS,
                sizeBytes = safeSizeOf(paths.charactersDir),
                path = paths.charactersDir.absolutePath
            ),
            StorageUsageItem(
                id = StorageUsageItemId.AVATARS_AND_BACKGROUNDS,
                sizeBytes = safeSizeOf(paths.avatarAndBackgroundDirs),
                path = paths.avatarAndBackgroundDirs.joinToString(separator = "\n") { it.absolutePath }
            ),
            StorageUsageItem(
                id = StorageUsageItemId.WORLDS,
                sizeBytes = safeSizeOf(paths.worldsDir),
                path = paths.worldsDir.absolutePath
            ),
            StorageUsageItem(
                id = StorageUsageItemId.EXTENSIONS_AND_PLUGINS,
                sizeBytes = safeSizeOf(paths.extensionAndPluginDirs),
                path = paths.extensionAndPluginDirs.joinToString(separator = "\n") { it.absolutePath }
            ),
            StorageUsageItem(
                id = StorageUsageItemId.SERVER_RUNTIME,
                sizeBytes = safeSizeOf(paths.serverDir),
                path = paths.serverDir.absolutePath
            ),
            StorageUsageItem(
                id = StorageUsageItemId.HOST_RUNTIME,
                sizeBytes = safeSizeOf(paths.hostRuntimeDirs),
                path = paths.hostRuntimeDirs.joinToString(separator = "\n") { it.absolutePath }
            ),
            StorageUsageItem(
                id = StorageUsageItemId.LOGS,
                sizeBytes = safeSizeOf(paths.logsDir),
                path = paths.logsDir.absolutePath,
                cleanupAction = StorageCleanupAction.CLEAR_LOGS
            ),
            StorageUsageItem(
                id = StorageUsageItemId.WEBVIEW_DATA,
                sizeBytes = safeSizeOf(paths.webViewDirs),
                path = paths.webViewDirs.joinToString(separator = "\n") { it.absolutePath },
                cleanupAction = StorageCleanupAction.CLEAR_BROWSER_DATA
            ),
            StorageUsageItem(
                id = StorageUsageItemId.IMAGE_PROXY_CACHE,
                sizeBytes = safeSizeOf(paths.imageProxyCacheDir),
                path = paths.imageProxyCacheDir.absolutePath,
                cleanupAction = StorageCleanupAction.CLEAR_IMAGE_PROXY_CACHE
            ),
            StorageUsageItem(
                id = StorageUsageItemId.TEMPORARY_FILES,
                sizeBytes = safeSizeOf(paths.temporaryDirs),
                path = paths.temporaryDirs.joinToString(separator = "\n") { it.absolutePath },
                cleanupAction = StorageCleanupAction.CLEAR_TEMPORARY_FILES
            ),
            StorageUsageItem(
                id = StorageUsageItemId.LOCAL_BACKUPS,
                sizeBytes = safeSizeOf(paths.localBackupDir),
                path = paths.localBackupDir.absolutePath,
                cleanupAction = StorageCleanupAction.CLEAN_REMOTE_BACKUP_DUPLICATES
            )
        )
        return StorageUsageSnapshot(
            items = items,
            scannedAtEpochMillis = System.currentTimeMillis()
        )
    }

    override fun clearImageProxyCache(): StorageCleanupResult {
        return clearDirectories(listOf(StoragePaths.from(appContext).imageProxyCacheDir))
    }

    override fun clearTemporaryFiles(): StorageCleanupResult {
        return clearDirectories(StoragePaths.from(appContext).temporaryDirs)
    }

    private fun clearDirectories(directories: List<File>): StorageCleanupResult {
        val beforeBytes = safeSizeOf(directories)
        directories.forEach { directory ->
            runCatching {
                if (directory.exists()) {
                    directory.deleteRecursively()
                }
                directory.mkdirs()
            }
        }
        val afterBytes = safeSizeOf(directories)
        return StorageCleanupResult(
            freedBytes = (beforeBytes - afterBytes).coerceAtLeast(0L)
        )
    }

    private fun safeSizeOf(files: List<File>): Long {
        return files.sumOf(::safeSizeOf)
    }

    private fun safeSizeOf(file: File): Long {
        return runCatching { sizeOf(file) }.getOrDefault(0L)
    }

    private fun sizeOf(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length().coerceAtLeast(0L)
        val children = file.listFiles() ?: return 0L
        return children.sumOf(::sizeOf)
    }

    private data class StoragePaths(
        val appDataDir: File,
        val dataRoot: File,
        val serverDir: File,
        val serverDataDir: File,
        val tavernDataDir: File,
        val defaultUserDir: File,
        val chatDirs: List<File>,
        val charactersDir: File,
        val avatarAndBackgroundDirs: List<File>,
        val worldsDir: File,
        val extensionAndPluginDirs: List<File>,
        val hostRuntimeDirs: List<File>,
        val logsDir: File,
        val webViewDirs: List<File>,
        val imageProxyCacheDir: File,
        val temporaryDirs: List<File>,
        val localBackupDir: File
    ) {
        companion object {
            fun from(context: Context): StoragePaths {
                val filesDir = context.filesDir
                val appDataDir = context.dataDir
                val bootstrapRoot = File(filesDir, "android-tavern/bootstrap")
                val dataRoot = File(filesDir, "android-tavern/data")
                val serverDataDir = File(dataRoot, "server")
                val tavernDataDir = File(serverDataDir, "data")
                val defaultUserDir = File(tavernDataDir, "default-user")
                val cacheDir = context.cacheDir
                return StoragePaths(
                    appDataDir = appDataDir,
                    dataRoot = dataRoot,
                    serverDir = File(bootstrapRoot, "server"),
                    serverDataDir = serverDataDir,
                    tavernDataDir = tavernDataDir,
                    defaultUserDir = defaultUserDir,
                    chatDirs = listOf(
                        File(defaultUserDir, "chats"),
                        File(defaultUserDir, "group chats")
                    ),
                    charactersDir = File(defaultUserDir, "characters"),
                    avatarAndBackgroundDirs = listOf(
                        File(defaultUserDir, "User Avatars"),
                        File(defaultUserDir, "backgrounds"),
                        File(defaultUserDir, "assets")
                    ),
                    worldsDir = File(defaultUserDir, "worlds"),
                    extensionAndPluginDirs = listOf(
                        File(serverDataDir, "extensions"),
                        File(defaultUserDir, "extensions"),
                        File(serverDataDir, "plugins")
                    ),
                    hostRuntimeDirs = listOf(
                        File(filesDir, "usr"),
                        File(bootstrapRoot, "rootfs")
                    ),
                    logsDir = File(filesDir, "android-tavern/logs"),
                    webViewDirs = listOf(
                        File(appDataDir, "app_webview"),
                        File(cacheDir, "WebView"),
                        File(cacheDir, "webview")
                    ).distinctBy { it.absolutePath },
                    imageProxyCacheDir = File(cacheDir, "image-proxy-cache"),
                    temporaryDirs = listOf(
                        File(cacheDir, "bootstrap-settings"),
                        File(cacheDir, "blob-download-chunks"),
                        File(cacheDir, "gecko-file-prompts"),
                        File(cacheDir, "host-log-upload")
                    ),
                    localBackupDir = File(dataRoot, "backups/st-remote-backup")
                )
            }
        }
    }
}
