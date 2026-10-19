@file:Suppress("TooManyFunctions", "MatchingDeclarationName")

package com.calimero.mero.sample.explorer

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calimero.mero.compose.MeroAuthState
import com.calimero.mero.compose.MeroClient
import com.calimero.mero.sample.app.NodePrefs
import com.calimero.mero.sample.app.SampleLog
import com.calimero.mero.sample.chat.ChatScreen
import com.calimero.mero.sample.chat.ChatService
import com.calimero.mero.sample.ui.Cal
import com.calimero.mero.sample.ui.CalCard
import com.calimero.mero.sample.ui.CalIconTile
import com.calimero.mero.sample.ui.CalPrimaryButton
import com.calimero.mero.sample.ui.CalRow
import com.calimero.mero.sample.ui.CalSecondaryButton
import com.calimero.mero.sample.ui.CalSection
import com.calimero.mero.sample.ui.CalTextButton
import com.calimero.mero.sample.ui.CalTextField
import com.calimero.mero.sample.ui.Callout
import com.calimero.mero.sample.ui.CalloutKind
import com.calimero.mero.sample.ui.CardHead
import com.calimero.mero.sample.ui.ChatAvatar
import com.calimero.mero.sample.ui.Hairline
import com.calimero.mero.sample.ui.IconTile
import com.calimero.mero.sample.ui.IdField
import com.calimero.mero.sample.ui.LimeGlow
import com.calimero.mero.sample.ui.LivePill
import com.calimero.mero.sample.ui.MeroExplorerTheme
import com.calimero.mero.sample.ui.RowDivider
import com.calimero.mero.sample.ui.SectionLabel
import com.calimero.mero.sample.ui.TechDetails
import com.calimero.mero.sample.ui.TopBar
import com.calimero.mero.sample.ui.screenPad
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Launch-time options for the explorer (callback URL, e2e hooks, mock defaults). */
data class ExplorerOptions(
    /** Where the wallet sends the person back; must match the manifest's intent filter. */
    val callbackUrl: String,
    /** Prefilled context for the SDK explorer's calls. */
    val initialContextId: String = "",
    val chatDisplayName: String? = null,
    val autoJoinInvite: String? = null,
    /** Prefills "Manually enter node URL" (the `nodeUrl` launch extra the live e2e tests pass). */
    val nodeUrl: String? = null,
    /** Shown on the login screen in mock mode: the in-app stand-in for the wallet. */
    val mockWallet: (@Composable () -> Unit)? = null,
)

/** Root of the app: sign-in (Calimero Cloud, or a node URL), then the tabs. */
@Composable
fun ExplorerApp(
    client: MeroClient,
    options: ExplorerOptions,
) {
    MeroExplorerTheme {
        val state by client.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.isAuthenticated) {
            if (state.isAuthenticated) {
                SampleLog.log(
                    SampleLog.Level.OK,
                    state.nodeUrl?.let { "authenticated on the node $it" } ?: "signed in as account ${state.account?.take(ACCOUNT_LOG)}…",
                )
            }
        }
        AnimatedContent(targetState = state.isAuthenticated, label = "auth") { signedIn ->
            if (signedIn) SignedIn(client, state, options) else LoginScreen(client, state, options)
        }
    }
}

// ---- Sign in -----------------------------------------------------------------------------

/**
 * Calimero Cloud first: one button opens the wallet, the person approves this device with their
 * passkey, and the app connects to the relay serving their account. Under it, "Manually enter
 * node URL" signs in to a node directly (a local `merod`) with its admin username and password.
 */
