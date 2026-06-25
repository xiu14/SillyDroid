package com.jm.sillydroid.feature.main.ui.home.webview

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.jm.sillydroid.domain.settings.TavernShellSettingsRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.security.MessageDigest

class TavernShellWebResourceInterceptor(
    private val context: Context,
    private val settingsRepository: TavernShellSettingsRepository,
    private val localTavernUrl: () -> String,
    private val currentPageUrl: () -> String?,
) {
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val requestUrl = request.url ?: return null
        if (settingsRepository.skipOnlineFontsEnabled) {
            createSkippedOnlineFontResponse(requestUrl)?.let { response ->
                Log.d(LOG_TAG, "[FontSkip] blocked $requestUrl")
                return response
            }
        }
        if (!request.method.equals("GET", ignoreCase = true) || request.isForMainFrame) {
            return null
        }
        return runCatching {
            createProxiedImageResponse(
                requestUrl = requestUrl,
                requestHeaders = request.requestHeaders.orEmpty(),
            )
        }.onFailure { error ->
            Log.w(LOG_TAG, "[ImageProxy] intercept failed url=$requestUrl", error)
        }.getOrNull()
    }

    private fun createSkippedOnlineFontResponse(url: Uri): WebResourceResponse? {
        val mimeType = when (url.host?.lowercase()) {
            "fonts.googleapis.com" -> "text/css"
            "fonts.gstatic.com" -> "font/woff2"
            else -> return null
        }
        return WebResourceResponse(
            mimeType,
            if (mimeType == "text/css") "UTF-8" else null,
            200,
            "OK",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "max-age=86400",
            ),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    private fun createProxiedImageResponse(
        requestUrl: Uri,
        requestHeaders: Map<String, String>,
    ): WebResourceResponse? {
        if (!settingsRepository.imageProxyEnabled) return null
        if (!requestUrl.isHttpUrl()) return null
        if (!requestUrl.looksLikeImageRequest(requestHeaders)) return null

        val requestHost = requestUrl.host?.normalizedHost().orEmpty()
        val proxyUrl = runCatching { settingsRepository.imageProxyUrl }.getOrNull() ?: return null
        val excludedHosts = buildImageProxyExcludedHosts(proxyUrl)

        if (requestHost.isBlank()) return null
        if (requestHost.isLocalOrPrivateHost()) return null
        if (excludedHosts.any { rule -> requestHost.matchesHostRule(rule) }) return null
        if (requestHost == "fonts.googleapis.com" || requestHost == "fonts.gstatic.com") return null

        val cacheKey = requestUrl.toString().sha256Hex()
        createCachedImageResponse(cacheKey, allowExpired = false)?.let { response ->
            Log.d(LOG_TAG, "[ImageProxy] cache hit $requestUrl")
            return response
        }

        val proxiedUrl = Uri.parse(proxyUrl)
            .buildUpon()
            .appendQueryParameter("url", requestUrl.toString())
            .build()
            .toString()

        return runCatching {
            val connection = java.net.URL(proxiedUrl).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = IMAGE_PROXY_CONNECT_TIMEOUT_MS
            connection.readTimeout = IMAGE_PROXY_READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", requestHeaders.findHeader("Accept") ?: "image/avif,image/webp,image/*,*/*")
            connection.setRequestProperty("User-Agent", requestHeaders.findHeader("User-Agent") ?: "Mozilla/5.0 Tavern/1.0")

            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                connection.disconnect()
                Log.w(LOG_TAG, "[ImageProxy] upstream status=$statusCode url=$requestUrl")
                error("image proxy upstream status=$statusCode")
            }

            val contentTypeHeader = connection.contentType.orEmpty()
            val mimeType = contentTypeHeader.substringBefore(';').trim().ifBlank { "image/*" }
            if (!mimeType.startsWith("image/") && mimeType != "image/svg+xml") {
                connection.disconnect()
                Log.w(LOG_TAG, "[ImageProxy] non-image type=$mimeType url=$requestUrl")
                error("image proxy returned non-image type=$mimeType")
            }

            val dataFile = writeImageProxyCache(
                cacheKey = cacheKey,
                mimeType = mimeType,
                connection = connection,
            )
            Log.d(LOG_TAG, "[ImageProxy] proxied and cached $requestUrl")
            WebResourceResponse(
                mimeType,
                null,
                statusCode,
                connection.responseMessage ?: "OK",
                imageProxyHeaders("stored"),
                FileInputStream(dataFile),
            )
        }.onFailure { error ->
            Log.w(LOG_TAG, "[ImageProxy] failed url=$requestUrl", error)
            createCachedImageResponse(cacheKey, allowExpired = true)?.let { response ->
                Log.w(LOG_TAG, "[ImageProxy] using stale cache url=$requestUrl")
                return response
            }
        }.getOrNull()
    }

    private fun createCachedImageResponse(
        cacheKey: String,
        allowExpired: Boolean,
    ): WebResourceResponse? {
        val dataFile = imageProxyCacheDataFile(cacheKey)
        val metaFile = imageProxyCacheMetaFile(cacheKey)
        if (!dataFile.isFile || dataFile.length() <= 0L || !metaFile.isFile) return null
        val ageMs = System.currentTimeMillis() - dataFile.lastModified()
        if (!allowExpired && ageMs > IMAGE_PROXY_CACHE_MAX_AGE_MS) return null

        val mimeType = runCatching { metaFile.readText().trim() }
            .getOrNull()
            ?.takeIf { value -> value.startsWith("image/") || value == "image/svg+xml" }
            ?: return null
        val now = System.currentTimeMillis()
        dataFile.setLastModified(now)
        metaFile.setLastModified(now)

        return WebResourceResponse(
            mimeType,
            null,
            200,
            "OK",
            imageProxyHeaders(if (allowExpired) "stale" else "hit"),
            FileInputStream(dataFile),
        )
    }

    private fun writeImageProxyCache(
        cacheKey: String,
        mimeType: String,
        connection: HttpURLConnection,
    ): File {
        val cacheDir = imageProxyCacheDir()
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val dataFile = imageProxyCacheDataFile(cacheKey)
        val metaFile = imageProxyCacheMetaFile(cacheKey)
        val tempFile = File(cacheDir, "$cacheKey.tmp")

        try {
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }

        if (dataFile.exists()) dataFile.delete()
        if (!tempFile.renameTo(dataFile)) {
            tempFile.copyTo(dataFile, overwrite = true)
            tempFile.delete()
        }
        if (dataFile.length() <= 0L) {
            dataFile.delete()
            metaFile.delete()
            error("image proxy returned empty body")
        }
        metaFile.writeText(mimeType)

        val now = System.currentTimeMillis()
        dataFile.setLastModified(now)
        metaFile.setLastModified(now)
        pruneImageProxyCache(keepFile = dataFile)
        return dataFile
    }

    private fun pruneImageProxyCache(keepFile: File) {
        val dataFiles = imageProxyCacheDir()
            .listFiles { file -> file.isFile && file.extension == IMAGE_PROXY_CACHE_DATA_EXTENSION }
            ?.toList()
            .orEmpty()
        var totalBytes = dataFiles.sumOf { file -> file.length() }
        if (totalBytes <= IMAGE_PROXY_CACHE_MAX_BYTES) return

        dataFiles
            .filterNot { file -> file == keepFile }
            .sortedBy { file -> file.lastModified() }
            .forEach { file ->
                if (totalBytes <= IMAGE_PROXY_CACHE_MAX_BYTES) return@forEach
                totalBytes -= file.length()
                val key = file.nameWithoutExtension
                file.delete()
                imageProxyCacheMetaFile(key).delete()
            }
    }

    private fun buildImageProxyExcludedHosts(proxyUrl: String): Set<String> {
        val automaticHosts = listOfNotNull(
            runCatching { settingsRepository.hostOf(localTavernUrl()) }.getOrNull(),
            runCatching { currentPageUrl()?.let { url -> Uri.parse(url).host } }.getOrNull(),
            runCatching { Uri.parse(proxyUrl).host }.getOrNull(),
        )
        val manualHosts = settingsRepository.imageProxyExcludedHosts
            .split(',', '\n', '\r', '\t', ' ')
        return (automaticHosts + manualHosts)
            .mapNotNull { value -> value.toHostRuleOrNull() }
            .toSet()
    }

    private fun imageProxyCacheDir(): File {
        return File(context.cacheDir, IMAGE_PROXY_CACHE_DIR)
    }

    private fun imageProxyCacheDataFile(cacheKey: String): File {
        return File(imageProxyCacheDir(), "$cacheKey.$IMAGE_PROXY_CACHE_DATA_EXTENSION")
    }

    private fun imageProxyCacheMetaFile(cacheKey: String): File {
        return File(imageProxyCacheDir(), "$cacheKey.$IMAGE_PROXY_CACHE_META_EXTENSION")
    }

    private fun imageProxyHeaders(cacheState: String): Map<String, String> {
        return mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to IMAGE_PROXY_CACHE_CONTROL,
            "X-Tavern-Image-Cache" to cacheState,
        )
    }

    private companion object {
        private const val LOG_TAG = "SillyDroidMain"
        private val IMAGE_EXTENSIONS = setOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".svg", ".bmp", ".ico")
        private const val IMAGE_PROXY_CACHE_DIR = "image-proxy-cache"
        private const val IMAGE_PROXY_CACHE_DATA_EXTENSION = "bin"
        private const val IMAGE_PROXY_CACHE_META_EXTENSION = "mime"
        private const val IMAGE_PROXY_CACHE_CONTROL = "public, max-age=2592000"
        private const val IMAGE_PROXY_CACHE_MAX_BYTES = 128L * 1024L * 1024L
        private const val IMAGE_PROXY_CACHE_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L
        private const val IMAGE_PROXY_CONNECT_TIMEOUT_MS = 8_000
        private const val IMAGE_PROXY_READ_TIMEOUT_MS = 15_000
    }
}

