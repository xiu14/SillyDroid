package com.jm.sillydroid.feature.main.status

import android.content.Context

class NotificationStatusPresenter(
    private val context: Context,
) : StatusPresenter {
    private var wasVisibleForGeneration = false

    override fun onBridgeReady(event: GenerationStatusEvent.BridgeReady) = Unit

    override fun onGenerationPending(session: GenerationSession) {
        wasVisibleForGeneration = true
        GenerationLiveUpdateService.pending(context, "准备中", session.characterName)
    }

    override fun onGenerationStarted(session: GenerationSession) {
        wasVisibleForGeneration = true
        GenerationLiveUpdateService.start(context, "在想了", session.characterName)
    }

    override fun onGenerationContentStarted(session: GenerationSession) {
        wasVisibleForGeneration = true
        GenerationLiveUpdateService.contentStarted(context, "在写了", session.characterName)
    }

    override fun onGenerationHeartbeat(session: GenerationSession) {
        wasVisibleForGeneration = true
        GenerationLiveUpdateService.heartbeat(context, "在写了", session.characterName)
    }

    override fun onGenerationCompleted(
        session: GenerationSession,
        event: GenerationStatusEvent.Ended,
    ) {
        if (!wasVisibleForGeneration) return
        GenerationLiveUpdateService.complete(context, "已完成", session.characterName ?: event.characterName)
        wasVisibleForGeneration = false
    }

    override fun onGenerationStopped(
        session: GenerationSession,
        event: GenerationStatusEvent.Stopped,
    ) {
        if (!wasVisibleForGeneration) return
        GenerationLiveUpdateService.stop(context, "已中断", session.characterName ?: event.characterName)
        wasVisibleForGeneration = false
    }
}