@Composable
@Suppress("LongMethod")
private fun LoginScreen(
    client: MeroClient,
    state: MeroAuthState,
    options: ExplorerOptions,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val prefs = remember { NodePrefs(context) }
    var manual by rememberSaveable { mutableStateOf(options.nodeUrl != null) }
    var nodeUrl by rememberSaveable { mutableStateOf(options.nodeUrl ?: prefs.lastNodeUrl) }
    var username by rememberSaveable { mutableStateOf(prefs.lastNodeUser) }
    var password by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var showLogs by remember { mutableStateOf(false) }
    if (showLogs) {
        DiagnosticsScreen(onBack = { showLogs = false })
        return
    }

    val loading = state.isLoading
    val canConnect = !loading && nodeUrl.isNotBlank() && username.isNotEmpty() && password.isNotEmpty()
    val error = localError ?: state.error?.let { friendlyNodeError(it, nodeUrl.trim()) }

    fun connect() {
        if (!canConnect) return
        val url = nodeUrl.trim().trimEnd('/')
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            localError = "Enter a full node URL, like ${NodePrefs.DEFAULT_NODE_URL}."
            return
        }
        localError = null
        focus.clearFocus()
        prefs.lastNodeUrl = url
        prefs.lastNodeUser = username
        SampleLog.log(SampleLog.Level.REQ, "node $url")
        scope.launch {
            if (!client.login(url, username, password)) {
                SampleLog.log(SampleLog.Level.ERR, "node login failed: ${client.state.value.error}")
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Cal.bg)) {
        LimeGlow()
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(if (manual) 40.dp else 96.dp))
                Column(
                    Modifier.widthIn(max = 440.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    CalIconTile(76.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (manual) "Connect to a node" else "Welcome to MeroKit",
                            color = Cal.text,
                            fontSize = 32.sp,
                            lineHeight = 38.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("loginTitle"),
                        )
                        Text(
                            if (manual) {
                                "Sign in to a node you run, like a local merod, with its admin username and password."
                            } else {
                                "Private, peer-to-peer apps on Calimero. Sign in with your passkey; your account key never leaves the wallet."
                            },
                            color = Cal.textDim,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (error != null) {
                        Callout(error, kind = CalloutKind.DANGER, modifier = Modifier.testTag("loginError"))
                    } else if (state.cancelled && !manual) {
                        Callout(
                            "You declined in the wallet, so nothing was saved. You can try again.",
                            kind = CalloutKind.INFO,
                            modifier = Modifier.testTag("cloudLoginCancelled"),
                        )
                    }
                    if (manual) {
                        CalCard(padding = 18.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                CalTextField(
                                    nodeUrl,
                                    { nodeUrl = it },
                                    "Node URL",
                                    placeholder = NodePrefs.DEFAULT_NODE_URL,
                                    modifier = Modifier.testTag("nodeUrlField"),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                                )
                                CalTextField(
                                    username,
                                    { username = it },
                                    "Username",
                                    placeholder = NodePrefs.DEFAULT_NODE_USER,
                                    modifier = Modifier.testTag("usernameField"),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                )
                                CalTextField(
                                    password,
                                    { password = it },
                                    "Password",
                                    placeholder = "Admin password",
                                    password = true,
                                    modifier = Modifier.testTag("passwordField"),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { connect() }),
                                )
                                LoadingPrimaryButton(
                                    text = "Connect to node",
                                    icon = Icons.Outlined.Dns,
                                    loading = loading,
                                    enabled = canConnect,
                                    onClick = ::connect,
                                    modifier = Modifier.testTag("loginButton"),
                                )
                                Text(
                                    "In the emulator, 10.0.2.2 is your computer. On a phone, use your computer's LAN address.",
                                    color = Cal.textFaint,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            LoadingPrimaryButton(
                                text = "Continue with Calimero Cloud",
                                icon = Icons.Filled.Cloud,
                                loading = loading,
                                enabled = !loading,
                                onClick = {
                                    SampleLog.log(SampleLog.Level.REQ, "opening the Calimero wallet")
                                    client.signInWithCloud(context, options.callbackUrl)
                                },
                                modifier = Modifier.testTag("cloudLoginButton"),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.VpnKey, null, tint = Cal.textFaint, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Opens the Calimero wallet to approve this device", color = Cal.textFaint, fontSize = 12.sp)
                            }
                        }
                        options.mockWallet?.invoke()
                    }
                    CalTextButton(
                        if (manual) "Use Calimero Cloud instead" else "Manually enter node URL",
                        onClick = {
                            manual = !manual
                            localError = null
                        },
                        underline = !manual,
                        modifier = Modifier.testTag(if (manual) "useCloud" else "manualNode"),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
            CalTextButton(
                "Diagnostics",
                onClick = { showLogs = true },
                icon = Icons.Outlined.MonitorHeart,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
            )
        }
    }
}

/** The lime primary button, full width, with a spinner in place of the icon while [loading]. */
@Composable
private fun LoadingPrimaryButton(
    text: String,
    icon: ImageVector,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(contentAlignment = Alignment.Center) {
        CalPrimaryButton(text, onClick = onClick, enabled = enabled, icon = if (loading) null else icon, modifier = modifier.fillMaxWidth())
        if (loading) {
            CircularProgressIndicator(
                color = Cal.onLime,
                strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp).size(18.dp),
            )
        }
    }
}

/** The SDK's node-login messages, in the words the sign-in screen uses. */
private fun friendlyNodeError(
    error: String,
    nodeUrl: String,
): String =
    when {
        error.startsWith("Can't reach the node") -> "Can't reach a node at $nodeUrl. Is merod running?"
        error.startsWith("Login failed") -> "The node refused that username or password."
        else -> error
    }

// ---- Signed in: Home, Chat, SDK -------------------------------------------------------------

private enum class Tab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val tag: String,
) {
    HOME("Home", Icons.Outlined.Home, Icons.Outlined.Home, "tabHome"),
    CHAT("Chat", Icons.Outlined.Forum, Icons.Filled.Forum, "tabChat"),
    SDK("SDK", Icons.Outlined.Apps, Icons.Filled.Apps, "tabSdk"),
}

