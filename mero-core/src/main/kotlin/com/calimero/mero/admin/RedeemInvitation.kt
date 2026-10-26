package com.calimero.mero.admin

import com.calimero.mero.crypto.Hex
import com.calimero.mero.http.ErrorKind
import com.calimero.mero.http.classifyError
import com.calimero.mero.http.messageOf
import kotlinx.coroutines.CancellationException

// Redeeming an invitation: joining once, and knowing whether it worked — port of mero-js
// `src/admin-api/redeem-invitation.ts`.
//
// Deciding "did we join?" by whether the join request returned is the wrong question, and on
// a real node it fails two ways: a join whose members are all offline waits for one to appear
// (95 s, measured) and a proxy times the request out while the join lands anyway; and a
// refresh sharing the join's `try` turns a real join into a reported failure. So membership
// is the question, with two independent proofs of it — the request returning, and the
// namespace being listed. Either one is enough, because either one can be missing.

/** The two calls a redeem needs, so any client (or any app's join) can drive it. mero-js `InviteRedeemer`. */
interface InviteRedeemer {
    /**
     * Send the join; return on success, throw on failure. It need not be reliable or
     * idempotent on the client: the node's join is idempotent, and [memberships] settles any
     * ambiguity a throw leaves.
     */
    suspend fun join(
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
    )

    /**
     * The namespace ids this caller is a member of. Throwing is a legitimate answer ("could
     * not tell") and never turns a join that succeeded into a failure.
     */
    suspend fun memberships(): List<String>
}

/** Why a join failed, as far as it tells a person what to do next. mero-js `InviteFailureReason`. */
enum class InviteFailureReason(
    /** mero-js's spelling, e.g. `no-one-online`. */
    val wire: String,
) {
    /** The invitation's time ran out. Only a new link helps. */
    EXPIRED("expired"),

    /** The link is malformed or names nothing joinable. Only a new link helps. */
    INVALID("invalid"),

    /** The node refused this person (removed, blocked, not allowed). */
    REFUSED("refused"),

    /** This client's session with the node lapsed; signing in finishes it. */
    SIGNED_OUT("signed-out"),

    /** No member was reachable to let them in yet. Trying later can work. */
    NO_ONE_ONLINE("no-one-online"),

    /** This client could not reach its own node at all. */
    NODE_UNREACHABLE("node-unreachable"),

    /** Nothing more specific is known; the node's message is all there is. */
    UNKNOWN("unknown"),
}

/**
 * What happened, in the terms a UI needs — mero-js `RedeemOutcome`. [AlreadyMember] is a
 * success, and deliberately distinct: it is what following a link twice (or the timeout
 * above) produces, and "you are already in this one" is neither an error nor "you just joined".
 */
sealed class RedeemOutcome {
    abstract val namespaceId: String?

    /** The join request succeeded. */
    data class Joined(
        override val namespaceId: String,
        val teamName: String? = null,
    ) : RedeemOutcome()

    /** The request failed, but the namespace is listed: the join landed anyway, or had already. */
    data class AlreadyMember(
        override val namespaceId: String,
        val teamName: String? = null,
    ) : RedeemOutcome()

    /** The join did not happen. */
    data class Failed(
        override val namespaceId: String?,
        /** The node's own words, for logs and for a UI that shows detail. */
        val message: String,
        /** Why, in terms a person can act on. */
        val reason: InviteFailureReason,
        /** Whether trying again later could plausibly work — see [shouldRetain]. */
        val retryable: Boolean,
    ) : RedeemOutcome()
}

/** Whether [outcome] means the invitation is finished with (joined, already in, or dead for good). */
fun isSettled(outcome: RedeemOutcome): Boolean = outcome !is RedeemOutcome.Failed || !outcome.retryable

/** Whether the invitation should be kept for another attempt later: the inverse of [isSettled]. */
fun shouldRetain(outcome: RedeemOutcome): Boolean = !isSettled(outcome)

/**
 * Join the namespace an invitation names, and report what happened — mero-js
 * `redeemInvitation`. Sends the join **once** and never retries it: a failed request whose
 * namespace is then listed is [RedeemOutcome.AlreadyMember], not a failure. Never throws for
 * an ordinary failure (cancellation still propagates).
 *
 * Most callers want [AdminSurface.redeemInvitation], which supplies the redeemer from the
 * admin API; this form is for a join that is more than one call.
 */
