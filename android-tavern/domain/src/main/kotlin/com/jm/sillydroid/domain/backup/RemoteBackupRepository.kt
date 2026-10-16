package com.jm.sillydroid.domain.backup

data class RemoteBackupHealth(
    val dataDir: String,
    val backupDir: String,
    val r2Configured: Boolean,
)

data class RemoteBackupItem(
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: String,
    val local: Boolean,
    val remote: Boolean,
)

data class RemoteBackupList(
    val items: List<RemoteBackupItem>,
    val warning: String,
)

data class RemoteBackupResult(
    val fileName: String,
    val warning: String,
)

data class RemoteBackupRestoreResult(
    val restoreTargetDir: String,
    // 恢复前自动保存的本地快照文件名；目标目录原本为空时没有快照，为空字符串。
    val snapshotName: String = "",
)

data class RemoteBackupDeleteResult(
    val deletedLocal: Boolean,
    val deletedRemote: Boolean,
)

data class RemoteBackupConfig(
    val importPort: Int,
    val importToken: String,
    val r2AccountId: String,
    val r2Bucket: String,
    val r2AccessKeyId: String,
    val r2Prefix: String,
    val hasR2SecretAccessKey: Boolean,
    val r2Configured: Boolean,
)

data class RemoteBackupConfigDraft(
    val r2AccountId: String = "",
    val r2Bucket: String = "",
    val r2AccessKeyId: String = "",
    val r2SecretAccessKey: String? = null,
    val r2Prefix: String = "",
    val clearR2Config: Boolean = false,
)

// 远程编辑（测试版）连接状态；state 取值：unpaired / disabled / connecting / connected / disconnected / error。
data class RemoteEditStatus(
    val paired: Boolean,
    val url: String,
    val state: String,
    val lastError: String,
)

// active=false 表示未配对，调用方应放慢轮询。
data class RemoteEditReloadSignal(
    val seq: Long,
    val active: Boolean,
)

interface RemoteBackupRepository {
    suspend fun health(): RemoteBackupHealth
    suspend fun createBackup(): RemoteBackupResult
    suspend fun listBackups(): RemoteBackupList
    suspend fun restoreBackup(name: String): RemoteBackupRestoreResult
    suspend fun deleteBackup(name: String): RemoteBackupDeleteResult
    suspend fun logs(limit: Int = 300): List<String>
    suspend fun clearLogs()
    suspend fun config(): RemoteBackupConfig
    suspend fun saveConfig(draft: RemoteBackupConfigDraft)
    suspend fun resetImportToken(): String
    suspend fun remoteEditStatus(): RemoteEditStatus
    suspend fun pairRemoteEdit(url: String, code: String): RemoteEditStatus
    suspend fun unpairRemoteEdit(): RemoteEditStatus
    suspend fun waitRemoteEditReload(since: Long): RemoteEditReloadSignal
}