@Composable
private fun SignedIn(
    client: MeroClient,
    state: MeroAuthState,
    options: ExplorerOptions,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var inChannel by remember { mutableStateOf(false) }
    var sdkOp by remember { mutableStateOf<SDKOperation?>(null) }
    var runAll by remember { mutableStateOf(false) }
    var components by remember { mutableStateOf(false) }
    val runAllModel = remember(client, state.nodeUrl, state.account) { RunAllModel() }
    var showLogs by remember { mutableStateOf(false) }
    val chat = remember(client, state.nodeUrl, state.account) { ChatService(client, options.chatDisplayName) }

    BackHandler(enabled = tab != Tab.HOME && !inChannel && sdkOp == null && !runAll && !components && !showLogs) { tab = Tab.HOME }

    if (showLogs) {
        DiagnosticsScreen(onBack = { showLogs = false })
        return
    }
    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.HOME ->
                    HomeScreen(
                        client = client,
                        state = state,
                        displayName = chat.username,
                        onOpenChat = { tab = Tab.CHAT },
                        onOpenSdk = { tab = Tab.SDK },
                        onOpenLogs = { showLogs = true },
                    )
                Tab.CHAT -> ChatScreen(chat, autoJoinInvite = options.autoJoinInvite, onInChannel = { inChannel = it })
                Tab.SDK -> {
                    val op = sdkOp
                    when {
                        runAll -> RunAllScreen(client, runAllModel, signedInToNode = state.isNodeSession, onBack = { runAll = false })
                        components -> LoginComponentPreview(onBack = { components = false })
                        op == null ->
                            SdkListScreen(
                                onOpenOp = { sdkOp = it },
                                onOpenRunAll = { runAll = true },
                                onOpenComponents = { components = true },
                            )
                        else -> {
                            BackHandler { sdkOp = null }
                            OperationRunner(client, op, onBack = { sdkOp = null })
                        }
                    }
                }
            }
        }
        // The bottom bar hides inside a channel, where the composer sits, and over the login preview.
        if (!(tab == Tab.CHAT && inChannel) && !(tab == Tab.SDK && components)) {
            Hairline()
            NavigationBar(containerColor = Cal.surface, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(if (tab == t) t.selectedIcon else t.icon, contentDescription = null) },
                        label = { Text(t.label, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                        modifier = Modifier.testTag(t.tag),
                        colors =
                            NavigationBarItemDefaults.colors(
                                selectedIconColor = Cal.accentInk,
                                selectedTextColor = Cal.accentInk,
                                indicatorColor = Cal.accentSoft,
                                unselectedIconColor = Cal.textFaint,
                                unselectedTextColor = Cal.textFaint,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod", "LongParameterList")
private fun HomeScreen(
    client: MeroClient,
    state: MeroAuthState,
    displayName: String,
    onOpenChat: () -> Unit,
    onOpenSdk: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(Cal.bg)) {
        LimeGlow(height = 320.dp)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = screenPad + 4.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChatAvatar(displayName.ifEmpty { "?" }, size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Signed in as", color = Cal.textFaint, fontSize = 13.sp)
                    Text(
                        displayName,
                        color = Cal.text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.testTag("homeTitle"),
                    )
                }
                CalIconTile(34.dp)
            }
            ConnectionCard(state)
            state.relayNote?.let { note ->
                Callout(
                    note,
                    kind = if (state.relayUrl == null) CalloutKind.INFO else CalloutKind.WARNING,
                    modifier = Modifier.testTag("sessionNote"),
                )
            }
            CalSection("Try it") {
                CalRow(
                    Icons.Filled.Forum,
                    "Chat",
                    onClick = onOpenChat,
                    subtitle = "Spaces, channels and live messages",
                    accent = true,
                    modifier = Modifier.testTag("openChat"),
                )
                RowDivider()
                CalRow(
                    Icons.Filled.Apps,
                    "Explore SDK",
                    onClick = onOpenSdk,
                    subtitle = "${sdkOperations.size} methods in ${sdkCategories.size} categories",
                    modifier = Modifier.testTag("exploreSDK"),
                )
            }
            CalSection("Session") {
                CalRow(Icons.Outlined.MonitorHeart, "Diagnostics", onClick = onOpenLogs, subtitle = "Every request and event")
                RowDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            SampleLog.log(SampleLog.Level.REQ, "sign out")
                            scope.launch { client.signOut() }
                        }.padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("signOutButton"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Cal.errorSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, tint = Cal.error, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Sign out", color = Cal.error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ConnectionCard(state: MeroAuthState) {
    val node = state.nodeUrl
    val live = state.relayUrl != null || node != null
    val status =
        when {
            node != null -> node.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: node
            state.relayUrl == null -> "Signed in, no relay yet"
            else -> state.relayUrl!!.toHttpUrlOrNull()?.host ?: state.relayUrl!!
        }
    CalCard(Modifier.testTag("homeAccount"), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(if (node != null) Icons.Outlined.Dns else Icons.Filled.Cloud, accent = true, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (node != null) "Your node" else "Calimero Cloud", color = Cal.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(status, color = Cal.textFaint, fontSize = 13.sp, maxLines = 1)
            }
            LivePill(live)
        }
        TechDetails {
            state.account?.let { IdField("Account", it) }
            state.device?.let { IdField("Device", it) }
            state.relayUrl?.let { IdField("Relay", it) }
            node?.let { IdField("Node", it) }
        }
    }
}

// ---- Diagnostics ---------------------------------------------------------------------------

@Composable
private fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    BackHandler(onBack = onBack)
    LaunchedEffect(SampleLog.lines.size) {
        if (SampleLog.lines.isNotEmpty()) listState.animateScrollToItem(SampleLog.lines.size - 1)
    }
    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        TopBar(
            "Diagnostics",
            onBack = onBack,
            actions = {
                IconButton(onClick = SampleLog::clear) { Icon(Icons.Outlined.DeleteSweep, "Clear", tint = Cal.accentInk) }
                IconButton(onClick = { clipboard.setText(AnnotatedString(SampleLog.text())) }) {
                    Icon(Icons.Outlined.ContentCopy, "Copy", tint = Cal.accentInk)
                }
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SampleLog.text())
                    context.startActivity(Intent.createChooser(send, "Share diagnostics"))
                }) { Icon(Icons.Outlined.Share, "Share", tint = Cal.accentInk) }
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            item { Spacer(Modifier.height(10.dp)) }
            if (SampleLog.lines.isEmpty()) item { Text("No activity yet.", color = Cal.textDim, fontSize = 13.sp) }
            items(SampleLog.lines, key = { it.id }) { line ->
                Row(verticalAlignment = Alignment.Top) {
                    val (icon, tint) =
                        when (line.level) {
                            SampleLog.Level.ERR -> Icons.Outlined.ErrorOutline to Cal.error
                            SampleLog.Level.OK -> Icons.Outlined.CheckCircle to Cal.success
                            SampleLog.Level.WARN -> Icons.Outlined.WarningAmber to Cal.warning
                            SampleLog.Level.REQ -> Icons.Outlined.PlayArrow to Cal.text
                            SampleLog.Level.INFO -> Icons.Outlined.Info to Cal.textDim
                        }
                    Icon(icon, null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(13.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(line.text, color = Cal.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private const val ACCOUNT_LOG = 12

/** The categorized, searchable SDK surface. */
@Composable
@Suppress("LongMethod")
private fun SdkListScreen(
    onOpenOp: (SDKOperation) -> Unit,
    onOpenRunAll: () -> Unit,
    onOpenComponents: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var search by remember { mutableStateOf("") }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val query = search.trim().lowercase()
    val sections =
        sdkCategories.mapNotNull { category ->
            val ops =
                sdkOperations.filter { op ->
                    op.category == category &&
                        (query.isEmpty() || "${op.name} ${op.summary} ${op.category}".lowercase().contains(query))
                }
            if (ops.isEmpty()) null else category to ops
        }

    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        TopBar("Explore SDK", subtitle = "${sdkOperations.size} methods in ${sdkCategories.size} categories", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = screenPad).testTag("opList"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (search.isEmpty()) {
                item { Box(Modifier.padding(top = 16.dp)) { RunAllEntry(onClick = onOpenRunAll) } }
                item { ComponentsEntry(onClick = onOpenComponents) }
            }
            item {
                Row(
                    Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Cal.radiusControl))
                        .background(Cal.surface)
                        .border(1.dp, Cal.borderStrong, RoundedCornerShape(Cal.radiusControl))
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, null, tint = Cal.textFaint, modifier = Modifier.size(18.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = search,
                        onValueChange = { search = it },
                        singleLine = true,
                        textStyle =
                            androidx.compose.ui.text
                                .TextStyle(color = Cal.text, fontSize = 14.sp),
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 12.dp).testTag("sdkSearch"),
                        decorationBox = { inner ->
                            if (search.isEmpty()) Text("Search methods", color = Cal.textFaint, fontSize = 14.sp)
                            inner()
                        },
                    )
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { search = "" }, modifier = Modifier.testTag("sdkSearchClear")) {
                            Icon(Icons.Outlined.Clear, "Clear search", tint = Cal.textFaint)
                        }
                    }
                }
            }
            items(sections, key = { it.first }) { (category, ops) ->
                val open = search.isNotEmpty() || expanded[category] == true
                CalCard(padding = 0.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { expanded[category] = !(expanded[category] ?: false) }
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                            .testTag("category:$category"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(category, color = Cal.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${ops.size}", color = Cal.textFaint, fontSize = 12.5.sp)
                        Icon(
                            Icons.Outlined.ExpandMore,
                            null,
                            tint = Cal.textFaint,
                            modifier = Modifier.padding(start = 6.dp).size(18.dp).rotate(if (open) 180f else 0f),
                        )
                    }
                    if (open) {
                        ops.forEach { op ->
                            Hairline()
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenOp(op) }
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                                    .testTag("op:${op.name}"),
                            ) {
                                Text(op.name, color = Cal.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                                Text(op.summary, color = Cal.textFaint, fontSize = 12.5.sp)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun OperationRunner(
    client: MeroClient,
    op: SDKOperation,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val inputs = remember { mutableStateMapOf<String, String>() }
    var output by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        TopBar(op.name, subtitle = op.category, onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = screenPad),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Text(op.summary, color = Cal.textDim, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp)) }
            items(op.fields, key = { it.id }) { field ->
                CalTextField(
                    value = inputs[field.id] ?: "",
                    onValueChange = { inputs[field.id] = it },
                    label = field.label,
                    placeholder = field.placeholder.ifEmpty { null },
                    singleLine = field.kind == OpFieldKind.LINE,
                    mono = true,
                )
            }
            item {
                CalPrimaryButton(
                    text = if (running) "Running…" else "Run",
                    icon = Icons.Outlined.PlayArrow,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().testTag("runOp"),
                    onClick = {
                        running = true
                        scope.launch {
                            try {
                                output = op.run(OpEnv(client), inputs.toMap())
                                failed = false
                            } catch (e: Exception) {
                                output = e.message ?: e.toString()
                                failed = true
                            } finally {
                                running = false
                            }
                        }
                    },
                )
            }
            if (output.isNotEmpty()) {
                item {
                    Column {
                        SectionLabel(if (failed) "Error" else "Response")
                        Spacer(Modifier.size(6.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (failed) Cal.errorSoft else Cal.surfaceSunken)
                                .border(1.dp, Cal.border, RoundedCornerShape(10.dp))
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            Text(
                                output,
                                color = if (failed) Cal.error else Cal.text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.testTag("opResponse"),
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}

/** The mock-mode stand-in for the wallet: approve or decline the pending sign-in in-app. */
@Composable
fun MockWalletCard(
    pendingUrl: String?,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
) {
    if (pendingUrl == null) return
    CalCard(Modifier.widthIn(max = 480.dp).testTag("mockWallet")) {
        CardHead(Icons.Outlined.Wallet, "Mock wallet", "Stands in for the Calimero wallet in mock mode")
        Spacer(Modifier.size(14.dp))
        Text(
            "Approve this device for a throwaway test account? The real wallet asks for your passkey here.",
            color = Cal.textDim,
            fontSize = 14.sp,
        )
        Spacer(Modifier.size(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalPrimaryButton("Approve", onClick = onApprove, modifier = Modifier.testTag("mockWalletApprove"))
            CalSecondaryButton("Decline", onClick = onDecline, modifier = Modifier.testTag("mockWalletDecline"))
        }
    }
}
