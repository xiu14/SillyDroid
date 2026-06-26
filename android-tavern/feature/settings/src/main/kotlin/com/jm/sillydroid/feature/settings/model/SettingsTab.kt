package com.jm.sillydroid.feature.settings.model

/**
 * 设置页新增终端后，所有页签切换必须走命名枚举，避免继续散落 0/1/2/3 这类魔法数字，
 * 否则后续再插页签时很容易把日志刷新、校验跳转等联动逻辑整体错位。
 * “关于”已经改成标题右侧入口，所以它保留页面语义，但不再占用 tab strip 的位置。
 */
enum class SettingsTab(val tabPosition: Int?) {
    DATA(0),
    TAVERN_SHELL(1),
    BACKUP(2),
    SETTINGS(3),
    EXTENSIONS(4),
    TERMINAL(5),
    LOGS(6),
    ABOUT(null);

    companion object {
        fun fromTabPosition(position: Int): SettingsTab {
            return entries.firstOrNull { tab -> tab.tabPosition == position } ?: DATA
        }
    }
}
