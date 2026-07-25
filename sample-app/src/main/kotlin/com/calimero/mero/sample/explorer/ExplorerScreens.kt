package com.calimero.mero.sample.explorer

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calimero.mero.sample.chat.ChatScreen
import com.calimero.mero.sample.ui.Cal
import com.calimero.mero.sample.ui.CalCard
import com.calimero.mero.sample.ui.CalLogo
import com.calimero.mero.sample.ui.CalMark
import com.calimero.mero.sample.ui.CalPrimaryButton
import com.calimero.mero.sample.ui.CalTextField
import com.calimero.mero.sample.ui.MeroExplorerTheme
import com.calimero.mero.sample.ui.MinimalField
import com.calimero.mero.sample.ui.screenPad
import kotlinx.coroutines.launch

private sealed interface ExplorerScreen {
    /** The clean landing: Open Chat + Explore SDK. */
    data object Landing : ExplorerScreen

    data object SdkList : ExplorerScreen

    data class Op(
        val op: SDKOperation,
    ) : ExplorerScreen

    data object Chat : ExplorerScreen

    data object Logs : ExplorerScreen
}

/** Root of the real-node explorer. Routes login ⇄ explorer on [MeroSession] auth state. */
@Composable
fun ExplorerApp(session: MeroSession) {
    MeroExplorerTheme {
        Column(Modifier.fillMaxSize().background(Cal.bg)) {
            if (session.isAuthenticated) {
                ExplorerHome(session)
            } else {
                ExplorerLogin(session)
            }
        }
    }
}

