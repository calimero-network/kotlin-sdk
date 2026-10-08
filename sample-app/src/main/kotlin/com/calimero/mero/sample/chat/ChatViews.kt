package com.calimero.mero.sample.chat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calimero.mero.sample.ui.Cal
import com.calimero.mero.sample.ui.CalCard
import com.calimero.mero.sample.ui.CalPrimaryButton
import com.calimero.mero.sample.ui.CalSecondaryButton
import com.calimero.mero.sample.ui.CalTextField
import com.calimero.mero.sample.ui.EmptyState
import com.calimero.mero.sample.ui.Hairline
import com.calimero.mero.sample.ui.IdField
import com.calimero.mero.sample.ui.ListRow
import com.calimero.mero.sample.ui.TopBar
import com.calimero.mero.sample.ui.screenPad
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat home: the account's channels, then a channel's messages.
 *
 * [autoJoinInvite] is the e2e hook (the `invite` launch extra): when set, the screen redeems
 * that invite as the account on open.
 */
@Composable
fun ChatScreen(
    service: ChatService,
    onClose: () -> Unit,
    autoJoinInvite: String? = null,
) {
    var open by remember { mutableStateOf<ChatChannel?>(null) }
    LaunchedEffect(Unit) {
        if (!autoJoinInvite.isNullOrEmpty()) service.joinSpace(autoJoinInvite) else service.loadChannels()
    }
    val channel = open
    if (channel == null) {
        ChannelList(service, onOpen = { open = it }, onClose = onClose)
    } else {
        Messages(service, channel, onBack = { open = null })
    }
    service.lastInvite?.let { invite -> InviteDialog(invite, onDismiss = service::clearInvite) }
}

@Composable
private fun ChannelList(
    service: ChatService,
    onOpen: (ChatChannel) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var joining by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        TopBar(
            title = "Chat",
            subtitle = "Channels your account belongs to",
            onBack = onClose,
            actions = {
                IconButton(onClick = { creating = true }, modifier = Modifier.testTag("chatNewSpace")) {
                    Icon(Icons.Outlined.AddCircleOutline, contentDescription = "New space", tint = Cal.textDim)
                }
                IconButton(onClick = { joining = true }) {
                    Icon(Icons.Outlined.GroupAdd, contentDescription = "Join with an invite", tint = Cal.textDim)
                }
                IconButton(onClick = { scope.launch { service.loadChannels() } }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = Cal.textDim)
                }
            },
        )
        if (service.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cal.accentInk, trackColor = Cal.border)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = screenPad),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Spacer(Modifier.size(4.dp)) }
            if (service.status.isNotEmpty()) item { Text(service.status, color = Cal.textFaint, fontSize = 13.sp) }
            if (service.channels.isEmpty() && !service.busy) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.ChatBubbleOutline,
                        title = "No channels yet",
                        body = "Start a space of your own and invite people, or join one with an invite code.",
                        action = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                CalPrimaryButton("Create a space", onClick = { creating = true }, icon = Icons.Outlined.AddCircleOutline)
                                CalSecondaryButton("Join with an invite", onClick = { joining = true }, icon = Icons.Outlined.GroupAdd)
                            }
                        },
                    )
                }
            } else {
                item {
                    CalCard(padding = 0.dp) {
                        service.channels.forEachIndexed { i, ch ->
                            if (i > 0) Hairline()
                            ListRow(Icons.Outlined.Tag, ch.name, ch.kind, onClick = { onOpen(ch) })
                        }
                    }
                }
            }
        }
    }
    if (creating) {
        NewSpaceDialog(
            onDismiss = { creating = false },
            onCreate = { name ->
                creating = false
                scope.launch { service.createSpace(name) }
            },
        )
    }
    if (joining) {
        JoinDialog(
            onDismiss = { joining = false },
            onJoin = { code ->
                joining = false
                scope.launch { service.joinSpace(code) }
            },
        )
    }
}

