package com.calimero.mero.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginOptionsTest {
    @Test
    fun `each mode offers its tabs, cloud first`() {
        assertEquals(listOf(LoginModalTab.CLOUD, LoginModalTab.NODE), LoginMode.CLOUD_AND_NODE.tabs)
        assertEquals(listOf(LoginModalTab.CLOUD), LoginMode.CLOUD_ONLY.tabs)
        assertEquals(listOf(LoginModalTab.NODE), LoginMode.NODE_ONLY.tabs)
    }

    @Test
    fun `old tab lists map to a mode`() {
        assertEquals(LoginMode.CLOUD_AND_NODE, LoginMode.from(listOf(LoginModalTab.NODE, LoginModalTab.CLOUD)))
        assertEquals(LoginMode.CLOUD_AND_NODE, LoginMode.from(listOf(LoginModalTab.CLOUD, LoginModalTab.NODE)))
        assertEquals(LoginMode.CLOUD_ONLY, LoginMode.from(listOf(LoginModalTab.CLOUD)))
        assertEquals(LoginMode.NODE_ONLY, LoginMode.from(listOf(LoginModalTab.NODE)))
        assertEquals(LoginMode.CLOUD_ONLY, LoginMode.from(emptyList()))
    }

    @Test
    fun `every mode maps back to itself`() {
        LoginMode.entries.forEach { assertEquals(it, LoginMode.from(it.tabs)) }
    }

    @Test
    fun `branding defaults match the Swift SDK`() {
        val b = LoginBranding()
        assertEquals("Welcome to Calimero", b.cloudTitle)
        assertEquals("Connect to a node", b.nodeTitle)
        assertEquals("Continue with Calimero Cloud", b.cloudButton)
        assertNull(b.logo)
    }

    @Test
    fun `the default theme is calimero light`() {
        val t = MeroTheme.calimeroLight
        assertEquals(Color(0xFFA5FF11), t.primary)
        assertEquals(Color(0xFFF6F6F3), t.background)
        assertEquals(Color(0xFFFFFFFF), t.surface)
        assertEquals(Color(0xFF6B6B70), t.textTertiary)
        assertEquals(12.dp, t.radius)
        assertEquals(18.dp, t.cardRadius)
        assertEquals(52.dp, t.controlHeight)
        assertEquals(FontFamily.Default, t.titleFontFamily)
        assertTrue(t.showsGlow)
    }

    @Test
    fun `the dark preset is mero-react's palette`() {
        val t = MeroTheme.calimero
        assertEquals(Color(0xFF0D1117), t.background)
        assertEquals(Color(0xFF161B22), t.surface)
        assertEquals(18.dp, t.cardRadius)
        assertEquals(defaultMeroTheme, t)
    }

    @Test
    fun `new tokens derive from the old ones when not given`() {
        val t =
            MeroTheme(
                primary = Color.Red, primaryHover = Color.Red, primaryText = Color.White, background = Color.White,
                backgroundSecondary = Color.Gray, backgroundTertiary = Color.Gray, border = Color.Gray, text = Color.Black,
                textSecondary = Color.DarkGray, error = Color.Red, overlay = Color.Black, radius = 10.dp,
            )
        assertEquals(Color.Gray, t.surface)
        assertEquals(Color.DarkGray, t.textTertiary)
        assertEquals(20.dp, t.cardRadius)
        assertEquals(52.dp, t.controlHeight)
        assertTrue(t.showsGlow)
    }

    @Test
    fun `copy overrides single tokens`() {
        val t = MeroTheme.calimeroLight.copy(primary = Color(0xFF5B5BD6), showsGlow = false)
        assertEquals(Color(0xFF5B5BD6), t.primary)
        assertEquals(MeroTheme.calimeroLight.background, t.background)
        assertEquals(false, t.showsGlow)
    }

    @Test
    fun `sheet colours lay over a theme`() {
        val t = MeroTheme.calimero.with(LoginSheetColors())
        assertEquals(LoginSheetColors().accent, t.primary)
        assertEquals(LoginSheetColors().surface, t.surface)
        assertEquals(LoginSheetColors().text, t.text)
    }
}
