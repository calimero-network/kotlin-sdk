package com.calimero.mero.compose.invitations

import com.calimero.mero.admin.InviteFailureReason
import com.calimero.mero.admin.InviteRedeemer
import com.calimero.mero.admin.RedeemOutcome
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.admin.redeemInvitation
import com.calimero.mero.admin.shouldRetain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** What an invitation token carries, once the app's codec has read it — mero-react `ParsedInvitation`. */
interface ParsedInvitation {
    val namespaceId: String

    /** The invitation the node's join body expects. */
    val invitation: SignedGroupOpenInvitation

    /** The human name the inviter embedded, if any. */
    val teamName: String?
}

/** A [ParsedInvitation] with nothing more to it. Apps with extra fields implement the interface themselves. */
data class BasicParsedInvitation(
    override val namespaceId: String,
    override val invitation: SignedGroupOpenInvitation,
    override val teamName: String? = null,
) : ParsedInvitation

/**
 * Where redemption has got to, for rendering — mero-react `InviteState`. [Joining] is the state
 * apps used to lack: following a link put them on a screen with no sign anything was happening,
 * for as long as the join took (up to 95 s when no member of the namespace was online).
 */
sealed class InviteState {
    data object Idle : InviteState()

    data class AwaitingConfirmation(
        val teamName: String? = null,
    ) : InviteState()

    data class Joining(
        val teamName: String? = null,
    ) : InviteState()

    data class Joined(
        val namespaceId: String,
        val teamName: String? = null,
    ) : InviteState()

    data class AlreadyMember(
        val namespaceId: String,
        val teamName: String? = null,
    ) : InviteState()

    data class Failed(
        /** The node's own words, or this package's for an unreadable link. */
        val message: String,
        /** Why; [describeInviteFailure] turns it into something to show. */
        val reason: InviteFailureReason,
        val retryable: Boolean,
    ) : InviteState()

    /** The team name the state carries, if any. */
    val namedTeam: String?
        get() =
            when (this) {
                is AwaitingConfirmation -> teamName
                is Joining -> teamName
                is Joined -> teamName
                is AlreadyMember -> teamName
                else -> null
            }
}

/**
 * Redeem an invitation that arrived by link, and report progress — mero-react
 * `useInviteRedemption`. Create it once, high enough not to be torn down by navigation (the
 * `rememberInviteRedemption` composable at the app root). Capture is process-wide and buffers,
 * so creating it late is safe.
 *
 * - With [confirm] (the default — an invitation reaches the app only by a link being opened, and
 *   joining puts this device in someone's namespace) the state goes [InviteState.AwaitingConfirmation]
 *   and waits for [accept]; [decline] acks it so it does not come back.
 * - An unreadable invitation is acked and [InviteState.Failed] (`INVALID`, not retryable).
 * - The invitation is acked on the outcome — a join, an existing membership, or a failure retrying
 *   cannot fix — never on the render; a retryable failure keeps it for the next launch.
 * - A successful join marks the namespace just-joined ([JustJoinedNamespaces]) and calls [onJoined] before
 *   anything navigates.
 *
 * @param parse the app's codec: a raw token and the link's parameters to a [P], or null (or throw)
 *   when unusable. Extra fields the app returns come back as [parsed] and are handed to [redeem].
 * @param redeemer the standard join's two calls (join, then check membership).
 * @param redeem an app's own join flow, which takes precedence; report a failure as a `Failed`
 *   outcome rather than throwing (a throw reads as an `UNKNOWN`, retryable failure).
 * @param enabled hold off while false (no session yet): the invitation is buffered, not dropped.
 */
