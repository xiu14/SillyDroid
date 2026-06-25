package com.jm.sillydroid.feature.main.status

data class GenerationSession(
    val startedAt: Long,
    val href: String?,
    val type: String?,
    val characterName: String?,
    val lastHeartbeatAt: Long = startedAt,
    val lastPreview: String? = null,
    val outputLength: Int? = null,
)

class GenerationStatusController(
    private val presenter: StatusPresenter,
) {
    private var activeSession: GenerationSession? = null

    fun handle(event: GenerationStatusEvent) {
        when (event) {
            is GenerationStatusEvent.BridgeReady -> presenter.onBridgeReady(event)
            is GenerationStatusEvent.Pending -> {
                val current = activeSession
                if (
                    current != null &&
                    current.type != "pending" &&
                    event.at - current.lastHeartbeatAt <= ACTIVE_SESSION_STALE_MS
                ) {
                    return
                }
                val session = GenerationSession(
                    startedAt = event.at,
                    href = event.href,
                    type = "pending",
                    characterName = event.characterName,
                )
                activeSession = session
                presenter.onGenerationPending(session)
            }

            is GenerationStatusEvent.Started -> {
                val session = GenerationSession(
                    startedAt = event.at,
                    href = event.href,
                    type = event.type,
                    characterName = event.characterName,
                )
                activeSession = session
                presenter.onGenerationStarted(session)
            }

            is GenerationStatusEvent.Streaming -> {
                val current = activeSession ?: GenerationSession(
                    startedAt = event.at,
                    href = null,
                    type = "content",
                    characterName = event.characterName,
                    lastPreview = event.preview,
                    outputLength = event.length,
                )
                val wasContent = current.type == "content"
                val updated = current.copy(
                    type = "content",
                    lastHeartbeatAt = event.at,
                    lastPreview = event.preview ?: current.lastPreview,
                    outputLength = event.length ?: current.outputLength,
                    characterName = event.characterName ?: current.characterName,
                )
                activeSession = updated
                if (wasContent) {
                    presenter.onGenerationHeartbeat(updated)
                } else {
                    presenter.onGenerationContentStarted(updated)
                }
            }

            is GenerationStatusEvent.ContentStarted -> {
                val current = activeSession ?: GenerationSession(
                    startedAt = event.at,
                    href = event.href,
                    type = "content",
                    characterName = event.characterName,
                )
                val updated = current.copy(
                    href = event.href ?: current.href,
                    type = "content",
                    characterName = event.characterName ?: current.characterName,
                    lastHeartbeatAt = event.at,
                    outputLength = event.length ?: current.outputLength,
                )
                activeSession = updated
                presenter.onGenerationContentStarted(updated)
            }

            is GenerationStatusEvent.Ended -> {
                val session = (activeSession ?: GenerationSession(
                    startedAt = event.at,
                    href = event.href,
                    type = null,
                    characterName = event.characterName,
                )).copy(lastHeartbeatAt = event.at)
                activeSession = null
                presenter.onGenerationCompleted(session, event)
            }

            is GenerationStatusEvent.Stopped -> {
                val session = activeSession ?: return
                activeSession = null
                presenter.onGenerationStopped(session, event)
            }
        }
    }

    private companion object {
        private const val ACTIVE_SESSION_STALE_MS = 2 * 60 * 1000L
    }
}
