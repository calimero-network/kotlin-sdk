@file:Suppress("MatchingDeclarationName")

package com.calimero.mero.sample.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
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
    val scheme =
        darkColorScheme(
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
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Cal.lime,
                contentColor = Cal.bg,
                disabledContainerColor = Cal.lime.copy(alpha = 0.3f),
            ),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/** Outlined secondary button (surface fill + hairline border). */
@Composable
fun CalSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        border = BorderStroke(1.dp, Cal.border),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor = Cal.surface2,
                contentColor = Cal.text,
            ),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/** A rounded surface "card" with a hairline border. */
@Composable
fun CalCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Cal.surface)
            .border(1.dp, Cal.border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        content = content,
    )
}

/** The Calimero wordmark: the brand chip + "calimero". */
@Composable
fun CalLogo(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    showWordmark: Boolean = true,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        CalMark(size)
        if (showWordmark) {
            Text(
                "calimero",
                color = Cal.text,
                fontSize = (size.value * 0.66f).sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * The Calimero mark. Drawn rather than shipped as a raster: a lime rounded square with the
 * cut-out corner of the brand icon, so the sample needs no binary asset.
 */
@Composable
fun CalMark(size: Dp = 20.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Cal.lime),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size * 0.34f)
                .clip(RoundedCornerShape(size * 0.1f))
                .background(Cal.bg),
        )
    }
}

/**
 * Minimal icon + placeholder field (no floating label) — used on the login screen, mirroring the
 * Swift sample's `MinimalField`.
 */
@Composable
fun MinimalField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    secure: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Cal.textDim) },
        leadingIcon = leading,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                keyboardType = if (secure) KeyboardType.Password else KeyboardType.Text,
            ),
        modifier = modifier.fillMaxWidth(),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Cal.surface,
                unfocusedContainerColor = Cal.surface,
                focusedTextColor = Cal.text,
                unfocusedTextColor = Cal.text,
                focusedBorderColor = Cal.lime,
                unfocusedBorderColor = Cal.border,
                cursorColor = Cal.lime,
            ),
    )
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
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        enabled = enabled,
        visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth(),
        colors =
            OutlinedTextFieldDefaults.colors(
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

/** App-wide horizontal screen padding — tight, so content runs almost full-width. */
val screenPad = 8.dp
