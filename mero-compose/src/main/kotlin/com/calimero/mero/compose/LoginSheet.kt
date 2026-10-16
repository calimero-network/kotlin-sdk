package com.calimero.mero.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch

/**
 * The sign-in sheet — mero-react's `LoginModal`, inline. Two tabs:
 *
 * - **Cloud** (the default): "Continue with Calimero" ([AccountSignInPanel]). The person approves
 *   this device in the Calimero wallet (a passkey, in a Custom Tab); the redirect comes back to
 *   [callbackUrl], which the app hands to [MeroClient.handleDeepLink].
 * - **Node**: running local nodes (found on [ANDROID_EMULATOR_HOST][com.calimero.mero.discovery.ANDROID_EMULATOR_HOST]
 *   on an emulator, `localhost` on a device) plus a manual URL. Connecting opens the node's own
 *   login page ([MeroClient.connectToNode], back to [nodeCallbackUrl]) — or calls [onConnectNode].
 *
 * Pass `tabs = listOf(LoginModalTab.CLOUD)` for the Cloud panel alone (no tab row). Colours come
 * from the ambient [MeroTheme] unless [colors] is given.
 *
 * Test tags: `cloudLoginTitle`, `cloudLoginButton`, `cloudLoginError`, `cloudLoginCancelled`,
 * `loginTabNode`, `loginTabCloud`, `nodeDiscovering`, `nodeOption-<host:port>`, `nodeOption-custom`,
 * `nodeUrlInput`, `nodeRescan`, `nodeConnectButton`, `nodeLoginError`.
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
fun LoginSheet(
    callbackUrl: String,
    modifier: Modifier = Modifier,
    client: MeroClient = useMero(),
    colors: LoginSheetColors = LoginSheetColors.from(LocalMeroTheme.current),
    title: String = "Connect to Calimero",
    onAuthenticated: () -> Unit = {},
    tabs: List<LoginModalTab> = listOf(LoginModalTab.NODE, LoginModalTab.CLOUD),
    initialTab: LoginModalTab = LoginModalTab.CLOUD,
    nodeCallbackUrl: String = callbackUrl,
    onConnectNode: ((String) -> Unit)? = null,
) {
    val state by client.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(if (initialTab in tabs) initialTab else tabs.first()) }

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
            CalimeroLogo(size = 24.dp, color = colors.onAccent)
        }
        Text(
            title,
            color = colors.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("cloudLoginTitle"),
        )
        if (tabs.size > 1) LoginTabs(tabs, tab, colors) { tab = it }
        when (tab) {
            LoginModalTab.CLOUD -> AccountSignInPanel(callbackUrl = callbackUrl, client = client, colors = colors)
            LoginModalTab.NODE -> NodeLoginPanel(client = client, colors = colors, callbackUrl = nodeCallbackUrl, onConnectNode = onConnectNode)
        }
    }
}

@Composable
private fun LoginTabs(
    tabs: List<LoginModalTab>,
    selected: LoginModalTab,
    colors: LoginSheetColors,
    onSelect: (LoginModalTab) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.border.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { t ->
            val active = t == selected
            val label = if (t == LoginModalTab.NODE) "Node" else "Cloud"
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .background(if (active) colors.surface else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { onSelect(t) }
                        .padding(vertical = 8.dp)
                        .testTag(if (t == LoginModalTab.NODE) "loginTabNode" else "loginTabCloud"),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (active) colors.text else colors.textDim, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/**
 * The Cloud tab — mero-react `AccountSignInPanel`: one "Continue with Calimero" button, what it
 * does, and whatever the last attempt had to say. The flow itself is [MeroClient.signInWithCloud].
 */
