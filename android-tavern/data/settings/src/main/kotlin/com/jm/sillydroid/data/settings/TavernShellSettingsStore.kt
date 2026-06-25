package com.jm.sillydroid.data.settings

import android.content.Context
import android.net.Uri
import com.jm.sillydroid.domain.settings.TavernShellBridgeStatus
import com.jm.sillydroid.domain.settings.TavernShellSettingsRepository
import com.jm.sillydroid.domain.settings.TavernShellSiteCredentials

class TavernShellSettingsStore(context: Context) : TavernShellSettingsRepository {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(settingsPreferencesName, Context.MODE_PRIVATE)
    private val credentialsPreferences = appContext.getSharedPreferences(credentialsPreferencesName, Context.MODE_PRIVATE)

    override var foregroundPillEnabled: Boolean
        get() = preferences.getBoolean(foregroundPillEnabledKey, true)
        set(value) {
            preferences.edit().putBoolean(foregroundPillEnabledKey, value).apply()
        }

    override var strongKeepAliveEnabled: Boolean
        get() = preferences.getBoolean(strongKeepAliveEnabledKey, true)
        set(value) {
            preferences.edit().putBoolean(strongKeepAliveEnabledKey, value).apply()
        }

    override var keepAliveNotificationOngoing: Boolean
        get() = preferences.getBoolean(keepAliveNotificationOngoingKey, true)
        set(value) {
            preferences.edit().putBoolean(keepAliveNotificationOngoingKey, value).apply()
        }

    override var pendingKeepAliveMinutes: Long
        get() = preferences.getLong(
            pendingKeepAliveMinutesKey,
            TavernShellSettingsRepository.DEFAULT_PENDING_KEEP_ALIVE_MINUTES
        ).coercePendingKeepAliveMinutes()
        set(value) {
            preferences.edit()
                .putLong(pendingKeepAliveMinutesKey, value.coercePendingKeepAliveMinutes())
                .apply()
        }

    override var wakeLockEnabled: Boolean
        get() = preferences.getBoolean(wakeLockEnabledKey, true)
        set(value) {
            preferences.edit().putBoolean(wakeLockEnabledKey, value).apply()
        }

    override var completionSoundEnabled: Boolean
        get() = preferences.getBoolean(completionSoundEnabledKey, true)
        set(value) {
            preferences.edit().putBoolean(completionSoundEnabledKey, value).apply()
        }

    override var completionMediaSoundEnabled: Boolean
        get() = preferences.getBoolean(completionMediaSoundEnabledKey, false)
        set(value) {
            preferences.edit().putBoolean(completionMediaSoundEnabledKey, value).apply()
        }

    override var contentStartVibrationEnabled: Boolean
        get() = preferences.getBoolean(contentStartVibrationEnabledKey, true)
        set(value) {
            preferences.edit().putBoolean(contentStartVibrationEnabledKey, value).apply()
        }

    override var skipOnlineFontsEnabled: Boolean
        get() = preferences.getBoolean(skipOnlineFontsEnabledKey, true)
        set(value) {
            preferences.edit().putBoolean(skipOnlineFontsEnabledKey, value).apply()
        }

    override var imageProxyEnabled: Boolean
        get() = preferences.getBoolean(imageProxyEnabledKey, true)
        set(value) {
            preferences.edit().putBoolean(imageProxyEnabledKey, value).apply()
        }

    override var imageProxyUrl: String
        get() = normalizeUrl(
            preferences.getString(imageProxyUrlKey, null)
                ?.takeIf { value -> value.isNotBlank() }
                ?: TavernShellSettingsRepository.DEFAULT_IMAGE_PROXY_URL
        )
        set(value) {
            preferences.edit().putString(imageProxyUrlKey, normalizeUrl(value)).apply()
        }

    override var imageProxyExcludedHosts: String
        get() = preferences.getString(imageProxyExcludedHostsKey, "").orEmpty()
        set(value) {
            preferences.edit().putString(imageProxyExcludedHostsKey, value.trim()).apply()
        }

    override fun bridgeStatus(): TavernShellBridgeStatus {
        return TavernShellBridgeStatus(
            mode = preferences.getString(bridgeModeKey, TavernShellBridgeStatus.MODE_UNKNOWN).orEmpty(),
            version = preferences.getString(bridgeVersionKey, "").orEmpty(),
            lastEvent = preferences.getString(bridgeLastEventKey, "").orEmpty(),
            lastEventAt = preferences.getLong(bridgeLastEventAtKey, 0L),
        )
    }

