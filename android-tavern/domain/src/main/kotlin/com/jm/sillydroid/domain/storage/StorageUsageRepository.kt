package com.jm.sillydroid.domain.storage

enum class StorageUsageItemId {
    APP_PRIVATE_DATA,
    TAVERN_USER_DATA,
    CHAT_HISTORY,
    CHARACTERS,
    AVATARS_AND_BACKGROUNDS,
    WORLDS,
    EXTENSIONS_AND_PLUGINS,
    SERVER_RUNTIME,
    HOST_RUNTIME,
    LOGS,
    WEBVIEW_DATA,
    IMAGE_PROXY_CACHE,
    TEMPORARY_FILES,
    LOCAL_BACKUPS
}

enum class StorageCleanupAction {
    NONE,
    CLEAR_LOGS,
    CLEAR_BROWSER_DATA,
    CLEAR_IMAGE_PROXY_CACHE,
    CLEAR_TEMPORARY_FILES,
    CLEAN_REMOTE_BACKUP_DUPLICATES
}

data class StorageUsageItem(
    val id: StorageUsageItemId,
    val sizeBytes: Long,
    val path: String,
    val cleanupAction: StorageCleanupAction = StorageCleanupAction.NONE
)

data class StorageUsageSnapshot(
    val items: List<StorageUsageItem>,
    val scannedAtEpochMillis: Long
)

data class StorageCleanupResult(
    val freedBytes: Long
)

interface StorageUsageRepository {
    fun snapshot(): StorageUsageSnapshot
    fun clearImageProxyCache(): StorageCleanupResult
    fun clearTemporaryFiles(): StorageCleanupResult
}
