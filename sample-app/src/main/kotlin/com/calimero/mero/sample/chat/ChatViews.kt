package com.calimero.mero.sample.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calimero.mero.sample.ui.Cal
import com.calimero.mero.sample.ui.CalCard
import com.calimero.mero.sample.ui.CalPrimaryButton
import com.calimero.mero.sample.ui.CalSecondaryButton
import com.calimero.mero.sample.ui.CalTextField
import com.calimero.mero.sample.ui.screenPad
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface ChatRoute {
    data object Spaces : ChatRoute

    data class Channels(
        val space: ChatSpace,
    ) : ChatRoute

    data class Messages(
        val space: ChatSpace,
        val channel: ChatChannel,
    ) : ChatRoute
}

/**
 * Chat home: routes spaces → channels → messages, with the install gate up front.
 *
 * [autoJoinInvite] is the e2e hook (the `invite` launch extra, Android analog of the Swift sample's
 * `E2E_JOIN`): when set, the screen installs curb and joins that invite on open, so the multi-user
 * harness can hand a guest an invite without typing it.
 */
@Composable
fun ChatScreen(
    service: ChatService,
    onClose: () -> Unit,
    autoJoinInvite: String? = null,
) {
    var route by remember { mutableStateOf<ChatRoute>(ChatRoute.Spaces) }

    LaunchedEffect(Unit) {
        if (!autoJoinInvite.isNullOrEmpty() && service.appId == null) {
            service.setup()
            service.joinSpace(autoJoinInvite)
        } else {
            // Skip the install gate if curb is already installed on this node.
            service.detectInstalled()
        }
    }

    when (val current = route) {
        is ChatRoute.Spaces ->
            ChatSpacesScreen(
                service = service,
                onOpen = { route = ChatRoute.Channels(it) },
                onClose = onClose,
            )
        is ChatRoute.Channels ->
            ChatChannelsScreen(
                service = service,
                space = current.space,
                onOpen = { route = ChatRoute.Messages(current.space, it) },
                onBack = { route = ChatRoute.Spaces },
            )
        is ChatRoute.Messages ->
            ChatMessagesScreen(
                service = service,
                channel = current.channel,
                onBack = { route = ChatRoute.Channels(current.space) },
            )
    }
}

@Composable
private fun ChatSpacesScreen(
    service: ChatService,
    onOpen: (ChatSpace) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showNewSpace by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(service.appId) { if (service.appId != null) service.loadSpaces() }

    Box(Modifier.fillMaxSize().background(Cal.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Close", color = Cal.lime) }
                Text("Chat", color = Cal.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (service.appId != null) {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("chatAdd")) {
                            Icon(Icons.Default.Add, "add", tint = Cal.lime)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("New space") },
                                onClick = {
                                    menuOpen = false
                                    showNewSpace = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Join existing space") },
                                onClick = {
                                    menuOpen = false
                                    showJoin = true
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                onClick = {
                                    menuOpen = false
                                    scope.launch { service.loadSpaces() }
                                },
                            )
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = screenPad, vertical = 14.dp)) {
                StatusLine(service.status)
                if (service.appId == null) {
                    InstallGate(service)
                } else {
                    SpacesList(service, onOpen)
                }
            }
        }
        if (service.busy) BusyOverlay(service.status)
    }

    if (showNewSpace) {
        NameDialog("New space", "Space name", onDismiss = { showNewSpace = false }) { name ->
            showNewSpace = false
            scope.launch { service.createSpace(name) }
        }
    }
    if (showJoin) {
        JoinDialog(service, onDismiss = { showJoin = false })
    }
}

