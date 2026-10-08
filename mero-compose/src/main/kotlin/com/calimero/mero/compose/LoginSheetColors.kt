package com.calimero.mero.compose

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color

/** Colours for [LoginSheet]. Defaults are the Calimero light tokens. */
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
)
