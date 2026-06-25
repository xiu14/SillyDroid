package com.jm.sillydroid.feature.main.status

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jm.sillydroid.domain.app.SillyDroidAppGraphProvider
import com.jm.sillydroid.domain.settings.TavernShellSettingsRepository
import com.jm.sillydroid.feature.main.MainActivity

class GenerationLiveUpdateService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastContentStartVibrationAt = 0L
    private val releaseWifiLockRunnable = Runnable { releaseGenerationWifiLock() }
    private val shellSettings: TavernShellSettingsRepository?
        get() = (applicationContext as? SillyDroidAppGraphProvider)
            ?.sillyDroidAppGraph
            ?.tavernShellSettingsRepository

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PENDING -> {
                mainHandler.removeCallbacksAndMessages(STOP_TOKEN)
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "准备中" }
                val characterName = intent.getStringExtra(EXTRA_CHARACTER_NAME)?.takeIf { it.isNotBlank() }
                holdGenerationLocks()
                startLiveForeground(buildLiveNotification(text, 20, LiveState.Preparing, characterName))
                scheduleStop(pendingTimeoutMs(), removeForeground = true)
            }

            ACTION_START -> {
                mainHandler.removeCallbacksAndMessages(STOP_TOKEN)
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "AI 回复生成中" }
                val characterName = intent.getStringExtra(EXTRA_CHARACTER_NAME)?.takeIf { it.isNotBlank() }
                holdGenerationLocks()
                startLiveForeground(buildLiveNotification(text, 60, LiveState.Generating, characterName))
            }

            ACTION_HEARTBEAT -> {
                mainHandler.removeCallbacksAndMessages(STOP_TOKEN)
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "正在输出内容" }
                val characterName = intent.getStringExtra(EXTRA_CHARACTER_NAME)?.takeIf { it.isNotBlank() }
                holdGenerationLocks()
                startLiveForeground(buildLiveNotification(text, 75, LiveState.Generating, characterName))
            }

            ACTION_CONTENT_STARTED -> {
                mainHandler.removeCallbacksAndMessages(STOP_TOKEN)
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "在写了" }
                val characterName = intent.getStringExtra(EXTRA_CHARACTER_NAME)?.takeIf { it.isNotBlank() }
                holdGenerationLocks()
                startLiveForeground(buildLiveNotification(text, 75, LiveState.Generating, characterName))
                vibrateContentStarted()
            }

            ACTION_COMPLETE -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "AI 回复已生成完成" }
                val characterName = intent.getStringExtra(EXTRA_CHARACTER_NAME)?.takeIf { it.isNotBlank() }
                releaseGenerationWakeLock()
                releaseGenerationWifiLock()
                startLiveForeground(buildLiveNotification(text, 100, LiveState.Completed, characterName))
                playCompletionSound()
                scheduleStop(COMPLETED_DISPLAY_MS, removeForeground = true, showDismissal = true)
            }

            ACTION_STOP -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "当前生成已中断" }
                val characterName = intent.getStringExtra(EXTRA_CHARACTER_NAME)?.takeIf { it.isNotBlank() }
                releaseGenerationWakeLock()
                releaseGenerationWifiLock()
                notificationManager().notify(
                    LIVE_NOTIFICATION_ID,
                    buildFallbackNotification(characterName ?: "SillyTavern 已停止", text),
                )
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                scheduleStop(DEFAULT_DISMISS_DELAY_MS)
            }

            ACTION_STOP_KEEP_ALIVE -> {
                mainHandler.removeCallbacksAndMessages(STOP_TOKEN)
                releaseGenerationWakeLock()
                releaseGenerationWifiLock()
                removePromotedLiveUpdate()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseGenerationWakeLock()
        releaseGenerationWifiLock()
        super.onDestroy()
    }

    private fun buildLiveNotification(
        text: String,
        progress: Int,
        state: LiveState,
        characterName: String?,
    ): Notification {
        val stateText = text.ifBlank {
            when (state) {
                LiveState.Preparing -> "准备中"
                LiveState.Generating -> "在写了"
                LiveState.Completed -> "已完成"
            }
        }
        val body = if (characterName.isNullOrBlank()) {
            when (state) {
                LiveState.Preparing -> "SillyTavern 准备生成"
                LiveState.Generating -> "SillyTavern 正在回复"
                LiveState.Completed -> "SillyTavern 回复完成"
            }
        } else {
            characterName
        }
        val builder = Notification.Builder(this, LIVE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setColor(
                when (state) {
                    LiveState.Preparing -> Color.parseColor("#F0B35A")
                    LiveState.Generating -> Color.parseColor("#34C759")
                    LiveState.Completed -> Color.parseColor("#6F869A")
                },
            )
            .setContentTitle(stateText)
            .setContentText(body)
            .setOnlyAlertOnce(true)
            .setOngoing(isLiveNotificationOngoing(state))
            .setContentIntent(createContentIntent())
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())

        buildProgressStyle(progress)?.let { builder.setStyle(it) }

        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
        }
        builder.addExtras(extras)
        invokeBooleanMethod(builder, "setRequestPromotedOngoing", true)
        invokeCharSequenceMethod(builder, "setShortCriticalText", text)

        if (state == LiveState.Completed) {
            builder.setTimeoutAfter(COMPLETED_DISPLAY_MS)
        }

        val notification = builder.build()
        logPromotionState(notification, true)
        return notification
    }

    private fun buildFallbackNotification(
        title: String,
        text: String,
    ): Notification {
        return Notification.Builder(this, FALLBACK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText("SillyTavern 状态更新")
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setContentIntent(createContentIntent())
            .setCategory(Notification.CATEGORY_STATUS)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setAutoCancel(true)
            .setTimeoutAfter(DEFAULT_DISMISS_DELAY_MS)
            .build()
    }

    private fun buildProgressStyle(progress: Int): Notification.Style? {
        return runCatching {
            val style = Notification.ProgressStyle()
            invokeBooleanMethod(style, "setStyledByProgress", true)
            invokeIntMethod(style, "setProgress", progress)
            runCatching {
                style.javaClass.getMethod(
                    "setProgressTrackerIcon",
                    Icon::class.java,
                ).invoke(style, Icon.createWithResource(this, android.R.drawable.stat_notify_sync))
            }
            style
        }.onFailure {
            Log.w("STPromoted", "ProgressStyle unavailable", it)
        }.getOrNull()
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val liveChannel = NotificationChannel(
            LIVE_CHANNEL_ID,
            "实时活动（流体云）",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "SillyTavern 生成中的实时活动通知"
            setShowBadge(false)
            vibrationPattern = longArrayOf(0L)
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val fallbackChannel = NotificationChannel(
            FALLBACK_CHANNEL_ID,
            "SillyTavern 状态",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "SillyTavern 普通状态通知"
            setShowBadge(false)
            vibrationPattern = longArrayOf(0L)
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        runCatching {
            notificationManager().deleteNotificationChannel(LEGACY_LIVE_CHANNEL_ID)
        }
        notificationManager().createNotificationChannel(liveChannel)
        notificationManager().createNotificationChannel(fallbackChannel)
    }

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun scheduleStop(
        delayMs: Long,
        removeForeground: Boolean = false,
        showDismissal: Boolean = false,
    ) {
        mainHandler.removeCallbacksAndMessages(STOP_TOKEN)
        mainHandler.postDelayed(
            {
                if (removeForeground) {
                    if (showDismissal) {
                        dismissPromotedLiveUpdate()
                    } else {
                        removePromotedLiveUpdate()
                    }
                } else {
                    notificationManager().cancel(LIVE_NOTIFICATION_ID)
                    stopSelf()
                }
            },
            STOP_TOKEN,
            delayMs,
        )
    }

    private fun removePromotedLiveUpdate() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        notificationManager().cancel(LIVE_NOTIFICATION_ID)
        stopSelf()
    }

    private fun dismissPromotedLiveUpdate() {
        val dismissal = buildDismissedNotification()
        notificationManager().notify(LIVE_NOTIFICATION_ID, dismissal)

        mainHandler.postDelayed(
            {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notificationManager().cancel(LIVE_NOTIFICATION_ID)
                stopSelf()
            },
            STOP_TOKEN,
            DISMISS_PROPAGATION_MS,
        )
    }

    private fun holdGenerationLocks() {
        if (!isStrongKeepAliveEnabled()) {
            releaseGenerationWakeLock()
            releaseGenerationWifiLock()
            return
        }
        acquireGenerationWakeLock()
        acquireGenerationWifiLock()
    }

    private fun acquireGenerationWakeLock() {
        if (!isWakeLockEnabled()) return

        val current = wakeLock
        if (current?.isHeld == true) return

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Tavern:GenerationWakeLock",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MAX_MS)
        }
    }

    private fun releaseGenerationWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun acquireGenerationWifiLock() {
        if (!isWakeLockEnabled()) return

        mainHandler.removeCallbacks(releaseWifiLockRunnable)
        if (wifiLock?.isHeld == true) {
            mainHandler.postDelayed(releaseWifiLockRunnable, WIFI_LOCK_MAX_MS)
            return
        }

        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "Tavern:GenerationWifiLock",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        mainHandler.postDelayed(releaseWifiLockRunnable, WIFI_LOCK_MAX_MS)
    }

    private fun releaseGenerationWifiLock() {
        mainHandler.removeCallbacks(releaseWifiLockRunnable)
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private fun playCompletionSound() {
        if (!isCompletionSoundEnabled()) return

        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: return
            val ringtone = RingtoneManager.getRingtone(applicationContext, uri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val usage = if (isCompletionMediaSoundEnabled()) {
                    AudioAttributes.USAGE_MEDIA
                } else {
                    AudioAttributes.USAGE_NOTIFICATION
                }
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            ringtone.play()
        }.onFailure {
            Log.w("STPromoted", "Failed to play completion sound", it)
        }
    }

    private fun vibrateContentStarted() {
        if (!isContentStartVibrationEnabled()) return

        runCatching {
            val now = System.currentTimeMillis()
            if (now - lastContentStartVibrationAt < CONTENT_START_VIBRATION_COOLDOWN_MS) return
            lastContentStartVibrationAt = now

            val vibrator = getSystemService(Vibrator::class.java) ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    CONTENT_START_VIBRATION_MS,
                    CONTENT_START_VIBRATION_AMPLITUDE,
                ),
            )
        }.onFailure {
            Log.w("STPromoted", "Failed to vibrate on content start", it)
        }
    }

    private fun notificationManager(): NotificationManager {
        return getSystemService(NotificationManager::class.java)
    }

    private fun startLiveForeground(notification: Notification) {
        val foregroundType = if (Build.VERSION.SDK_INT >= 34) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            LIVE_NOTIFICATION_ID,
            notification,
            foregroundType,
        )
    }

    private fun isLiveNotificationOngoing(state: LiveState): Boolean {
        return state != LiveState.Completed &&
            isStrongKeepAliveEnabled() &&
            isKeepAliveNotificationOngoing()
    }

    private fun pendingTimeoutMs(): Long {
        return if (isStrongKeepAliveEnabled()) {
            shellSettings?.pendingKeepAliveMs ?: DEFAULT_PENDING_KEEP_ALIVE_MS
        } else {
            PENDING_TIMEOUT_MS
        }
    }

    private fun isStrongKeepAliveEnabled(): Boolean {
        return shellSettings?.strongKeepAliveEnabled ?: true
    }

    private fun isKeepAliveNotificationOngoing(): Boolean {
        return shellSettings?.keepAliveNotificationOngoing ?: true
    }

    private fun isWakeLockEnabled(): Boolean {
        return shellSettings?.wakeLockEnabled ?: true
    }

    private fun isCompletionSoundEnabled(): Boolean {
        return shellSettings?.completionSoundEnabled ?: true
    }

    private fun isCompletionMediaSoundEnabled(): Boolean {
        return shellSettings?.completionMediaSoundEnabled ?: false
    }

    private fun isContentStartVibrationEnabled(): Boolean {
        return shellSettings?.contentStartVibrationEnabled ?: true
    }

    private fun logPromotionState(notification: Notification, requestPromoted: Boolean) {
        if (Build.VERSION.SDK_INT < 36) return
        val canPost = runCatching {
            NotificationManager::class.java.getMethod("canPostPromotedNotifications")
                .invoke(notificationManager()) as Boolean
        }.getOrNull()
        val promotable = runCatching {
            Notification::class.java.getMethod("hasPromotableCharacteristics")
                .invoke(notification) as Boolean
        }.getOrNull()
        Log.d("STPromoted", "service requestPromoted=$requestPromoted canPost=$canPost promotable=$promotable")
    }

    private fun invokeBooleanMethod(target: Any, methodName: String, value: Boolean) {
        runCatching {
            target.javaClass.getMethod(methodName, Boolean::class.javaPrimitiveType).invoke(target, value)
        }
    }

    private fun invokeIntMethod(target: Any, methodName: String, value: Int) {
        runCatching {
            target.javaClass.getMethod(methodName, Int::class.javaPrimitiveType).invoke(target, value)
        }
    }

    private fun invokeCharSequenceMethod(target: Any, methodName: String, value: CharSequence) {
        runCatching {
            target.javaClass.getMethod(methodName, CharSequence::class.java).invoke(target, value)
        }
    }

    private fun buildDismissedNotification(): Notification {
        val builder = Notification.Builder(this, LIVE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setColor(Color.parseColor("#6F869A"))
            .setContentTitle("已完成")
            .setContentText("SillyTavern 回复完成")
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setContentIntent(createContentIntent())
            .setCategory(Notification.CATEGORY_STATUS)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setAutoCancel(true)
            .setTimeoutAfter(1)

        val extras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", false)
        }
        builder.addExtras(extras)
        invokeBooleanMethod(builder, "setRequestPromotedOngoing", false)
        invokeCharSequenceMethod(builder, "setShortCriticalText", "已完成")
        return builder.build()
    }

    companion object {
        private const val ACTION_PENDING = "com.jm.sillydroid.action.LIVE_PENDING"
        private const val ACTION_START = "com.jm.sillydroid.action.LIVE_START"
        private const val ACTION_HEARTBEAT = "com.jm.sillydroid.action.LIVE_HEARTBEAT"
        private const val ACTION_CONTENT_STARTED = "com.jm.sillydroid.action.LIVE_CONTENT_STARTED"
        private const val ACTION_COMPLETE = "com.jm.sillydroid.action.LIVE_COMPLETE"
        private const val ACTION_STOP = "com.jm.sillydroid.action.LIVE_STOP"
        private const val ACTION_STOP_KEEP_ALIVE = "com.jm.sillydroid.action.STOP_KEEP_ALIVE"
        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_CHARACTER_NAME = "extra_character_name"
        private const val LIVE_CHANNEL_ID = "st_live_update_v3_silent"
        private const val LEGACY_LIVE_CHANNEL_ID = "st_live_update_v2"
        private const val FALLBACK_CHANNEL_ID = "st_generation_status"
        private const val LIVE_NOTIFICATION_ID = 3001
        private const val DEFAULT_DISMISS_DELAY_MS = 8_000L
        private const val PENDING_TIMEOUT_MS = 30_000L
        private const val COMPLETED_DISPLAY_MS = 10_000L
        private const val DISMISS_PROPAGATION_MS = 300L
        private const val DEFAULT_PENDING_KEEP_ALIVE_MS = 10L * 60L * 1000L
        private const val WAKE_LOCK_MAX_MS = 10 * 60 * 1000L
        private const val WIFI_LOCK_MAX_MS = 10 * 60 * 1000L
        private const val CONTENT_START_VIBRATION_MS = 25L
        private const val CONTENT_START_VIBRATION_AMPLITUDE = 40
        private const val CONTENT_START_VIBRATION_COOLDOWN_MS = 10_000L
        private val STOP_TOKEN = Any()

        fun pending(context: Context, text: String, characterName: String?) {
            startService(context, ACTION_PENDING, text, characterName)
        }

        fun start(context: Context, text: String, characterName: String?) {
            startService(context, ACTION_START, text, characterName)
        }

        fun heartbeat(context: Context, text: String, characterName: String?) {
            startService(context, ACTION_HEARTBEAT, text, characterName)
        }

        fun contentStarted(context: Context, text: String, characterName: String?) {
            startService(context, ACTION_CONTENT_STARTED, text, characterName)
        }

        fun complete(context: Context, text: String, characterName: String?) {
            startService(context, ACTION_COMPLETE, text, characterName)
        }

        fun stop(context: Context, text: String, characterName: String?) {
            startService(context, ACTION_STOP, text, characterName)
        }

        fun stopKeepAlive(context: Context) {
            context.startService(
                Intent(context, GenerationLiveUpdateService::class.java).apply {
                    action = ACTION_STOP_KEEP_ALIVE
                },
            )
        }

        private fun startService(context: Context, action: String, text: String, characterName: String?) {
            val intent = Intent(context, GenerationLiveUpdateService::class.java).apply {
                this.action = action
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_CHARACTER_NAME, characterName)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private enum class LiveState {
        Preparing,
        Generating,
        Completed,
    }
}