/** Dimmed, blocking progress overlay carrying the service's current status message. */
@Composable
private fun BusyOverlay(status: String) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Cal.surface)
                .border(1.dp, Cal.border, RoundedCornerShape(16.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(color = Cal.lime)
            Text(status.ifEmpty { "Working…" }, color = Cal.text, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StatusLine(status: String) {
    if (status.isNotEmpty()) {
        Text(status, color = Cal.textDim, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun InstallGate(service: ChatService) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text("mero-chat", color = Cal.text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text(
            "Install the curb chat app (com.calimero.curb) from the registry to start.",
            color = Cal.textDim,
            fontSize = 13.sp,
        )
        CalPrimaryButton(
            text = "Install mero-chat",
            onClick = { scope.launch { service.setup() } },
            enabled = !service.busy,
            modifier = Modifier.fillMaxWidth().testTag("installChat"),
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SpacesList(
    service: ChatService,
    onOpen: (ChatSpace) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (service.spaces.isEmpty()) {
            item { Text("No spaces yet. Tap + to create one.", color = Cal.textDim, fontSize = 13.sp) }
        }
        items(service.spaces, key = { it.id }) { space ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Cal.surface)
                    .border(1.dp, Cal.border, RoundedCornerShape(12.dp))
                    .clickable { onOpen(space) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#", color = Cal.lime, fontWeight = FontWeight.Bold)
                Text(
                    space.name,
                    color = Cal.text,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp),
                )
                Spacer(Modifier.weight(1f))
                Text("›", color = Cal.textDim)
            }
        }
    }
}

@Composable
private fun ChatChannelsScreen(
    service: ChatService,
    space: ChatSpace,
    onOpen: (ChatChannel) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showNew by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf<String?>(null) }
    var inviteError by remember { mutableStateOf(false) }

    LaunchedEffect(space.id) { service.loadChannels(space) }

    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        ChannelsHeader(
            service = service,
            space = space,
            menuOpen = menuOpen,
            onMenu = { menuOpen = it },
            onBack = onBack,
            onNewChannel = { showNew = true },
            onInvite = { code -> if (code != null) invite = code else inviteError = true },
        )
        Column(Modifier.padding(horizontal = screenPad, vertical = 14.dp)) {
            if (service.status.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (service.busy) {
                        CircularProgressIndicator(
                            color = Cal.lime,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp).padding(end = 8.dp),
                        )
                    }
                    Text(service.status, color = Cal.textDim, fontSize = 12.sp)
                }
                Spacer(Modifier.size(10.dp))
            }
            if (service.channels.isEmpty() && !service.busy) {
                EmptyChannelsCard { scope.launch { service.resync(space) } }
                Spacer(Modifier.size(10.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(service.channels, key = { it.id }) { ch ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Cal.surface)
                            .border(1.dp, Cal.border, RoundedCornerShape(12.dp))
                            .clickable { onOpen(ch) }
                            .padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("#", color = Cal.lime)
                        Text(
                            ch.name,
                            color = Cal.text,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        Text("›", color = Cal.textDim)
                    }
                }
            }
        }
    }

    if (showNew) {
        NameDialog("New channel", "channel-name", onDismiss = { showNew = false }) { name ->
            showNew = false
            scope.launch { service.createChannel(space, name, open = true) }
        }
    }
    invite?.let { code -> InviteSheet(code, space.name) { invite = null } }
    if (inviteError) {
        AlertDialog(
            onDismissRequest = { inviteError = false },
            title = { Text("Couldn't create invite") },
            text = { Text(service.status.ifEmpty { "The node did not return an invitation." }) },
            confirmButton = { TextButton(onClick = { inviteError = false }) { Text("OK") } },
        )
    }
}

/** Back / title / refresh / overflow bar for a space's channel list. */
@Composable
private fun ChannelsHeader(
    service: ChatService,
    space: ChatSpace,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onBack: () -> Unit,
    onNewChannel: () -> Unit,
    onInvite: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = Cal.lime) }
        Text(space.name, color = Cal.text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = { scope.launch { service.loadChannels(space) } },
            modifier = Modifier.testTag("channelRefresh"),
        ) { Icon(Icons.Default.Refresh, "refresh", tint = Cal.lime) }
        Box {
            IconButton(onClick = { onMenu(true) }, modifier = Modifier.testTag("channelAdd")) {
                Icon(Icons.Default.Add, "add", tint = Cal.lime)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }) {
                DropdownMenuItem(text = { Text("New channel") }, onClick = {
                    onMenu(false)
                    onNewChannel()
                })
                DropdownMenuItem(
                    text = { Text("Invite people") },
                    onClick = {
                        onMenu(false)
                        scope.launch { onInvite(service.makeInvite(space)) }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Sync now") },
                    onClick = {
                        onMenu(false)
                        scope.launch { service.resync(space) }
                    },
                )
            }
        }
    }
}

/** Shown when a space has no channels — usually a joined space still syncing from the inviter. */
@Composable
private fun EmptyChannelsCard(onSync: () -> Unit) {
    CalCard {
        Text("No channels yet.", color = Cal.text, fontSize = 14.sp)
        Spacer(Modifier.size(6.dp))
        Text(
            "If you just joined, channels sync from the inviter — tap Sync. Or create one with +.",
            color = Cal.textDim,
            fontSize = 12.sp,
        )
        Spacer(Modifier.size(10.dp))
        CalSecondaryButton(
            text = "Sync now",
            onClick = onSync,
            modifier = Modifier.fillMaxWidth().testTag("syncNow"),
        )
    }
}

