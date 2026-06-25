package com.jm.sillydroid.feature.main.status

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.jm.sillydroid.domain.settings.TavernShellSettingsRepository

class ForegroundPillStatusPresenter(
    private val pillView: View,
    private val titleView: TextView,
    private val subtitleView: TextView,
    private val settingsRepository: TavernShellSettingsRepository,
    private val scrollChatToLatest: () -> Boolean,
) : StatusPresenter {
    private val handler = Handler(Looper.getMainLooper())
    private var generationKey = 0L
    private var hiddenGenerationKey = 0L
    private var wasVisibleForGeneration = false

    init {
        pillView.setOnClickListener {
            scrollChatToLatest()
        }
        pillView.setOnLongClickListener {
            hideForCurrentGeneration()
            true
        }
    }

    override fun onBridgeReady(event: GenerationStatusEvent.BridgeReady) = Unit

    override fun onGenerationPending(session: GenerationSession) {
        generationKey = session.startedAt
        hiddenGenerationKey = 0L
        wasVisibleForGeneration = true
        show(title = "准备中", subtitle = session.characterName)
    }

    override fun onGenerationStarted(session: GenerationSession) {
        generationKey = session.startedAt
        hiddenGenerationKey = 0L
        wasVisibleForGeneration = true
        show(title = "在想了", subtitle = session.characterName)
    }

    override fun onGenerationContentStarted(session: GenerationSession) {
        ensureGenerationKey(session.lastHeartbeatAt)
        wasVisibleForGeneration = true
        show(title = "在写了", subtitle = session.characterName)
    }

    override fun onGenerationHeartbeat(session: GenerationSession) {
        ensureGenerationKey(session.lastHeartbeatAt)
        wasVisibleForGeneration = true
        show(title = "在写了", subtitle = session.characterName)
    }

    override fun onGenerationCompleted(
        session: GenerationSession,
        event: GenerationStatusEvent.Ended,
    ) {
        if (!wasVisibleForGeneration) return
        ensureGenerationKey(event.at)
        show(title = "已完成", subtitle = event.characterName ?: session.characterName, autoDismissMs = COMPLETE_AUTO_DISMISS_MS)
        wasVisibleForGeneration = false
    }

    override fun onGenerationStopped(
        session: GenerationSession,
        event: GenerationStatusEvent.Stopped,
    ) {
        if (!wasVisibleForGeneration) return
        ensureGenerationKey(event.at)
        show(title = "已中断", subtitle = event.characterName ?: session.characterName, autoDismissMs = COMPLETE_AUTO_DISMISS_MS)
        wasVisibleForGeneration = false
    }

    private fun show(title: String, subtitle: String?, autoDismissMs: Long? = null) {
        handler.removeCallbacksAndMessages(FOREGROUND_PILL_TOKEN)
        if (!settingsRepository.foregroundPillEnabled) {
            hide()
            return
        }
        if (hiddenGenerationKey == generationKey) return

        val key = generationKey
        handler.post {
            if (hiddenGenerationKey == key || !settingsRepository.foregroundPillEnabled) return@post
            titleView.text = "Tavern · $title"
            subtitleView.text = subtitle.orEmpty()
            subtitleView.isVisible = !subtitle.isNullOrBlank()
            pillView.isVisible = true
        }
        if (autoDismissMs != null) {
            handler.postDelayed(
                {
                    if (generationKey == key) {
                        hide()
                    }
                },
                FOREGROUND_PILL_TOKEN,
                autoDismissMs
            )
        }
    }

    private fun hideForCurrentGeneration() {
        hiddenGenerationKey = generationKey
        hide()
    }

    private fun hide() {
        handler.removeCallbacksAndMessages(FOREGROUND_PILL_TOKEN)
        handler.post {
            pillView.isVisible = false
        }
    }

    private fun ensureGenerationKey(fallbackKey: Long) {
        if (generationKey == 0L) {
            generationKey = fallbackKey
        }
    }

    private companion object {
        private val FOREGROUND_PILL_TOKEN = Any()
        private const val COMPLETE_AUTO_DISMISS_MS = 3_000L
    }
}
