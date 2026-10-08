package com.calimero.mero.cloud

import com.calimero.mero.crypto.Hex
import com.calimero.mero.http.HttpResponse
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import java.net.URLEncoder

/**
 * The Calimero cloud manager's device-proven routing reads.
 *
 * Port of the device-credential half of mero-js `CloudClient` (`src/cloud/cloud-client.ts`).
 * The session-based half (Google / account-root sign-in, `/api/cloud/me/…`) is wallet- and
 * dashboard-side and not needed on a phone: a device only ever proves itself with its
 * certificate ([RoutingCredential]).
 *
 * | method | endpoint |
 * |---|---|
 * | [getAccountRelaysChallenge] | `GET /api/cloud/accounts/{acc}/challenge` |
 * | [getAccountRelays] | `GET /api/cloud/accounts/{acc}/relays` + routing proof |
 * | [getRoutingChallenge] | `GET /api/cloud/namespaces/{ns}/challenge` |
 * | [getNamespaceRouting] | `GET /api/cloud/namespaces/{ns}/admitters` + routing proof |
 */
class CloudClient internal constructor(
    baseUrl: String,
    private val routingCredential: RoutingCredential?,
    private val http: PlainJsonHttp,
) {
    constructor(
        baseUrl: String = DEFAULT_BASE_URL,
        routingCredential: RoutingCredential? = null,
        httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
    ) : this(baseUrl, routingCredential, PlainJsonHttp(httpClient))

    private val base = baseUrl.trimBase()
    private val json = Json { ignoreUnknownKeys = true }

    /** A fresh account-bound nonce to sign. Unauthenticated; nonces live about two minutes. */
    suspend fun getAccountRelaysChallenge(accountId: String): DiscoveryChallenge {
        val body = get("/api/cloud/accounts/${enc(accountId)}/challenge")
        return DiscoveryChallenge(
            accountId = body.str("account_id") ?: accountId,
            nonce = body.str("nonce").orEmpty(),
            expiresAtMs = body.long("expires_at_ms") ?: 0,
        )
    }

    /**
     * The relays that serve [accountId], proven with this client's device certificate.
     *
     * Unusable relays (stale, or with no address yet) are reported rather than filtered:
     * "your relay is down", "no address yet" and "no relay" need different handling. Use
     * [chooseRelay] to pick one.
     */
    suspend fun getAccountRelays(accountId: String): List<CloudAccountRelay> {
        val credential =
            routingCredential
                ?: throw MeroStateException(
                    "getAccountRelays needs a routingCredential: construct the client with the device certificate and its signing key",
                )
        val challenge = getAccountRelaysChallenge(accountId)
        val body = get("/api/cloud/accounts/${enc(accountId)}/relays", RoutingProof.headers(challenge.nonce, credential))
        return (body["relays"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { row ->
            CloudAccountRelay(
                peerId = row.str("peer_id").orEmpty(),
                relayUrl = row.str("relay_url")?.ifEmpty { null },
                fresh = row.bool("fresh") == true,
                executorAccount = row.str("executor_account")?.takeIf { Hex.isHex32(it) },
                assigned = row.bool("assigned") == true,
            )
        }
    }

    /** A fresh namespace-bound nonce to sign. Unauthenticated. */
    suspend fun getRoutingChallenge(namespaceId: String): RoutingChallenge {
        val body = get("/api/cloud/namespaces/${enc(namespaceId)}/challenge")
        return RoutingChallenge(
            namespaceId = body.str("namespace_id") ?: namespaceId,
            nonce = body.str("nonce").orEmpty(),
            expiresAtMs = body.long("expires_at_ms") ?: 0,
        )
    }

    /**
     * Which nodes serve [namespaceId], and which can admit a joiner or execute for it.
     * Proven with the device certificate when this client has one.
     */
    suspend fun getNamespaceRouting(namespaceId: String): CloudNamespaceRouting {
        val headers = routingCredential?.let { RoutingProof.headers(getRoutingChallenge(namespaceId).nonce, it) }.orEmpty()
        val body = get("/api/cloud/namespaces/${enc(namespaceId)}/admitters", headers)
        val nodes =
            (body["admitters"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { row ->
                val account = row.str("account")
                val relayUrl = row.str("relay_url")?.ifEmpty { null }
                val fresh = row.bool("fresh") == true
                val authorshipReady = row.bool("authorship_ready") == true
                CloudNamespaceNode(
                    peerId = row.str("peer_id").orEmpty(),
                    account = account,
                    relayUrl = relayUrl,
                    admitUrl = row.str("admit_url")?.ifEmpty { null },
                    status = row.str("status").orEmpty(),
                    fresh = fresh,
                    canAdmit = row.bool("can_admit") == true,
                    authorshipReady = authorshipReady,
                    teeRole = row.str("tee_role")?.ifEmpty { null },
                    canExecute = row.bool("can_execute") ?: (authorshipReady && relayUrl != null && account != null && fresh),
                )
            }
        return CloudNamespaceRouting(
            namespaceId = body.str("namespace_id") ?: namespaceId,
            nodes = nodes,
            servable = body.bool("servable") == true,
            writable = body.bool("writable") == true,
        )
    }

    private suspend fun get(
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): JsonObject {
        val res: HttpResponse = http.execute("GET", base + path, headers = headers).ensureSuccessful()
        return if (res.body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(res.body).jsonObject
    }

    companion object {
        /** The hosted cloud manager. */
        const val DEFAULT_BASE_URL = "https://manager.cloud.calimero.network"

        /**
         * Pick the relay to talk to, as mero-react's `chooseRelay` does: a fresh relay with an
         * address; otherwise any relay with an address (with a note that it may not answer);
         * otherwise none — which still counts as signed in: a new account is a member of
         * nothing, and redeeming an invitation is what gives it a relay.
         */
        fun chooseRelay(relays: List<CloudAccountRelay>): RelayChoice {
            val reachable = relays.filter { !it.relayUrl.isNullOrEmpty() }
            reachable.firstOrNull { it.fresh }?.let { return RelayChoice(it.relayUrl, it.executorAccount, null) }
            reachable.firstOrNull()?.let {
                return RelayChoice(
                    it.relayUrl,
                    it.executorAccount,
                    "Connected through a relay whose last heartbeat has lapsed — it may not answer. It was the only one with an address.",
                )
            }
            if (relays.isNotEmpty()) {
                val n = relays.size
                return RelayChoice(
                    null,
                    null,
                    "Signed in. Your account has $n relay${if (n == 1) "" else "s"} assigned, but the cloud knows no address " +
                        "for any of them yet. Reads and writes resume as soon as one reports in.",
                )
            }
            return RelayChoice(
                null,
                null,
                "Signed in, with nowhere to write yet: a new account is a member of nothing, so no node serves it. " +
                    "Redeeming an invitation admits this account to a namespace and gives it a relay.",
            )
        }

        private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

/** An account-bound challenge (sealed by the cloud; opaque here). */
data class DiscoveryChallenge(
    val accountId: String,
    val nonce: String,
    val expiresAtMs: Long,
)

/** A namespace-bound challenge (sealed by the cloud; opaque here). */
data class RoutingChallenge(
    val namespaceId: String,
    val nonce: String,
    val expiresAtMs: Long,
)

/** One relay that serves an account. */
data class CloudAccountRelay(
    val peerId: String,
    /** Base URL, or null when the cloud knows none yet. */
    val relayUrl: String?,
    val fresh: Boolean,
    /** The relay's own account (the executor warrants name), 64 hex; null when unknown or malformed. */
    val executorAccount: String?,
    /** True when the cloud just assigned this relay to an account that had none. */
    val assigned: Boolean,
)

/** What [CloudClient.chooseRelay] picked, and why when it is not the obvious answer. */
data class RelayChoice(
    val relayUrl: String?,
    val executorAccount: String?,
    /** A human-readable note for a degraded or empty choice; null for a fresh relay. */
    val note: String?,
)

/** One node serving a namespace. */
data class CloudNamespaceNode(
    val peerId: String,
    /** The node's own account, hex. Intersect against an invitation's signed admitters before using it. */
    val account: String?,
    val relayUrl: String?,
    /** Ready-made admission URL. */
    val admitUrl: String?,
    /** `assigned` or `active`. */
    val status: String,
    val fresh: Boolean,
    val canAdmit: Boolean,
    val authorshipReady: Boolean,
    val teeRole: String?,
    val canExecute: Boolean,
)

/** The routing answer for a namespace. */
data class CloudNamespaceRouting(
    val namespaceId: String,
    val nodes: List<CloudNamespaceNode>,
    val servable: Boolean,
    val writable: Boolean,
)