@Suppress("LongParameterList")
class InviteRedemption<P : ParsedInvitation>(
    private val scope: CoroutineScope,
    private val parse: (token: String, params: Map<String, String>) -> P?,
    private val redeemer: InviteRedeemer? = null,
    private val redeem: (suspend (P) -> RedeemOutcome)? = null,
    private val confirm: Boolean = true,
    private val onJoined: ((namespaceId: String, teamName: String?) -> Unit)? = null,
    enabled: StateFlow<Boolean> = MutableStateFlow(true),
    private val capture: InvitationCapture = InvitationCapture.shared,
) : AutoCloseable {
    private val _state = MutableStateFlow<InviteState>(InviteState.Idle)
    val state: StateFlow<InviteState> = _state.asStateFlow()

    private val _token = MutableStateFlow<String?>(null)

    /** The captured token, for prefilling a manual join field. */
    val token: StateFlow<String?> = _token.asStateFlow()

    private val _parsed = MutableStateFlow<P?>(null)

    /** What the invitation names, available while confirming. */
    val parsed: StateFlow<P?> = _parsed.asStateFlow()

    private val lock = Any()
    private val attempted = HashSet<String>()

    @Volatile private var pending: CapturedInvitation? = null

    private val listening: Job =
        scope.launch {
            enabled.collectLatest { on ->
                if (!on) return@collectLatest
                val off = capture.onInvitation(::onCaptured)
                try {
                    awaitCancellation()
                } finally {
                    off()
                }
            }
        }

    private fun onCaptured(captured: CapturedInvitation) {
        pending = captured
        _token.value = captured.token
        synchronized(lock) {
            if (captured.token in attempted) return
            // The attempt cap stops a link that cannot join re-firing on every launch. Asking is
            // not joining: a prompt is still owed.
            if (!captured.autoJoin && !confirm) return
            attempted += captured.token
        }
        begin(captured)
    }

    private fun begin(captured: CapturedInvitation) {
        val invitation = runCatching { parse(captured.token, captured.params) }.getOrNull()
        if (invitation == null || invitation.namespaceId.isEmpty()) {
            // Unreadable: retrying cannot help, so ack it rather than let it replay forever.
            captured.resolve()
            _state.value = InviteState.Failed("That invitation could not be read.", InviteFailureReason.INVALID, retryable = false)
            return
        }
        _parsed.value = invitation
        if (confirm) {
            _state.value = InviteState.AwaitingConfirmation(invitation.teamName)
            return
        }
        perform(captured, invitation)
    }

    private fun perform(
        captured: CapturedInvitation,
        invitation: P,
    ) {
        scope.launch {
            _state.value = InviteState.Joining(invitation.teamName)
            val outcome = runRedeem(invitation)
            if (!shouldRetain(outcome)) captured.resolve()
            _state.value =
                when (outcome) {
                    is RedeemOutcome.Failed -> InviteState.Failed(outcome.message, outcome.reason, outcome.retryable)
                    is RedeemOutcome.Joined -> {
                        joined(outcome.namespaceId, outcome.teamName)
                        InviteState.Joined(outcome.namespaceId, outcome.teamName)
                    }
                    is RedeemOutcome.AlreadyMember -> {
                        joined(outcome.namespaceId, outcome.teamName)
                        InviteState.AlreadyMember(outcome.namespaceId, outcome.teamName)
                    }
                }
        }
    }

    private fun joined(
        namespaceId: String,
        teamName: String?,
    ) {
        // Before any navigation, so the gate is already set wherever the app goes next.
        JustJoinedNamespaces.mark(namespaceId)
        runCatching { onJoined?.invoke(namespaceId, teamName) }
    }

    private suspend fun runRedeem(invitation: P): RedeemOutcome =
        try {
            when {
                redeem != null -> redeem.invoke(invitation)
                redeemer != null -> redeemInvitation(invitation.namespaceId, invitation.invitation, redeemer, invitation.teamName)
                else ->
                    RedeemOutcome.Failed(
                        invitation.namespaceId,
                        "This app cannot redeem invitations: no redeemer is configured.",
                        InviteFailureReason.UNKNOWN,
                        retryable = false,
                    )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RedeemOutcome.Failed(invitation.namespaceId, e.message ?: e.toString(), InviteFailureReason.UNKNOWN, retryable = true)
        }

    /** Go ahead with a join that is [InviteState.AwaitingConfirmation]. */
    fun accept() {
        val captured = pending ?: return
        val invitation = _parsed.value ?: return
        perform(captured, invitation)
    }

    /** Decline it, and ack it so it does not come back: "Not now" IS handled. */
    fun decline() {
        pending?.resolve()
        _state.value = InviteState.Idle
    }

    /**
     * Retry the captured invitation by hand, past the automatic attempt cap. The person already
     * said yes, so this joins; only an invitation never read is begun anew.
     */
    fun retry() {
        val captured = pending ?: return
        synchronized(lock) { attempted += captured.token }
        val invitation = _parsed.value
        if (invitation != null) perform(captured, invitation) else begin(captured)
    }

    /** Take the result off the screen. */
    fun dismiss() {
        _state.value = InviteState.Idle
    }

    override fun close() = listening.cancel()
}