@Composable
private fun ChatMessagesScreen(
    service: ChatService,
    channel: ChatChannel,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(channel.id) {
        // Live updates over SSE — reload messages on each node event for this context (no polling).
        // Cancelling the effect closes the stream.
        service.loadMessages(channel)
        runCatching {
            service.eventStream(channel).collect { service.loadMessages(channel) }
        }
    }
    // Keep the newest message in view, like the Swift sample's ScrollViewReader.
    LaunchedEffect(service.messages.size) {
        if (service.messages.isNotEmpty()) listState.animateScrollToItem(service.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = Cal.lime) }
            Text("#${channel.name}", color = Cal.text, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = screenPad, vertical = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(service.messages, key = { it.id }) { message -> MessageRow(message) }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CalTextField(draft, { draft = it }, "Message #${channel.name}", Modifier.weight(1f).testTag("messageField"))
            IconButton(
                onClick = {
                    val text = draft
                    draft = ""
                    scope.launch { service.sendMessage(channel, text) }
                },
                enabled = draft.isNotBlank(),
                modifier = Modifier.testTag("sendMessage"),
            ) { Icon(Icons.AutoMirrored.Filled.Send, "send", tint = Cal.lime) }
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    val name = message.senderUsername.ifEmpty { message.sender.take(6) }
    Row(verticalAlignment = Alignment.Top) {
        ChatAvatar(name)
        Column(Modifier.padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = Cal.text, fontWeight = FontWeight.SemiBold)
                Text(
                    shortTime(message.timestamp),
                    color = Cal.textDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(message.text, color = Cal.text.copy(alpha = 0.92f))
        }
    }
}

/** A colored initials avatar — the color is a deterministic (djb2) hash of the display name. */
@Composable
private fun ChatAvatar(
    name: String,
    size: Int = 34,
) {
    val initials =
        name
            .split(" ")
            .take(2)
            .mapNotNull { it.firstOrNull()?.toString() }
            .joinToString("")
            .ifEmpty { name.take(1) }
            .uppercase()
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(avatarColor(name)),
        contentAlignment = Alignment.Center,
    ) { Text(initials, color = Cal.bg, fontWeight = FontWeight.Bold, fontSize = (size * 0.4).sp) }
}

private val avatarPalette =
    listOf(
        Color(0xFFA5FF11), Color(0xFFFF7A00), Color(0xFF38BDF8), Color(0xFFF472B6),
        Color(0xFFA78BFA), Color(0xFF34D399), Color(0xFFFBBF24),
    )

private fun avatarColor(name: String): Color {
    var hash = 5381L
    for (byte in name.toByteArray()) hash = (hash shl 5) + hash + byte
    val index = ((hash % avatarPalette.size) + avatarPalette.size) % avatarPalette.size
    return avatarPalette[index.toInt()]
}

@Composable
private fun NameDialog(
    title: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { CalTextField(text, { text = it }, placeholder, Modifier.testTag("createField")) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Paste-an-invite dialog. Single-line: an invite code is one line, so a one-liner avoids stray line
 * breaks that could mangle the code, and a Paste button makes the (long, unreadable) code easy to
 * drop in. Dismisses itself once the join reports success.
 */
@Composable
private fun JoinDialog(
    service: ChatService,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!service.busy) onDismiss() },
        title = { Text("Join a space") },
        text = {
            Column {
                Text("Paste an invite code", color = Cal.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CalTextField(
                        text,
                        { text = it },
                        "invite code",
                        Modifier.weight(1f).testTag("joinField"),
                        enabled = !service.busy,
                    )
                    IconButton(
                        onClick = { clipboard.getText()?.text?.let { text = it } },
                        enabled = !service.busy,
                        modifier = Modifier.testTag("pasteInvite"),
                    ) { Text("📋") }
                }
                if (service.status.isNotEmpty()) {
                    Spacer(Modifier.size(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (service.busy) {
                            CircularProgressIndicator(
                                color = Cal.lime,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp).padding(end = 8.dp),
                            )
                        }
                        Text(
                            service.status,
                            color = if (service.status.startsWith("✗")) Cal.error else Cal.textDim,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        // Strip any whitespace/newlines a paste may have introduced.
                        service.joinSpace(text.trim())
                        if (service.status.startsWith("✓")) onDismiss()
                    }
                },
                enabled = !service.busy && text.isNotBlank(),
            ) { Text(if (service.busy) "Joining…" else "Join space") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !service.busy) { Text("Close") } },
    )
}

/** Shows a generated invite code with Copy and Share, mirroring the Swift `InviteSheet`. */
@Composable
private fun InviteSheet(
    text: String,
    spaceName: String,
    onDone: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Invite") },
        text = {
            Column {
                Text(
                    "Share this invite code so someone can join \"$spaceName\".",
                    color = Cal.textDim,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.size(10.dp))
                Box(
                    Modifier
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Cal.surface2)
                        .border(1.dp, Cal.border, RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Text(text, color = Cal.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(text))
                        copied = true
                    },
                ) { Text(if (copied) "Copied" else "Copy") }
                TextButton(
                    onClick = {
                        val send =
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                        context.startActivity(Intent.createChooser(send, "Share invite"))
                    },
                ) { Text("Share") }
            }
        },
    )
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

private fun shortTime(milliseconds: Long): String = timeFormat.format(Date(milliseconds))
