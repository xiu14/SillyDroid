package com.jm.sillydroid.data.settings

import com.jm.sillydroid.domain.backup.RemoteBackupConfig
import com.jm.sillydroid.domain.backup.RemoteBackupConfigDraft
import com.jm.sillydroid.domain.backup.RemoteBackupDeleteResult
import com.jm.sillydroid.domain.backup.RemoteBackupHealth
import com.jm.sillydroid.domain.backup.RemoteBackupItem
import com.jm.sillydroid.domain.backup.RemoteBackupList
import com.jm.sillydroid.domain.backup.RemoteBackupRepository
import com.jm.sillydroid.domain.backup.RemoteBackupRestoreResult
import com.jm.sillydroid.domain.backup.RemoteBackupResult
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

class RemoteBackupRepositoryImpl(
    private val baseUrl: String = defaultBaseUrl,
) : RemoteBackupRepository {
    override suspend fun health(): RemoteBackupHealth {
        val json = request(method = "GET", path = "/health")
        return RemoteBackupHealth(
            dataDir = json.optString("dataDir"),
            backupDir = json.optString("backupDir"),
            r2Configured = json.optBoolean("r2Configured")
        )
    }

    override suspend fun createBackup(): RemoteBackupResult {
        val json = request(method = "POST", path = "/backup", readTimeoutMs = longOperationTimeoutMs)
        return RemoteBackupResult(
            fileName = json.optString("file"),
            warning = json.optString("warning")
        )
    }

    override suspend fun listBackups(): RemoteBackupList {
        val json = request(method = "GET", path = "/list", readTimeoutMs = longOperationTimeoutMs)
        val itemsJson = json.optJSONArray("items")
        val items = buildList {
            if (itemsJson == null) return@buildList
            for (index in 0 until itemsJson.length()) {
                val item = itemsJson.optJSONObject(index) ?: continue
                val name = item.optString("name")
                if (name.isBlank()) continue
                add(
                    RemoteBackupItem(
                        name = name,
                        sizeBytes = item.optLong("size"),
                        modifiedAt = item.optString("mtime"),
                        local = item.optBoolean("local"),
                        remote = item.optBoolean("remote")
                    )
                )
            }
        }
        return RemoteBackupList(
            items = items,
            warning = json.optString("warning")
        )
    }

    override suspend fun restoreBackup(name: String): RemoteBackupRestoreResult {
        val json = request(
            method = "POST",
            path = "/restore",
            body = JSONObject().put("name", name),
            readTimeoutMs = longOperationTimeoutMs
        )
        return RemoteBackupRestoreResult(
            restoreTargetDir = json.optString("restoreTargetDir")
        )
    }

    override suspend fun deleteBackup(name: String): RemoteBackupDeleteResult {
        val json = request(
            method = "DELETE",
            path = "/delete?name=${name.urlEncoded()}",
            readTimeoutMs = longOperationTimeoutMs
        )
        return RemoteBackupDeleteResult(
            deletedLocal = json.optBoolean("local"),
            deletedRemote = json.optBoolean("remote")
        )
    }

    override suspend fun logs(limit: Int): List<String> {
        val boundedLimit = limit.coerceIn(1, 2000)
        val json = request(method = "GET", path = "/logs?limit=$boundedLimit")
        val lines = json.optJSONArray("lines") ?: return emptyList()
        return buildList {
            for (index in 0 until lines.length()) {
                add(lines.optString(index))
            }
        }
    }

    override suspend fun clearLogs() {
        request(method = "DELETE", path = "/logs")
    }

    override suspend fun config(): RemoteBackupConfig {
        val json = request(method = "GET", path = "/config")
        val config = json.optJSONObject("config") ?: JSONObject()
        return RemoteBackupConfig(
            importPort = config.optInt("importPort", defaultImportPort),
            importToken = config.optString("importToken"),
            r2AccountId = config.optString("r2AccountId"),
            r2Bucket = config.optString("r2Bucket"),
            r2AccessKeyId = config.optString("r2AccessKeyId"),
            r2Prefix = config.optString("r2Prefix"),
            hasR2SecretAccessKey = config.optBoolean("hasR2SecretAccessKey"),
            r2Configured = config.optBoolean("r2Configured")
        )
    }

    override suspend fun saveConfig(draft: RemoteBackupConfigDraft) {
        val body = JSONObject()
            .put("r2AccountId", draft.r2AccountId)
            .put("r2Bucket", draft.r2Bucket)
            .put("r2AccessKeyId", draft.r2AccessKeyId)
            .put("r2Prefix", draft.r2Prefix)
            .put("clearR2Config", draft.clearR2Config)
        draft.r2SecretAccessKey?.let { secret ->
            body.put("r2SecretAccessKey", secret)
        }
        request(method = "POST", path = "/config", body = body)
    }

    override suspend fun resetImportToken(): String {
        val json = request(
            method = "POST",
            path = "/config",
            body = JSONObject().put("resetImportToken", true)
        )
        val config = json.optJSONObject("config") ?: JSONObject()
        return config.optString("importToken")
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        readTimeoutMs: Int = defaultReadTimeoutMs,
    ): JSONObject {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = defaultConnectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }

            val statusCode = connection.responseCode
            val responseText = runCatching {
                val stream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: connection.inputStream
                }
                stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrDefault("")
            val json = responseText
                .takeIf { it.isNotBlank() }
                ?.let { text -> runCatching { JSONObject(text) }.getOrNull() }
                ?: JSONObject()

            if (statusCode !in 200..299) {
                throw IOException(json.optString("error").ifBlank { "HTTP $statusCode" })
            }
            if (json.has("ok") && !json.optBoolean("ok")) {
                throw IOException(json.optString("error").ifBlank { "remote backup request failed" })
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun String.urlEncoded(): String {
        return URLEncoder.encode(this, "UTF-8").replace("+", "%20")
    }

    private companion object {
        private const val defaultBaseUrl = "http://127.0.0.1:8787"
        private const val defaultImportPort = 8788
        private const val defaultConnectTimeoutMs = 5000
        private const val defaultReadTimeoutMs = 15000
        private const val longOperationTimeoutMs = 180000
    }
}