@Composable
private fun ExplorerLogin(session: MeroSession) {
    val context = LocalContext.current
    // The node URL lives on the session, not in a `remember` snapshot of it: MainActivity applies the
    // `nodeUrl` launch extra from a LaunchedEffect, which runs *after* this screen's first
    // composition — a remembered copy would keep the default port and the login would be posted to a
    // node that isn't there (which is exactly how the live e2e was failing).
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }

    if (showLogs) {
        LogsScreen(session) { showLogs = false }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.size(64.dp))
        CalMark(size = 62.dp)
        Spacer(Modifier.size(18.dp))
        Text(
            "SDK Explorer",
            color = Cal.text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("loginTitle"),
        )
        Spacer(Modifier.size(7.dp))
        Text(
            "Sign in to a Calimero node to explore the full Mero SDK.",
            color = Cal.textDim,
            fontSize = 14.sp,
        )
        Spacer(Modifier.size(34.dp))
        Column(
            Modifier.widthIn(max = 430.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            MinimalField(
                session.nodeUrl,
                { session.nodeUrl = it },
                "Node URL",
                Modifier.testTag("nodeURLField"),
                leading = { CalMark(size = 16.dp) },
            )
            MinimalField(
                username,
                { username = it },
                "Username",
                Modifier.testTag("usernameField"),
                leading = { Icon(Icons.Default.Person, null, tint = Cal.textDim) },
            )
            MinimalField(
                password,
                { password = it },
                "Password",
                Modifier.testTag("passwordField"),
                leading = { Icon(Icons.Default.Lock, null, tint = Cal.textDim) },
                secure = true,
            )

            session.errorMessage?.let {
                Text(it, color = Cal.error, fontSize = 13.sp, modifier = Modifier.testTag("loginError"))
            }

            CalPrimaryButton(
                text = if (session.isLoading) "Connecting…" else "Connect",
                onClick = { session.login(session.nodeUrl, username, password) },
                enabled = !session.isLoading,
                modifier = Modifier.fillMaxWidth().testTag("loginButton"),
            )
            TextButton(onClick = { session.connect(context, session.nodeUrl) }, modifier = Modifier.fillMaxWidth()) {
                Text("Sign in with browser (SSO)", color = Cal.lime)
            }
            TextButton(onClick = { showLogs = true }, modifier = Modifier.testTag("connectionLogs")) {
                Text("Connection logs", color = Cal.textDim, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.size(40.dp))
    }
}

@Composable
private fun ExplorerHome(session: MeroSession) {
    var screen by remember { mutableStateOf<ExplorerScreen>(ExplorerScreen.Landing) }

    when (val current = screen) {
        is ExplorerScreen.Landing ->
            ExplorerLanding(
                session = session,
                onOpenChat = { screen = ExplorerScreen.Chat },
                onExploreSdk = { screen = ExplorerScreen.SdkList },
                onOpenLogs = { screen = ExplorerScreen.Logs },
            )
        is ExplorerScreen.SdkList ->
            SdkListScreen(
                onOpenOp = { screen = ExplorerScreen.Op(it) },
                onBack = { screen = ExplorerScreen.Landing },
            )
        is ExplorerScreen.Op -> OperationRunner(session, current.op) { screen = ExplorerScreen.SdkList }
        is ExplorerScreen.Logs -> LogsScreen(session) { screen = ExplorerScreen.Landing }
        is ExplorerScreen.Chat -> {
            val chat = session.chat
            if (chat != null) {
                ChatScreen(
                    service = chat,
                    onClose = { screen = ExplorerScreen.Landing },
                    autoJoinInvite = session.autoJoinInvite,
                )
            } else {
                screen = ExplorerScreen.Landing
            }
        }
    }
}

/** The clean landing: who/where you are, plus the two big entries. */
@Composable
private fun ExplorerLanding(
    session: MeroSession,
    onOpenChat: () -> Unit,
    onExploreSdk: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenLogs, modifier = Modifier.testTag("openLogs")) {
                Text(">_", color = Cal.lime, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            CalLogo(size = 22.dp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { session.logout() }) { Text("Log Out", color = Cal.lime) }
        }
        Column(
            Modifier.padding(horizontal = screenPad, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CalCard {
                Text(
                    session.username.ifEmpty { "connected" },
                    color = Cal.text,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(session.nodeUrl, color = Cal.textDim, fontSize = 12.sp)
                if (session.nodeSummary.isNotEmpty()) {
                    Text(session.nodeSummary, color = Cal.lime, fontSize = 11.sp)
                }
            }
            BigEntry(
                title = "Open Chat Example",
                subtitle = "Spaces, channels & messaging on curb",
                accent = true,
                tag = "openChat",
                onClick = onOpenChat,
            )
            BigEntry(
                title = "Explore SDK",
                subtitle = "${sdkOperations.size} methods across ${sdkCategories.size} categories",
                accent = false,
                tag = "exploreSDK",
                onClick = onExploreSdk,
            )
        }
    }
}

@Composable
private fun BigEntry(
    title: String,
    subtitle: String,
    accent: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Cal.surface)
            .border(1.dp, Cal.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (accent) Cal.lime else Cal.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (accent) "💬" else "▦",
                color = if (accent) Cal.bg else Cal.lime,
                fontSize = 20.sp,
            )
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, color = Cal.text, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Cal.textDim, fontSize = 12.sp)
        }
        Text("›", color = Cal.textDim)
    }
}

/**
 * The categorized, searchable SDK surface behind "Explore SDK". Categories are collapsed by default
 * and force-expanded while a search is active, mirroring the Swift sample's DisclosureGroup list.
 */
