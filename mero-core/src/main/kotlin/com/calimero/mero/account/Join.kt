package com.calimero.mero.account

import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.cloud.CloudClient
import com.calimero.mero.cloud.CloudNamespaceNode
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.http.NetworkException
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Joining a namespace as an account that holds no node.
 *
 * Port of mero-js `src/account/{relay-from-invitation,join-with-node,bootstrap-from-invitation}.ts`.
 * The device signs its own `MemberJoined` op ([NamespaceOp.signMemberJoinOp]) and hands it
 * to a node the invitation names as an admitter (`POST /admin-api/namespaces/{ns}/admit`),
 * which publishes it. The cloud says which of those nodes it hosts and where they are.
 */
object Join {
    /** Which node to be admitted through, chosen from the cloud's routing ∩ the invitation's admitters. */
    @Suppress("ThrowsCount")
    suspend fun resolveRelayFromInvitation(
        cloud: CloudClient,
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
    ): ResolvedRelay {
        val routing =
            try {
                cloud.getNamespaceRouting(namespaceId)
            } catch (e: Exception) {
                throw JoinException(
                    JoinStep.ADMITTERS_LOOKUP,
                    "The cloud could not be asked which nodes serve namespace $namespaceId: ${e.message}. " +
                        "This is the lookup failing, not the invitation.",
                    cause = e,
                )
            }
        val invited =
            invitation.invitation.admitters
                .mapNotNull(::normaliseAccount)
                .toSet()
        if (routing.nodes.isEmpty()) {
            val relayUrl =
                invitation.admitterAddrs.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }?.trimBase()
                    ?: throw JoinException(
                        JoinStep.NO_NODES,
                        "The cloud lists no nodes serving this namespace, so there is no hosted node to be admitted through.",
                    )
            return ResolvedRelay(
                relayUrl = relayUrl,
                admitUrl = "$relayUrl/admin-api/namespaces/$namespaceId/admit",
                admitterAccount = invited.singleOrNull(),
                writable = true,
                stale = false,
            )
        }
        val named = routing.nodes.filter { invited.isEmpty() || normaliseAccount(it.account) in invited }
        if (named.isEmpty()) {
            throw JoinException(
                JoinStep.NOT_INVITED,
                "${routing.nodes.size} node(s) serve this namespace, but your invitation names none of them. " +
                    "The signed admitter list is a snapshot from when it was minted; ask for a fresh invitation.",
            )
        }
        val usable = named.filter { it.canAdmit }
        val candidates = usable.ifEmpty { named }
        val addressable = candidates.filter { !it.relayUrl.isNullOrEmpty() }
        if (addressable.isEmpty()) {
            throw JoinException(
                JoinStep.NO_RELAY_URL,
                "A node your invitation names can admit you, but the cloud knows no address for it yet. Try again shortly.",
            )
        }
        val chosen: CloudNamespaceNode =
            addressable.firstOrNull { it.canExecute } ?: addressable.firstOrNull { it.fresh } ?: addressable.first()
        val relayUrl = chosen.relayUrl!!.trimBase()
        return ResolvedRelay(
            relayUrl = relayUrl,
            admitUrl = chosen.admitUrl ?: "$relayUrl/admin-api/namespaces/$namespaceId/admit",
            admitterAccount = chosen.account,
            writable = chosen.canExecute,
            stale = !chosen.fresh || usable.isEmpty() || !routing.servable,
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
                    "The admitter could not be reached: ${e.message}. The invitation is untouched.",
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

    private fun explainAdmitFailure(
        status: Int,
        body: String,
    ): String {
        val detail = if (body.isNotEmpty()) ": $body" else ""
        return when (status) {
            400 ->
                "The node refused the join as malformed (400)$detail. The signature covers the invitation exactly as sent, " +
                    "so an edited invitation fails here."
            403 ->
                "The node refused to carry this join (403)$detail. It is not in the invitation's signed admitters, or the " +
                    "invitation was rejected as expired."
            409 -> "That node holds no device of its own, so it cannot endorse anyone (409)$detail."
            else -> "The join was not published (HTTP $status)$detail."
        }
    }
}

/** The node a join goes through. */
data class ResolvedRelay(
    val relayUrl: String,
    val admitUrl: String,
    val admitterAccount: String?,
    val writable: Boolean,
    val stale: Boolean,
)

/** Where a join failed. */
enum class JoinStep { ADMITTERS_LOOKUP, NO_NODES, NOT_INVITED, NO_RELAY_URL, SIGN, ADMIT }

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
