package com.jm.sillydroid.feature.main.ui.home.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupSurfacePaletteTest {

    @Test
    fun `parses opaque hex colors reported by the page`() {
        assertEquals(0xFFDED4BF.toInt(), parseOpaqueHexColor("#DED4BF"))
        assertEquals(0xFF1A2B3C.toInt(), parseOpaqueHexColor(" #1a2b3c "))
        assertNull(parseOpaqueHexColor(null))
        assertNull(parseOpaqueHexColor(""))
        assertNull(parseOpaqueHexColor("#abc"))
        assertNull(parseOpaqueHexColor("#11223344"))
        assertNull(parseOpaqueHexColor("rgba(222, 212, 191, 1)"))
    }

    @Test
    fun `warm light theme is light and default dark themes are dark`() {
        assertTrue(isLightSurface(0xFFDED4BF.toInt()))
        assertTrue(isLightSurface(0xFFF7F7F7.toInt()))
        assertFalse(isLightSurface(0xFF171717.toInt()))
        assertFalse(isLightSurface(0xFF111827.toInt()))
    }

    @Test
    fun `blend mixes each channel and stays opaque`() {
        assertEquals(0xFFDED4BF.toInt(), blendColors(0xFFDED4BF.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), blendColors(0xFFDED4BF.toInt(), 0xFFFFFFFF.toInt(), 1f))
        assertEquals(0xFF7F7F7F.toInt(), blendColors(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
        assertEquals(0xFF000000.toInt(), blendColors(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), -1f))
    }
}
