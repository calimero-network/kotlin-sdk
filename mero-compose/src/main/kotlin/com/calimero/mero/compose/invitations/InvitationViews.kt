package com.calimero.mero.compose.invitations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calimero.mero.admin.InviteRedeemer
import com.calimero.mero.admin.RedeemOutcome
import com.calimero.mero.compose.AccountEnrolment
import com.calimero.mero.compose.ConnectButton
import com.calimero.mero.compose.ConnectButtonLabels
import com.calimero.mero.compose.LocalMeroTheme
import com.calimero.mero.compose.MeroClient
import com.calimero.mero.compose.MeroTheme
import com.calimero.mero.compose.useMero
import kotlinx.coroutines.flow.MutableStateFlow

// The Compose face of the invitation flow: thin views over InviteRedemption,
// InvitationDialogModel, decideInvitationGate and JoinSync, which hold the logic.

/**
 * Remember an [InviteRedemption] at the app root — mero-react `useInviteRedemption`. The latest
 * [parse] / [redeem] / [onJoined] are used (a new lambda each recomposition does not re-fire the
 * join); [enabled] holds it off until the app can join (a session, a resolved application id).
 */
@Suppress("LongParameterList")
@Composable
fun <P : ParsedInvitation> rememberInviteRedemption(
    parse: (token: String, params: Map<String, String>) -> P?,
    redeemer: InviteRedeemer? = null,
    redeem: (suspend (P) -> RedeemOutcome)? = null,
    confirm: Boolean = true,
    onJoined: ((namespaceId: String, teamName: String?) -> Unit)? = null,
    enabled: Boolean = true,
    capture: InvitationCapture = InvitationCapture.shared,
): InviteRedemption<P> {
    val scope = rememberCoroutineScope()
    val latestParse by rememberUpdatedState(parse)
    val latestRedeemer by rememberUpdatedState(redeemer)
    val latestRedeem by rememberUpdatedState(redeem)
    val latestJoined by rememberUpdatedState(onJoined)
    val enabledFlow = remember { MutableStateFlow(enabled) }
    SideEffect { enabledFlow.value = enabled }
    val holder =
        remember(capture, confirm) {
            val viaRedeemer =
                object : InviteRedeemer {
                    override suspend fun join(
                        namespaceId: String,
                        invitation: com.calimero.mero.admin.SignedGroupOpenInvitation,
                    ) = checkNotNull(latestRedeemer).join(namespaceId, invitation)

                    override suspend fun memberships(): List<String> = checkNotNull(latestRedeemer).memberships()
                }
            InviteRedemption<P>(
                scope,
                parse = { t, p -> latestParse(t, p) },
                redeemer = if (redeem == null && redeemer != null) viaRedeemer else null,
                redeem = if (redeem != null) ({ p -> checkNotNull(latestRedeem)(p) }) else null,
                confirm = confirm,
                onJoined = { ns, team -> latestJoined?.invoke(ns, team) },
                enabled = enabledFlow,
                capture = capture,
            )
        }
    DisposableEffect(holder) { onDispose { holder.close() } }
    return holder
}

/** The captured invitation, recomposing when one arrives or is acked — mero-react `usePendingInvitation`. */
@Composable
fun rememberPendingInvitation(capture: InvitationCapture = InvitationCapture.shared): State<CapturedInvitation?> = capture.current.collectAsState()

