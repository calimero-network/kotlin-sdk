package com.calimero.mero.sample.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calimero.mero.sample.chat.ChatScreen
import com.calimero.mero.sample.ui.Cal
import com.calimero.mero.sample.ui.CalPrimaryButton
import com.calimero.mero.sample.ui.CalTextField
import com.calimero.mero.sample.ui.MeroExplorerTheme
import kotlinx.coroutines.launch

private sealed interface ExplorerScreen {
    data object OpList : ExplorerScreen
    data class Op(val op: SDKOperation) : ExplorerScreen
    data object Chat : ExplorerScreen
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
    var node by remember { mutableStateOf(session.nodeUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "SDK Explorer",
            color = Cal.text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("loginTitle"),
        )
        Text("Sign in to a Calimero node to explore the full SDK.", color = Cal.textDim, fontSize = 13.sp)
        CalTextField(node, { node = it; session.nodeUrl = it }, "Node URL", Modifier.testTag("nodeURLField"))
        CalTextField(username, { username = it }, "Username", Modifier.testTag("usernameField"))
        CalTextField(password, { password = it }, "Password", Modifier.testTag("passwordField"), secure = true)

        session.errorMessage?.let { Text(it, color = Cal.error, modifier = Modifier.testTag("loginError")) }

        CalPrimaryButton(
            text = "Connect",
            onClick = { session.login(node, username, password) },
            enabled = !session.isLoading,
            modifier = Modifier.fillMaxWidth().testTag("loginButton"),
        )
        TextButton(onClick = { session.connect(context, node) }) {
            Text("Sign in with browser (SSO)", color = Cal.lime)
        }
    }
}

@Composable
private fun ExplorerHome(session: MeroSession) {
    var screen by remember { mutableStateOf<ExplorerScreen>(ExplorerScreen.OpList) }

    when (val current = screen) {
        is ExplorerScreen.OpList -> ExplorerList(
            session = session,
            onOpenChat = { screen = ExplorerScreen.Chat },
            onOpenOp = { screen = ExplorerScreen.Op(it) },
        )
        is ExplorerScreen.Op -> OperationRunner(session, current.op) { screen = ExplorerScreen.OpList }
        is ExplorerScreen.Chat -> {
            val chat = session.chat
            if (chat != null) {
                ChatScreen(chat) { screen = ExplorerScreen.OpList }
            } else {
                screen = ExplorerScreen.OpList
            }
        }
    }
}

@Composable
private fun ExplorerList(session: MeroSession, onOpenChat: () -> Unit, onOpenOp: (SDKOperation) -> Unit) {
    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(session.username.ifEmpty { "connected" }, color = Cal.text, fontWeight = FontWeight.SemiBold)
                Text(session.nodeUrl, color = Cal.textDim, fontSize = 12.sp)
                if (session.nodeSummary.isNotEmpty()) Text(session.nodeSummary, color = Cal.lime, fontSize = 11.sp)
            }
            TextButton(onClick = { session.logout() }) { Text("Log Out", color = Cal.lime) }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("opList"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Cal.surface)
                        .clickable(onClick = onOpenChat).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Open Chat", color = Cal.text, fontWeight = FontWeight.SemiBold)
                        Text("Spaces, channels & messaging on curb", color = Cal.textDim, fontSize = 12.sp)
                    }
                    Text("›", color = Cal.textDim)
                }
            }
            for (category in sdkCategories) {
                item {
                    Text(
                        category.uppercase(),
                        color = Cal.textDim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                items(sdkOperations.filter { it.category == category }, key = { it.id }) { op ->
                    OpRow(op, onOpenOp)
                }
            }
        }
    }
}

@Composable
private fun OpRow(op: SDKOperation, onOpenOp: (SDKOperation) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Cal.surface)
            .clickable { onOpenOp(op) }.padding(horizontal = 14.dp, vertical = 11.dp),
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
private fun OperationRunner(session: MeroSession, op: SDKOperation, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val inputs = remember { mutableStateMapOf<String, String>() }
    var output by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = Cal.lime) }
            Text(op.name, color = Cal.text, fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(op.summary, color = Cal.textDim, fontSize = 13.sp) }
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
                    text = "Run",
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (output.isNotEmpty()) {
                item { ResponseView(output, failed) }
            }
        }
    }
}

@Composable
private fun ResponseView(output: String, failed: Boolean) {
    Column {
        Text(
            if (failed) "ERROR" else "RESPONSE",
            color = if (failed) Cal.error else Cal.lime,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.padding(top = 4.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Cal.surface2)
                .horizontalScroll(rememberScrollState()).padding(12.dp),
        ) {
            Text(
                output,
                color = if (failed) Cal.error else Cal.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
}
