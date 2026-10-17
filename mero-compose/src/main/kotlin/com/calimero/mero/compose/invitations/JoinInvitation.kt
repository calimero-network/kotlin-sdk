package com.calimero.mero.compose.invitations

import com.calimero.mero.account.BootstrapResult
import com.calimero.mero.account.DelegatedSession
import com.calimero.mero.account.JoinStep
import com.calimero.mero.account.carryExecutorAccount
import com.calimero.mero.account.rememberRelay
import com.calimero.mero.admin.InviteRedeemer
import com.calimero.mero.admin.JoinNamespaceRequest
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.compose.MeroClient
import com.calimero.mero.compose.MeroSession
import com.calimero.mero.http.statusOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bootstrap an account that is a member of nothing from an invitation — mero-react
 * `useDelegatedBootstrap`: resolve the node the invitation names, have it admit the account, and
 * connect through it (the session moves onto that relay, keeping its executor when it is the same
 * relay). [needsBootstrap] is true for a signed-in account with no relay yet.
 *
 * Built from a [MeroClient] with [forClient]; the three steps are parameters so it is testable.
 */
class DelegatedBootstrap(
    private val session: () -> DelegatedSession?,
    private val run: suspend (namespaceId: String, invitation: SignedGroupOpenInvitation, nodeUrl: String?, session: DelegatedSession) -> BootstrapResult,
    private val adopt: suspend (success: BootstrapResult.Success, previous: DelegatedSession, namespaceId: String, contextId: String?) -> Unit,
) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _result = MutableStateFlow<BootstrapResult?>(null)
    val result: StateFlow<BootstrapResult?> = _result.asStateFlow()

    /** Whether this device holds an enrolled account. */
    val hasCredential: Boolean get() = session() != null

    /** Signed in with no relay: the invitation is what gives the account its first membership. */
    val needsBootstrap: Boolean get() = session()?.let { it.relayUrl == null } ?: false

    /**
     * Join [namespaceId] as the account. [nodeUrl] names the admitting node when the caller knows
     * it (never one read off the invitation); otherwise the cloud's routing decides.
     */
    suspend fun bootstrap(
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
        nodeUrl: String? = null,
        contextId: String? = null,
    ): BootstrapResult {
        val held = session()
        if (held == null) {
            return BootstrapResult
                .Failure(
                    JoinStep.NO_CREDENTIAL,
                    "This device holds no enrolled account, so there is no identity to admit. Sign in with your account " +
                        "first — the invitation is then what gives it its first membership.",
                ).also { _result.value = it }
        }
        _running.value = true
        try {
            val outcome = run(namespaceId, invitation, nodeUrl, held)
            _result.value = outcome
            if (outcome is BootstrapResult.Success) adopt(outcome, held, namespaceId, contextId)
            return outcome
        } finally {
            _running.value = false
        }
    }

    companion object {
        /** The real steps: [com.calimero.mero.account.CloudAccount.bootstrapFromInvitation], then [MeroClient.connectWithAccount]. */
        fun forClient(client: MeroClient): DelegatedBootstrap =
            DelegatedBootstrap(
                session = { client.delegatedSession },
                run = { ns, invitation, nodeUrl, s ->
                    client.account.bootstrapFromInvitation(ns, invitation, nodeUrl, s, client.config.cloudBaseUrl)
                },
                adopt = { success, previous, ns, contextId ->
                    success.session.relayUrl?.let { client.account.rememberRelay(previous.account, it, namespaceId = ns, contextId = contextId) }
                    client.connectWithAccount(carryExecutorAccount(previous, success.session))
                },
            )
    }
}

/** What to join — mero-react `JoinInvitationInput`. */
data class JoinInvitationInput(
    val namespaceId: String,
    val invitation: SignedGroupOpenInvitation,
    /** A context to join too, on a node. */
    val contextId: String? = null,
)

/** Which step of a join failed — a bootstrap step for an account, or [NODE_JOIN] on a node. mero-react `JoinInvitationStep`. */
enum class JoinInvitationStep(
    val wire: String,
) {
    NO_CREDENTIAL("no-credential"),
    ADMITTERS_LOOKUP("admitters-lookup"),
    NO_NODES("no-nodes"),
    NOT_INVITED("not-invited"),
    INVITED_UNREACHABLE("invited-unreachable"),
    NO_RELAY_URL("no-relay-url"),
    SIGN("sign"),
    ADMIT("admit"),
    NODE_JOIN("node-join"),
    ;

    /** What the step means, in words — mero-react's `STEP_LABEL` (null for [NODE_JOIN]). */
    val label: String?
        get() =
            when (this) {
                NO_CREDENTIAL -> "No enrolled account"
                ADMITTERS_LOOKUP -> "Admitters lookup failed"
                NO_NODES -> "No hosted node serves this namespace"
                NOT_INVITED -> "This invitation names none of the serving nodes"
                INVITED_UNREACHABLE -> "The invited admitter cannot take a join right now"
                NO_RELAY_URL -> "The invited admitter has no address yet"
                SIGN -> "The join could not be signed"
                ADMIT -> "The admitter refused the join"
                NODE_JOIN -> null
            }

    companion object {
        fun of(step: JoinStep): JoinInvitationStep = entries.first { it.wire == step.wire }
    }
}