/**
 * The one invitation prompt — mero-react `InvitationDialog`: mount it once at the app root with
 * the app's [rememberInviteRedemption]. Shows nothing until a link is opened, then walks through
 * asking, joining, syncing (until the screen reading the data settles it, or [synced]), done.
 * Joining and syncing cannot be dismissed by a stray tap.
 *
 * Test tags: `invitePrompt`, `inviteAccept`, `inviteDecline`, `inviteRetry`, `inviteContinue`,
 * `inviteDone`, `inviteError`.
 */
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
fun InvitationDialog(
    redemption: InviteRedemption<*>,
    noun: String = "team",
    appName: String? = null,
    synced: Boolean = false,
    detail: String? = null,
    status: String? = null,
    theme: MeroTheme = LocalMeroTheme.current,
) {
    val scope = rememberCoroutineScope()
    val model = remember(redemption) { InvitationDialogModel(redemption, scope) }
    DisposableEffect(model) { onDispose { model.close() } }
    LaunchedEffect(model, synced) { model.setSynced(synced) }
    val stage by model.stage.collectAsStateWithLifecycle()
    val slow by model.slow.collectAsStateWithLifecycle()
    val state by redemption.state.collectAsStateWithLifecycle()
    val current = stage ?: return
    val copy = invitationDialogCopy(current, state, noun, slow, detail, status)

    Dialog(onDismissRequest = model::requestClose, properties = DialogProperties(dismissOnClickOutside = true, dismissOnBackPress = true)) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 420.dp)
                    .background(theme.background, RoundedCornerShape(theme.radius * 2))
                    .padding(24.dp)
                    .testTag("invitePrompt"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            appName?.let { Text(it, color = theme.textSecondary, fontSize = 12.sp) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InviteIcon(copy.icon, theme)
                Text(copy.title, color = theme.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            copy.body?.let {
                Text(
                    it,
                    color = if (current == InvitationDialogStage.FAILED) theme.error else theme.textSecondary,
                    fontSize = 14.sp,
                    modifier = if (current == InvitationDialogStage.FAILED) Modifier.testTag("inviteError") else Modifier,
                )
            }
            if (current == InvitationDialogStage.JOINING || current == InvitationDialogStage.SYNCING) {
                LinearProgressIndicator(color = theme.primary, trackColor = theme.backgroundSecondary, modifier = Modifier.fillMaxWidth())
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when (current) {
                    InvitationDialogStage.AWAITING_CONFIRMATION -> {
                        TextButton(onClick = redemption::decline, modifier = Modifier.testTag("inviteDecline")) { Text("Not now", color = theme.textSecondary) }
                        PrimaryAction("Join", "inviteAccept", theme, redemption::accept)
                    }
                    InvitationDialogStage.SYNCING ->
                        if (slow) {
                            TextButton(onClick = model::continueInBackground, modifier = Modifier.testTag("inviteContinue")) {
                                Text("Continue in background", color = theme.textSecondary)
                            }
                        }
                    InvitationDialogStage.READY, InvitationDialogStage.ALREADY_MEMBER -> PrimaryAction("Done", "inviteDone", theme, redemption::dismiss)
                    InvitationDialogStage.FAILED -> {
                        TextButton(onClick = redemption::dismiss, modifier = Modifier.testTag("inviteDecline")) { Text("Close", color = theme.textSecondary) }
                        if ((state as? InviteState.Failed)?.retryable == true) PrimaryAction("Try again", "inviteRetry", theme, redemption::retry)
                    }
                    InvitationDialogStage.JOINING -> Unit
                }
            }
        }
    }
}

/**
 * The screen an invitation lands on while signed out — mero-react `InvitationGate`. With a pending
 * invitation and no session it shows "You're invited to join …" with Not now / Log in to join
 * (a [ConnectButton]); a spinner while the session restores; "This invitation can't be read" for
 * a bad link. Otherwise it shows [content]. Not now acks the invitation, as the dialog's does.
 *
 * Test tags: `inviteLanding` (`inviteLanding-checking` / `-invited` / `-invalid`),
 * `inviteLandingLogin`, `inviteLandingDecline`.
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
fun InvitationGate(
    callbackUrl: String,
    modifier: Modifier = Modifier,
    client: MeroClient = useMero(),
    parse: ((token: String, params: Map<String, String>) -> ParsedInvitation?)? = null,
    noun: String = "team",
    appName: String? = null,
    detail: String? = null,
    capture: InvitationCapture = InvitationCapture.shared,
    theme: MeroTheme = LocalMeroTheme.current,
    content: @Composable () -> Unit = {},
) {
    val session by client.state.collectAsStateWithLifecycle()
    val pending by rememberPendingInvitation(capture)
    val decision = decideInvitationGate(pending, session.isAuthenticated, session.isLoading, parse)
    val stage = decision.stage
    if (stage == null) {
        content()
        return
    }
    Box(modifier = modifier.fillMaxSize().background(theme.background), contentAlignment = Alignment.Center) {
        if (stage == InvitationGateStage.CHECKING) {
            CircularProgressIndicator(color = theme.primary, modifier = Modifier.testTag("inviteLanding-checking"))
            return@Box
        }
        val copy = invitationGateCopy(stage, decision.teamName, noun, appName)
        Column(
            modifier =
                Modifier
                    .widthIn(max = 420.dp)
                    .padding(24.dp)
                    .background(theme.backgroundSecondary, RoundedCornerShape(theme.radius * 2))
                    .padding(24.dp)
                    .testTag("inviteLanding-${stage.wire}"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            appName?.let { Text(it, color = theme.textSecondary, fontSize = 12.sp) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InviteIcon(if (stage == InvitationGateStage.INVALID) InvitationIcon.ERROR else InvitationIcon.INVITE, theme)
                Text(copy.title, color = theme.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(copy.body, color = theme.textSecondary, fontSize = 14.sp)
            if (detail != null && stage == InvitationGateStage.INVITED) Text(detail, color = theme.textSecondary, fontSize = 14.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (stage == InvitationGateStage.INVALID) {
                    PrimaryAction("Continue", "inviteLandingDecline", theme) { pending?.resolve() }
                } else {
                    TextButton(onClick = { pending?.resolve() }, modifier = Modifier.testTag("inviteLandingDecline")) {
                        Text("Not now", color = theme.textSecondary)
                    }
                    Box(modifier = Modifier.testTag("inviteLandingLogin")) {
                        ConnectButton(
                            callbackUrl = callbackUrl,
                            client = client,
                            labels = ConnectButtonLabels(connect = "Log in to join"),
                            theme = theme,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Remember a [JoinSync] fed with the screen's current inputs — mero-react `useJoinSync`. Read
 * [JoinSync.isSyncing] (collect it) and wire [JoinSync.dismiss] to a "show me anyway" control.
 */
