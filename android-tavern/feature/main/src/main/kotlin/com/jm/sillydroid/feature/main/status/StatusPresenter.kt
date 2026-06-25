package com.jm.sillydroid.feature.main.status

interface StatusPresenter {
    fun onBridgeReady(event: GenerationStatusEvent.BridgeReady)

    fun onGenerationPending(session: GenerationSession)

    fun onGenerationStarted(session: GenerationSession)

    fun onGenerationContentStarted(session: GenerationSession)

    fun onGenerationHeartbeat(session: GenerationSession)

    fun onGenerationCompleted(
        session: GenerationSession,
        event: GenerationStatusEvent.Ended,
    )

    fun onGenerationStopped(
        session: GenerationSession,
        event: GenerationStatusEvent.Stopped,
    )
}

class CompositeStatusPresenter(
    private val presenters: List<StatusPresenter>,
) : StatusPresenter {
    override fun onBridgeReady(event: GenerationStatusEvent.BridgeReady) {
        presenters.forEach { it.onBridgeReady(event) }
    }

    override fun onGenerationPending(session: GenerationSession) {
        presenters.forEach { it.onGenerationPending(session) }
    }

    override fun onGenerationStarted(session: GenerationSession) {
        presenters.forEach { it.onGenerationStarted(session) }
    }

    override fun onGenerationContentStarted(session: GenerationSession) {
        presenters.forEach { it.onGenerationContentStarted(session) }
    }

    override fun onGenerationHeartbeat(session: GenerationSession) {
        presenters.forEach { it.onGenerationHeartbeat(session) }
    }

    override fun onGenerationCompleted(session: GenerationSession, event: GenerationStatusEvent.Ended) {
        presenters.forEach { it.onGenerationCompleted(session, event) }
    }

    override fun onGenerationStopped(session: GenerationSession, event: GenerationStatusEvent.Stopped) {
        presenters.forEach { it.onGenerationStopped(session, event) }
    }
}
