package com.calimero.mero.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * A button that reflects the Cloud session: "Sign out" when signed in, otherwise
 * "Continue with Calimero", which opens the wallet ([MeroClient.signInWithCloud]) — or
 * calls [onConnect] instead when given (e.g. to show a [LoginSheet] first).
 * Kotlin analogue of mero-react's `ConnectButton` in its Cloud mode.
 */
@Composable
fun ConnectButton(
    callbackUrl: String,
    modifier: Modifier = Modifier,
    client: MeroClient = useMero(),
    onConnect: (() -> Unit)? = null,
) {
    val state by client.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    if (state.isAuthenticated) {
        OutlinedButton(
            onClick = { scope.launch { client.signOut() } },
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color(0xFFD4D4CE)),
            modifier = modifier,
        ) {
            Text("Sign out", color = Color(0xFF131215))
        }
    } else {
        Button(
            onClick = { onConnect?.invoke() ?: client.signInWithCloud(context, callbackUrl) },
            enabled = !state.isLoading,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA5FF11), contentColor = Color(0xFF131215)),
            modifier = modifier,
        ) {
            Text(if (state.isLoading) "Connecting…" else "Continue with Calimero")
        }
    }
}
