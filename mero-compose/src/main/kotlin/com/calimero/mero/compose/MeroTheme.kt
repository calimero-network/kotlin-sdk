package com.calimero.mero.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Theme tokens for the mero-compose components — mero-react `MeroTheme`, with the same fields.
 * Provide one with [ProvideMeroTheme]; components read [LocalMeroTheme]. Override any token with
 * `copy(...)`.
 */
@Immutable
data class MeroTheme(
    /** Primary accent / call to action. */
    val primary: Color,
    val primaryHover: Color,
    /** Text drawn on [primary]. */
    val primaryText: Color,
    /** The main surface (sheets, dialogs). */
    val background: Color,
    /** The secondary surface (fields, chips). */
    val backgroundSecondary: Color,
    /** Subtle elevation. */
    val backgroundTertiary: Color,
    val border: Color,
    val text: Color,
    /** Muted / secondary text. */
    val textSecondary: Color,
    val error: Color,
    /** A dialog's scrim. */
    val overlay: Color,
    val radius: Dp,
)

/** mero-react's `defaultMeroTheme`: the dark green palette of Calimero's apps (accent #A5FF11). */
val defaultMeroTheme: MeroTheme =
    MeroTheme(
        primary = Color(0xFFA5FF11),
        primaryHover = Color(0xFF8ED40D),
        primaryText = Color(0xFF0D1117),
        background = Color(0xFF0D1117),
        backgroundSecondary = Color(0xFF161B22),
        backgroundTertiary = Color(0xFF1C2128),
        border = Color(0xFF30363D),
        text = Color(0xFFE6EDF3),
        textSecondary = Color(0xFF8B949E),
        error = Color(0xFFFF6B6B),
        overlay = Color(0xBF000000),
        radius = 8.dp,
    )

/** The light Calimero tokens [LoginSheetColors] has always defaulted to. */
val lightMeroTheme: MeroTheme =
    MeroTheme(
        primary = Color(0xFFA5FF11),
        primaryHover = Color(0xFF8ED40D),
        primaryText = Color(0xFF131215),
        background = Color(0xFFFFFFFF),
        backgroundSecondary = Color(0xFFF7F7F4),
        backgroundTertiary = Color(0xFFEFEFEA),
        border = Color(0xFFE5E5E0),
        text = Color(0xFF131215),
        textSecondary = Color(0xFF4A4A4F),
        error = Color(0xFFC62828),
        overlay = Color(0x66000000),
        radius = 8.dp,
    )

/** The ambient [MeroTheme]; [defaultMeroTheme] when none is provided. */
val LocalMeroTheme: ProvidableCompositionLocal<MeroTheme> = staticCompositionLocalOf { defaultMeroTheme }

/** Give [content] a [MeroTheme]. */
@Composable
fun ProvideMeroTheme(
    theme: MeroTheme,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMeroTheme provides theme, content = content)
}
