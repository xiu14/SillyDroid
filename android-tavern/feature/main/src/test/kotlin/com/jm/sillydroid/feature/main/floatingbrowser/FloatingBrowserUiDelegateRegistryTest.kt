package com.jm.sillydroid.feature.main.floatingbrowser

import com.jm.sillydroid.feature.main.ui.home.bridge.BrowserHostBridgeActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证进程级 bridge 不会持有或调用已经注销的 Activity delegate。 */
class FloatingBrowserUiDelegateRegistryTest {

    /** Activity 注销后 UI 动作停止转发，但浏览器会话和缓存宿主信息仍有效。 */
    @Test
    fun `unregistered activity stops ui calls without deactivating session`() {
        var openSettingsCalls = 0
        val owner = Any()
        val registry = FloatingBrowserUiDelegateRegistry(isBrowserSessionActive = { true })
        registry.register(
            owner = owner,
            delegate = activityActions(
                hostInfo = "{\"hostVersion\":\"test\"}",
                openSettings = { openSettingsCalls += 1 }
            )
        )
        val processActions = registry.createProcessActions()

        processActions.openSettings()
        registry.unregister(owner)
        processActions.openSettings()

        assertEquals(1, openSettingsCalls)
        assertTrue(processActions.isHostActive())
        assertEquals("{\"hostVersion\":\"test\"}", processActions.hostVersionInfoJson())
    }

    /** 自定义壳桥事件必须经进程代理转发，Activity 注销后则安全丢弃。 */
    @Test
    fun `post events follow the active activity delegate`() {
        val received = mutableListOf<Pair<String, String?>>()
        val owner = Any()
        val registry = FloatingBrowserUiDelegateRegistry(isBrowserSessionActive = { true })
        registry.register(
            owner = owner,
            delegate = activityActions(
                hostInfo = "{}",
                openSettings = {},
                postEvent = { name, payloadJson -> received += name to payloadJson }
            )
        )
        val processActions = registry.createProcessActions()

        processActions.postEvent("generation_started", "{\"source\":\"native\"}")
        registry.unregister(owner)
        processActions.postEvent("generation_stopped", null)

        assertEquals(listOf("generation_started" to "{\"source\":\"native\"}"), received)
    }

    /** 创建测试 Activity delegate；未关注的 UI 动作保持空实现。 */
    private fun activityActions(
        hostInfo: String,
        openSettings: () -> Unit,
        postEvent: (String, String?) -> Unit = { _, _ -> }
    ): BrowserHostBridgeActions {
        return BrowserHostBridgeActions(
            isHostActive = { true },
            runOnUiThread = { action -> action() },
            openSettings = openSettings,
            showFloatingLogsBubble = {},
            requestOpenCurrentPageInBrowser = {},
            applyFloatingLogsBubbleEnabled = {},
            applyBrowserPullRefreshEnabled = {},
            applySystemBarsBackgroundColor = {},
            applySystemBarsBackgroundColors = { _, _ -> },
            reloadTavern = {},
            hostVersionInfoJson = { hostInfo },
            postEvent = postEvent
        )
    }
}
