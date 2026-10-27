@file:Suppress("TooManyFunctions")

package com.calimero.mero.compose

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Sign-in, bound to [MeroClient]. (== mero-react `LoginModal`; the Swift SDK's `LoginView`.)
 *
 * - **Cloud**: one "Continue with Calimero Cloud" button that opens the Calimero wallet (a
 *   passkey, in a Custom Tab); the redirect comes back to [callbackUrl], which the app hands to
 *   [MeroClient.handleDeepLink].
 * - **Node**: the local nodes discovery found and a URL field. By default Connect opens the node's
 *   own login page ([MeroClient.connectToNode], back to [nodeCallbackUrl]); with
 *   [NodeSignInMethod.PASSWORD] it asks for the node's admin username and password instead.
 *
 * [mode] picks which are offered: [LoginMode.CLOUD_AND_NODE] (Cloud, with a small "Manually enter
 * node URL" link under it), [LoginMode.CLOUD_ONLY] or [LoginMode.NODE_ONLY]. Colours, radii and
 * type come from the ambient [MeroTheme]; every string and the logo from [branding].
 *
 * Test tags: `loginTitle`, `loginError`, `cloudSignInButton` (the button), `loginButton` (the
 * Cloud panel), `cloudLoginCancelled`, `manualNode` / `useCloud` (the link between them), and on
 * the node side `nodeDiscovering`, `nodeOption-<host:port>`, `nodeOption-custom`, `nodeUrlInput`,
 * `nodeUserInput`, `nodePasswordInput`, `rescanButton` and `nodeConnectButton`.
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
fun LoginView(
    callbackUrl: String,
    modifier: Modifier = Modifier,
    client: MeroClient = useMero(),
    mode: LoginMode = LoginMode.CLOUD_AND_NODE,
    nodeSignIn: NodeSignInMethod = NodeSignInMethod.LOGIN_PAGE,
    branding: LoginBranding = LoginBranding(),
    nodeCallbackUrl: String = callbackUrl,
    initialTab: LoginModalTab = mode.tabs.first(),
    onConnectNode: ((String) -> Unit)? = null,
    onAuthenticated: () -> Unit = {},
) {
    val theme = LocalMeroTheme.current
    val state by client.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable(mode) { mutableStateOf(if (initialTab in mode.tabs) initialTab else mode.tabs.first()) }
    LaunchedEffect(state.isAuthenticated) { if (state.isAuthenticated) onAuthenticated() }

    Box(
        modifier
            .fillMaxSize()
            .background(theme.background)
            .then(if (theme.showsGlow) Modifier.glow(theme.primary) else Modifier),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.heightIn(min = if (tab == LoginModalTab.NODE) 36.dp else 88.dp))
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Hero(tab, branding, theme)
                Spacer(Modifier.heightIn(min = 12.dp))
                state.error?.let { ErrorRow(it, theme, "loginError") }
                when (tab) {
                    LoginModalTab.CLOUD -> AccountSignInPanel(callbackUrl = callbackUrl, client = client, branding = branding)
                    LoginModalTab.NODE ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(theme.cardRadius))
                                .background(theme.surface)
                                .border(1.dp, theme.border, RoundedCornerShape(theme.cardRadius))
                                .padding(18.dp),
                        ) {
                            NodeLoginPanel(client, nodeCallbackUrl, nodeSignIn, onConnectNode, branding)
                        }
                }
                if (mode == LoginMode.CLOUD_AND_NODE) {
                    Text(
                        if (tab == LoginModalTab.CLOUD) branding.manualNodeLink else branding.useCloudLink,
                        color = theme.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (tab == LoginModalTab.CLOUD) TextDecoration.Underline else null,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    tab = if (tab == LoginModalTab.CLOUD) LoginModalTab.NODE else LoginModalTab.CLOUD
                                    client.clearError()
                                }.padding(horizontal = 8.dp, vertical = 8.dp)
                                .testTag(if (tab == LoginModalTab.CLOUD) "manualNode" else "useCloud"),
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(
    tab: LoginModalTab,
    branding: LoginBranding,
    theme: MeroTheme,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val tileFill = branding.logoTileBackground ?: theme.primary
        val tileShape = RoundedCornerShape(branding.logoTileCornerRadius)
        Box(
            Modifier
                .size(branding.logoTileSize)
                .shadow(
                    branding.logoTileShadow,
                    tileShape,
                    ambientColor = tileFill.copy(alpha = 0.35f),
                    spotColor = tileFill.copy(alpha = 0.35f),
                ).clip(tileShape)
                .background(tileFill)
                .border(1.dp, Color(0x0F000000), tileShape),
            contentAlignment = Alignment.Center,
        ) {
            val logo = branding.logo
            if (logo != null) {
                Icon(logo, contentDescription = null, tint = theme.primaryText, modifier = Modifier.size(44.dp))
            } else {
                CalimeroLogo(size = 40.dp, color = theme.primaryText)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (tab == LoginModalTab.NODE) branding.nodeTitle else branding.cloudTitle,
                color = theme.text,
                fontSize = 32.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = theme.titleFontFamily,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("loginTitle"),
            )
            Text(
                if (tab == LoginModalTab.NODE) branding.nodeSubtitle else branding.cloudSubtitle,
                color = theme.textSecondary,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A soft glow of [color] behind the top of the screen. */
private fun Modifier.glow(color: Color): Modifier =
    drawBehind {
        if (size.width <= 0f || size.height <= 0f) return@drawBehind
        drawRect(
            Brush.radialGradient(
                colorStops = arrayOf(0f to color.copy(alpha = 0.35f), GLOW_MID to color.copy(alpha = 0.08f), 1f to Color.Transparent),
                center = Offset(size.width / 2f, 0f),
                radius = GLOW_RADIUS.dp.toPx(),
            ),
        )
    }

private const val GLOW_MID = 0.45f
private const val GLOW_RADIUS = 420

/**
 * The sign-in sheet, as before [LoginView]: [tabs] map to a [LoginMode] and [title], when given,
 * to the Cloud title. Kept for existing callers; new code uses [LoginView]. [colors], when given,
 * overrides the ambient [MeroTheme]'s matching tokens. [branding] sets every other string.
 */
@Suppress("LongParameterList")
@Composable
fun LoginSheet(
    callbackUrl: String,
    modifier: Modifier = Modifier,
    client: MeroClient = useMero(),
    colors: LoginSheetColors? = null,
    title: String? = null,
    onAuthenticated: () -> Unit = {},
    tabs: List<LoginModalTab> = listOf(LoginModalTab.NODE, LoginModalTab.CLOUD),
    initialTab: LoginModalTab = LoginModalTab.CLOUD,
    nodeCallbackUrl: String = callbackUrl,
    onConnectNode: ((String) -> Unit)? = null,
    branding: LoginBranding = LoginBranding(),
) {
    val base = LocalMeroTheme.current
    val theme = colors?.let { base.with(it) } ?: base
    ProvideMeroTheme(theme) {
        LoginView(
            callbackUrl = callbackUrl,
            modifier = modifier,
            client = client,
            mode = LoginMode.from(tabs),
            branding = if (title != null) branding.copy(cloudTitle = title) else branding,
            nodeCallbackUrl = nodeCallbackUrl,
            initialTab = initialTab,
            onConnectNode = onConnectNode,
            onAuthenticated = onAuthenticated,
        )
    }
}

/** [this] with [colors]' tokens laid over it. */
internal fun MeroTheme.with(colors: LoginSheetColors): MeroTheme =
    copy(
        surface = colors.surface,
        border = colors.border,
        text = colors.text,
        textSecondary = colors.textDim,
        textTertiary = colors.textFaint,
        primary = colors.accent,
        primaryText = colors.onAccent,
        error = colors.danger,
    )

/**
 * The Cloud side: one button to the wallet, what it does, and what the last attempt had to say.
 * The flow itself is [MeroClient.signInWithCloud]. (== mero-react `AccountSignInPanel`.) The words
 * come from [branding]; [buttonTitle] overrides its `cloudButton`.
 *
 * Test tags: `loginButton` (the panel), `cloudSignInButton`, `cloudLoginCancelled`.
 */
@Composable
fun AccountSignInPanel(
    callbackUrl: String,
    modifier: Modifier = Modifier,
    client: MeroClient = useMero(),
    branding: LoginBranding = LoginBranding(),
    buttonTitle: String = branding.cloudButton,
) {
    val theme = LocalMeroTheme.current
    val state by client.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val customWallet = client.account.walletUrl != com.calimero.mero.account.Enrolment.DEFAULT_WALLET_URL
    Column(
        modifier.fillMaxWidth().testTag("loginButton"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PrimaryButton(
            text = buttonTitle,
            icon = { Icon(CloudIcon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            loading = state.isLoading,
            enabled = !state.isLoading,
            theme = theme,
            tag = "cloudSignInButton",
        ) { client.signInWithCloud(context, callbackUrl) }
        branding.cloudHint?.let { hint ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = theme.textTertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(hint, color = theme.textTertiary, fontSize = 12.sp)
            }
        }
        if (state.cancelled) {
            Text(
                branding.cloudCancelled,
                color = theme.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.testTag("cloudLoginCancelled"),
            )
        }
        state.relayNote?.let { Text(it, color = theme.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center) }
        if (customWallet) {
            Text(branding.customWalletNote(client.account.walletUrl), color = theme.textTertiary, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun NodeLoginPanel(
    client: MeroClient,
    callbackUrl: String,
    method: NodeSignInMethod,
    onConnectNode: ((String) -> Unit)?,
    branding: LoginBranding,
) {
    val theme = LocalMeroTheme.current
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
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val credentialsReady = method == NodeSignInMethod.LOGIN_PAGE || (username.isNotEmpty() && password.isNotEmpty())
    val canConnect = !loading && !discovering && !state.isLoading && (selected != null || isValidNodeUrl(customUrl)) && credentialsReady
    val connect: () -> Unit = {
        scope.launch {
            val url = login.connect() ?: return@launch
            when {
                onConnectNode != null -> onConnectNode(url)
                method == NodeSignInMethod.PASSWORD -> client.login(url, username, password)
                else -> client.connectToNode(context, url, callbackUrl)
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (discovering) {
            Row(Modifier.fillMaxWidth().testTag("nodeDiscovering"), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = theme.text, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(branding.searchingNodes, color = theme.textSecondary, fontSize = 14.sp)
            }
        } else {
            discovered.forEach { url ->
                NodeOption(displayNodeUrl(url), branding.localNodeTag, selected == url, theme, "nodeOption-${displayNodeUrl(url)}") { login.select(url) }
            }
            if (discovered.isNotEmpty()) {
                NodeOption(branding.manualNodeOption, null, selected == null, theme, "nodeOption-custom") { login.select(null) }
            }
            if (selected == null) {
                Field(branding.nodeUrlLabel, customUrl, login::setCustomUrl, branding.nodeUrlPlaceholder, theme, "nodeUrlInput", keyboardType = KeyboardType.Uri)
            }
            if (method == NodeSignInMethod.PASSWORD) {
                Field(branding.usernameLabel, username, { username = it }, branding.usernamePlaceholder, theme, "nodeUserInput")
                Field(branding.passwordLabel, password, { password = it }, branding.passwordPlaceholder, theme, "nodePasswordInput", secure = true)
            }
            val insecure = remember(selected, customUrl) { login.isInsecure }
            when {
                error != null -> Text(error.orEmpty(), color = theme.error, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
                insecure ->
                    Text(
                        MeroClient.INSECURE_NODE_URL,
                        color = theme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().testTag("nodeInsecureWarning"),
                    )
            }
        }
        PrimaryButton(
            text = branding.nodeButton,
            icon = { Icon(ServerIcon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            loading = loading || state.isLoading,
            enabled = canConnect,
            theme = theme,
            tag = "nodeConnectButton",
            onClick = connect,
        )
        if (!discovering) {
            Row(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = login::rescan)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("rescanButton"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, tint = theme.textSecondary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(branding.rescanNodes, color = theme.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun Field(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    theme: MeroTheme,
    tag: String,
    secure: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = theme.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text(placeholder, color = theme.textTertiary) },
            visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secure) KeyboardType.Password else keyboardType),
            shape = RoundedCornerShape(theme.radius),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = theme.surface,
                    unfocusedContainerColor = theme.surface,
                    focusedBorderColor = theme.text,
                    unfocusedBorderColor = theme.border,
                    focusedTextColor = theme.text,
                    unfocusedTextColor = theme.text,
                    cursorColor = theme.text,
                ),
            modifier = Modifier.fillMaxWidth().heightIn(min = theme.controlHeight - 4.dp).testTag(tag),
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun NodeOption(
    label: String,
    meta: String?,
    active: Boolean,
    theme: MeroTheme,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(theme.radius))
                .background(if (active) theme.primary.copy(alpha = 0.15f) else theme.surface)
                .border(1.dp, theme.border, RoundedCornerShape(theme.radius))
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = active, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = theme.text, unselectedColor = theme.textTertiary))
        Text(label, color = theme.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        meta?.let { Text(it, color = theme.textTertiary, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp)) }
    }
}

@Composable
private fun ErrorRow(
    message: String,
    theme: MeroTheme,
    tag: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(theme.radius))
                .background(theme.error.copy(alpha = 0.1f))
                .padding(12.dp)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = theme.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, color = theme.error, fontSize = 13.sp)
    }
}

@Suppress("LongParameterList")
@Composable
private fun PrimaryButton(
    text: String,
    icon: @Composable () -> Unit,
    loading: Boolean,
    enabled: Boolean,
    theme: MeroTheme,
    tag: String,
    height: Dp = theme.controlHeight,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .clip(RoundedCornerShape(theme.radius))
            .background(theme.primary.copy(alpha = if (enabled) 1f else DISABLED_ALPHA))
            .border(1.dp, Color(0x0F000000), RoundedCornerShape(theme.radius))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(color = theme.primaryText, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides theme.primaryText,
                    content = icon,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = theme.primaryText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

private const val DISABLED_ALPHA = 0.5f

/** Material's "cloud" glyph, inline: mero-compose ships only the core icon set. */
private val CloudIcon: ImageVector by lazy {
    materialIcon(
        "Cloud",
        "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13" +
            "c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z",
    )
}

/** Material's "dns" (server) glyph, inline. */
private val ServerIcon: ImageVector by lazy {
    materialIcon(
        "Server",
        "M20,13H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1v-6c0,-0.55 -0.45,-1 -1,-1z" +
            "M7,19c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z" +
            "M20,3H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1V4c0,-0.55 -0.45,-1 -1,-1z" +
            "M7,9c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z",
    )
}

private fun materialIcon(
    name: String,
    path: String,
): ImageVector =
    ImageVector
        .Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .build()
