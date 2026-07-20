package com.calimero.mero.compose

import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * A button that reflects auth state: shows "Log out" when authenticated (and logs out on tap),
 * otherwise invokes [onConnect] to begin login. Kotlin analogue of mero-react's `ConnectButton`.
 */
@Composable
fun ConnectButton(
    modifier: Modifier = Modifier,
    client: MeroClient = useMero(),
    clientId: String? = null,
    onConnect: () -> Unit = {},
) {
    val state by client.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    if (state.isAuthenticated) {
        OutlinedButton(
            onClick = { scope.launch { client.logout(clientId) } },
            modifier = modifier,
        ) {
            Text("Log out")
        }
    } else {
        Button(
            onClick = onConnect,
            enabled = !state.isLoading,
            modifier = modifier,
        ) {
            Text(if (state.isLoading) "Connecting…" else "Connect")
        }
    }
}
