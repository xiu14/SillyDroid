package com.jm.sillydroid.domain.settings

data class TavernShellBridgeStatus(
    val mode: String,
    val version: String,
    val lastEvent: String,
    val lastEventAt: Long,
) {
    companion object {
        const val MODE_EXTENSION = "extension"
        const val MODE_FALLBACK = "fallback"
        const val MODE_UNKNOWN = "unknown"
    }
}

data class TavernShellSiteCredentials(
    val username: String,
    val password: String,
)

interface TavernShellSettingsRepository {
    var foregroundPillEnabled: Boolean
    var strongKeepAliveEnabled: Boolean
    var keepAliveNotificationOngoing: Boolean
    var pendingKeepAliveMinutes: Long
    var wakeLockEnabled: Boolean
    var completionSoundEnabled: Boolean
    var completionMediaSoundEnabled: Boolean
    var contentStartVibrationEnabled: Boolean
    var skipOnlineFontsEnabled: Boolean
    var imageProxyEnabled: Boolean
    var imageProxyUrl: String
    var imageProxyExcludedHosts: String

    val pendingKeepAliveMs: Long
        get() = pendingKeepAliveMinutes * 60L * 1000L

    fun bridgeStatus(): TavernShellBridgeStatus
    fun recordBridgeEvent(eventName: String, isNativeBridge: Boolean, version: String? = null)
    fun siteCredentials(host: String, realm: String? = null): TavernShellSiteCredentials?
    fun putSiteCredentials(host: String, realm: String? = null, credentials: TavernShellSiteCredentials)
    fun clearSiteCredentials(host: String)
    fun normalizeUrl(value: String): String
    fun hostOf(url: String): String

    companion object {
        const val DEFAULT_IMAGE_PROXY_URL = "https://pic.biatch.party/"
        const val DEFAULT_PENDING_KEEP_ALIVE_MINUTES = 10L
        const val MIN_PENDING_KEEP_ALIVE_MINUTES = 1L
        const val MAX_PENDING_KEEP_ALIVE_MINUTES = 60L
    }
}
