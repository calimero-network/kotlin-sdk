package com.calimero.mero.compose

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color

/** Colours for [LoginSheet]. Defaults are the Calimero light tokens ([lightMeroTheme]); [from] derives them from any [MeroTheme]. */
@Stable
data class LoginSheetColors(
    val surface: Color = Color(0xFFFFFFFF),
    val border: Color = Color(0xFFE5E5E0),
    val text: Color = Color(0xFF131215),
    val textDim: Color = Color(0xFF4A4A4F),
    val textFaint: Color = Color(0xFF6B6B70),
    val accent: Color = Color(0xFFA5FF11),
    val onAccent: Color = Color(0xFF131215),
    val danger: Color = Color(0xFFC62828),
    val dangerSoft: Color = Color(0xFFFDECEC),
) {
    companion object {
        /** The sheet's colours from [theme]: its surface, border, text tiers, accent and error. */
        fun from(theme: MeroTheme): LoginSheetColors =
            LoginSheetColors(
                surface = theme.background,
                border = theme.border,
                text = theme.text,
                textDim = theme.textSecondary,
                textFaint = theme.textSecondary.copy(alpha = 0.8f),
                accent = theme.primary,
                onAccent = theme.primaryText,
                danger = theme.error,
                dangerSoft = theme.error.copy(alpha = 0.1f),
            )
    }
}
