package com.calimero.mero.sample.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calimero.mero.compose.LoginSheet
import com.calimero.mero.compose.MeroAuthState
import com.calimero.mero.compose.MeroClient
import com.calimero.mero.sample.chat.ChatInvite
import com.calimero.mero.sample.chat.ChatScreen
import com.calimero.mero.sample.chat.ChatService
import com.calimero.mero.sample.ui.Cal
import com.calimero.mero.sample.ui.CalCard
import com.calimero.mero.sample.ui.CalLogo
import com.calimero.mero.sample.ui.CalPrimaryButton
import com.calimero.mero.sample.ui.CalSecondaryButton
import com.calimero.mero.sample.ui.CalTextField
import com.calimero.mero.sample.ui.Callout
import com.calimero.mero.sample.ui.CalloutKind
import com.calimero.mero.sample.ui.CardHead
import com.calimero.mero.sample.ui.Hairline
import com.calimero.mero.sample.ui.IdField
import com.calimero.mero.sample.ui.ListRow
import com.calimero.mero.sample.ui.MeroExplorerTheme
import com.calimero.mero.sample.ui.SectionLabel
import com.calimero.mero.sample.ui.StatusPill
import com.calimero.mero.sample.ui.TechDetails
import com.calimero.mero.sample.ui.TopBar
import com.calimero.mero.sample.ui.screenPad
import com.calimero.mero.sample.ui.shortId
import kotlinx.coroutines.launch

/** What the explorer is showing. */
private sealed interface ExplorerScreen {
    data object Home : ExplorerScreen

    data object SdkList : ExplorerScreen

    data class Op(
        val op: SDKOperation,
    ) : ExplorerScreen

    data object Chat : ExplorerScreen
}

/** Launch-time options for the explorer (callback URL, e2e hooks, mock defaults). */
data class ExplorerOptions(
    /** Where the wallet sends the person back; must match the manifest's intent filter. */
    val callbackUrl: String,
    /** Prefilled context for the home screen's quick call. */
    val initialContextId: String = "",
    val chatDisplayName: String? = null,
    val autoJoinInvite: String? = null,
    /** Shown on the login screen in mock mode: the in-app stand-in for the wallet. */
    val mockWallet: (@Composable () -> Unit)? = null,
)

/** Root of the app: Cloud sign-in, then the explorer. */
@Composable
fun ExplorerApp(
    client: MeroClient,
    options: ExplorerOptions,
) {
    MeroExplorerTheme {
        val state by client.state.collectAsStateWithLifecycle()
        if (!state.isAuthenticated) {
            LoginScreen(client, options)
        } else {
            SignedIn(client, state, options)
        }
    }
}

@Composable
private fun LoginScreen(
    client: MeroClient,
    options: ExplorerOptions,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Cal.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = screenPad, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        CalLogo(name = "Mero Sample")
        Spacer(Modifier.size(8.dp))
        LoginSheet(callbackUrl = options.callbackUrl, client = client, modifier = Modifier.widthIn(max = 480.dp))
        options.mockWallet?.invoke()
        Text(
            "Sign-in happens in the Calimero wallet, with your passkey. This app never sees your account key.",
            color = Cal.textFaint,
            fontSize = 12.sp,
            modifier = Modifier.widthIn(max = 420.dp),
        )
    }
}

@Composable
private fun SignedIn(
    client: MeroClient,
    state: MeroAuthState,
    options: ExplorerOptions,
) {
    var screen by remember { mutableStateOf<ExplorerScreen>(ExplorerScreen.Home) }
    val chat = remember(client) { ChatService(client, options.chatDisplayName) }
    when (val current = screen) {
        ExplorerScreen.Home ->
            HomeScreen(
                client = client,
                state = state,
                options = options,
                onOpenSdk = { screen = ExplorerScreen.SdkList },
                onOpenChat = { screen = ExplorerScreen.Chat },
            )
        ExplorerScreen.SdkList ->
            SdkListScreen(onOpenOp = { screen = ExplorerScreen.Op(it) }, onBack = { screen = ExplorerScreen.Home })
        is ExplorerScreen.Op -> OperationRunner(client, current.op, onBack = { screen = ExplorerScreen.SdkList })
        ExplorerScreen.Chat ->
            ChatScreen(chat, onClose = { screen = ExplorerScreen.Home }, autoJoinInvite = options.autoJoinInvite)
    }
}

