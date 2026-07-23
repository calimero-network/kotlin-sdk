package com.calimero.mero.sample.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calimero.mero.sample.ui.Cal
import com.calimero.mero.sample.ui.CalPrimaryButton
import com.calimero.mero.sample.ui.CalTextField
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface ChatRoute {
    data object Spaces : ChatRoute
    data class Channels(val space: ChatSpace) : ChatRoute
    data class Messages(val channel: ChatChannel) : ChatRoute
}

/** Chat home: routes spaces → channels → messages, with the install gate up front. */
@Composable
fun ChatScreen(service: ChatService, onClose: () -> Unit) {
    var route by remember { mutableStateOf<ChatRoute>(ChatRoute.Spaces) }

    LaunchedEffect(Unit) { service.detectInstalled() }

    when (val current = route) {
        is ChatRoute.Spaces -> ChatSpacesScreen(
            service = service,
            onOpen = { route = ChatRoute.Channels(it) },
            onClose = onClose,
        )
        is ChatRoute.Channels -> ChatChannelsScreen(
            service = service,
            space = current.space,
            onOpen = { route = ChatRoute.Messages(it) },
            onBack = { route = ChatRoute.Spaces },
        )
        is ChatRoute.Messages -> ChatMessagesScreen(
            service = service,
            channel = current.channel,
            onBack = { route = ChatRoute.Channels(spaceOf(service, current.channel)) },
        )
    }
}

private fun spaceOf(service: ChatService, channel: ChatChannel): ChatSpace =
    service.spaces.firstOrNull() ?: ChatSpace(channel.groupId, "space")

@Composable
private fun ChatSpacesScreen(service: ChatService, onOpen: (ChatSpace) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var showNewSpace by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(service.appId) { if (service.appId != null) service.loadSpaces() }

    Column(Modifier.fillMaxSize().background(Cal.bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Close", color = Cal.lime) }
            Text("Chat", color = Cal.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            if (service.appId != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("chatAdd")) {
                        Icon(Icons.Default.Add, "add", tint = Cal.lime)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("New space") }, onClick = { menuOpen = false; showNewSpace = true })
                        DropdownMenuItem(
                            text = { Text("Join with invite") },
                            onClick = { menuOpen = false; showJoin = true },
                        )
                    }
                }
            }
        }
        if (service.status.isNotEmpty()) Text(service.status, color = Cal.textDim, fontSize = 12.sp)

        if (service.appId == null) {
            InstallGate(service)
        } else {
            SpacesList(service.spaces, onOpen)
        }
    }

    if (showNewSpace) {
        NameDialog("New space", "Space name", onDismiss = { showNewSpace = false }) { name ->
            showNewSpace = false
            scope.launch { service.createSpace(name) }
        }
    }
    if (showJoin) {
        JoinDialog(onDismiss = { showJoin = false }) { code ->
            showJoin = false
            scope.launch { service.joinSpace(code) }
        }
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
private fun SpacesList(spaces: List<ChatSpace>, onOpen: (ChatSpace) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (spaces.isEmpty()) {
            item { Text("No spaces yet. Tap + to create one.", color = Cal.textDim, fontSize = 13.sp) }
        }
        items(spaces, key = { it.id }) { space ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Cal.surface)
                    .clickable { onOpen(space) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#", color = Cal.lime, fontWeight = FontWeight.Bold)
                Text(space.name, color = Cal.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
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

    LaunchedEffect(space.id) { service.loadChannels(space) }

    Column(Modifier.fillMaxSize().background(Cal.bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = Cal.lime) }
            Text(space.name, color = Cal.text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("channelAdd")) {
                    Icon(Icons.Default.Add, "add", tint = Cal.lime)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("New channel") }, onClick = { menuOpen = false; showNew = true })
                    DropdownMenuItem(
                        text = { Text("Invite people") },
                        onClick = {
                            menuOpen = false
                            scope.launch { invite = service.makeInvite(space) }
                        },
                    )
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (service.channels.isEmpty()) {
                item { Text("No channels yet. Tap + to create one.", color = Cal.textDim, fontSize = 13.sp) }
            }
            items(service.channels, key = { it.id }) { ch ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Cal.surface)
                        .clickable { onOpen(ch) }.padding(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("#", color = Cal.lime)
                    Text(ch.name, color = Cal.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
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
    invite?.let { code ->
        InviteSheet(code) { invite = null }
    }
}

@Composable
private fun ChatMessagesScreen(service: ChatService, channel: ChatChannel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(channel.id) {
        service.loadMessages(channel)
        runCatching {
            service.eventStream(channel).collect { service.loadMessages(channel) }
        }
    }

    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = Cal.lime) }
            Text("#${channel.name}", color = Cal.text, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
            ) { Icon(Icons.Default.Add, "send", tint = Cal.lime) }
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    val name = message.senderUsername.ifEmpty { message.sender.take(6) }
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Cal.lime),
            contentAlignment = Alignment.Center,
        ) { Text(name.take(1).uppercase(), color = Cal.bg, fontWeight = FontWeight.Bold) }
        Column(Modifier.padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = Cal.text, fontWeight = FontWeight.SemiBold)
                Text(shortTime(message.timestamp), color = Cal.textDim, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
            }
            Text(message.text, color = Cal.text)
        }
    }
}

@Composable
private fun NameDialog(title: String, placeholder: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { CalTextField(text, { text = it }, placeholder, Modifier.testTag("createField")) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun JoinDialog(onDismiss: () -> Unit, onJoin: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join with invite") },
        text = { CalTextField(text, { text = it }, "Paste an invite", Modifier.testTag("joinField"), singleLine = false) },
        confirmButton = { TextButton(onClick = { onJoin(text) }) { Text("Join space") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun InviteSheet(text: String, onDone: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Invite") },
        text = { Text(text, fontSize = 12.sp) },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
        },
    )
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

private fun shortTime(milliseconds: Long): String = timeFormat.format(Date(milliseconds))
