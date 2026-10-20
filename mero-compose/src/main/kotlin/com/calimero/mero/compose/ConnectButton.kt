@file:Suppress("MatchingDeclarationName")

package com.calimero.mero.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** [ConnectButton]'s words per state — mero-react's `label` object. */
data class ConnectButtonLabels(
    val connect: String = "Connect",
    val connected: String = "Connected",
    val reconnecting: String = "Reconnecting...",
    /** Before the short account id in the signed-in menu. */
    val account: String = "Account",
    /** In the signed-in menu, for an account no relay serves yet. */
    val noRelay: String = "no relay yet",
    val logOut: String = "Log out",
)

/** `abcdef…123456`: enough of a 64-hex account to recognise it. */
fun shortAccount(account: String): String = if (account.length > 14) "${account.take(6)}…${account.takeLast(6)}" else account

/**
 * The session at a glance, with sign-in and sign-out — mero-react `ConnectButton`:
 *
 * - signed out: "Connect", which opens a [LoginSheet] (Node and Cloud tabs, Cloud first) in a
 *   dialog — or calls [onConnect] instead when given;
 * - signed in but the node's event stream is down: a disabled "Reconnecting...";
 * - signed in: "Connected", opening a menu with the account (short) or the node URL — "no relay
 *   yet" for an account no relay serves — the relay note, and "Log out".
 *
 * Test tags: `connectButton`, `connectedMenu`, `logoutButton`.
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
fun ConnectButton(
    callbackUrl: String,
    modifier: Modifier = Modifier,
    client: MeroClient = useMero(),
    onConnect: (() -> Unit)? = null,
    labels: ConnectButtonLabels = ConnectButtonLabels(),
    logoOnly: Boolean = false,
    tabs: List<LoginModalTab> = listOf(LoginModalTab.NODE, LoginModalTab.CLOUD),
    theme: MeroTheme = LocalMeroTheme.current,
    /** The sign-in sheet's words and mark; its logo also replaces the Calimero logo on this button. */
    branding: LoginBranding = LoginBranding(),
) {
    val state by client.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var sheetOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(theme.radius)

    when {
        state.isAuthenticated && !state.isOnline ->
            OutlinedButton(onClick = {}, enabled = false, shape = shape, modifier = modifier.testTag("connectButton")) {
                Mark(branding, theme.textSecondary)
                if (!logoOnly) Text("  ${labels.reconnecting}", color = theme.textSecondary)
            }
        state.isAuthenticated ->
            Box(modifier = modifier) {
                OutlinedButton(
                    onClick = { menuOpen = !menuOpen },
                    shape = shape,
                    border = BorderStroke(1.dp, theme.primary),
                    modifier = Modifier.testTag("connectButton"),
                ) {
                    Mark(branding, theme.primary)
                    if (!logoOnly) Text("  ${labels.connected}", color = theme.text)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.testTag("connectedMenu")) {
                    state.account?.let { DropdownMenuItem(text = { Text("${labels.account} ${shortAccount(it)}") }, onClick = {}, enabled = false) }
                    val where = state.nodeUrl ?: state.relayUrl ?: if (state.isDelegated) labels.noRelay else null
                    where?.let { DropdownMenuItem(text = { Text(it) }, onClick = {}, enabled = false) }
                    state.relayNote?.takeIf { state.isDelegated }?.let { DropdownMenuItem(text = { Text(it) }, onClick = {}, enabled = false) }
                    DropdownMenuItem(
                        text = { Text(labels.logOut) },
                        onClick = {
                            menuOpen = false
                            scope.launch { client.logout() }
                        },
                        modifier = Modifier.testTag("logoutButton"),
                    )
                }
            }
        else ->
            Button(
                onClick = { onConnect?.invoke() ?: run { sheetOpen = true } },
                enabled = !state.isLoading,
                shape = shape,
                colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = theme.primaryText),
                modifier = modifier.testTag("connectButton"),
            ) {
                Mark(branding, theme.primaryText)
                if (!logoOnly) Text("  ${labels.connect}")
            }
    }

    if (sheetOpen && !state.isAuthenticated) {
        Dialog(onDismissRequest = { sheetOpen = false }) {
            LoginSheet(
                callbackUrl = callbackUrl,
                client = client,
                colors = LoginSheetColors.from(theme),
                tabs = tabs,
                branding = branding,
                onAuthenticated = { sheetOpen = false },
                modifier = Modifier.widthIn(max = 480.dp),
            )
        }
    }
}

/** [branding]'s logo, or the Calimero logo, at button size. */
@Composable
private fun Mark(
    branding: LoginBranding,
    color: Color,
) {
    val logo = branding.logo
    if (logo != null) {
        Icon(logo, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    } else {
        CalimeroLogo(size = 18.dp, color = color)
    }
}