@Composable
@Suppress("LongMethod")
private fun HomeScreen(
    client: MeroClient,
    state: MeroAuthState,
    options: ExplorerOptions,
    onOpenSdk: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        TopBar(
            title = "Mero Sample",
            subtitle = "Signed in with Calimero",
            modifier = Modifier.testTag("homeTitle"),
            actions = {
                IconButton(onClick = { scope.launch { client.signOut() } }, modifier = Modifier.testTag("signOutButton")) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "Sign out", tint = Cal.textDim)
                }
            },
        )
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = screenPad),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Spacer(Modifier.size(4.dp)) }
            item { AccountCard(state) }
            if (state.signedInWithoutRelay) {
                item { NoRelayCallout(client, state) }
            } else {
                state.relayNote?.let { note -> item { Callout(note, title = "Relay", kind = CalloutKind.WARNING) } }
                item { QuickCall(client, state, options.initialContextId) }
            }
            item {
                CalCard(padding = 0.dp) {
                    ListRow(
                        Icons.Outlined.ChatBubbleOutline,
                        "Chat",
                        "Channels and messages, written through your relay",
                        onClick = onOpenChat,
                        accent = true,
                        modifier = Modifier.testTag("openChat"),
                    )
                    Hairline()
                    ListRow(
                        Icons.Outlined.Code,
                        "Explore the SDK",
                        "${sdkOperations.size} methods: Cloud, relay, admin",
                        onClick = onOpenSdk,
                        modifier = Modifier.testTag("openSdk"),
                    )
                }
            }
            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun AccountCard(state: MeroAuthState) {
    CalCard(Modifier.testTag("homeAccount")) {
        CardHead(
            icon = Icons.Outlined.AccountCircle,
            title = "Your account",
            meta = shortId(state.account),
            accent = true,
            trailing = {
                when {
                    state.relayUrl == null -> StatusPill("No relay yet", live = false)
                    state.sessionReady -> StatusPill("Connected", live = true)
                    else -> StatusPill(if (state.isLoading) "Connecting" else "Writes only", live = false)
                }
            },
        )
        TechDetails {
            state.account?.let { IdField("Account", it) }
            state.device?.let { IdField("Device", it) }
            state.relayUrl?.let { IdField("Relay", it) }
        }
    }
}

@Composable
private fun NoRelayCallout(
    client: MeroClient,
    state: MeroAuthState,
) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Callout(
        body = state.relayNote ?: "No relay serves this account yet. Redeeming an invitation gives it one.",
        title = "Signed in, with nowhere to write yet",
        kind = CalloutKind.INFO,
        modifier = Modifier.testTag("noRelay"),
    ) {
        Spacer(Modifier.size(6.dp))
        CalTextField(code, { code = it }, "Invite code or link", singleLine = false, mono = true)
        Spacer(Modifier.size(6.dp))
        CalPrimaryButton(
            if (busy) "Joining…" else "Join with invite",
            enabled = code.isNotBlank() && !busy,
            onClick = {
                val invite = ChatInvite.decode(code.trim())
                if (invite == null) {
                    result = "That invite code could not be read."
                    return@CalPrimaryButton
                }
                busy = true
                scope.launch {
                    result =
                        try {
                            client.joinWithInvitation(invite.namespaceId, invite.invitation)
                            "Joined \"${invite.spaceName}\"."
                        } catch (e: Exception) {
                            e.message ?: "Join failed"
                        } finally {
                            busy = false
                        }
                }
            },
        )
        result?.let { Text(it, color = Cal.textDim, fontSize = 13.sp) }
    }
}

/** Call a method through the relay: a query when it is a view, a warrant otherwise. */
@Composable
private fun QuickCall(
    client: MeroClient,
    state: MeroAuthState,
    initialContextId: String,
) {
    val scope = rememberCoroutineScope()
    var contextId by remember { mutableStateOf(initialContextId) }
    var method by remember { mutableStateOf("get") }
    var output by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    CalCard {
        CardHead(Icons.Outlined.Hub, "Call a method", "Read by query, or write by warrant")
        Spacer(Modifier.size(14.dp))
        CalTextField(contextId, { contextId = it }, "Context ID", mono = true)
        Spacer(Modifier.size(12.dp))
        CalTextField(method, { method = it }, "Method", mono = true)
        Spacer(Modifier.size(14.dp))
        CalPrimaryButton(
            if (running) "Running…" else "Run",
            icon = Icons.Outlined.PlayArrow,
            enabled = !running && contextId.isNotBlank() && method.isNotBlank() && state.relayUrl != null,
            modifier = Modifier.fillMaxWidth().testTag("runRpcButton"),
            onClick = {
                val relay = client.relay ?: return@CalPrimaryButton
                running = true
                scope.launch {
                    output =
                        try {
                            "Result: ${relay.call(contextId.trim(), method.trim()) ?: "null"}"
                        } catch (e: Exception) {
                            "Failed: ${e.message ?: e}"
                        } finally {
                            running = false
                        }
                }
            },
        )
        output?.let {
            Spacer(Modifier.size(12.dp))
            Text(
                it,
                color = Cal.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Cal.surfaceSunken)
                        .padding(10.dp)
                        .testTag("rpcResult"),
            )
        }
    }
}

/** The categorized, searchable SDK surface. */
@Composable
@Suppress("LongMethod")
private fun SdkListScreen(
    onOpenOp: (SDKOperation) -> Unit,
    onBack: () -> Unit,
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
        TopBar("Explore the SDK", subtitle = "${sdkOperations.size} methods", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = screenPad).testTag("opList"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
