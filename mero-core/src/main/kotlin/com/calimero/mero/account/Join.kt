package com.calimero.mero.account

import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.cloud.CloudClient
import com.calimero.mero.cloud.CloudNamespaceNode
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.http.NetworkException
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * Joining a namespace as an account that holds no node.
 *
 * Port of mero-js `src/account/{relay-from-invitation,join-with-node,bootstrap-from-invitation}.ts`.
 * The device signs its own `MemberJoined` op ([NamespaceOp.signMemberJoinOp]) and hands it
 * to a node the invitation names as an admitter (`POST /admin-api/namespaces/{ns}/admit`),
 * which publishes it. The cloud says which of those nodes it hosts and where they are.
 *
 * **A node URL never comes from the invitation**, signed or not: its `admitter_addrs` sit
 * outside the inviter's signature, so an http(s) entry there could name a host that proxies a
 * genuine relay (mero-js #264). The node is the caller's, or one the cloud's routing lists.
 */
object Join {
    /**
     * Which node to be admitted through, chosen from the cloud's routing ∩ the invitation's
     * signed admitters — mero-js `resolveRelayFromInvitation`. The intersection is never
     * relaxed: it turns an unexplainable `403` into "your invitation names none of these
     * nodes". `canAdmit` and `servable` are advisory (a lapsed heartbeat is not a node that
     * will refuse), so a named node with an address is tried and [ResolvedRelay.stale] says so.
     *
     * @throws JoinException at [JoinStep.ADMITTERS_LOOKUP], [JoinStep.NO_NODES],
     *   [JoinStep.NOT_INVITED] or [JoinStep.NO_RELAY_URL].
     */
    @Suppress("ThrowsCount")
    suspend fun resolveRelayFromInvitation(
        cloud: CloudClient,
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
    ): ResolvedRelay {
        val routing =
            try {
                cloud.getNamespaceRouting(namespaceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw JoinException(
                    JoinStep.ADMITTERS_LOOKUP,
                    "The cloud could not be asked which nodes serve namespace $namespaceId: ${e.message}. Nothing is known " +
                        "about the invitation yet — this is the lookup failing, not the invitation.",
                    cause = e,
                )
            }
        if (routing.nodes.isEmpty()) {
            throw JoinException(
                JoinStep.NO_NODES,
                "The cloud lists no nodes serving this namespace, so there is no hosted node to be admitted through. The " +
                    "namespace has no fleet assignment — whoever invited you is running their own node, and admission has to " +
                    "go through a node the cloud routes to.",
            )
        }
        val invited =
            invitation.invitation.admitters
                .mapNotNull(::normaliseAccount)
                .toSet()
        val named = routing.nodes.filter { invited.isEmpty() || normaliseAccount(it.account) in invited }
        if (named.isEmpty()) {
            val n = routing.nodes.size
            throw JoinException(
                JoinStep.NOT_INVITED,
                "$n node${if (n == 1) "" else "s"} serve this namespace, but your invitation names none of them. The signed " +
                    "admitter list is a snapshot from when the invitation was minted, so a node assigned since is healthy and " +
                    "will still refuse the claim. Ask for a fresh invitation.",
            )
        }
        val usable = named.filter { it.canAdmit }
        val candidates = usable.ifEmpty { named }
        val addressable = candidates.filter { !it.relayUrl.isNullOrEmpty() }
        if (addressable.isEmpty()) {
            throw JoinException(
                JoinStep.NO_RELAY_URL,
                "A node your invitation names can admit you, but the cloud knows no address for it yet, so there would be " +
                    "nothing to connect through afterwards. Try again shortly.",
            )
        }
        // Prefer a node that can do both legs — admit now and author later — so the session lands on one node.
        val chosen: CloudNamespaceNode =
            addressable.firstOrNull { it.canExecute } ?: addressable.firstOrNull { it.fresh } ?: addressable.first()
        val relayUrl = chosen.relayUrl!!.trimBase()
        return ResolvedRelay(
            relayUrl = relayUrl,
            admitUrl = chosen.admitUrl ?: admitUrlOn(relayUrl, namespaceId),
            admitterAccount = chosen.account,
            writable = chosen.canExecute,
            stale = !chosen.fresh || usable.isEmpty() || !routing.servable,
            peerId = chosen.peerId,
        )
    }

    /**
     * Sign the join and post it to [admitUrl]. Returns whether the node says it
     * **published** the op (not "joined": membership lands once it propagates).
     */
    @Suppress("LongParameterList", "ThrowsCount")
    internal suspend fun joinWithNode(
        http: PlainJsonHttp,
        admitUrl: String,
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
        account: String,
        credential: String,
        signer: DeviceSigner,
        nonce: ULong,
    ): Boolean {
        val signedOp =
            try {
                NamespaceOp.signMemberJoinOp(namespaceId, account, invitation, credential, signer, nonce)
            } catch (e: IllegalArgumentException) {
                throw JoinException(JoinStep.SIGN, "The join op could not be signed, so nothing was sent: ${e.message}", cause = e)
            }
        val body =
            buildJsonObject {
                put("invitation", invitation.raw)
                put("signedOp", signedOp)
            }
        val res =
            try {
                http.execute("POST", admitUrl, body.toString())
            } catch (e: NetworkException) {
                throw JoinException(
                    JoinStep.ADMIT,
                    "The admitter at $admitUrl could not be reached: ${e.message}. The invitation is untouched — this is the " +
                        "node being unreachable, not a refusal.",
                    cause = e,
                )
            }
        if (!res.isSuccessful) throw JoinException(JoinStep.ADMIT, explainAdmitFailure(res.status, res.body), res.status)
        return runCatching {
            val data = Json.parseToJsonElement(res.body).jsonObject["data"] as? JsonObject
            (data?.get("published") as? JsonPrimitive)?.booleanOrNull == true
        }.getOrDefault(false)
    }

    /** Lowercase, `0x` stripped; null when empty. */
    fun normaliseAccount(account: String?): String? {
        val trimmed = account?.trim()?.lowercase() ?: return null
        return trimmed.removePrefix("0x").ifEmpty { null }
    }

    /** The documented admission route on [nodeUrl]. */
    internal fun admitUrlOn(
        nodeUrl: String,
        namespaceId: String,
    ): String = "${nodeUrl.trimBase()}/admin-api/namespaces/${URLEncoder.encode(namespaceId, "UTF-8").replace("+", "%20")}/admit"

    private fun explainAdmitFailure(
        status: Int,
        body: String,
    ): String {
        val detail = if (body.isNotEmpty()) ": $body" else ""
        return when (status) {
            400 ->
                "The node refused the join as malformed (400)$detail. The signature covers the invitation exactly as sent, " +
                    "so an edited or re-serialised invitation fails here — as does a namespace id that is not the invitation's own group."
            403 ->
                "The node refused to carry this join (403)$detail. Either it is not in the invitation's signed admitters list — " +
                    "being live and listed by the cloud is not the same thing — or the invitation itself was rejected as expired " +
                    "or not the inviter's to issue."
            409 -> "That node holds no device of its own, so it cannot endorse anyone (409)$detail. Another admitter from the same invitation would work."
            else -> "The join was not published (HTTP $status)$detail."
        }
    }
}

/** The node a join goes through. */
data class ResolvedRelay(
    /** The node origin to connect through. */
    val relayUrl: String,
    /** Where to POST the signed join — the cloud's own, never rebuilt when given. */
    val admitUrl: String,
    /** The admitter's account, as the cloud reported it. */
    val admitterAccount: String?,
    /** Whether this node can also run delegated writes (`CAN_AUTHOR_ON_BEHALF`); `false` is not a failure of admission. */
    val writable: Boolean,
    /** Whether the choice rests on advisory data (a lapsed heartbeat, or a namespace the cloud calls not servable). */
    val stale: Boolean,
    /** The node's peer id, as the cloud reported it. */
    val peerId: String = "",
)

/**
 * Where a join failed — each needs a different action from the person, which is why this is
 * an enum and not a message. mero-js `BootstrapStep` (`RelayResolutionStep` + `JoinWithNodeStep`
 * + `no-credential`).
 */
enum class JoinStep(
    /** mero-js's spelling, e.g. `admitters-lookup`. */
    val wire: String,
) {
    /** The cloud read itself failed (offline, 5xx, a refused routing proof). Nothing is known about the namespace. */
    ADMITTERS_LOOKUP("admitters-lookup"),

    /** The cloud lists no node to admit through. An address on the invitation is not one. */
    NO_NODES("no-nodes"),

    /** Nodes serve the namespace and the invitation names none of them: ask for a fresh one. */
    NOT_INVITED("not-invited"),

    /** A named node exists but cannot take a join now. A wait, not a wrong invitation. */
    INVITED_UNREACHABLE("invited-unreachable"),

    /** A named node can admit, but the cloud knows no address for it yet. Also a wait. */
    NO_RELAY_URL("no-relay-url"),

    /** The op could not be signed. Local and deterministic; nothing was sent. */
    SIGN("sign"),

    /** The node refused the join, or could not be reached. */
    ADMIT("admit"),

    /** This device holds no enrolled account, so there is nothing to admit. */
    NO_CREDENTIAL("no-credential"),
}

/** A join that did not happen, and at which step. */
class JoinException(
    val step: JoinStep,
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** What a successful join produced. */
data class JoinResult(
    val namespaceId: String,
    /** The relay the account was admitted through; it now serves the account. */
    val relayUrl: String,
    val published: Boolean,
    val session: DelegatedSession,
)

/**
 * What [CloudAccount.bootstrapFromInvitation] came to — mero-js `BootstrapResult`. Never an
 * exception for an expected failure: each [Failure.step] is a different thing for the person to do.
 */
sealed class BootstrapResult {
    /** Joined (or at least published): [session] is the admitting node, to connect with straight away. */
    data class Success(
        /** The session to connect with: the admitting node, used immediately (the cloud's account lookup lags). */
        val session: DelegatedSession,
        /** How the node was chosen, when the cloud chose it; null when the caller named the node. */
        val relay: ResolvedRelay?,
        /** Whether the admitter said it published the op — NOT "you are a member". */
        val published: Boolean,
    ) : BootstrapResult()

    /** The step that stopped it, what to do about it, and the HTTP status when the step was an HTTP call. */
    data class Failure(
        val step: JoinStep,
        val reason: String,
        val status: Int? = null,
    ) : BootstrapResult()
}

/** The Swift SDK's (and mero-js's) name for [JoinStep]: which step of a bootstrap failed. */
typealias BootstrapStep = JoinStep

/** The Swift SDK's name for a failed bootstrap: step, reason and HTTP status. */
typealias BootstrapFailure = BootstrapResult.Failure

/**
 * Redeem an invitation for [session]'s account the way mero-react's `useDelegatedBootstrap`
 * does — mero-js `joinAsAccount`: resolve the relay the invitation names, have it admit the
 * account, add that relay to the account's map ([rememberRelay]) and hand the session, moved
 * onto that relay ([carryExecutorAccount]), to [onJoined]. It works with no relay yet: the
 * join is how an account gets its first one. An account admin's `joinNamespace` runs it.
 *
 * @return the namespace joined.
 * @throws JoinException with the refusal's reason, step and HTTP status; nothing is
 *   remembered or switched then.
 */
suspend fun CloudAccount.joinAsAccount(
    session: DelegatedSession,
    namespaceId: String,
    invitation: SignedGroupOpenInvitation,
    cloudBaseUrl: String = this.cloudBaseUrl,
    onJoined: (DelegatedSession) -> Unit,
): String =
    when (val outcome = bootstrapFromInvitation(namespaceId, invitation, session = session, cloudBaseUrl = cloudBaseUrl)) {
        is BootstrapResult.Failure -> throw JoinException(outcome.step, outcome.reason, outcome.status)
        is BootstrapResult.Success -> {
            outcome.session.relayUrl?.let { rememberRelay(session.account, it, namespaceId = namespaceId) }
            onJoined(carryExecutorAccount(session, outcome.session))
            namespaceId
        }
    }
