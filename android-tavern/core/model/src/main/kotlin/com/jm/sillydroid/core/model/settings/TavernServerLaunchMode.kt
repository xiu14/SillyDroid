package com.jm.sillydroid.core.model.settings

/**
 * Tavern 服务启动时宿主命令环境的暴露模式。
 *
 * AUTO 先按快速模式启动，待 HTTP ready 后再动态补齐 Git；
 * FAST 全程隐藏 Git；
 * FULL 从启动开始就暴露完整 Git 环境。
 */
enum class TavernServerLaunchMode {
    AUTO,
    FAST,
    FULL;

    val hostCommandProfile: String
        get() = if (this == FULL) "full" else "server-fast"

    val shouldInjectGitAfterHttpReady: Boolean
        get() = this == AUTO

    companion object {
        fun fromStorageValue(rawValue: String?): TavernServerLaunchMode {
            return entries.firstOrNull { entry ->
                entry.name.equals(rawValue.orEmpty().trim(), ignoreCase = true)
            } ?: AUTO
        }
    }
}