@Composable
@Suppress("LongMethod")
private fun Messages(
    service: ChatService,
    channel: ChatChannel,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var info by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(channel.contextId) {
        service.registerProfile(channel.contextId)
        service.loadMessages(channel)
    }
    LaunchedEffect(channel.contextId) {
        runCatching { service.eventStream(channel).collect { service.loadMessages(channel) } }
    }
    LaunchedEffect(service.messages.size) {
        if (service.messages.isNotEmpty()) listState.animateScrollToItem(service.messages.size - 1)
    }

    Column(Modifier.fillMaxSize().background(Cal.bg).imePadding()) {
        TopBar(
            title = "#${channel.name}",
            subtitle = "${service.messages.size} messages",
            onBack = onBack,
            actions = {
                IconButton(onClick = { info = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "Channel details", tint = Cal.textDim)
                }
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = screenPad),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.size(6.dp)) }
            if (service.messages.isEmpty()) {
                item {
                    EmptyState(Icons.Outlined.ChatBubbleOutline, "No messages yet", "Say hello to start the conversation.")
                }
            }
            items(service.messages, key = { it.id }) { MessageBubble(it, mine = it.senderUsername == service.username) }
            item { Spacer(Modifier.size(6.dp)) }
        }
        Hairline()
        Row(
            Modifier.fillMaxWidth().background(Cal.surface).padding(horizontal = screenPad, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message #${channel.name}", color = Cal.textFaint) },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f).testTag("chatComposer"),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Cal.surfaceSunken,
                        unfocusedContainerColor = Cal.surfaceSunken,
                        focusedBorderColor = Cal.text,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = Cal.text,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val text = draft.trim()
                    draft = ""
                    scope.launch { service.sendMessage(channel, text) }
                },
                enabled = draft.isNotBlank(),
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (draft.isNotBlank()) Cal.lime else Cal.lime.copy(alpha = 0.5f))
                        .testTag("chatSend"),
            ) {
                Icon(Icons.Outlined.ArrowUpward, contentDescription = "Send", tint = Cal.onLime)
            }
        }
        Text(
            "Posting as ${service.username}",
            color = Cal.textFaint,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().background(Cal.surface).padding(start = screenPad, bottom = 8.dp),
        )
    }

    if (info) {
        AlertDialog(
            onDismissRequest = { info = false },
            confirmButton = {
                CalPrimaryButton(
                    "Invite people",
                    onClick = {
                        info = false
                        scope.launch { service.inviteTo(channel) }
                    },
                    enabled = channel.groupId != null,
                    icon = Icons.Outlined.PersonAddAlt,
                )
            },
            dismissButton = { CalSecondaryButton("Close", onClick = { info = false }) },
            title = { Text("Technical details", color = Cal.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    IdField("Context", channel.contextId)
                    channel.groupId?.let { IdField("Group", it) }
                }
            },
            containerColor = Cal.surface,
        )
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    mine: Boolean,
) {
    val shape =
        if (mine) {
            RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)
        } else {
            RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
        }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        if (!mine) {
            Avatar(message.senderUsername.ifEmpty { message.sender })
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (!mine) {
                Text(
                    message.senderUsername.ifEmpty { message.sender.take(SENDER_PREFIX) },
                    color = toneOf(message.senderUsername).second,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Box(
                Modifier
                    .widthIn(max = 280.dp)
                    .clip(shape)
                    .background(if (mine) Cal.lime else Cal.surface)
                    .border(1.dp, if (mine) Cal.limeEdge else Cal.border, shape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(message.text, color = Cal.text, fontSize = 15.sp, lineHeight = 21.sp)
            }
            if (message.timestamp > 0) Text(shortTime(message.timestamp), color = Cal.textFaint, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Avatar(name: String) {
    val (bg, fg) = toneOf(name)
    Box(Modifier.size(28.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Deterministic avatar tone (forum `index.css` tones), by name hash. */
private fun toneOf(name: String): Pair<Color, Color> =
    TONES[Math.floorMod(name.hashCode(), TONES.size)]

private val TONES =
    listOf(
        Color(0xFFF0FFD6) to Color(0xFF4A7300),
        Color(0xFFE6EEFB) to Color(0xFF1D4F9F),
        Color(0xFFFBE9E4) to Color(0xFF9A3412),
        Color(0xFFEFE8FB) to Color(0xFF5B3AA8),
        Color(0xFFFDF3DC) to Color(0xFF8A5300),
        Color(0xFFE2F4F1) to Color(0xFF116A5C),
    )

@Composable
private fun JoinDialog(
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Cal.surface,
        title = { Text("Join a space", color = Cal.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Paste an invite code or link. Your account is admitted through a node the invitation names, " +
                        "which becomes your relay if you have none yet.",
                    color = Cal.textDim,
                    fontSize = 14.sp,
                )
                CalTextField(code, { code = it }, "Invite", singleLine = false, mono = true)
            }
        },
        confirmButton = { CalPrimaryButton("Join", onClick = { onJoin(code.trim()) }, enabled = code.isNotBlank()) },
        dismissButton = { CalSecondaryButton("Cancel", onClick = onDismiss) },
    )
}

@Composable
private fun NewSpaceDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Cal.surface,
        title = { Text("Create a space", color = Cal.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Your account founds the space through its relay and owns it. It starts with a #general channel, " +
                        "and you get an invite to share.",
                    color = Cal.textDim,
                    fontSize = 14.sp,
                )
                CalTextField(name, { name = it }, "Name", placeholder = "Team", modifier = Modifier.testTag("spaceName"))
            }
        },
        confirmButton = { CalPrimaryButton("Create", onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) },
        dismissButton = { CalSecondaryButton("Cancel", onClick = onDismiss) },
    )
}

@Composable
private fun InviteDialog(
    invite: ChatInvite,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val link = remember(invite) { invite.shareableLink() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Cal.surface,
        title = { Text("Invite to ${invite.spaceName}", color = Cal.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Anyone with this link can join for the next 24 hours. It is signed by this device.",
                    color = Cal.textDim,
                    fontSize = 14.sp,
                )
                IdField("Invite link", link, modifier = Modifier.testTag("inviteLink"))
            }
        },
        confirmButton = {
            CalPrimaryButton(
                "Share",
                onClick = {
                    val send =
                        Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, link)
                    context.startActivity(Intent.createChooser(send, "Share invite"))
                },
                icon = Icons.Outlined.Share,
            )
        },
        dismissButton = {
            CalSecondaryButton(
                "Copy",
                onClick = {
                    clipboard.setText(AnnotatedString(link))
                    onDismiss()
                },
                icon = Icons.Outlined.ContentCopy,
            )
        },
    )
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun shortTime(milliseconds: Long): String = timeFormat.format(Date(milliseconds))

private const val SENDER_PREFIX = 8
