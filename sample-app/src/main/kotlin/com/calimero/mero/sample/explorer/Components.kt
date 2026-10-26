package com.calimero.mero.sample.explorer

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calimero.mero.account.CloudAccount
import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.compose.LoginMode
import com.calimero.mero.compose.LoginView
import com.calimero.mero.compose.MeroAppConfig
import com.calimero.mero.compose.MeroClient
import com.calimero.mero.compose.MeroTheme
import com.calimero.mero.compose.NodeSignInMethod
import com.calimero.mero.compose.ProvideMeroTheme
import com.calimero.mero.sample.MainActivity
import com.calimero.mero.sample.ui.Cal
import com.calimero.mero.sample.ui.Callout
import com.calimero.mero.sample.ui.CalloutKind
import com.calimero.mero.sample.ui.IconTile
import com.calimero.mero.sample.ui.SectionLabel
import com.calimero.mero.sample.ui.TopBar
import com.calimero.mero.storage.MemoryTokenStore

/** The "Login component" entry under Run all: mero-compose's [LoginView], live, with its flags. */
@Composable
fun ComponentsEntry(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Cal.radiusCard))
            .background(Cal.surface)
            .border(1.dp, Cal.border, RoundedCornerShape(Cal.radiusCard))
            .clickable(onClick = onClick)
            .padding(16.dp)
            .testTag("componentsEntry"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(Icons.Outlined.ViewCarousel, accent = true, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Login component", color = Cal.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("mero-compose's LoginView with every mode and theme", color = Cal.textDim, fontSize = 13.sp)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Cal.textFaint, modifier = Modifier.size(18.dp))
    }
}

private enum class ThemeChoice(
    val label: String,
) {
    LIGHT("Light"),
    DARK("Dark"),
    CUSTOM("Custom"),
    ;

    val theme: MeroTheme
        get() =
            when (this) {
                LIGHT -> MeroTheme.calimeroLight
                DARK -> MeroTheme.calimero
                CUSTOM ->
                    MeroTheme.calimeroLight.copy(
                        primary = Color(0xFF5B5BD6),
                        primaryHover = Color(0xFF6E6ADE),
                        primaryText = Color.White,
                        radius = 26.dp,
                        titleFontFamily = FontFamily.Serif,
                    )
            }
}

/**
 * [LoginView] on its own client (memory-backed, so signing in here never touches the app's own
 * session), with an Options sheet: mode, node sign-in, theme, glow, and the code for them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
fun LoginComponentPreview(onBack: () -> Unit) {
    val client =
        remember {
            // `mero-sample://` callbacks: a dev app's choice; a release app uses an https App Link.
            MeroClient(CloudAccount(MemorySecureStore()), MemoryTokenStore(), config = MeroAppConfig(allowCustomSchemeCallbacks = true))
        }
    var mode by remember { mutableStateOf(LoginMode.CLOUD_AND_NODE) }
    var method by remember { mutableStateOf(NodeSignInMethod.PASSWORD) }
    var themeChoice by remember { mutableStateOf(ThemeChoice.LIGHT) }
    var glow by remember { mutableStateOf(true) }
    var showOptions by remember { mutableStateOf(false) }
    val state by client.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(Cal.bg)) {
        TopBar(
            "LoginView",
            onBack = onBack,
            actions = {
                IconButton(onClick = { showOptions = true }, modifier = Modifier.testTag("componentOptions")) {
                    Icon(Icons.Outlined.Tune, "Options", tint = Cal.accentInk)
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ProvideMeroTheme(themeChoice.theme.copy(showsGlow = glow)) {
                key(mode, method) {
                    LoginView(
                        callbackUrl = MainActivity.DEFAULT_CALLBACK_URL,
                        client = client,
                        mode = mode,
                        nodeSignIn = method,
                    )
                }
            }
            if (state.isAuthenticated) {
                Callout(
                    "Signed in through the component. This preview does not keep it.",
                    kind = CalloutKind.SUCCESS,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    }

    if (showOptions) {
        ModalBottomSheet(onDismissRequest = { showOptions = false }, containerColor = Cal.bg) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("Options", color = Cal.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Choice(
                    "Mode",
                    listOf("Cloud + node", "Cloud only", "Node only"),
                    LoginMode.entries.indexOf(mode),
                ) { mode = LoginMode.entries[it] }
                Choice(
                    "Node sign-in",
                    listOf("Username + password", "Node login page"),
                    if (method == NodeSignInMethod.PASSWORD) 0 else 1,
                ) { method = if (it == 0) NodeSignInMethod.PASSWORD else NodeSignInMethod.LOGIN_PAGE }
                Choice("Theme", ThemeChoice.entries.map { it.label }, themeChoice.ordinal) { themeChoice = ThemeChoice.entries[it] }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Glow", color = Cal.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = glow,
                        onCheckedChange = { glow = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Cal.lime, checkedThumbColor = Cal.onLime),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Code")
                    Text(
                        code(mode, method, themeChoice, glow),
                        color = Cal.text,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Cal.radiusControl))
                                .background(Cal.surfaceSunken)
                                .padding(12.dp),
                    )
                }
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

@Composable
private fun Choice(
    title: String,
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = i == selected,
                    onClick = { onSelect(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                    icon = {},
                    colors =
                        SegmentedButtonDefaults.colors(
                            activeContainerColor = Cal.surface,
                            activeContentColor = Cal.text,
                            activeBorderColor = Cal.borderStrong,
                            inactiveContainerColor = Cal.bgSubtle,
                            inactiveContentColor = Cal.textDim,
                            inactiveBorderColor = Cal.borderStrong,
                        ),
                ) { Text(label, fontSize = 12.sp, maxLines = 1) }
            }
        }
    }
}

private fun code(
    mode: LoginMode,
    method: NodeSignInMethod,
    theme: ThemeChoice,
    glow: Boolean,
): String {
    val base =
        when (theme) {
            ThemeChoice.LIGHT -> "MeroTheme.calimeroLight"
            ThemeChoice.DARK -> "MeroTheme.calimero"
            ThemeChoice.CUSTOM -> "MeroTheme.calimeroLight.copy(primary = Color(0xFF5B5BD6))"
        }
    val themed = if (glow) base else "$base.copy(showsGlow = false)"
    return """
        ProvideMeroTheme($themed) {
            LoginView(
                callbackUrl = "myapp://enrol",
                mode = LoginMode.${mode.name},
                nodeSignIn = NodeSignInMethod.${method.name},
            )
        }
        """.trimIndent()
}
