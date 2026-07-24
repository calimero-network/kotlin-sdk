package com.calimero.mero.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * CompositionLocal carrying the [MeroClient] down the tree — the Compose analogue of mero-react's
 * `MeroProvider` / `useMero`. Wrap your app in [MeroProvider] and read it with [useMero].
 */
val LocalMeroClient: ProvidableCompositionLocal<MeroClient?> = staticCompositionLocalOf { null }

@Composable
fun MeroProvider(
    client: MeroClient,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMeroClient provides client, content = content)
}

/** Read the ambient [MeroClient]. Throws if no [MeroProvider] is present above the call site. */
@Composable
fun useMero(): MeroClient =
    LocalMeroClient.current ?: error("No MeroProvider found. Wrap your UI in MeroProvider(client) { ... }")