/** What a join came to — mero-react `JoinInvitationResult`. */
sealed class JoinInvitationResult {
    data object Ok : JoinInvitationResult()

    data class Failed(
        val step: JoinInvitationStep,
        val reason: String,
        /** Whether the invitation itself is the problem, so keeping it would only fail again. */
        val final: Boolean,
        val status: Int? = null,
    ) : JoinInvitationResult()
}

/**
 * Whether a join failure's message is about the invitation itself (expired, malformed, a bad
 * signature, revoked, already a member) — keep it for anything transient or unfamiliar.
 * mero-react `isFinalInvitationError`.
 */
fun isFinalInvitationError(message: String?): Boolean {
    if (message.isNullOrEmpty()) return false
    val m = message.lowercase()
    return FINAL_INVITATION_ERRORS.any { m.contains(it) }
}

private val FINAL_INVITATION_ERRORS =
    listOf(
        "invitation expired",
        "expired invitation",
        "invalid invitation",
        "invitation is invalid",
        "malformed invitation",
        "malformed payload",
        "invalid signature",
        "signature verification failed",
        "not admin",
        "revoked",
        "already a member",
    )

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_FORBIDDEN = 403

/** An account join is final when the invitation names nobody, or the admitter refused it outright. */
internal fun accountJoinIsFinal(failure: BootstrapResult.Failure): Boolean =
    if (failure.step == JoinStep.ADMIT) {
        failure.status == HTTP_BAD_REQUEST || failure.status == HTTP_FORBIDDEN
    } else {
        failure.step == JoinStep.NOT_INVITED || failure.step == JoinStep.SIGN
    }

/**
 * Join from an invitation, whatever the connection — mero-react `useJoinInvitation`. As an
 * account (a delegated session, or an enrolled account with no node session) it bootstraps
 * through the node the invitation names ([DelegatedBootstrap]); on a node it joins the namespace
 * and then [JoinInvitationInput.contextId]. Never throws for an ordinary failure.
 */
@Suppress("MemberNameEqualsClassName")
class JoinInvitation(
    private val sessions: StateFlow<MeroSession>,
    private val bootstrapper: DelegatedBootstrap,
) {
    private val _joining = MutableStateFlow(false)
    val joining: StateFlow<Boolean> = _joining.asStateFlow()

    private val asAccount: Boolean
        get() = sessions.value.isDelegated || (bootstrapper.hasCredential && sessions.value.rawAdmin == null)

    suspend fun joinInvitation(input: JoinInvitationInput): JoinInvitationResult {
        _joining.value = true
        try {
            if (asAccount) {
                return when (val outcome = bootstrapper.bootstrap(input.namespaceId, input.invitation, contextId = input.contextId)) {
                    is BootstrapResult.Success -> JoinInvitationResult.Ok
                    is BootstrapResult.Failure -> {
                        val step = JoinInvitationStep.of(outcome.step)
                        JoinInvitationResult.Failed(step, "${step.label}. ${outcome.reason}", accountJoinIsFinal(outcome), outcome.status)
                    }
                }
            }
            val admin =
                sessions.value.rawAdmin
                    ?: return JoinInvitationResult.Failed(JoinInvitationStep.NODE_JOIN, "Not connected to a node.", final = false)
            admin.joinNamespace(input.namespaceId, JoinNamespaceRequest(input.invitation))
            input.contextId?.let { admin.joinContext(it) }
            return JoinInvitationResult.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.toString()
            return JoinInvitationResult.Failed(JoinInvitationStep.NODE_JOIN, reason, isFinalInvitationError(reason), statusOf(e))
        } finally {
            _joining.value = false
        }
    }

    /** The namespace ids this connection is a member of. */
    suspend fun memberships(): List<String> {
        val session = sessions.value
        if (asAccount) {
            return session.delegated
                ?.listNamespaces()
                ?.map { it.namespaceId }
                .orEmpty()
        }
        return session.rawAdmin
            ?.listNamespaces()
            ?.map { it.namespaceId }
            .orEmpty()
    }

    /** An [InviteRedeemer] over this join, for [InviteRedemption]: a failed join throws with its status. */
    fun invitationRedeemer(input: JoinInvitationInput): InviteRedeemer =
        object : InviteRedeemer {
            override suspend fun join(
                namespaceId: String,
                invitation: SignedGroupOpenInvitation,
            ) {
                val outcome = joinInvitation(input)
                if (outcome is JoinInvitationResult.Failed) {
                    throw if (outcome.status != null) JoinInvitationException(outcome) else IllegalStateException(outcome.reason)
                }
            }

            override suspend fun memberships(): List<String> = this@JoinInvitation.memberships()
        }
}

/** A failed [JoinInvitation], as an exception carrying its HTTP status for [com.calimero.mero.admin.redeemInvitation]. */
class JoinInvitationException(
    val failure: JoinInvitationResult.Failed,
) : com.calimero.mero.http.HttpException(checkNotNull(failure.status), "", emptyMap(), "") {
    override val message: String get() = failure.reason
}
