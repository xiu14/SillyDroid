package com.jm.sillydroid.feature.settings.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTabTest {

    @Test
    fun `fromTabPosition maps all supported tab positions`() {
        assertEquals(SettingsTab.DATA, SettingsTab.fromTabPosition(0))
        assertEquals(SettingsTab.TAVERN_SHELL, SettingsTab.fromTabPosition(1))
        assertEquals(SettingsTab.SETTINGS, SettingsTab.fromTabPosition(2))
        assertEquals(SettingsTab.EXTENSIONS, SettingsTab.fromTabPosition(3))
        assertEquals(SettingsTab.TERMINAL, SettingsTab.fromTabPosition(4))
        assertEquals(SettingsTab.LOGS, SettingsTab.fromTabPosition(5))
    }

    @Test
    fun `fromTabPosition falls back to data for unsupported positions and about has no tab slot`() {
        assertEquals(null, SettingsTab.ABOUT.tabPosition)
        assertEquals(SettingsTab.DATA, SettingsTab.fromTabPosition(-1))
        assertEquals(SettingsTab.DATA, SettingsTab.fromTabPosition(6))
        assertEquals(SettingsTab.DATA, SettingsTab.fromTabPosition(7))
    }
}