suspend fun redeemInvitation(
    namespaceId: String,
    invitation: SignedGroupOpenInvitation,
    redeemer: InviteRedeemer,
    teamName: String? = null,
): RedeemOutcome {
    // The id comes from the invite, so it is untrusted: refuse anything but a namespace id
    // (64 lowercase hex, as core spells it) before it is put in a path. (SEC-02)
    if (!Hex.isHex32(namespaceId)) {
        return RedeemOutcome.Failed(null, "This invitation does not name a valid namespace.", InviteFailureReason.INVALID, false)
    }
    var joinError: Throwable? = null
    try {
        redeemer.join(namespaceId, invitation)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        joinError = e
    }
    // The request returning is proof on its own, so this must never demote a real join:
    // a memberships() that throws is "could not tell".
    val listed: Boolean? =
        try {
            namespaceId in redeemer.memberships()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    if (joinError == null) return RedeemOutcome.Joined(namespaceId, teamName)
    if (listed == true) return RedeemOutcome.AlreadyMember(namespaceId, teamName)
    val message = messageOf(joinError, "Could not join. Check the invitation.")
    val (reason, retryable) = classifyJoinFailure(joinError, message)
    return RedeemOutcome.Failed(namespaceId, message, reason, retryable)
}

/**
 * The reason the message alone names, for a node that answers without a useful status (older
 * than rc.56, where every refusal was a bare 500). Narrow on purpose: anything unrecognised
 * stays retryable, because retrying a dead link costs one attempt and discarding a live one
 * costs someone who cannot join at all.
 */
private fun reasonFromMessage(message: String): InviteFailureReason? {
    val m = message.lowercase()
    if (m.contains("invitation has expired") || m.contains("invitation expired") || EXPIRED_AT.containsMatchIn(m)) {
        return InviteFailureReason.EXPIRED
    }
    if (m.contains("malformed") ||
        m.contains("does not match namespace_id") ||
        m.contains("invalid invitation") ||
        IS_INVALID.containsMatchIn(m)
    ) {
        return InviteFailureReason.INVALID
    }
    return null
}

// Core rc.56+ names the group between the words: "invitation for group <id> expired at <secs>".
private val EXPIRED_AT = Regex("""\binvitation for group .+? expired at \d+""")
private val IS_INVALID = Regex("""\binvitation for group .+? is invalid:""")

/**
 * Why a join failed and whether a later attempt could work. A 4xx the node chose is final;
 * a 5xx or no answer is about the moment. A 401 is the client's session, not the invitation,
 * and a 404 is not specific enough to throw a link away on.
 */
private fun classifyJoinFailure(
    error: Throwable,
    message: String,
): Pair<InviteFailureReason, Boolean> {
    val classified = classifyError(error)
    val fromText = reasonFromMessage(message)
    return when {
        classified.status == 401 -> InviteFailureReason.SIGNED_OUT to true
        classified.status == 400 -> (fromText ?: InviteFailureReason.INVALID) to false
        classified.status == 410 -> InviteFailureReason.EXPIRED to false
        classified.status == 403 -> InviteFailureReason.REFUSED to false
        // An expired invitation, a spent one, a member removed or blocked: every 409 a join answers is final.
        classified.status == 409 -> (fromText ?: InviteFailureReason.REFUSED) to false
        classified.kind == ErrorKind.UNAVAILABLE -> InviteFailureReason.NO_ONE_ONLINE to true
        classified.kind == ErrorKind.UNREACHABLE -> InviteFailureReason.NODE_UNREACHABLE to true
        // A 500 from a node older than rc.56 may still be a refusal in disguise.
        fromText != null -> fromText to false
        else -> InviteFailureReason.UNKNOWN to true
    }
}

/** The redeemer an admin surface supplies: [AdminSurface.joinNamespace] and [AdminSurface.listNamespaces]. */
internal fun adminRedeemer(
    admin: AdminSurface,
    groupName: String?,
): InviteRedeemer =
    object : InviteRedeemer {
        override suspend fun join(
            namespaceId: String,
            invitation: SignedGroupOpenInvitation,
        ) {
            admin.joinNamespace(namespaceId, JoinNamespaceRequest(invitation, groupName))
        }

        override suspend fun memberships(): List<String> = admin.listNamespaces().map { it.namespaceId }
    }