@Suppress("LongMethod")
@Composable
fun AccountSignInPanel(
    callbackUrl: String,
    modifier: Modifier = Modifier,
    client: MeroClient = useMero(),
    colors: LoginSheetColors = LoginSheetColors.from(LocalMeroTheme.current),
) {
    val state by client.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val customWallet = client.account.walletUrl != com.calimero.mero.account.Enrolment.DEFAULT_WALLET_URL
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
        state.error?.let { error -> ErrorRow(error, colors, "cloudLoginError") }
        if (state.cancelled) {
            Text(
                "Sign-in was cancelled in the wallet.",
                color = colors.textFaint,
                fontSize = 13.sp,
                modifier = Modifier.testTag("cloudLoginCancelled"),
            )
        }
        PrimaryButton(
            text = if (state.isLoading) "  Waiting for the wallet…" else "Continue with Calimero",
            loading = state.isLoading,
            enabled = !state.isLoading,
            colors = colors,
            tag = "cloudLoginButton",
        ) { client.signInWithCloud(context, callbackUrl) }
        if (customWallet) {
            Text("Enrolling at ${client.account.walletUrl}, not the hosted wallet.", color = colors.textFaint, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun NodeLoginPanel(
    client: MeroClient,
    colors: LoginSheetColors,
    callbackUrl: String,
    onConnectNode: ((String) -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val login = remember(client) { NodeLoginState(scope, initialUrl = client.storage.nodeUrl) }
    LaunchedEffect(login) { login.rescan() }
    val discovered by login.discovered.collectAsStateWithLifecycle()
    val discovering by login.discovering.collectAsStateWithLifecycle()
    val selected by login.selected.collectAsStateWithLifecycle()
    val customUrl by login.customUrl.collectAsStateWithLifecycle()
    val loading by login.loading.collectAsStateWithLifecycle()
    val error by login.error.collectAsStateWithLifecycle()
    val state by client.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        (error ?: state.error)?.let { ErrorRow(it, colors, "nodeLoginError") }
        if (discovering) {
            Row(
                modifier = Modifier.fillMaxWidth().testTag("nodeDiscovering"),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text("  Searching for local nodes…", color = colors.textDim, fontSize = 14.sp)
            }
        } else {
            Text(
                if (discovered.isEmpty()) "No local node found. Enter a node URL to continue." else "Select your Calimero node to continue.",
                color = colors.textDim,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (discovered.isNotEmpty()) {
                discovered.forEach { url -> NodeOption(displayNodeUrl(url), "local", selected == url, colors, "nodeOption-${displayNodeUrl(url)}") { login.select(url) } }
                NodeOption("Enter node URL manually", null, selected == null, colors, "nodeOption-custom") { login.select(null) }
            }
            if (selected == null) {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = login::setCustomUrl,
                    singleLine = true,
                    placeholder = { Text("https://your-node-url.calimero.network") },
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.text,
                            unfocusedTextColor = colors.text,
                        ),
                    modifier = Modifier.fillMaxWidth().testTag("nodeUrlInput"),
                )
            }
            TextButton(onClick = login::rescan, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("nodeRescan")) {
                Text("↻ Rescan local nodes", color = colors.textDim, fontSize = 13.sp)
            }
        }
        val canConnect = !loading && !discovering && (selected != null || isValidNodeUrl(customUrl))
        PrimaryButton(
            text = if (loading) "  Connecting to node…" else "Connect",
            loading = loading,
            enabled = canConnect,
            colors = colors,
            tag = "nodeConnectButton",
        ) {
            scope.launch {
                val url = login.connect() ?: return@launch
                onConnectNode?.invoke(url) ?: client.connectToNode(context, url, callbackUrl)
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun NodeOption(
    label: String,
    meta: String?,
    active: Boolean,
    colors: LoginSheetColors,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, if (active) colors.accent else colors.border, RoundedCornerShape(8.dp))
                .background(if (active) colors.accent.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = active, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = colors.accent))
        Text(label, color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        meta?.let { Text(it, color = colors.textFaint, fontSize = 12.sp) }
    }
}

@Composable
private fun ErrorRow(
    message: String,
    colors: LoginSheetColors,
    tag: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.dangerSoft, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.danger, modifier = Modifier.size(18.dp))
        Text(message, color = colors.danger, fontSize = 13.sp)
    }
}

@Suppress("LongParameterList")
@Composable
private fun PrimaryButton(
    text: String,
    loading: Boolean,
    enabled: Boolean,
    colors: LoginSheetColors,
    tag: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0x0F000000)),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.onAccent,
                disabledContainerColor = colors.accent.copy(alpha = 0.5f),
                disabledContentColor = colors.onAccent.copy(alpha = 0.6f),
            ),
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag(tag),
    ) {
        if (loading) CircularProgressIndicator(color = colors.onAccent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Text(text, fontWeight = FontWeight.Medium)
    }
}
