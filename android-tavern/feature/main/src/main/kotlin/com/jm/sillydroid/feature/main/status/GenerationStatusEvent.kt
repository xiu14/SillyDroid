package com.jm.sillydroid.feature.main.status

import org.json.JSONObject

sealed interface GenerationStatusEvent {
    data class BridgeReady(
        val at: Long,
        val href: String?,
    ) : GenerationStatusEvent

    data class Pending(
        val at: Long,
        val href: String?,
        val characterName: String?,
    ) : GenerationStatusEvent

    data class Started(
        val at: Long,
        val href: String?,
        val type: String?,
        val characterName: String?,
    ) : GenerationStatusEvent

    data class Streaming(
        val at: Long,
        val preview: String?,
        val length: Int?,
        val characterName: String?,
    ) : GenerationStatusEvent

    data class ContentStarted(
        val at: Long,
        val href: String?,
        val length: Int?,
        val characterName: String?,
    ) : GenerationStatusEvent

    data class Ended(
        val at: Long,
        val href: String?,
        val messageId: String?,
        val characterName: String?,
    ) : GenerationStatusEvent

    data class Stopped(
        val at: Long,
        val href: String?,
        val characterName: String?,
    ) : GenerationStatusEvent

    companion object {
        fun from(name: String, payloadJson: String?): GenerationStatusEvent? {
            val payload = payloadJson?.takeIf { it.isNotBlank() }?.let(::JSONObject)
            val at = payload?.optLong("at")?.takeIf { it > 0 } ?: System.currentTimeMillis()

            return when (name) {
                "st_bridge_ready" -> BridgeReady(at, payload?.optString("href")?.takeIf { it.isNotBlank() })
                "generation_pending" -> Pending(
                    at = at,
                    href = payload?.optString("href")?.takeIf { it.isNotBlank() },
                    characterName = payload?.optString("characterName")?.takeIf { it.isNotBlank() },
                )

                "generation_started" -> Started(
                    at = at,
                    href = payload?.optString("href")?.takeIf { it.isNotBlank() },
                    type = payload?.optString("type")?.takeIf { it.isNotBlank() },
                    characterName = payload?.optString("characterName")?.takeIf { it.isNotBlank() },
                )

                "generation_streaming" -> Streaming(
                    at = at,
                    preview = payload?.optString("preview")?.takeIf { it.isNotBlank() },
                    length = payload?.optInt("length")?.takeIf { it >= 0 },
                    characterName = payload?.optString("characterName")?.takeIf { it.isNotBlank() },
                )

                "generation_content_started" -> ContentStarted(
                    at = at,
                    href = payload?.optString("href")?.takeIf { it.isNotBlank() },
                    length = payload?.optInt("length")?.takeIf { it >= 0 },
                    characterName = payload?.optString("characterName")?.takeIf { it.isNotBlank() },
                )

                "generation_ended" -> Ended(
                    at = at,
                    href = payload?.optString("href")?.takeIf { it.isNotBlank() },
                    messageId = payload?.opt("messageId")?.toString()?.takeIf { it.isNotBlank() },
                    characterName = payload?.optString("characterName")?.takeIf { it.isNotBlank() },
                )

                "generation_stopped" -> Stopped(
                    at = at,
                    href = payload?.optString("href")?.takeIf { it.isNotBlank() },
                    characterName = payload?.optString("characterName")?.takeIf { it.isNotBlank() },
                )

                else -> null
            }
        }
    }
}
