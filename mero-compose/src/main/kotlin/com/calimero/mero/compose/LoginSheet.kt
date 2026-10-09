package com.calimero.mero.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Cloud sign-in: one "Continue with Calimero" button and a short explanation. There is no
 * node URL, username or password — the person approves this device in the Calimero wallet
 * (a passkey, in a Custom Tab), and the redirect comes back to [callbackUrl], which the app
 * hands to [MeroClient.handleEnrolmentCallback].
 *
 * Adapted from mero-react's `AccountSignInPanel`, in the light Calimero style.
 *
 * Test tags: `cloudLoginTitle`, `cloudLoginButton`, `cloudLoginError`, `cloudLoginCancelled`.
 */
@Suppress("LongMethod")
@Composable
fun LoginSheet(
    callbackUrl: String,
    modifier: Modifier = Modifier,
    client: MeroClient = useMero(),
    colors: LoginSheetColors = LoginSheetColors(),
    title: String = "Connect to Calimero",
    onAuthenticated: () -> Unit = {},
) {
    val state by client.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.isAuthenticated) { if (state.isAuthenticated) onAuthenticated() }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(14.dp))
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(14.dp))
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .background(colors.accent, RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0x0F000000), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(22.dp))
        }
        Text(
            title,
            color = colors.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("cloudLoginTitle"),
        )
        Text(
            "You'll approve this device on the Calimero wallet's own page, then come back here. " +
                "Your account key stays in the wallet — the relay only ever sees the certificate it signs for this device.",
            color = colors.textDim,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            "That one certificate does the rest: finding the relay that serves your account, writing through it, " +
                "reading your contexts and live updates. Nothing to paste in.",
            color = colors.textFaint,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )

        state.error?.let { error ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(colors.dangerSoft, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("cloudLoginError"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.danger, modifier = Modifier.size(18.dp))
                Text(error, color = colors.danger, fontSize = 13.sp)
            }
        }
        if (state.cancelled) {
            Text(
                "Sign-in was cancelled in the wallet.",
                color = colors.textFaint,
                fontSize = 13.sp,
                modifier = Modifier.testTag("cloudLoginCancelled"),
            )
        }

        Button(
            onClick = { client.signInWithCloud(context, callbackUrl) },
            enabled = !state.isLoading,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color(0x0F000000)),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.onAccent,
                    disabledContainerColor = colors.accent.copy(alpha = 0.5f),
                    disabledContentColor = colors.onAccent.copy(alpha = 0.6f),
                ),
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("cloudLoginButton"),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    color = colors.onAccent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Text("  Waiting for the wallet…", fontWeight = FontWeight.Medium)
            } else {
                Text("Continue with Calimero", fontWeight = FontWeight.Medium)
            }
        }
    }
}
