@file:Suppress("MatchingDeclarationName")

package com.calimero.mero.sample.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Calimero brand palette (dark + lime), lifted from the Swift sample's `Theme.swift`. */
object Cal {
    val bg = Color(0xFF0A0E13)
    val surface = Color(0xFF14181F)
    val surface2 = Color(0xFF1B212B)
    val border = Color(0x1AFFFFFF)
    val text = Color(0xFFFFFFFF)
    val textDim = Color(0x99FFFFFF)
    val lime = Color(0xFFA5FF11)
    val orange = Color(0xFFFF7A00)
    val error = Color(0xFFEF4444)
}

/** Wraps content in a dark, lime-tinted Material theme. */
@Composable
fun MeroExplorerTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Cal.lime,
        onPrimary = Cal.bg,
        background = Cal.bg,
        surface = Cal.surface,
        onSurface = Cal.text,
        error = Cal.error,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Filled lime primary button. */
@Composable
fun CalPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = Cal.lime,
            contentColor = Cal.bg,
            disabledContainerColor = Cal.lime.copy(alpha = 0.3f),
        ),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/** The Calimero wordmark: a lime chip + "calimero". */
@Composable
fun CalLogo(modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Text(
            "calimero",
            color = Cal.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Dark styled single-line text field used across the branded UI. */
@Composable
fun CalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    secure: Boolean = false,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Cal.text,
            unfocusedTextColor = Cal.text,
            focusedBorderColor = Cal.lime,
            unfocusedBorderColor = Cal.border,
            focusedLabelColor = Cal.lime,
            unfocusedLabelColor = Cal.textDim,
            cursorColor = Cal.lime,
        ),
    )
}

/** A padding preset for screen content. */
val screenPadding = PaddingValues(16.dp)