    override fun recordBridgeEvent(eventName: String, isNativeBridge: Boolean, version: String?) {
        preferences.edit()
            .putString(
                bridgeModeKey,
                if (isNativeBridge) TavernShellBridgeStatus.MODE_EXTENSION else TavernShellBridgeStatus.MODE_FALLBACK
            )
            .putString(bridgeLastEventKey, eventName)
            .putLong(bridgeLastEventAtKey, System.currentTimeMillis())
            .apply {
                if (!version.isNullOrBlank()) {
                    putString(bridgeVersionKey, version)
                }
            }
            .apply()
    }

    override fun siteCredentials(host: String, realm: String?): TavernShellSiteCredentials? {
        val normalizedHost = host.trim()
        if (normalizedHost.isBlank()) return null
        val username = credentialsPreferences.getString(credentialsKey(normalizedHost, realm, "username"), null)
            ?: credentialsPreferences.getString(credentialsKey(normalizedHost, null, "username"), null)
        val password = credentialsPreferences.getString(credentialsKey(normalizedHost, realm, "password"), null)
            ?: credentialsPreferences.getString(credentialsKey(normalizedHost, null, "password"), null)
        if (username.isNullOrBlank() || password.isNullOrBlank()) return null
        return TavernShellSiteCredentials(username = username, password = password)
    }

    override fun putSiteCredentials(
        host: String,
        realm: String?,
        credentials: TavernShellSiteCredentials
    ) {
        val normalizedHost = host.trim()
        if (normalizedHost.isBlank()) return
        credentialsPreferences.edit()
            .putString(credentialsKey(normalizedHost, realm, "username"), credentials.username)
            .putString(credentialsKey(normalizedHost, realm, "password"), credentials.password)
            .apply()
    }

    override fun clearSiteCredentials(host: String) {
        val prefix = "${host.trim()}|"
        if (prefix == "|") return
        val keys = credentialsPreferences.all.keys.filter { key -> key.startsWith(prefix) }
        if (keys.isEmpty()) return
        credentialsPreferences.edit().apply {
            keys.forEach(::remove)
        }.apply()
    }

    override fun normalizeUrl(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return TavernShellSettingsRepository.DEFAULT_IMAGE_PROXY_URL
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }

    override fun hostOf(url: String): String {
        return Uri.parse(normalizeUrl(url)).host.orEmpty()
    }

    private fun credentialsKey(host: String, realm: String?, field: String): String {
        return "${host.trim()}|${realm.orEmpty().trim()}|$field"
    }

    private fun Long.coercePendingKeepAliveMinutes(): Long {
        return coerceIn(
            TavernShellSettingsRepository.MIN_PENDING_KEEP_ALIVE_MINUTES,
            TavernShellSettingsRepository.MAX_PENDING_KEEP_ALIVE_MINUTES
        )
    }

    private companion object {
        private const val settingsPreferencesName = "tavern-shell-settings"
        private const val credentialsPreferencesName = "tavern-shell-http-auth"
        private const val foregroundPillEnabledKey = "foreground-pill-enabled"
        private const val strongKeepAliveEnabledKey = "strong-keep-alive-enabled"
        private const val keepAliveNotificationOngoingKey = "keep-alive-notification-ongoing"
        private const val pendingKeepAliveMinutesKey = "pending-keep-alive-minutes"
        private const val wakeLockEnabledKey = "wake-lock-enabled"
        private const val completionSoundEnabledKey = "completion-sound-enabled"
        private const val completionMediaSoundEnabledKey = "completion-media-sound-enabled"
        private const val contentStartVibrationEnabledKey = "content-start-vibration-enabled"
        private const val skipOnlineFontsEnabledKey = "skip-online-fonts-enabled"
        private const val imageProxyEnabledKey = "image-proxy-enabled"
        private const val imageProxyUrlKey = "image-proxy-url"
        private const val imageProxyExcludedHostsKey = "image-proxy-excluded-hosts"
        private const val bridgeModeKey = "bridge-mode"
        private const val bridgeVersionKey = "bridge-version"
        private const val bridgeLastEventKey = "bridge-last-event"
        private const val bridgeLastEventAtKey = "bridge-last-event-at"
    }
}