@Composable
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
                        (
                            query.isEmpty() ||
                                op.name.lowercase().contains(query) ||
                                op.summary.lowercase().contains(query) ||
                                op.category.lowercase().contains(query)
                        )
                }
            if (ops.isEmpty()) null else category to ops
        }
    val matchCount = sections.sumOf { it.second.size }

    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = Cal.lime) }
            Text("Explore SDK", color = Cal.text, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = screenPad).testTag("opList"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                // A plain, always-visible search field (rather than a collapsing search bar, which
                // isn't reliably reachable in UI tests).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Cal.surface2)
                        .border(1.dp, Cal.border, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, null, tint = Cal.textDim, modifier = Modifier.size(16.dp))
                    CalTextField(
                        search,
                        { search = it },
                        "Search ${sdkOperations.size} methods",
                        Modifier.weight(1f).padding(start = 8.dp).testTag("sdkSearch"),
                    )
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { search = "" }, modifier = Modifier.testTag("sdkSearchClear")) {
                            Icon(Icons.Default.Clear, "clear", tint = Cal.textDim)
                        }
                    }
                }
            }
            item {
                Text(
                    if (search.isEmpty()) "SDK OPTIONS" else "$matchCount RESULTS",
                    color = Cal.textDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(sections, key = { it.first }) { (category, ops) ->
                CategoryCard(
                    category = category,
                    ops = ops,
                    expanded = search.isNotEmpty() || expanded[category] == true,
                    onToggle = { expanded[category] = !(expanded[category] ?: false) },
                    onOpenOp = onOpenOp,
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: String,
    ops: List<SDKOperation>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenOp: (SDKOperation) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Cal.surface)
            .border(1.dp, Cal.border, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).testTag("category:$category"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(category, color = Cal.text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${ops.size}", color = Cal.textDim, fontSize = 12.sp)
            Text(if (expanded) " ⌄" else " ›", color = Cal.lime)
        }
        if (expanded) {
            Spacer(Modifier.size(6.dp))
            ops.forEachIndexed { index, op ->
                OpRow(op, onOpenOp)
                if (index < ops.lastIndex) HorizontalDivider(color = Cal.border)
            }
        }
    }
}

@Composable
private fun OpRow(
    op: SDKOperation,
    onOpenOp: (SDKOperation) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenOp(op) }
            .padding(horizontal = 14.dp, vertical = 11.dp)
            // Tagged per method: the search box holds the same text as the row it filtered to, so
            // matching on text alone is ambiguous for a UI test.
            .testTag("op:${op.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(op.name, color = Cal.text, fontWeight = FontWeight.SemiBold)
            Text(op.summary, color = Cal.textDim, fontSize = 12.sp)
        }
        Text("›", color = Cal.textDim)
    }
}

@Composable
private fun OperationRunner(
    session: MeroSession,
    op: SDKOperation,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val inputs = remember { mutableStateMapOf<String, String>() }
    var output by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = Cal.lime) }
            Text(op.name, color = Cal.text, fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = screenPad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text(op.summary, color = Cal.textDim, fontSize = 13.sp)
                    Text(op.category, color = Cal.lime, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            items(op.fields, key = { it.id }) { field ->
                CalTextField(
                    value = inputs[field.id] ?: "",
                    onValueChange = { inputs[field.id] = it },
                    label = field.label,
                    singleLine = field.kind == OpFieldKind.LINE,
                )
            }
            item {
                CalPrimaryButton(
                    text = if (running) "Running…" else "Run",
                    onClick = {
                        val mero = session.mero ?: return@CalPrimaryButton
                        running = true
                        scope.launch {
                            try {
                                output = op.run(mero, inputs.toMap())
                                failed = false
                            } catch (e: Exception) {
                                output = e.message ?: e.toString()
                                failed = true
                            } finally {
                                running = false
                            }
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().testTag("runOp"),
                )
            }
            if (output.isNotEmpty()) {
                item { ResponseView(output, failed) }
            }
        }
    }
}

@Composable
private fun ResponseView(
    output: String,
    failed: Boolean,
) {
    Column {
        Text(
            if (failed) "ERROR" else "RESPONSE",
            color = if (failed) Cal.error else Cal.lime,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Cal.surface2)
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

/**
 * The in-app diagnostics log — every request/auth event the session recorded, copyable and
 * shareable, so a connection problem is debuggable without Android Studio attached.
 */
@Composable
private fun LogsScreen(
    session: MeroSession,
    onDone: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(session.logs.size) {
        if (session.logs.isNotEmpty()) listState.animateScrollToItem(session.logs.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { session.clearLogs() }) { Text("Clear", color = Cal.lime) }
            Text("Diagnostics", color = Cal.text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(session.logText())) },
                modifier = Modifier.testTag("copyLogs"),
            ) { Text("Copy", color = Cal.lime) }
            TextButton(onClick = onDone) { Text("Done", color = Cal.lime) }
        }
        if (session.logs.isEmpty()) {
            Text(
                "No activity yet. Try connecting.",
                color = Cal.textDim,
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp).testTag("logList"),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            items(session.logs) { line ->
                Row {
                    Text(
                        line.level,
                        color = levelColor(line.level),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.size(width = 14.dp, height = 16.dp),
                    )
                    Text(
                        line.text,
                        color = Cal.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

private fun levelColor(level: String) =
    when (level) {
        "✗" -> Cal.error
        "✓" -> Cal.lime
        "!" -> Cal.orange
        "→" -> Cal.text
        else -> Cal.textDim
    }
