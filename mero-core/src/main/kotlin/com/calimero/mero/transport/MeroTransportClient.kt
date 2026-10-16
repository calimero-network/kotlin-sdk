package com.calimero.mero.transport

import com.calimero.mero.ExperimentalMeroApi
import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
import com.calimero.mero.admin.AdminApi
import com.calimero.mero.admin.AdminSurface
import com.calimero.mero.auth.AuthApi
import com.calimero.mero.cloud.CloudClient
import com.calimero.mero.ephemeral.EphemeralClient
import com.calimero.mero.http.HttpClient
import com.calimero.mero.http.HttpResponse
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.OkHttpTransport
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.presence.RelayPresenceClient
import com.calimero.mero.relay.RelayClient
import com.calimero.mero.request.ProofCredential
import com.calimero.mero.request.createProofSigner
import com.calimero.mero.rpc.RpcExecution
import com.calimero.mero.sse.ReconnectBackoff
import com.calimero.mero.sse.SseConnection
import com.calimero.mero.ws.WsClient
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * What a [MeroTransportClient] is built from — mero-js `MeroClientConfig`. The transport is
 * chosen here, once: [Node] is the default everywhere, so nothing an existing app does can
 * select the relay by accident.
 */
sealed class MeroTransportConfig {
    /** A node you have a session on: everything [Mero] takes. */
    class Node(
        val config: MeroConfig,
        /** The cloud the bundled [MeroTransportClient.cloud] talks to. */
        val cloudBaseUrl: String = CloudClient.DEFAULT_BASE_URL,
    ) : MeroTransportConfig()

    /**
     * Delegated execution through a relay: no node, no node credential — mero-js
     * `RelayTransportConfig`.
     *
     * The delegated path has two credentials besides the warrant every write carries: a
     * [proof] (the device certificate signing each request; needs no login and no node key)
     * and a [session] (a Bearer token minted by [com.calimero.mero.relay.RelayLogin], which a
     * hosted relay's forward-auth answers when it knows no proof). Either makes
     * [MeroTransportClient.admin] the relay's admin API **as the account**, so the
     * caller-scoped reads answer this account's own contexts and namespaces. A session wins
     * where both are given — the node's own precedence.
     */
    class Relay(
        /** The relay to write through, already configured ([com.calimero.mero.cloud.connectCloud] hands one back). */
        val relay: RelayClient,
        /** The current relay session token, or `null` while there is none; called per request. */
        val session: (suspend () -> String?)? = null,
        /** How to observe the relay node; without a key it writes but does not observe through a session. */
        val observe: RelayObserveConfig? = null,
        /** The device cert that authenticates this client's own requests (admin, SSE). */
        val proof: ProofCredential? = null,
        /** HTTP for admin reads, the observing login and events. */
        val httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
    ) : MeroTransportConfig()
}

/** The ephemeral surface of a [MeroTransportClient]: the node's own, or an account's presence through the relay. */
sealed class TransportEphemeral {
    /** A node client's [EphemeralClient] (JSON-RPC `set_ephemeral`). */
    data class Node(
        val client: EphemeralClient,
    ) : TransportEphemeral()

    /** A relay client's [RelayPresenceClient]: device-signed presence posted to `presence-intents`. */
    data class Relay(
        val client: RelayPresenceClient,
    ) : TransportEphemeral()
}

/**
 * One client, two transports, chosen once at construction — port of mero-js `MeroClient`
 * (`createMeroClient`).
 *
 * After that, `client.rpc.execute(contextId, method, argsJson)` is the same call against a
 * node and through a relay: same arguments, same return. Events work on both, given the one
 * input that cannot be inferred (the relay node's key) or a request proof; [canSubscribe] and
 * [transport] are there so an app branches **before** touching a surface. A surface a relay
 * client does not have throws [MeroStateException] naming what is missing — nothing here
 * silently connects to a node the app did not choose.
 */
