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
)

data class RemoteBackupDeleteResult(
    val deletedLocal: Boolean,
    val deletedRemote: Boolean,
)

data class RemoteBackupConfig(
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
}