private fun String.toHostRuleOrNull(): String? {
    val trimmed = trim()
    if (trimmed.isBlank()) return null
    val host = runCatching {
        val value = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        Uri.parse(value).host
    }.getOrNull() ?: trimmed
    return host.normalizedHost().ifBlank { null }
}

private fun String.normalizedHost(): String {
    return trim()
        .trim('[', ']')
        .lowercase()
        .removeSuffix(".")
}

private fun String.matchesHostRule(rule: String): Boolean {
    val host = normalizedHost()
    val normalizedRule = rule.normalizedHost()
    if (host.isBlank() || normalizedRule.isBlank()) return false
    return host == normalizedRule ||
        normalizedRule.startsWith(".") && host.endsWith(normalizedRule) ||
        host.endsWith(".$normalizedRule")
}

private fun String.isLocalOrPrivateHost(): Boolean {
    val host = normalizedHost()
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
    if (host == "::1") return true

    val parts = host.split('.').mapNotNull { part -> part.toIntOrNull() }
    if (parts.size != 4 || parts.any { part -> part !in 0..255 }) return false
    return parts[0] == 10 ||
        parts[0] == 127 ||
        parts[0] == 169 && parts[1] == 254 ||
        parts[0] == 172 && parts[1] in 16..31 ||
        parts[0] == 192 && parts[1] == 168
}

private fun Uri.isHttpUrl(): Boolean {
    return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
}

private fun Uri.looksLikeImageRequest(headers: Map<String, String>): Boolean {
    val accept = headers.findHeader("Accept").orEmpty()
    if (accept.contains("image/", ignoreCase = true)) return true

    val path = path.orEmpty().lowercase()
    return IMAGE_EXTENSIONS_FOR_MATCH.any { extension -> path.endsWith(extension) }
}

private fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun Map<String, String>.findHeader(name: String): String? {
    return entries.firstOrNull { entry -> entry.key.equals(name, ignoreCase = true) }?.value
}

private val IMAGE_EXTENSIONS_FOR_MATCH = setOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".svg", ".bmp", ".ico")