class MeroTransportClient internal constructor(
    config: MeroTransportConfig,
    /** Delegated sessions: read by query (needs a session). */
    readsByQuery: Boolean,
    /** Delegated sessions on a hosted relay not attested yet: events open nothing. */
    private val eventsDormant: Boolean,
) {
    constructor(config: MeroTransportConfig) : this(config, readsByQuery = false, eventsDormant = false)

    /** Which path writes take: [RpcExecution.TRANSPORT_NODE] or [RpcExecution.TRANSPORT_RELAY]. Set once. */
    val transport: String

    private val nodeClient: Mero?
    private val cloudBaseUrl: String?
    private val rpcTransport: ExecuteTransport
    private val relayTransport: RelayTransport?
    private val relayObserver: RelayObserver?
    private val relayAdmin: AdminSurface?
    private val proofClient: Mero?
    private val relayHttp: OkHttpClient?

    init {
        when (config) {
            is MeroTransportConfig.Node -> {
                transport = RpcExecution.TRANSPORT_NODE
                nodeClient = Mero(config.config)
                cloudBaseUrl = config.cloudBaseUrl
                rpcTransport = NodeTransport(nodeClient.rpc)
                relayTransport = null
                relayObserver = null
                relayAdmin = null
                proofClient = null
                relayHttp = null
            }
            is MeroTransportConfig.Relay -> {
                transport = RpcExecution.TRANSPORT_RELAY
                nodeClient = null
                cloudBaseUrl = null
                relayHttp = config.httpClient
                val relay = config.relay
                val observe = config.observe
                relayObserver =
                    RelayObserver.of(
                        nodeUrl = observe?.nodeUrl ?: relay.relayUrl,
                        nodeKey = observe?.nodeKey,
                        accountProof = observe?.accountProof ?: relay.authorProof,
                        signer = observe?.signer?.let { s -> { s } } ?: { relay.authorSigner },
                        audience = observe?.audience ?: com.calimero.mero.relay.Audience.Cli,
                        clientName = observe?.clientName ?: "mero-kotlin-sdk",
                        ttlSeconds = observe?.ttlSeconds ?: DEFAULT_LOGIN_TTL,
                        httpClient = config.httpClient,
                    )
                relayTransport = RelayTransport(relay, relayObserver)
                rpcTransport = if (readsByQuery) ReadsThroughQuery(relayTransport) else relayTransport
                proofClient =
                    config.proof?.let { proof ->
                        Mero(MeroConfig(baseUrl = relay.relayUrl, requestProof = createProofSigner(proof), httpClient = config.httpClient))
                    }
                val session = config.session
                relayAdmin =
                    when {
                        session != null ->
                            AdminApi(BearerHttpClient(OkHttpTransport(relay.relayUrl, config.httpClient, ADMIN_JSON), session))
                        proofClient != null -> proofClient.admin
                        else -> null
                    }
            }
        }
    }

    /** The write surface, identical on both transports — the whole point. */
    val rpc: ExecuteTransport get() = rpcTransport

    /**
     * Whether [events] will work: always on a node; on a relay when a node key or a proof was
     * supplied (and, for a delegated session on a hosted relay, once its key is attested).
     */
    val canSubscribe: Boolean get() = !eventsDormant && (rpcTransport.canSubscribe || proofClient != null)

    /** The underlying [Mero]. Node transport only. */
    val node: Mero get() = nodeClient ?: throw relayHasNo("node connection", "it holds a signing key and a relay URL, not a node")

    /** The underlying [RelayClient], for `describe()`. Relay transport only. */
    val relay: RelayClient
        get() = relayTransport?.client ?: throw MeroStateException("this client was constructed with the node transport and has no relay")

    /**
     * The admin surface. On a node, the node's own. On a relay, the relay's admin API
     * authenticated **as the account** (by the session, else the proof), so the caller-scoped
     * reads (`getContexts`, `listNamespaces`) answer this account's own. Mutations are not
     * filtered here: the relay refuses what the account may not do with a `403` naming the
     * missing permission. For the account-shaped writes, wrap it in
     * [com.calimero.mero.account.createAccountAdmin].
     */
    val admin: AdminSurface
        get() =
            nodeClient?.admin ?: relayAdmin ?: throw MeroStateException(
                "this relay-transport client has no admin surface because it was given neither a `proof` nor a `session`. " +
                    "A warrant authorises one intent and is not a credential for reading. The caller-scoped reads " +
                    "(getContexts, listNamespaces) want one of the other two: pass `proof = ProofCredential(credential, …)` " +
                    "— the device cert you already hold, which needs no login and no node key — or a `session` minted by " +
                    "RelayLogin. Context STATE reads work through execute/query either way.",
            )

    /** Auth API. Node transport only — a relay issues no node credential to this client. */
    val auth: AuthApi get() = node.auth

    private val dormantLazy =
        lazy {
            // A connection whose credential never arrives: it holds its subscriptions, reports
            // no error, and reaches the network never (mero-js `dormantEvents`).
            SseConnection(relay.relayUrl, { null }, streamingClient(relayHttp ?: OkHttpClient()), backoff = ReconnectBackoff.fixed(DORMANT_POLL_MS))
        }
    private val proofEventsLazy = lazy { checkNotNull(proofClient).sse }

    /**
     * SSE events — on **both** transports. On a relay: the observer's session-backed stream
     * when a node key was given, else one authenticated by the request proof. Reading it opens
     * nothing. Throws when the relay client has neither.
     */
    val events: SseConnection
        get() =
            when {
                nodeClient != null -> nodeClient.sse
                eventsDormant -> dormantLazy.value
                relayObserver != null -> relayObserver.events
                proofClient != null -> proofEventsLazy.value
                else -> throw missingNodeKey("SSE events")
            }

    /**
     * WebSocket events, on both transports — experimental, prefer [events]. A relay client
     * with only a request proof refuses it, as mero-js does: SSE carries the proof on every
     * request and is the recommended transport.
     */
    @ExperimentalMeroApi
    val ws: WsClient
        get() =
            when {
                nodeClient != null -> nodeClient.ws
                relayObserver != null -> relayObserver.ws
                proofClient != null ->
                    throw MeroStateException(
                        "this client authenticates with a request proof, which this SDK does not carry on a WebSocket " +
                            "(mero-js parity: a browser WebSocket cannot set the X-Calimero-Proof header). Use `events` — " +
                            "SSE signs every request and is the recommended transport anyway.",
                    )
                else -> throw missingNodeKey("WebSocket events")
            }

    private val presenceLazy = lazy { RelayPresenceClient(relay, events = { events }) }

    /**
     * Ephemeral presence: the node's [EphemeralClient], or on a relay the account's
     * [RelayPresenceClient] — each update signed with the device key and posted to
     * `presence-intents`, resent every 2.5 s. Publishing needs no node key; reading back needs [events].
     */
    val ephemeral: TransportEphemeral
        get() = nodeClient?.let { TransportEphemeral.Node(it.ephemeral) } ?: TransportEphemeral.Relay(presenceLazy.value)

    private val cloudLazy = lazy { CloudClient(checkNotNull(cloudBaseUrl)) }

    /**
     * Calimero Cloud. Node transport only: a relay client is built **from** a cloud connection
     * rather than owning one — reach it through [com.calimero.mero.cloud.CloudConnection.cloud].
     */
    val cloud: CloudClient
        get() {
            if (nodeClient == null) {
                throw relayHasNo(
                    "bundled cloud client",
                    "a relay client is built from a cloud connection rather than owning one — reach it through the `cloud` " +
                        "field connectCloud returns, or construct a CloudClient directly",
                )
            }
            return cloudLazy.value
        }

    /**
     * Release whatever the transport holds open: the node's event stream, the relay observer's
     * session streams, the proof stream, presence resends. Safe on either transport.
     */
    fun close() {
        nodeClient?.sse?.close()
        relayObserver?.close()
        if (proofEventsLazy.isInitialized()) proofEventsLazy.value.close()
        if (dormantLazy.isInitialized()) dormantLazy.value.close()
        if (presenceLazy.isInitialized()) presenceLazy.value.close()
    }

    private companion object {
        const val DEFAULT_LOGIN_TTL = 300L
        const val DORMANT_POLL_MS = 60_000L
        val ADMIN_JSON =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = false
            }

        fun relayHasNo(
            surface: String,
            why: String,
        ) = MeroStateException(
            "this client was constructed with the relay transport, which has no $surface: $why. Construct a node client " +
                "(MeroTransportConfig.Node) for that surface — nothing here will silently connect to a node you did not choose.",
        )

        fun missingNodeKey(surface: String) =
            MeroStateException(
                "this relay-transport client cannot open $surface: it was given no relay node key and no request proof. A relay " +
                    "is an ordinary node and does serve /sse and /ws, but the login statement must be signed against the node's " +
                    "device signing key, learned out of band or from its attestation (never from the node's answer to a login, " +
                    "and never derived from its peerId). Pass it as `observe = RelayObserveConfig(nodeKey)`, or pass `proof`.",
            )
    }
}

/** Build a client for the chosen transport — mero-js `createMeroClient`. */
fun createMeroClient(config: MeroTransportConfig): MeroTransportClient = MeroTransportClient(config)

/** An [HttpClient] that adds `Authorization: Bearer <session()>` to every request without one. */
internal class BearerHttpClient(
    private val inner: HttpClient,
    private val session: suspend () -> String?,
) : HttpClient {
    override val json: Json get() = inner.json

    override suspend fun execute(
        method: String,
        path: String,
        body: String?,
        headers: Map<String, String>,
        contentType: String,
    ): HttpResponse {
        val authed =
            if (headers.keys.any { it.equals("Authorization", ignoreCase = true) }) {
                headers
            } else {
                session()?.takeIf { it.isNotBlank() }?.let { headers + ("Authorization" to "Bearer $it") } ?: headers
            }
        return inner.execute(method, path, body, authed, contentType)
    }
}

/** A long-lived streaming copy of [client]: no read timeout. */
internal fun streamingClient(client: OkHttpClient): OkHttpClient = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