@Composable
fun rememberJoinSync(
    namespaceId: String?,
    settled: Boolean,
    active: Boolean? = null,
): JoinSync {
    val scope = rememberCoroutineScope()
    val sync = remember { JoinSync(scope) }
    DisposableEffect(sync) { onDispose { sync.close() } }
    SideEffect { sync.update(namespaceId, settled, active) }
    return sync
}

/**
 * "Joined. Syncing [what] from peers…" — mero-react `JoinSyncBanner`. Renders nothing unless
 * [show] (usually [JoinSync.isSyncing]); [onDismiss] adds a "Show anyway" escape.
 */
@Composable
fun JoinSyncBanner(
    show: Boolean,
    modifier: Modifier = Modifier,
    what: String = "data",
    onDismiss: (() -> Unit)? = null,
    theme: MeroTheme = LocalMeroTheme.current,
) {
    if (!show) return
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(theme.backgroundSecondary, RoundedCornerShape(theme.radius))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("joinSyncBanner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(color = theme.text, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Text(
            "Joined. Syncing $what from peers… This can take a moment on a cold connection.",
            color = theme.text,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        onDismiss?.let {
            TextButton(onClick = it, modifier = Modifier.testTag("joinSyncDismiss")) { Text("Show anyway", color = theme.textSecondary, fontSize = 13.sp) }
        }
    }
}

/** Remember a [JoinInvitation] over the ambient client — mero-react `useJoinInvitation`. */
@Composable
fun rememberJoinInvitation(client: MeroClient = useMero()): JoinInvitation =
    remember(client) { JoinInvitation(client.session, DelegatedBootstrap.forClient(client)) }

/** Remember a [DelegatedBootstrap] over the ambient client — mero-react `useDelegatedBootstrap`. */
@Composable
fun rememberDelegatedBootstrap(client: MeroClient = useMero()): DelegatedBootstrap = remember(client) { DelegatedBootstrap.forClient(client) }

/** Remember an [AccountEnrolment] — mero-react `useAccountEnrolment`. */
@Composable
fun rememberAccountEnrolment(
    callbackUrl: String,
    client: MeroClient = useMero(),
): AccountEnrolment = remember(client, callbackUrl) { AccountEnrolment(client, callbackUrl) }

@Composable
private fun PrimaryAction(
    text: String,
    tag: String,
    theme: MeroTheme,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(theme.radius),
        colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = theme.primaryText),
        modifier = Modifier.testTag(tag),
    ) { Text(text) }
}

@Composable
private fun InviteIcon(
    icon: InvitationIcon,
    theme: MeroTheme,
) {
    when (icon) {
        InvitationIcon.SPINNER -> CircularProgressIndicator(color = theme.primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        InvitationIcon.DONE -> Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = theme.primary, modifier = Modifier.size(20.dp))
        InvitationIcon.ERROR -> Icon(Icons.Outlined.Warning, contentDescription = null, tint = theme.error, modifier = Modifier.size(20.dp))
        InvitationIcon.INVITE -> Icon(Icons.Outlined.Email, contentDescription = null, tint = theme.primary, modifier = Modifier.size(20.dp))
    }
}
