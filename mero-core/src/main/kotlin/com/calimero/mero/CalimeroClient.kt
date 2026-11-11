package com.calimero.mero

import com.calimero.mero.api.facade.AppsFacade
import com.calimero.mero.api.facade.AuthFacade
import com.calimero.mero.api.facade.ConsentFacade
import com.calimero.mero.api.facade.DeploymentsFacade
import com.calimero.mero.api.facade.OpsFacade
import com.calimero.mero.api.facade.OrgsFacade
import com.calimero.mero.api.facade.PackagesFacade
import com.calimero.mero.api.facade.PublicFacade
import com.calimero.mero.api.facade.Routes
import com.calimero.mero.api.infrastructure.RequestConfig
import com.calimero.mero.api.infrastructure.Serializer
import com.calimero.mero.api.models.AccountOutboundApprovalRequest
import com.calimero.mero.api.models.AccountReleaseApprovalRequest
import com.calimero.mero.api.models.AliasSetRequest
import com.calimero.mero.api.models.ApikeyCreateRequest
import com.calimero.mero.api.models.AppAccessRequest
import com.calimero.mero.api.models.AppCreateRequest
import com.calimero.mero.api.models.AppMetadataRequest
import com.calimero.mero.api.models.AppTransferRequest
import com.calimero.mero.api.models.ConfirmOnly
import com.calimero.mero.api.models.DeployerGrantRequest
import com.calimero.mero.api.models.DeploymentCreateRequest
import com.calimero.mero.api.models.DeploymentOfferRequest
import com.calimero.mero.api.models.IntentsRequest
import com.calimero.mero.api.models.InviteCreateRequest
import com.calimero.mero.api.models.MemberMetadataRequest
import com.calimero.mero.api.models.OpsReleaseDeadlineRequest
import com.calimero.mero.api.models.OrgCapabilitiesRequest
import com.calimero.mero.api.models.OrgCreateRequest
import com.calimero.mero.api.models.OrgOutboundApprovalRequest
import com.calimero.mero.api.models.OrgReleaseApprovalRequest
import com.calimero.mero.api.models.OrgSetMemberRequest
import com.calimero.mero.api.models.OrgUpgradeRetryRequest
import com.calimero.mero.api.models.OriginsAddRequest
import com.calimero.mero.api.models.OriginsRemoveRequest
import com.calimero.mero.api.models.PackageInstallRequest
import com.calimero.mero.api.models.PasskeyLabelRequest
import com.calimero.mero.api.models.PresencePublishRequest
import com.calimero.mero.api.models.PublisherUpgradeRequest
import com.calimero.mero.api.models.RecoverySetRequest
import com.calimero.mero.api.models.RecoveryStartRequest
import com.calimero.mero.api.models.TeamCreateRequest
import com.calimero.mero.api.models.TeamSetMemberRequest
import com.calimero.mero.api.models.TeamUpdateRequest
import com.calimero.mero.api.models.ViewsRequest
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.fromBase64
import com.calimero.mero.crypto.randomBytes
import com.calimero.mero.http.MeroCallFactory
import com.calimero.mero.http.NetworkException
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import com.calimero.mero.tee.SealedTransport
import com.calimero.mero.tee.SealedTransportException
import com.calimero.mero.tee.StaleTransportKeyException
import com.calimero.mero.tee.TeeTrustException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit
import com.calimero.mero.api.models.OrgRole as ApiOrgRole
import com.calimero.mero.api.models.TeamJoin as ApiTeamJoin
import com.calimero.mero.api.models.TeamRole as ApiTeamRole

/** How a [CalimeroClient] reaches and trusts a cluster (mero-js `CalimeroClientOptions`). */
data class CalimeroClientOptions(
    /** One node of the cluster: the one the client talks to first. */
    val baseUrl: String,
    /**
     * Other nodes of the same cluster, in the order to move to them when a node answers
     * `421 WRONG_NODE` (its release may not serve this account), or stays unavailable to a call or
     * a view through all its retries. Each is attested under the same [policy] before anything is
     * sent to it. Without them a `421` reaches the caller as [WrongNode].
     */
    val nodes: List<String> = emptyList(),
    val policy: CloudPolicy,
    /** Carries the attestation and the sealed envelopes. */
    val httpClient: OkHttpClient = defaultHttpClient(),
    /** How calls and views retry a 503 or a failed connection. */
    val retry: CloudRetryOptions = CloudRetryOptions(),
    /**
     * Told every time the session changes: a sign-in, a refresh, a sign-out (null). Keep the
     * session here (encrypted) to restore it with [CalimeroClient.useSession]: a refresh token is
     * used once, so the one kept must always be the newest.
     */
    val onSession: ((CalimeroSession?) -> Unit)? = null,
    /**
     * Approves a `412 CONFIRM_REQUIRED` for the facade (`client.orgs.inviteCreate(...)`) and the
     * generated API, when a call is given no passkey of its own. Null: the [ConfirmRequired] reaches
     * the caller. Also [CalimeroClient.passkey].
     */
    val passkey: PasskeyProvider? = null,
    /**
     * Signs a `428 OPERATOR_SIGNATURE_REQUIRED` (operator decisions) for the facade and the generated
     * API, when a call is given no signer of its own. Also [CalimeroClient.operator].
     */
    val operator: DeviceSigner? = null,
) {
    companion object {
        /** 30 s to connect and to wait for an answer. */
        fun defaultHttpClient(): OkHttpClient = PlainJsonHttp.defaultClient(30_000)
    }
}

/** How [CalimeroClient.connect] reaches a Calimero Cloud cluster by name (mero-js `CalimeroConnectOptions`). */
data class CalimeroConnectOptions(
    /** The cluster's name, e.g. `eu-shared-1`. */
    val cluster: String,
    /** What every node must attest to before anything is sent to it. */
    val policy: CloudPolicy,
    /** The node directory to ask. Default [DEFAULT_DIRECTORY]. */
    val directory: String = DEFAULT_DIRECTORY,
    /** Try the nodes in this zone (e.g. `europe-west4-a`) first. */
    val zone: String? = null,
    /** Carries the directory, the attestation and the sealed envelopes. */
    val httpClient: OkHttpClient = CalimeroClientOptions.defaultHttpClient(),
    /** How calls and views retry a 503 or a failed connection. */
    val retry: CloudRetryOptions = CloudRetryOptions(),
    /** As [CalimeroClientOptions.onSession]. */
    val onSession: ((CalimeroSession?) -> Unit)? = null,
    /** Randomness for the weighted node order, in [0, 1). For tests. */
    val random: () -> Double = Math::random,
    /** The current time in milliseconds. For tests. */
    val now: () -> Long = System::currentTimeMillis,
)

/**
 * The client for Calimero 1.0, the confidential cloud (mero-js `CalimeroClient`): one app = one
 * replicated log on attested nodes. It verifies the node, seals every request to it
 * (`calimero/sealed-http/v2`), signs people in with passkeys and machines with API keys.
 *
 * ```kotlin
 * val cloud = CalimeroClient(CalimeroClientOptions(baseUrl, policy = CloudPolicy(release = CloudReleasePolicy("2.3.109"))))
 * cloud.signIn(PlatformPasskey(activity))
 * val (index, result) = cloud.call(appId, "set", buildJsonObject { put("key", "a"); put("value", "1") })
 * ```
 *
 * Every method is a `suspend` function that does its I/O on [Dispatchers.IO]. A refusal throws
 * the [CalimeroError] it means ([errorFrom]); a node that does not attest throws
 * [TeeTrustException].
 */
@Suppress("TooManyFunctions", "LargeClass")
class CalimeroClient(
    val options: CalimeroClientOptions,
) {
    /** A client for one node, trusting it under [policy]. */
    constructor(baseUrl: String, policy: CloudPolicy) : this(CalimeroClientOptions(baseUrl, policy = policy))

    /** Every node this client may use, `baseUrl` first (or as the directory last ordered them). */
    @Volatile private var nodeUrls: List<String> = (listOf(options.baseUrl) + options.nodes).map { it.trimBase() }.distinct()

    /** The one in use: an index into [nodeUrls]. */
    @Volatile private var at = 0

    /** Where a client made by [connect] re-reads its node list. */
    private class DirectorySource(
        val options: CalimeroConnectOptions,
        /** When the listing is due to be read again (ms since the epoch). */
        @Volatile var due: Long,
        @Volatile var reading: Boolean = false,
    )

    @Volatile private var source: DirectorySource? = null

    private val channels = HashMap<String, SealedTransport>()
    private val lock = Any()

    @Volatile private var credential: String? = null

    @Volatile private var current: CalimeroSession? = null
    private var refreshing: Deferred<CalimeroSession>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val streaming: OkHttpClient by lazy {
        options.httpClient
            .newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /** The node requests go to now: `baseUrl`, until a `421 WRONG_NODE` or a failover moved the client on. */
    val nodeUrl: String get() = synchronized(lock) { nodeUrls[at] }

    /** Every node this client may use, in the order it moves through them. */
    val nodes: List<String> get() = nodeUrls

    /** The session in use, or null (signed out, or an API key). */
    val session: CalimeroSession? get() = current

    /** Approves a `412 CONFIRM_REQUIRED` for a facade or generated-API call given no passkey of its own (see [CalimeroClientOptions.passkey]). */
    @Volatile var passkey: PasskeyProvider? = options.passkey

    /** Signs a `428 OPERATOR_SIGNATURE_REQUIRED` for a facade or generated-API call given no signer of its own (see [CalimeroClientOptions.operator]). */
    @Volatile var operator: DeviceSigner? = options.operator

    // ---------------------------------------------------------------- the generated client

    private val defaultApi: MeroApi by lazy { MeroApi(callFactory(StepUp())) }

    /**
     * The OpenAPI Generator client over this client's transport (sealed, attested, `401` refresh,
     * `421` failover, `503` retries of calls and views). A `412` is approved with [passkey] (else the
     * client's [CalimeroClient.passkey]) and a `428` signed with [operator] (else
     * [CalimeroClient.operator]); with neither, the typed error reaches the caller. Prefer the facade
     * ([orgs], [apps], ...), which takes named parameters instead of request models.
     */
    fun api(
        passkey: PasskeyProvider? = null,
        operator: DeviceSigner? = null,
    ): MeroApi = if (passkey == null && operator == null) defaultApi else MeroApi(callFactory(StepUp(passkey, operator)))

    /** The generated client for one facade call: its step-up overrides and the fields it sends as `null`. */
    private fun facadeApi(
        passkey: PasskeyProvider?,
        operator: DeviceSigner?,
        nulls: Set<String>,
    ): MeroApi = if (nulls.isEmpty()) api(passkey, operator) else MeroApi(callFactory(StepUp(passkey, operator, nulls)))

    private fun callFactory(override: StepUp): MeroCallFactory = MeroCallFactory(scope) { send(it, stepUp(override)) }

    private fun stepUp(override: StepUp): StepUp = StepUp(override.passkey ?: passkey, override.operator ?: operator, override.nulls)

    /** Core's account operations (`auth`): passkeys, sessions, API keys, aliases, recovery, usage. */
    val auth: AuthFacade by lazy { AuthFacade(::facadeApi) }

    /** Core's app operations: instances, calls and views, blobs, files, presence. */
    val apps: AppsFacade by lazy { AppsFacade(::facadeApi) }

    /** The node's consent page. */
    val consent: ConsentFacade by lazy { ConsentFacade(::facadeApi) }

    /** Operator routes (the ops token, [useApiKey]). */
    val ops: OpsFacade by lazy { OpsFacade(::facadeApi) }

    /** Organizations, members, teams, invites. */
    val orgs: OrgsFacade by lazy { OrgsFacade(::facadeApi) }

    /** Packages and their web origins. */
    val packages: PackagesFacade by lazy { PackagesFacade(::facadeApi) }

    /** What any node answers anyone: health, attestation, releases, publisher bindings. */
    val public: PublicFacade by lazy { PublicFacade(::facadeApi) }

    /** Sign-up policy and invites, deployers and deployments. */
    val deployments: DeploymentsFacade by lazy { DeploymentsFacade(::facadeApi) }

    /** The sealed channel to [url], attested under the client's policy before its first request. */
    private fun channel(url: String): SealedTransport =
        synchronized(lock) {
            channels.getOrPut(url) {
                SealedTransport(url, options.httpClient) { fetchAttestedKey(url, options.policy, options.httpClient) }
            }
        }

    /**
     * Move on from node [from] (the one that answered `421 WRONG_NODE`) to the next one. False when
     * there is no other. A request that saw the same node refuse after another request moved on
     * already does not move again.
     */
    private fun moveOn(from: String): Boolean =
        synchronized(lock) {
            if (nodeUrls.size < 2) return false
            if (nodeUrls[at] == from) at = (at + 1) % nodeUrls.size
            true
        }

    /**
     * Fail over from node [from], where a request's retries ran out, to the next node it has not
     * [tried]. False when it has tried them all. A request whose node another request moved away
     * from already goes to that node, if untried.
     */
    private fun failOver(
        from: String,
        tried: Set<String>,
    ): Boolean {
        synchronized(lock) {
            val urls = nodeUrls
            if (urls[at] != from && urls[at] !in tried) return true
            for (step in 1 until urls.size) {
                val next = (at + step) % urls.size
                if (urls[next] !in tried) {
                    at = next
                    break
                }
            }
            if (urls[at] in tried) return false
        }
        reread()
        return true
    }

    /**
     * Read the node list again from the directory, in the background, for a client made by
     * [connect]: the node in use stays first, then the directory's order; a node no longer listed is
     * dropped. A failed read is ignored. One read at a time.
     */
    private fun reread() {
        val src = source ?: return
        synchronized(lock) {
            if (src.reading) return
            src.reading = true
        }
        scope.launch {
            try {
                val listing = readDirectory(src.options.directory, src.options.cluster, options.httpClient)
                val listed = orderNodes(listing.nodes, src.options.zone, src.options.random)
                synchronized(lock) {
                    val current = nodeUrls[at]
                    nodeUrls = listOf(current) + listed.filter { it != current }
                    at = 0
                }
                src.due = src.options.now() + (listing.refreshAfterSeconds * 1000).toLong()
            } catch (_: DirectoryError) {
                // Keep the list the client has.
            } finally {
                src.reading = false
            }
        }
    }

    /** Authenticate every request with an API key (`cal_…`, or an operator token). */
    fun useApiKey(key: String) {
        credential = key
        current = null
    }

    /** Use a session kept from before (an `onSession` callback's). */
    fun useSession(session: CalimeroSession) {
        credential = session.sessionToken
        current = session
    }

    @Volatile private var observer: ((CalimeroSession?) -> Unit)? = options.onSession

    /** Be told every time the session changes (replaces [CalimeroClientOptions.onSession]); null stops. */
    fun onSessionChange(observer: ((CalimeroSession?) -> Unit)?) {
        this.observer = observer
    }

    private fun takeSession(session: CalimeroSession) {
        useSession(session)
        observer?.invoke(session)
    }

    private fun dropSession() {
        credential = null
        current = null
        observer?.invoke(null)
    }

    // ---------------------------------------------------------------- transport

    private class Body(
        val bytes: ByteArray,
        val contentType: String,
    )

    private fun jsonBody(value: JsonElement): Body = Body(value.toString().encodeToByteArray(), "application/json")

    /** Send one request to the node in use, with the credential held now, and answer the response whatever its status. */
    @Suppress("ThrowsCount")
    private suspend fun request(
        method: String,
        path: String,
        body: Body?,
        stream: Boolean,
    ): Response =
        withContext(Dispatchers.IO) {
            source?.let { if (it.options.now() >= it.due) reread() }
            val url = nodeUrl
            val builder = Request.Builder().url("$url/$path")
            credential?.let { builder.header("authorization", "Bearer $it") }
            val payload = body?.let { it.bytes.toRequestBody(it.contentType.toMediaType()) }
            when (method) {
                "GET" -> builder.get()
                "DELETE" -> if (payload != null) builder.delete(payload) else builder.delete()
                else -> builder.method(method, payload ?: ByteArray(0).toRequestBody("application/json".toMediaType()))
            }
            val sealed = channel(url)
            val client = if (stream) streaming.newBuilder().apply { interceptors().add(0, sealed) }.build() else sealed.client
            try {
                client.newCall(builder.build()).execute()
            } catch (e: SealedTransportException) {
                val cause = e.cause
                if (cause is TeeTrustException) throw cause
                // The node refuses an envelope past its 64 MiB sealed cap with `413` in the clear,
                // before opening it: to the caller it is the API's `TOO_LARGE`.
                if (e.status == 413) {
                    throw errorFrom(
                        413,
                        buildJsonObject {
                            put("error", "TOO_LARGE")
                            put("message", (e.message ?: "").removePrefix("Sealed request refused: "))
                        },
                    )
                }
                throw e
            }
        }

    /**
     * [request], answering what a lost issuing node or a node on the wrong release does to a
     * session: a successful response, or the refusal as a typed error.
     *
     * - `401 UNAUTHENTICATED` to a request that carried a session: the client refreshes once,
     *   shared by every request that needs it at the same time ([refresh]), and repeats the request
     *   once. A refused refresh surfaces as [LoginRequired].
     * - `421 WRONG_NODE`: the client moves to the next node it knows and repeats the request once;
     *   with none, the [WrongNode] reaches the caller.
     *
     * Repeating is safe even for a request that is not idempotent: the node answers `401` and `421`
     * when it judges the credential, before it reads what the request asks. The exceptions are the
     * ceremonies that mint a session, never repeated here.
     */
    @Suppress("ComplexCondition", "LoopWithTooManyJumpStatements")
    private suspend fun exchange(
        method: String,
        path: String,
        body: Body? = null,
        stream: Boolean = false,
    ): Response {
        var refreshed = false
        var moved = false
        while (true) {
            val token = credential
            val from = nodeUrl
            val response = request(method, path, body, stream)
            if (response.isSuccessful) return response
            val error = refusal(response)
            if (error is WrongNode && !moved) {
                moved = true
                if (moveOn(from) && !ceremony(path)) continue
            } else if (error is LoginRequired && !refreshed && token != null && current != null && !ceremony(path)) {
                refreshed = true
                if (credential == token) refresh()
                continue
            }
            throw error
        }
    }

    private suspend fun json(
        method: String,
        path: String,
        body: Body? = null,
    ): JsonElement = parse(exchange(method, path, body))

    private suspend fun <T> typed(
        strategy: DeserializationStrategy<T>,
        method: String,
        path: String,
        body: Body? = null,
    ): T = wire.decodeFromJsonElement(strategy, json(method, path, body))

    /**
     * [json] for a call or a view: while the node answers 503 or cannot be reached, the same
     * request, with the same idempotency key, is sent again after [retryDelayMs] (or the pause the
     * node asked for, if longer), at most `retries` times. Every other answer is final.
     *
     * When a node's retries run out, the request fails over to the next node the client knows and
     * its retries start again there, with the same key: the key is kept in the app's replicated
     * log, so the cluster answers it once whichever node it reaches. Each node is tried once per
     * request. The last failure surfaces as [UnavailableError]: the node's 503, or reason `network`
     * for a connection that failed, whose `committed` is [Committed.Unknown].
     *
     * [once] sends the request exactly once, to the node in use (the confirmed attempt of
     * [callConfirmed], whose challenge is single-use).
     */
    @Suppress("ThrowsCount", "NestedBlockDepth")
    private suspend fun retriedExchange(
        method: String,
        path: String,
        body: Body?,
        once: Boolean = false,
    ): Response {
        val retries = if (once) 0 else options.retry.retries
        val tried = HashSet<String>()
        while (true) {
            var node: String
            var attempt = 0
            var unavailable: UnavailableError
            while (true) {
                node = nodeUrl
                tried += node
                unavailable =
                    try {
                        return exchange(method, path, body)
                    } catch (e: UnavailableError) {
                        e
                    } catch (e: SealedTransportException) {
                        // A node that cannot be reached to attest it is as unavailable as one
                        // that drops the connection; any other sealed-channel failure is final.
                        if (e.status != null || e.cause !is NetworkException) throw e
                        UnavailableError("NETWORK", "the node could not be reached: ${e.cause?.message}", cause = e)
                    } catch (e: StaleTransportKeyException) {
                        throw e
                    } catch (e: IOException) {
                        UnavailableError("NETWORK", "the node could not be reached: ${e.message}", cause = e)
                    }
                if (attempt >= retries) break
                val backoff = retryDelayMs(attempt, options.retry)
                delay(maxOf(backoff, ((unavailable.retryAfter ?: 0.0) * 1000).toLong()))
                attempt += 1
            }
            if (once || !failOver(node, tried)) throw unavailable
        }
    }

    /** A generated request (on [MeroApi.API_BASE]), sent through [dispatch] to the node in use. */
    private suspend fun send(
        request: Request,
        stepUp: StepUp,
    ): Response {
        val bytes = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readByteArray() }
        val type = request.body?.contentType()?.let { "${it.type}/${it.subtype}" }
        var body = if (bytes == null || (bytes.isEmpty() && type == null)) null else Body(bytes, type ?: OCTET_STREAM)
        if (stepUp.nulls.isNotEmpty()) {
            // The generated request leaves every null out; these the caller set to `null` ([Explicit]).
            val json = body?.let { parseOrNull(it.bytes.decodeToString()) as? JsonObject } ?: JsonObject(emptyMap())
            body = jsonBody(JsonObject(json + stepUp.nulls.filterNot(json::containsKey).associateWith { JsonNull }))
        }
        return dispatch(request.method, relativePath(request.url), body, stepUp)
    }

    /**
     * Every request, generated or hand-written: [exchange] (`401` refresh, `421` failover), with the
     * `503` retries and failover of [retriedExchange] for calls and views (a call given no
     * idempotency key gets one, shared by its retries), then the step-ups: a
     * `412 CONFIRM_REQUIRED` answered with [StepUp.passkey]'s assertion as `confirm`, a
     * `428 OPERATOR_SIGNATURE_REQUIRED` with [StepUp.operator]'s signature as `operatorProof`, each
     * once, in the request's JSON body. Without the one it needs, the typed error reaches the caller.
     */
    @Suppress("ThrowsCount", "ReturnCount", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod")
    private suspend fun dispatch(
        method: String,
        path: String,
        body: Body?,
        stepUp: StepUp,
    ): Response {
        val route = path.substringBefore('?')
        if (Routes.plain.any { it.matches(method, route) }) return plain(method, path)
        val retriable = Routes.retrySafe.any { it.matches(method, route) }
        var json = body?.takeIf { it.contentType.startsWith(JSON) }?.let { parseOrNull(it.bytes.decodeToString()) as? JsonObject }
        if (json != null && Routes.idempotencyKey.any { it.matches(method, route) } && !json.containsKey("idempotencyKey")) {
            json = JsonObject(json + ("idempotencyKey" to JsonPrimitive(newIdempotencyKey())))
        }
        var current = json?.let(::jsonBody) ?: body
        while (true) {
            val sent = json
            val binary = current != null && sent == null
            val field: Pair<String, JsonElement> =
                try {
                    return if (retriable) retriedExchange(method, path, current, once = sent?.containsKey("confirm") == true) else exchange(method, path, current)
                } catch (e: ConfirmRequired) {
                    val passkey = stepUp.passkey ?: throw e
                    if (binary || sent?.containsKey("confirm") == true) throw e
                    "confirm" to assertionFor(passkey, e.details)
                } catch (e: OperatorSignatureRequired) {
                    val signer = stepUp.operator ?: throw e
                    if (binary || sent?.containsKey("operatorProof") == true) throw e
                    "operatorProof" to
                        buildJsonObject {
                            put("nonce", e.nonce)
                            put("signature", Hex.encode(signer.sign(fromBase64(e.statement))))
                        }
                }
            val next = JsonObject(sent.orEmpty() + field)
            json = next
            current = jsonBody(next)
        }
    }

    /** A route the spec says travels in the clear (`x-calimero-sealed: false`: `/health`, `/attestation`), to the node in use. */
    private suspend fun plain(
        method: String,
        path: String,
    ): Response =
        withContext(Dispatchers.IO) {
            val response =
                options.httpClient
                    .newCall(
                        Request
                            .Builder()
                            .url("$nodeUrl/$path")
                            .method(method, null)
                            .build(),
                    ).execute()
            if (!response.isSuccessful) throw refusal(response)
            response
        }

    /** A passkey's `confirm` for a `412`'s [challenge]: `{ challengeId, assertion }`. A declined prompt is [ConfirmCancelled]. */
    private suspend fun assertionFor(
        passkey: PasskeyProvider,
        challenge: JsonObject,
    ): JsonObject {
        val assertion =
            try {
                passkey.get(
                    fromB64url(challenge.string("challenge").orEmpty()),
                    challenge.string("rpId").orEmpty(),
                    (challenge["allowCredentials"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content },
                )
            } catch (e: PasskeyCancelled) {
                throw ConfirmCancelled(e)
            }
        return buildJsonObject {
            put("challengeId", challenge.string("challengeId").orEmpty())
            put("assertion", wire.encodeToJsonElement(assertion))
        }
    }

    /** A successful response's JSON (`{}` for an empty body). */
    private suspend fun parse(response: Response): JsonElement {
        val text = withContext(Dispatchers.IO) { response.use { it.body?.string().orEmpty() } }
        return if (text.isBlank()) JsonObject(emptyMap()) else wire.parseToJsonElement(text)
    }

    /**
     * A generated operation's request ([config], from `api().<tag>.<op>RequestConfig(...)`) sent
     * through [dispatch], its answer as JSON: how the deprecated methods call the generated client
     * and still read the answer as they always did.
     */
    private suspend inline fun <reified I> raw(
        config: RequestConfig<I>,
        passkey: PasskeyProvider? = null,
        extra: JsonObject? = null,
    ): JsonElement {
        val encoded = config.body?.let { Serializer.kotlinxSerializationJson.encodeToJsonElement(it) as JsonObject }
        val merged = if (extra == null) encoded else JsonObject(encoded.orEmpty() + extra)
        return parse(dispatch(config.method.name, configPath(config), merged?.let(::jsonBody), stepUp(StepUp(passkey))))
    }

    /**
     * [config]'s route with [body] in place of the generated one: the binary uploads that keep the
     * caller's content type, or the raw bytes, as they always sent them.
     */
    private suspend fun binary(
        method: String,
        config: RequestConfig<*>,
        body: Body,
    ): JsonElement = parse(dispatch(method, configPath(config), body, stepUp(StepUp())))

    /** A generated request model from the JSON a hand-written options type encodes to. */
    private inline fun <reified T> model(json: JsonElement): T = Serializer.kotlinxSerializationJson.decodeFromJsonElement(json)

    private fun OrgRole.api(): ApiOrgRole = checkNotNull(ApiOrgRole.decode(wire))

    private fun TeamRole.api(): ApiTeamRole = checkNotNull(ApiTeamRole.decode(wire))

    private fun TeamJoin.api(): ApiTeamJoin = checkNotNull(ApiTeamJoin.decode(wire))

    /** POST [body] to [path] (relative to the node, no leading slash) and answer the JSON. */
    @Deprecated("Raw request plumbing: use the facade's method for the route (client.orgs, client.apps, ...)")
    suspend fun post(
        path: String,
        body: JsonElement = JsonObject(emptyMap()),
    ): JsonElement = json("POST", path, jsonBody(body))

    @Deprecated("Raw request plumbing: use the facade's method for the route (client.orgs, client.apps, ...)")
    suspend fun get(path: String): JsonElement = json("GET", path)

    @Deprecated("Raw request plumbing: use the facade's method for the route (client.orgs, client.apps, ...)")
    suspend fun put(
        path: String,
        body: JsonElement = JsonObject(emptyMap()),
    ): JsonElement = json("PUT", path, jsonBody(body))

    @Deprecated("Raw request plumbing: use the facade's method for the route (client.orgs, client.apps, ...)")
    suspend fun delete(
        path: String,
        body: JsonElement? = null,
    ): JsonElement = json("DELETE", path, body?.let(::jsonBody))

    // ---------------------------------------------------------------- identity

    /**
     * Create an account with a new passkey, and sign in. The node then asks the new passkey to sign
     * the account it creates (`412 CONFIRM_REQUIRED`, answered here), so a sign-up prompts twice:
     * create, then sign.
     *
     * [SignUpOptions.invite] goes on both halves and on the retry; a sign-up the cluster's policy
     * refuses ([signUpPolicy]), or an unknown, expired or used invite, is [SignUpRefused], from
     * `begin`, before any passkey is made. [SignUpOptions.origin] names a registered web origin:
     * the passkey is made for its relying party and the session is bound to its package; an
     * unregistered one is [OriginNotRegistered].
     */
    suspend fun signUp(
        passkey: PasskeyProvider,
        name: String,
        options: SignUpOptions = SignUpOptions(),
    ): CalimeroSession {
        val begin =
            begin(
                "auth/passkey/register/begin",
                buildJsonObject {
                    put("name", name)
                    options.origin?.let { put("origin", it) }
                    options.invite?.let { put("invite", it) }
                },
                options.origin,
            )
        val registration = passkey.create(begin["options"] as? JsonObject ?: JsonObject(emptyMap()))
        val finished =
            postConfirmed(
                "auth/passkey/register/finish",
                buildJsonObject {
                    put("challengeId", begin.string("challengeId").orEmpty())
                    put("registration", wire.encodeToJsonElement(registration))
                    options.invite?.let { put("invite", it) }
                },
                passkey,
            )
        val session = wire.decodeFromJsonElement(CalimeroSession.serializer(), finished)
        takeSession(session)
        return session
    }

    /** Who may create an account on the cluster (`GET /sign-up`); needs no session. */
    @Deprecated(
        "Use the generated facade: deployments.signUpPublic",
        ReplaceWith("deployments.signUpPublic()"),
    )
    suspend fun signUpPolicy(): SignUpPolicy = wire.decodeFromJsonElement(SignUpPolicy.serializer(), raw(defaultApi.signUpAndDeployments.signUpPublicRequestConfig()))

    /**
     * Sign in with an existing passkey. With [SignInOptions.origin] (a registered web origin) the
     * assertion is made for that origin's relying-party id, as the node answers it, and the session
     * is bound to the origin's package; an unregistered one is [OriginNotRegistered].
     */
    suspend fun signIn(
        passkey: PasskeyProvider,
        options: SignInOptions = SignInOptions(),
    ): CalimeroSession {
        val begin = begin("auth/passkey/login/begin", options.origin?.let { o -> buildJsonObject { put("origin", o) } }, options.origin)
        val opts = begin["options"] as? JsonObject ?: JsonObject(emptyMap())
        val rpId = opts.string("rpId") ?: (opts["rp"] as? JsonObject).string("id")
        val assertion = passkey.get(fromB64url(opts.string("challenge").orEmpty()), rpId.orEmpty())
        val session =
            typed(
                CalimeroSession.serializer(),
                "POST",
                "auth/passkey/login/finish",
                jsonBody(
                    buildJsonObject {
                        put("challengeId", begin.string("challengeId").orEmpty())
                        put("assertion", wire.encodeToJsonElement(assertion))
                    },
                ),
            )
        takeSession(session)
        return session
    }

    /** POST a ceremony's `begin`, naming [origin] in an [OriginNotRegistered] the node left it out of. */
    private suspend fun begin(
        path: String,
        body: JsonObject?,
        origin: String?,
    ): JsonObject =
        try {
            post(path, body ?: JsonObject(emptyMap())) as JsonObject
        } catch (e: OriginNotRegistered) {
            if (e.origin.isNotEmpty() || origin == null) throw e
            throw OriginNotRegistered(e.nodeMessage, JsonObject(e.details + ("origin" to JsonPrimitive(origin))))
        }

    /**
     * Swap the refresh token for a new session, on whichever node the client uses: any node of the
     * cluster takes it. A refresh token is used once, so calls made while one refresh is under way
     * share it rather than sending the same token again (which would revoke the session).
     */
    suspend fun refresh(): CalimeroSession {
        val pending =
            synchronized(lock) {
                refreshing ?: scope.async { exchangeRefresh() }.also { started ->
                    refreshing = started
                    started.invokeOnCompletion { synchronized(lock) { if (refreshing === started) refreshing = null } }
                }
            }
        return pending.await()
    }

    private suspend fun exchangeRefresh(): CalimeroSession {
        val session = checkNotNull(current) { "no session to refresh" }
        var tried = 1
        while (true) {
            try {
                val fresh =
                    typed(
                        CalimeroSession.serializer(),
                        "POST",
                        "auth/refresh",
                        jsonBody(buildJsonObject { put("refreshToken", session.refreshToken) }),
                    )
                // A sign-in or another session taken while this one was refreshing wins.
                if (current === session) takeSession(fresh)
                return fresh
            } catch (e: WrongNode) {
                // A node that may not serve the account answers 421 before it spends the refresh
                // token, so the same token goes to the next node (`send` moved there): once per node.
                if (tried >= nodeUrls.size) throw e
                tried += 1
            }
        }
    }

    /** End this session. */
    suspend fun signOut() {
        raw(defaultApi.accounts.logoutRequestConfig())
        dropSession()
    }

    /** End every session of the signed-in account, on every device, this one too. */
    suspend fun signOutEverywhere() {
        raw(defaultApi.accounts.logoutAllRequestConfig())
        dropSession()
    }

    /** Forget the session here without telling the node (it lapses by itself). */
    fun forget() = dropSession()

    /** The signed-in account: its passkeys, its organizations. */
    @Deprecated(
        "Use the generated facade: auth.me",
        ReplaceWith("auth.me()"),
    )
    suspend fun me(): AccountInfo {
        val o = raw(defaultApi.accounts.meRequestConfig()) as JsonObject
        return AccountInfo(
            raw = o,
            accountId = o.string("accountId").orEmpty(),
            name = o.string("name").orEmpty(),
            passkeys = (o["passkeys"] as? JsonArray)?.let { wire.decodeFromJsonElement(ListSerializer(PasskeyInfo.serializer()), it) }.orEmpty(),
            orgs = (o["orgs"] as? JsonArray)?.let { wire.decodeFromJsonElement(ListSerializer(OrgMembership.serializer()), it) }.orEmpty(),
            via = o.string("via").orEmpty(),
            passkeyId = o.string("passkeyId"),
            app = o.string("app"),
            origin = o.string("origin"),
        )
    }

    /**
     * Add another passkey (another device, [passkey]) to the signed-in account; answers its
     * credential id. The node asks for the approval of a passkey the account already has
     * (`412 CONFIRM_REQUIRED`, never the new one), which [approver] gives.
     */
    suspend fun addPasskey(
        passkey: PasskeyProvider,
        approver: PasskeyProvider,
    ): String {
        val begin = post("auth/passkeys/add/begin") as JsonObject
        val registration = passkey.create(begin["options"] as? JsonObject ?: JsonObject(emptyMap()))
        val done =
            postConfirmed(
                "auth/passkeys/add/finish",
                buildJsonObject {
                    put("challengeId", begin.string("challengeId").orEmpty())
                    put("registration", wire.encodeToJsonElement(registration))
                },
                approver,
            ) as JsonObject
        return done.string("credentialId").orEmpty()
    }

    /** Remove one of the account's passkeys (not the last), approved with [passkey] (one it has). */
    @Deprecated(
        "Use the generated facade: auth.passkeyRemove",
        ReplaceWith("auth.passkeyRemove(cred = credentialId, passkey = passkey)"),
    )
    suspend fun removePasskey(
        credentialId: String,
        passkey: PasskeyProvider,
    ) {
        raw(defaultApi.accounts.passkeyRemoveRequestConfig(credentialId, ConfirmOnly()), passkey)
    }

    /** Name one of the account's passkeys (a device label); `""` clears the name. */
    @Deprecated(
        "Use the generated facade: auth.passkeyLabel",
        ReplaceWith("auth.passkeyLabel(cred = credentialId, label = label)"),
    )
    suspend fun labelPasskey(
        credentialId: String,
        label: String,
    ) {
        raw(defaultApi.accounts.passkeyLabelRequestConfig(credentialId, PasskeyLabelRequest(label = label)))
    }

    /** What this account uses (apps, bundle bytes, API keys, owned orgs), with the cluster's quotas. */
    @Deprecated(
        "Use the generated facade: auth.usage",
        ReplaceWith("auth.usage()"),
    )
    suspend fun usage(): JsonObject = raw(defaultApi.accounts.usageRequestConfig()) as JsonObject

    /**
     * The node's attestation document, unverified: for display only. Every request this client
     * sends is sealed to a key it verified itself first.
     */
    suspend fun attestation(): AttestationDoc =
        withContext(Dispatchers.IO) {
            val nonce = Hex.encode(randomBytes(32))
            val response = PlainJsonHttp(options.httpClient, role = null).execute("GET", "$nodeUrl/attestation?nonce=$nonce")
            if (!response.isSuccessful) throw errorFrom(response.status, parseOrNull(response.body), response.headers["retry-after"])
            wire.decodeFromString(AttestationDoc.serializer(), response.body)
        }

    /** The account's API keys (never the keys themselves). */
    @Deprecated(
        "Use the generated facade: auth.apikeyList",
        ReplaceWith("auth.apikeyList().propertyKeys"),
    )
    suspend fun listApiKeys(): List<ApiKeyInfo> = listOf("keys", ApiKeyInfo.serializer(), raw(defaultApi.accounts.apikeyListRequestConfig()))

    /** Revoke one of the account's API keys, approved with its [passkey] (`412 CONFIRM_REQUIRED`). */
    @Deprecated(
        "Use the generated facade: auth.apikeyRevoke",
        ReplaceWith("auth.apikeyRevoke(key = keyId, passkey = passkey)"),
    )
    suspend fun revokeApiKey(
        keyId: String,
        passkey: PasskeyProvider,
    ) {
        raw(defaultApi.accounts.apikeyRevokeRequestConfig(keyId, ConfirmOnly()), passkey)
    }

    /**
     * Create an API key for an agent or server, approved with the account's [passkey]
     * (`412 CONFIRM_REQUIRED`); needs a passkey session (an API key is refused `403`). The key is
     * returned once. [ApiKeyOptions.upgrades] makes an upgrade key for CI: it upgrades that one app
     * with [upgradeApp] (no passkey; a `version` of its package) and is refused everything else
     * (`403 UPGRADE_KEY_ONLY`).
     */
    @Deprecated(
        "Use the generated facade: auth.apikeyCreate",
        ReplaceWith("auth.apikeyCreate(label = options.label, apps = options.apps, methods = options.methods, readOnly = options.readOnly, passkey = passkey)"),
    )
    suspend fun createApiKey(
        options: ApiKeyOptions,
        passkey: PasskeyProvider,
    ): ApiKeyCreated {
        val request = model<ApikeyCreateRequest>(wire.encodeToJsonElement(options))
        return wire.decodeFromJsonElement(ApiKeyCreated.serializer(), raw(defaultApi.accounts.apikeyCreateRequestConfig(request), passkey))
    }

    // ---------------------------------------------------------------- apps

    /** Upload a raw WASM bundle (development clusters only); answers its hash. */
    @Deprecated(
        "Use the generated facade: packages.bundlePut",
        ReplaceWith("packages.bundlePut(body = wasm).hash"),
    )
    suspend fun uploadBundle(wasm: ByteArray): String =
        (binary("POST", defaultApi.packages.bundlePutRequestConfig(NO_BYTES), Body(wasm, "application/wasm")) as JsonObject).string("hash").orEmpty()

    /**
     * Upload a blob for [app]: `blobId` is base58 (what apps print), `id` hex. [contentType]
     * (printable ASCII; the node refuses another with `400`) is sent as the upload's
     * `Content-Type`: a component app records it and serves downloads with it. A component app
     * holding as many blobs as it may answers `429 QUOTA_EXCEEDED`.
     */
    suspend fun uploadBlob(
        app: String,
        data: ByteArray,
        contentType: String = OCTET_STREAM,
    ): BlobInfo = wire.decodeFromJsonElement(BlobInfo.serializer(), binary("POST", defaultApi.apps.blobUploadRequestConfig(app, NO_BYTES, null), Body(data, contentType)))

    /**
     * Upload a sealed file for component app [app] (`POST /apps/{app}/blobs?sealed=true`): sealed
     * under a key of its own that only the app's code reads, so it is never downloaded or granted.
     * Its owner and its organization's members only (`403` otherwise). Answers the file's ids with
     * the plaintext's size and [BlobInfo.sha256]. Held for an hour unless the app stores a
     * `BlobRef` to it. Past the organization's file quota: [QuotaExceeded].
     */
    @Deprecated("Core refuses a sealed upload (`400`) since calimero-network/core#4937; upload with apps.blobUpload")
    suspend fun uploadFile(
        app: String,
        data: ByteArray,
    ): BlobInfo = wire.decodeFromJsonElement(BlobInfo.serializer(), binary("POST", defaultApi.apps.blobUploadRequestConfig(app, NO_BYTES, true), Body(data, OCTET_STREAM)))

    /** One of [app]'s blobs, by id (base58 or hex). */
    @Deprecated(
        "Use the generated facade: apps.blobDownload",
        ReplaceWith("apps.blobDownload(app = app, id = id)"),
    )
    suspend fun downloadBlob(
        app: String,
        id: String,
    ): ByteArray = blobContent(app, id).data

    /**
     * Download a component app's blob with a grant one of its runs handed out (`BlobRef::grant`,
     * up to 24 h). No session needed: the grant is the permission. A bad or expired grant answers
     * [NotFound], as a missing blob.
     */
    @Deprecated(
        "Use the generated facade: apps.blobDownload",
        ReplaceWith("apps.blobDownload(app = app, id = id, grant = grant)"),
    )
    suspend fun downloadBlobGranted(
        app: String,
        id: String,
        grant: String,
    ): ByteArray = blobContent(app, id, grant).data

    /**
     * [downloadBlob] (or, with [grant], [downloadBlobGranted]) with the content type the node
     * serves it as: the uploader's, for a component app; `application/octet-stream` otherwise.
     */
    suspend fun blobContent(
        app: String,
        id: String,
        grant: String? = null,
    ): BlobContent {
        val response = dispatch("GET", configPath(defaultApi.apps.blobDownloadRequestConfig(app, id, grant)), null, StepUp())
        return withContext(Dispatchers.IO) {
            response.use { BlobContent(it.body?.bytes() ?: ByteArray(0), it.header("content-type") ?: OCTET_STREAM) }
        }
    }

    /** [app]'s blobs. */
    @Deprecated(
        "Use the generated facade: apps.blobList",
        ReplaceWith("apps.blobList(app = app).blobs"),
    )
    suspend fun listBlobs(app: String): List<BlobInfo> = listOf("blobs", BlobInfo.serializer(), raw(defaultApi.apps.blobListRequestConfig(app)))

    /** One of [app]'s blobs' id and size; [NotFound] if the app has no such blob. */
    @Deprecated(
        "Use the generated facade: apps.blobInfo",
        ReplaceWith("apps.blobInfo(app = app, id = id)"),
    )
    suspend fun blobInfo(
        app: String,
        id: String,
    ): BlobInfo = wire.decodeFromJsonElement(BlobInfo.serializer(), raw(defaultApi.apps.blobInfoRequestConfig(app, id)))

    /** Delete one of [app]'s blobs (its owner, or an admin of its organization). */
    @Deprecated(
        "Use the generated facade: apps.blobDelete",
        ReplaceWith("apps.blobDelete(app = app, id = id)"),
    )
    suspend fun deleteBlob(
        app: String,
        id: String,
    ) {
        raw(defaultApi.apps.blobDeleteRequestConfig(app, id))
    }

    // ---------------------------------------------------------------- packages

    /** Install a signed `.mpk` package file (development clusters only, else `403 UPLOADS_DISABLED`). */
    @Deprecated(
        "Use the generated facade: packages.upload",
        ReplaceWith("packages.upload(body = mpk)"),
    )
    suspend fun uploadPackage(mpk: ByteArray): PackageInstalled =
        wire.decodeFromJsonElement(PackageInstalled.serializer(), binary("POST", defaultApi.packages.packageUploadRequestConfig(NO_BYTES), Body(mpk, OCTET_STREAM)))

    /** Install `pkg@version` from the cluster's registry; every node that runs it verifies it. */
    @Deprecated(
        "Use the generated facade: packages.install",
        ReplaceWith("packages.install(`package` = pkg, version = version)"),
    )
    suspend fun installPackage(
        pkg: String,
        version: String,
    ): PackageInstalled =
        wire.decodeFromJsonElement(
            PackageInstalled.serializer(),
            raw(defaultApi.packages.packageInstallRequestConfig(PackageInstallRequest(`package` = pkg, version = version))),
        )

    /** Installed packages and their versions. */
    @Deprecated(
        "Use the generated facade: packages.list",
        ReplaceWith("packages.list().packages"),
    )
    suspend fun packages(): List<PackageInfo> = listOf("packages", PackageInfo.serializer(), raw(defaultApi.packages.packageListRequestConfig()))

    /** One installed package and its versions. */
    @Deprecated(
        "Use the generated facade: packages.get",
        ReplaceWith("packages.get(`package` = pkg)"),
    )
    suspend fun `package`(pkg: String): PackageInfo = wire.decodeFromJsonElement(PackageInfo.serializer(), raw(defaultApi.packages.packageGetRequestConfig(pkg)))

    /** Uninstall one version, or the whole package. Refused while an app runs it. */
    @Deprecated(
        "Use the generated facade: packages.uninstall. With a version: packages.versionUninstall",
        ReplaceWith("packages.uninstall(`package` = pkg)"),
    )
    suspend fun uninstallPackage(
        pkg: String,
        version: String? = null,
    ): JsonElement =
        if (version == null) {
            raw(defaultApi.packages.packageUninstallRequestConfig(pkg))
        } else {
            raw(defaultApi.packages.packageVersionUninstallRequestConfig(pkg, version))
        }

    // ---------------------------------------------------------------- app instances

    /** Apps this caller reaches, optionally only those of one package or bundle. */
    @Deprecated(
        "Use the generated facade: apps.list",
        ReplaceWith("apps.list(`package` = pkg, bundle = bundle).apps"),
    )
    suspend fun listApps(
        pkg: String? = null,
        bundle: String? = null,
    ): List<AppInfo> = listOf("apps", AppInfo.serializer(), raw(defaultApi.apps.appListRequestConfig(pkg, bundle)))

    /** One app instance: its record (`name`, `owner`, `org`, `access`, `metadata`, ...). */
    @Deprecated(
        "Use the generated facade: apps.get",
        ReplaceWith("apps.get(app = app)"),
    )
    suspend fun getApp(app: String): JsonObject = raw(defaultApi.apps.appGetRequestConfig(app)) as JsonObject

    /** Delete an app instance (its owner, or an admin of its organization). */
    @Deprecated(
        "Use the generated facade: apps.delete",
        ReplaceWith("apps.delete(app = app)"),
    )
    suspend fun deleteApp(app: String) {
        raw(defaultApi.apps.appDeleteRequestConfig(app))
    }

    /** Who may reach the app. */
    @Deprecated(
        "Use the generated facade: apps.access",
        ReplaceWith("apps.access(app = app, access = access)"),
    )
    suspend fun setAccess(
        app: String,
        access: AppAccess,
    ) {
        raw(defaultApi.apps.appAccessRequestConfig(app, AppAccessRequest(access = access)))
    }

    /** Set the app instance's metadata (its owner, or an admin of its organization); at most 16 KiB. */
    @Deprecated(
        "Use the generated facade: apps.metadata",
        ReplaceWith("apps.metadata(app = app, metadata = metadata)"),
    )
    suspend fun setAppMetadata(
        app: String,
        metadata: JsonElement,
    ) {
        raw(defaultApi.apps.appMetadataRequestConfig(app, AppMetadataRequest(metadata = metadata)))
    }

    /**
     * A component app's xcalls: how many messages wait in its outbox, and the dead letters (never
     * delivered), newest last. Its owner's or an org admin's (`403` to anyone else);
     * `409 NOT_SUPPORTED` for an app on the wasmer runtime, whose xcalls are follow-ups.
     */
    @Deprecated(
        "Use the generated facade: apps.xcalls",
        ReplaceWith("apps.xcalls(app = app)"),
    )
    suspend fun appXcalls(app: String): AppXcalls = wire.decodeFromJsonElement(AppXcalls.serializer(), raw(defaultApi.apps.appXcallsRequestConfig(app)))

    /** What the app's storage holds: `{ keys, bytes, blobs, blobBytes }`. */
    @Deprecated(
        "Use the generated facade: apps.storage",
        ReplaceWith("apps.storage(app = app)"),
    )
    suspend fun appStorage(app: String): JsonObject = raw(defaultApi.apps.appStorageRequestConfig(app)) as JsonObject

    /** The ABI the app's bundle embeds: `{ bundle, abi }`. */
    @Deprecated(
        "Use the generated facade: apps.abi",
        ReplaceWith("apps.abi(app = app)"),
    )
    suspend fun appAbi(app: String): JsonObject = raw(defaultApi.apps.appAbiRequestConfig(app)) as JsonObject

    /** Hand the app to another account. The owner approves with [passkey]. */
    @Deprecated(
        "Use the generated facade: apps.transfer",
        ReplaceWith("apps.transfer(app = app, to = to, passkey = passkey)"),
    )
    suspend fun transferApp(
        app: String,
        to: String,
        passkey: PasskeyProvider,
    ) {
        raw(defaultApi.apps.appTransferRequestConfig(app, AppTransferRequest(to = to)), passkey)
    }

    /**
     * Move an app to another release of its package, another package, or a bundle hash, running
     * its migration. Where owners hold app upgrades, the node asks for the approval of whoever the
     * package's manifest names (`412 CONFIRM_REQUIRED`), which [passkey] gives. Refused (`403`) to
     * a session opened on an app's frontend: ask with [requestConsent] instead.
     */
    @Deprecated(
        "Use the generated facade: apps.upgrade",
        ReplaceWith("apps.upgrade(app = app, bundle = target.bundle, `package` = target.`package`, version = target.version, service = target.service, migrate = target.migrate, passkey = passkey)"),
    )
    suspend fun upgradeApp(
        app: String,
        target: AppUpgradeTarget,
        passkey: PasskeyProvider? = null,
    ): JsonObject = raw(defaultApi.apps.appUpgradeRequestConfig(app, model(wire.encodeToJsonElement(target))), passkey) as JsonObject

    /** POST [body] to [path], answering a `412 CONFIRM_REQUIRED` with [passkey]'s assertion over the node's challenge. */
    @Deprecated("Raw request plumbing: use the facade's method for the route (client.orgs, client.apps, ...), which answers 412 itself")
    suspend fun postConfirmed(
        path: String,
        body: JsonObject,
        passkey: PasskeyProvider,
    ): JsonElement = confirmed(body, passkey) { json("POST", path, jsonBody(it)) }

    /** [postConfirmed] for a DELETE (deleting an organization). */
    @Deprecated("Raw request plumbing: use the facade's method for the route (client.orgs, client.apps, ...), which answers 412 itself")
    suspend fun deleteConfirmed(
        path: String,
        passkey: PasskeyProvider,
        body: JsonObject = JsonObject(emptyMap()),
    ): JsonElement = confirmed(body, passkey) { json("DELETE", path, jsonBody(it)) }

    private suspend fun confirmed(
        body: JsonObject,
        passkey: PasskeyProvider,
        send: suspend (JsonObject) -> JsonElement,
    ): JsonElement {
        val challenge =
            try {
                return send(body)
            } catch (e: ConfirmRequired) {
                e.details
            }
        return send(JsonObject(body + ("confirm" to assertionFor(passkey, challenge))))
    }

    /**
     * Create an app instance from a bundle hash, or from a package release (installed from the
     * registry first if the cluster lacks it), or from what a deployment offers
     * ([AppSource.Package.deployment]). Answers its app id. A failed init throws code
     * `INIT_FAILED`.
     */
    @Deprecated(
        "Use the generated facade: apps.create, with bundle, or package, version, service and deployment, from source. The spec has no confirmMethods",
        ReplaceWith("apps.create(name = name, org = org, access = access, initArgs = initArgs, metadata = metadata).appId"),
    )
    @Suppress("LongParameterList")
    suspend fun createApp(
        name: String,
        source: AppSource,
        org: String? = null,
        access: AppAccess? = null,
        confirmMethods: List<String>? = null,
        initArgs: JsonElement? = null,
        metadata: JsonElement? = null,
    ): String {
        val request =
            when (source) {
                is AppSource.Bundle -> AppCreateRequest(name = name, bundle = source.hash, org = org, access = access, initArgs = initArgs, metadata = metadata)
                is AppSource.Package ->
                    AppCreateRequest(
                        name = name,
                        `package` = source.`package`,
                        version = source.version,
                        service = source.service,
                        deployment = source.deployment,
                        org = org,
                        access = access,
                        initArgs = initArgs,
                        metadata = metadata,
                    )
            }
        // `confirmMethods` is not in core's OpenAPI document, so the generated request has no field for it.
        val extra = confirmMethods?.let { list -> buildJsonObject { put("confirmMethods", JsonArray(list.map(::JsonPrimitive))) } }
        return (raw(defaultApi.apps.appCreateRequestConfig(request), extra = extra) as JsonObject).string("appId").orEmpty()
    }

    /**
     * Call a method. A retry with the same [idempotencyKey] is answered once. Without one, the
     * client makes one, so its own retries of a 503 share it.
     */
    @Deprecated(
        "Use the generated facade: apps.intents",
        ReplaceWith("apps.intents(app = app, method = method, args = args, idempotencyKey = idempotencyKey)"),
    )
    suspend fun call(
        app: String,
        method: String,
        args: JsonElement = JsonObject(emptyMap()),
        idempotencyKey: String? = null,
    ): CallResult {
        val request = IntentsRequest(method = method, args = args, idempotencyKey = idempotencyKey ?: newIdempotencyKey())
        return wire.decodeFromJsonElement(CallResult.serializer(), raw(defaultApi.apps.intentsRequestConfig(app, request)))
    }

    /**
     * Call a method that needs the account holder's approval: the node returns a challenge over
     * this exact call, which [passkey] signs. A declined prompt throws [ConfirmCancelled].
     */
    @Deprecated(
        "Use the generated facade: apps.intents",
        ReplaceWith("apps.intents(app = app, method = method, args = args, idempotencyKey = idempotencyKey, passkey = passkey)"),
    )
    suspend fun callConfirmed(
        app: String,
        method: String,
        args: JsonElement,
        passkey: PasskeyProvider,
        idempotencyKey: String? = null,
    ): CallResult {
        // The confirmed attempt goes out exactly once ([dispatch]): its challenge is single-use, so a
        // resend would be refused on the challenge, not answered from the key.
        val request = IntentsRequest(method = method, args = args, idempotencyKey = idempotencyKey ?: newIdempotencyKey())
        return wire.decodeFromJsonElement(CallResult.serializer(), raw(defaultApi.apps.intentsRequestConfig(app, request), passkey))
    }

    /** Run a read-only method: strong by default, or [stale] from any replica. */
    @Deprecated(
        "Use the generated facade: apps.views",
        ReplaceWith("apps.views(app = app, method = method, args = args, consistency = if (stale) ViewsRequest.Consistency.STALE else ViewsRequest.Consistency.STRONG, minIndex = minIndex)"),
    )
    suspend fun view(
        app: String,
        method: String,
        args: JsonElement = JsonObject(emptyMap()),
        stale: Boolean = false,
        minIndex: Long = 0,
    ): ViewResult {
        val consistency = if (stale) ViewsRequest.Consistency.STALE else ViewsRequest.Consistency.STRONG
        val request = ViewsRequest(method = method, args = args, consistency = consistency, minIndex = minIndex)
        return wire.decodeFromJsonElement(ViewResult.serializer(), raw(defaultApi.apps.viewsRequestConfig(app, request)))
    }

    /** Committed events from index [from], then live, until the collector stops. */
    fun events(
        app: String,
        from: Long = 0,
    ): Flow<CloudEvent> = stream("apps/${seg(app)}/events?from=$from")

    /** A server-sent event stream on [path], sealed like every other answer. Cancelling the collector closes it. */
    private fun stream(path: String): Flow<CloudEvent> =
        channelFlow {
            val response = exchange("GET", path, stream = true)
            val source = response.body?.source() ?: throw CalimeroError(response.code, "EVENTS", "the event stream has no body")
            launch(Dispatchers.IO) {
                try {
                    sseFrames(source) { send(it) }
                    channel.close()
                } catch (e: IOException) {
                    if (isActive) channel.close(e)
                }
            }
            awaitClose { response.close() }
        }

    // ---------------------------------------------------------------- presence

    /**
     * Publish this device's presence in [app]: [state] (at most 4 KiB) or null to retract it. The
     * newest [seq] wins; send the same seq and state again as a heartbeat, since an entry not
     * refreshed within 7 s is gone.
     */
    @Deprecated(
        "Use the generated facade: apps.presencePublish",
        ReplaceWith("apps.presencePublish(app = app, seq = seq, state = Explicit(state?.let(Hex::encode)))"),
    )
    suspend fun publishPresence(
        app: String,
        seq: Long,
        state: ByteArray?,
    ) {
        // A null state retracts the entry and is sent as `null`, which the generated request leaves out.
        val extra = if (state == null) buildJsonObject { put("state", JsonNull) } else null
        raw(defaultApi.apps.presencePublishRequestConfig(app, PresencePublishRequest(seq = seq, state = state?.let(Hex::encode))), extra = extra)
    }

    /** Who is present in [app] now. */
    @Deprecated(
        "Use the generated facade: apps.presenceGet",
        ReplaceWith("apps.presenceGet(app = app).present"),
    )
    suspend fun presence(app: String): List<PresenceEntry> = listOf("present", PresenceEntry.serializer(), raw(defaultApi.apps.presenceGetRequestConfig(app)))

    /** Presence as a stream: a `snapshot` event (the entries present), then a `presence` event per change. */
    fun presenceStream(app: String): Flow<CloudEvent> = stream("apps/${seg(app)}/presence/stream")

    // ---------------------------------------------------------------- aliases

    /** Name an app for this account. */
    @Deprecated(
        "Use the generated facade: auth.aliasSet",
        ReplaceWith("auth.aliasSet(alias = alias, app = app)"),
    )
    suspend fun setAlias(
        alias: String,
        app: String,
    ) {
        raw(defaultApi.accounts.aliasSetRequestConfig(alias, AliasSetRequest(app = app)))
    }

    /** This account's aliases, alias -> app id. */
    @Deprecated(
        "Use the generated facade: auth.aliasList",
        ReplaceWith("auth.aliasList().aliases"),
    )
    suspend fun aliases(): Map<String, String> =
        ((raw(defaultApi.accounts.aliasListRequestConfig()) as JsonObject)["aliases"] as? JsonObject)
            ?.let { wire.decodeFromJsonElement(MapSerializer(String.serializer(), String.serializer()), it) }
            .orEmpty()

    /** The app an alias names. */
    @Deprecated(
        "Use the generated facade: auth.aliasGet",
        ReplaceWith("auth.aliasGet(alias = alias).appId"),
    )
    suspend fun alias(alias: String): String = (raw(defaultApi.accounts.aliasGetRequestConfig(alias)) as JsonObject).string("appId").orEmpty()

    @Deprecated(
        "Use the generated facade: auth.aliasDelete",
        ReplaceWith("auth.aliasDelete(alias = alias)"),
    )
    suspend fun deleteAlias(alias: String) {
        raw(defaultApi.accounts.aliasDeleteRequestConfig(alias))
    }

    // ---------------------------------------------------------------- organizations

    /**
     * Create an organization; answers its id. [recoveryKey] (from [recoveryPublicKey]) lets whoever
     * holds its seed recover the organization if its owner loses every passkey; keep the seed
     * offline ([recoverySeedText]).
     */
    @Deprecated(
        "Use the generated facade: orgs.create",
        ReplaceWith("orgs.create(name = name, recoveryKey = recoveryKey).orgId"),
    )
    suspend fun createOrg(
        name: String,
        recoveryKey: String? = null,
    ): String = (raw(defaultApi.organizations.orgCreateRequestConfig(OrgCreateRequest(name = name, recoveryKey = recoveryKey))) as JsonObject).string("orgId").orEmpty()

    /**
     * Set or rotate ([publicKey], from [recoveryPublicKey]) or remove (`null`) [org]'s recovery key:
     * its owner only, approved with [passkey]. A recovery under way is cancelled, and the old seed's
     * signatures are refused from then on.
     */
    @Deprecated(
        "Use the generated facade: orgs.recoverySet (Explicit(null) removes the key)",
        ReplaceWith("orgs.recoverySet(org = org, publicKey = Explicit(publicKey), passkey = passkey)"),
    )
    suspend fun setRecoveryKey(
        org: String,
        publicKey: String?,
        passkey: PasskeyProvider,
    ) {
        // Null removes the key and is sent as `null`, which the generated request leaves out.
        val extra = if (publicKey == null) buildJsonObject { put("publicKey", JsonNull) } else null
        raw(defaultApi.organizations.recoverySetRequestConfig(org, RecoverySetRequest(publicKey = publicKey)), passkey, extra)
    }

    /**
     * Start handing [org] to the signed-in account with the recovery [seed], which signs here and
     * never leaves this process. Finish with [finishRecovery] once [RecoveryTimes.readyAt] has
     * passed; until then the owner may [cancelRecovery]. A wrong seed (or no organization, or none
     * set) is `403 WRONG_RECOVERY_KEY`.
     */
    suspend fun startRecovery(
        org: String,
        seed: ByteArray,
    ): RecoveryTimes {
        val account = me().accountId
        val nonce = b64url(randomBytes(16))
        val signature = signRecovery(seed, org, account, nonce)
        val request = RecoveryStartRequest(nonce = nonce, signature = signature)
        return wire.decodeFromJsonElement(RecoveryTimes.serializer(), raw(defaultApi.organizations.recoveryStartRequestConfig(org, request)))
    }

    /** Cancel the recovery under way: [org]'s owner, approved with [passkey]. */
    @Deprecated(
        "Use the generated facade: orgs.recoveryCancel",
        ReplaceWith("orgs.recoveryCancel(org = org, passkey = passkey)"),
    )
    suspend fun cancelRecovery(
        org: String,
        passkey: PasskeyProvider,
    ) {
        raw(defaultApi.organizations.recoveryCancelRequestConfig(org, ConfirmOnly()), passkey)
    }

    /**
     * Finish a recovery this account started, once its delay passed ([RecoveryNotReady] before),
     * approved with this account's [passkey]. The account becomes the owner; the previous owners
     * leave the organization.
     */
    @Deprecated(
        "Use the generated facade: orgs.recoveryFinish",
        ReplaceWith("orgs.recoveryFinish(org = org, passkey = passkey)"),
    )
    suspend fun finishRecovery(
        org: String,
        passkey: PasskeyProvider,
    ): RecoveryFinished =
        wire.decodeFromJsonElement(RecoveryFinished.serializer(), raw(defaultApi.organizations.recoveryFinishRequestConfig(org, ConfirmOnly()), passkey))

    /** The organization, with the teams this caller reaches or manages. */
    @Deprecated(
        "Use the generated facade: orgs.get",
        ReplaceWith("orgs.get(org = org)"),
    )
    suspend fun getOrg(org: String): OrgInfo = orgInfo(raw(defaultApi.organizations.orgGetRequestConfig(org)) as JsonObject)

    /** Delete an organization with its teams, invites and apps (its owner only, approved with [passkey]). */
    @Deprecated(
        "Use the generated facade: orgs.delete",
        ReplaceWith("orgs.delete(org = org, passkey = passkey)"),
    )
    suspend fun deleteOrg(
        org: String,
        passkey: PasskeyProvider,
    ) {
        raw(defaultApi.organizations.orgDeleteRequestConfig(org, ConfirmOnly()), passkey)
    }

    /** Set the organization's metadata (an admin, or a holder of `MANAGE_METADATA`); at most 16 KiB. */
    @Deprecated(
        "Use the generated facade: orgs.metadata",
        ReplaceWith("orgs.metadata(org = org, metadata = metadata)"),
    )
    suspend fun setOrgMetadata(
        org: String,
        metadata: JsonElement,
    ) {
        raw(defaultApi.organizations.orgMetadataRequestConfig(org, MemberMetadataRequest(metadata = metadata)))
    }

    /** Set a member's metadata (theirs, or an admin's); [JsonNull] clears it. */
    @Deprecated(
        "Use the generated facade: orgs.memberMetadata",
        ReplaceWith("orgs.memberMetadata(org = org, account = account, metadata = metadata)"),
    )
    suspend fun setMemberMetadata(
        org: String,
        account: String,
        metadata: JsonElement,
    ) {
        raw(defaultApi.organizations.memberMetadataRequestConfig(org, account, MemberMetadataRequest(metadata = metadata)))
    }

    /** Upgrade every app of [org] that runs `target.package` to `target.version`, approved with [passkey]. */
    @Deprecated(
        "Use the generated facade: orgs.upgrade",
        ReplaceWith("orgs.upgrade(org = org, `package` = target.`package`, version = target.version, team = target.team, cascade = target.cascade, migrate = target.migrate, passkey = passkey)"),
    )
    suspend fun upgradeOrg(
        org: String,
        target: OrgUpgradeTarget,
        passkey: PasskeyProvider,
    ): UpgradeRun =
        wire.decodeFromJsonElement(
            UpgradeRun.serializer(),
            raw(defaultApi.organizations.orgUpgradeRequestConfig(org, model(wire.encodeToJsonElement(target))), passkey),
        )

    /** An organization-wide upgrade run: its apps, those done and those that failed. */
    @Deprecated(
        "Use the generated facade: orgs.upgradeStatus",
        ReplaceWith("orgs.upgradeStatus(org = org, run = run)"),
    )
    suspend fun upgradeRun(
        org: String,
        run: String,
    ): UpgradeRun = wire.decodeFromJsonElement(UpgradeRun.serializer(), raw(defaultApi.organizations.orgUpgradeStatusRequestConfig(org, run)))

    /** Run the apps that failed in [run] again, as a new run, approved with [passkey]. */
    @Deprecated(
        "Use the generated facade: orgs.upgradeRetry",
        ReplaceWith("orgs.upgradeRetry(org = org, run = run, passkey = passkey)"),
    )
    suspend fun retryUpgradeRun(
        org: String,
        run: String,
        passkey: PasskeyProvider,
    ): UpgradeRun =
        wire.decodeFromJsonElement(UpgradeRun.serializer(), raw(defaultApi.organizations.orgUpgradeRetryRequestConfig(org, run, OrgUpgradeRetryRequest()), passkey))

    /**
     * Approve a mero-tee release to hold the apps of [org], or of this account outside any
     * organization ([org] null); [approved] false withdraws it. Refused (`403`) to a session
     * opened on an app's frontend: ask with [requestConsent] instead.
     */
    @Deprecated(
        "Use the generated facade: orgs.releaseApproval. With no org: auth.accountReleaseApproval",
        ReplaceWith("orgs.releaseApproval(org = org, version = version, approved = approved, passkey = passkey)"),
    )
    suspend fun approveRelease(
        org: String?,
        version: String,
        passkey: PasskeyProvider,
        approved: Boolean = true,
    ): JsonObject =
        (
            if (org == null) {
                raw(defaultApi.accounts.accountReleaseApprovalRequestConfig(AccountReleaseApprovalRequest(version = version, approved = approved)), passkey)
            } else {
                raw(defaultApi.organizations.orgReleaseApprovalRequestConfig(org, OrgReleaseApprovalRequest(version = version, approved = approved)), passkey)
            }
        ) as JsonObject

    /**
     * Approve the URL prefixes apps from [pkg]'s publisher may call out to, for [org]'s apps or this
     * account's own ([org] null). [prefixes] null approves every prefix the package's latest
     * release declares; [replace] sets exactly [prefixes].
     */
    @Deprecated(
        "Use the generated facade: orgs.outboundApproval. With no org: auth.accountOutboundApproval",
        ReplaceWith("orgs.outboundApproval(org = org, `package` = pkg, prefixes = prefixes?.let { OutboundSelection.Prefixes(it) } ?: OutboundSelection.Manifest, replace = replace, passkey = passkey)"),
    )
    suspend fun approveOutbound(
        org: String?,
        pkg: String,
        passkey: PasskeyProvider,
        prefixes: List<String>? = null,
        replace: Boolean = false,
    ): JsonObject {
        val selection = prefixes?.let { OutboundSelection.Prefixes(it) } ?: OutboundSelection.Manifest
        val answer =
            if (org == null) {
                raw(defaultApi.accounts.accountOutboundApprovalRequestConfig(AccountOutboundApprovalRequest(`package` = pkg, prefixes = selection, replace = replace)), passkey)
            } else {
                raw(defaultApi.organizations.orgOutboundApprovalRequestConfig(org, OrgOutboundApprovalRequest(`package` = pkg, prefixes = selection, replace = replace)), passkey)
            }
        return answer as JsonObject
    }

    // ---------------------------------------------------------------- approvals

    /** One page of the report of a scope's apps: an organization's, or ([org] null) the account's own. */
    @Deprecated(
        "Use the generated facade: orgs.apps. With no org: auth.accountApps",
        ReplaceWith("orgs.apps(org = org, after = page.after, limit = page.limit?.toLong())"),
    )
    suspend fun appsReport(
        org: String?,
        page: PageOptions = PageOptions(),
    ): AppsReport {
        pageQuery(page) // refuses a limit outside 1..MAX_REPORT_PAGE
        val limit = page.limit?.toLong()
        val config =
            if (org == null) defaultApi.accounts.accountAppsRequestConfig(page.after, limit) else defaultApi.organizations.orgAppsRequestConfig(org, page.after, limit)
        return wire.decodeFromJsonElement(AppsReport.serializer(), raw(config))
    }

    /** [appsReport], every page, with the apps of all of them in `apps`. */
    suspend fun appsReportAll(org: String?): AppsReport {
        val first = appsReport(org)
        val apps = first.apps.toMutableList()
        var next = first.next
        while (next != null) {
            val page = appsReport(org, PageOptions(after = next))
            apps += page.apps
            next = page.next
        }
        return first.copy(apps = apps, next = null)
    }

    /** `GET /releases`: the cluster's governance and every release deadline. Public. */
    @Deprecated(
        "Use the generated facade: public.releasesPublic",
        ReplaceWith("public.releasesPublic()"),
    )
    suspend fun releaseRules(): ReleaseRules = wire.decodeFromJsonElement(ReleaseRules.serializer(), raw(defaultApi.publicApi.releasesPublicRequestConfig()))

    /** What waits for a decision in a scope ([org], or the account's own apps with null). */
    suspend fun pendingApprovals(org: String? = null): PendingApprovals = pendingApprovalsFrom(appsReportAll(org), releaseRules())

    /**
     * Ask the node to ask the user: a release, upgrade or outbound approval that only the account
     * holder may make, decided on the node's own consent page, never in the app. Send the person to
     * the answer's `url` (a Custom Tab: mero-compose's `ConsentLauncher`); the page sends them back
     * to `returnTo?request_id=…&result=approved|declined` ([consentReturn]), and [consentResult]
     * confirms the decision.
     */
    @Deprecated(
        "Use the generated facade: consent.pageCreate",
        ReplaceWith("consent.pageCreate(consentRequest = request)"),
    )
    suspend fun requestConsent(request: ConsentRequest): ConsentStarted =
        wire.decodeFromJsonElement(ConsentStarted.serializer(), raw(defaultApi.consent.consentPageCreateRequestConfig(request)))

    /** [requestConsent], then hand the consent page's URL to [navigate] (open it in a Custom Tab). */
    suspend fun openConsentPage(
        request: ConsentRequest,
        navigate: (String) -> Unit,
    ): ConsentStarted = openConsentPage(request, OpenConsentOptions(navigate))

    /**
     * [requestConsent], then send the person to the consent page ([OpenConsentOptions.navigate]).
     * [OpenConsentOptions.confirm] runs first with the node's answer, so the app can show the
     * person its `code`; when it answers `false` the page is not opened. When the node sends a
     * `code` that is not [confirmationCode] of `requestId`, it throws [CalimeroError]
     * (`CONSENT_CODE_MISMATCH`) and does not navigate ([checkConsentCode]).
     */
    suspend fun openConsentPage(
        request: ConsentRequest,
        options: OpenConsentOptions,
    ): ConsentStarted {
        val started = requestConsent(request)
        checkConsentCode(started)
        if (options.confirm?.invoke(started) == false) return started
        options.navigate(started.url)
        return started
    }

    /** The decision on a consent request (any node answers, for the account that asked). */
    @Deprecated(
        "Use the generated facade: consent.pageResult",
        ReplaceWith("consent.pageResult(request = requestId)"),
    )
    suspend fun consentResult(requestId: String): ConsentResult =
        wire.decodeFromJsonElement(ConsentResult.serializer(), raw(defaultApi.consent.consentPageResultRequestConfig(requestId)))

    /**
     * Poll [consentResult] until the request is decided, every [intervalMs], until [expiresAt]
     * (Unix seconds) passes, when it answers the last pending result. Cancel the coroutine to stop.
     */
    suspend fun waitForConsent(
        requestId: String,
        intervalMs: Long = 2_000,
        expiresAt: Long? = null,
    ): ConsentResult {
        while (true) {
            val result = consentResult(requestId)
            if (!result.isPending) return result
            if (expiresAt != null && System.currentTimeMillis() / 1000 >= expiresAt) return result
            delay(intervalMs)
        }
    }

    /** `GET /publishers/{signerId}`: the cluster name and binding generation a key signs to bind it next. */
    @Deprecated(
        "Use the generated facade: public.publisherBinding",
        ReplaceWith("public.publisherBinding(signer = signerId)"),
    )
    suspend fun publisherBinding(signerId: String): PublisherBindingInfo =
        wire.decodeFromJsonElement(PublisherBindingInfo.serializer(), raw(defaultApi.publicApi.publisherBindingRequestConfig(signerId)))

    /** Bind a package signing key to [org] as its publisher, approved with [passkey]. */
    @Deprecated(
        "Use the generated facade: orgs.publisher",
        ReplaceWith("orgs.publisher(org = org, signerId = proof.signerId, publicKey = proof.publicKey, signature = proof.signature, passkey = passkey)"),
    )
    suspend fun bindPublisher(
        org: String,
        proof: PublisherProof,
        passkey: PasskeyProvider,
    ): JsonObject = raw(defaultApi.organizations.orgPublisherRequestConfig(org, model(wire.encodeToJsonElement(proof))), passkey) as JsonObject

    /** Upgrade every instance of [pkg] whose release declares `upgrades: publisher`, as its publisher [org]. */
    @Deprecated(
        "Use the generated facade: orgs.publisherUpgrade",
        ReplaceWith("orgs.publisherUpgrade(org = org, `package` = pkg, version = version, migrate = migrate, passkey = passkey)"),
    )
    suspend fun upgradePublished(
        org: String,
        pkg: String,
        version: String,
        passkey: PasskeyProvider,
        migrate: String? = null,
    ): UpgradeRun =
        wire.decodeFromJsonElement(
            UpgradeRun.serializer(),
            raw(defaultApi.organizations.publisherUpgradeRequestConfig(org, pkg, PublisherUpgradeRequest(version = version, migrate = migrate)), passkey),
        )

    // ---------------------------------------------------------------- operator

    /** `GET /ops/apps`: what every app runs, without names or accounts. Operator token ([useApiKey]). */
    @Deprecated(
        "Use the generated facade: ops.apps",
        ReplaceWith("ops.apps(after = page.after, limit = page.limit?.toLong())"),
    )
    suspend fun opsApps(page: PageOptions = PageOptions()): OpsAppsReport {
        pageQuery(page)
        return wire.decodeFromJsonElement(OpsAppsReport.serializer(), raw(defaultApi.ops.opsAppsRequestConfig(page.after, page.limit?.toLong())))
    }

    /** `GET /ops/orgs`: each organization's approved releases. Operator token. */
    @Deprecated(
        "Use the generated facade: ops.orgs",
        ReplaceWith("ops.orgs(after = page.after, limit = page.limit?.toLong())"),
    )
    suspend fun opsOrgs(page: PageOptions = PageOptions()): OpsOrgsReport {
        pageQuery(page)
        return wire.decodeFromJsonElement(OpsOrgsReport.serializer(), raw(defaultApi.ops.opsOrgsRequestConfig(page.after, page.limit?.toLong())))
    }

    /** Announce when work on images older than [version] ends. Operator token. */
    @Deprecated(
        "Use the generated facade: ops.releaseDeadline",
        ReplaceWith("ops.releaseDeadline(version = version, after = after.toString())"),
    )
    suspend fun setReleaseDeadline(
        version: String,
        after: Instant,
    ) {
        raw(defaultApi.ops.opsReleaseDeadlineRequestConfig(version, OpsReleaseDeadlineRequest(after = after.toString())))
    }

    /** Lift a release's deadline. Operator token. */
    @Deprecated(
        "Use the generated facade: ops.releaseDeadlineLift",
        ReplaceWith("ops.releaseDeadlineLift(version = version)"),
    )
    suspend fun liftReleaseDeadline(version: String) {
        raw(defaultApi.ops.opsReleaseDeadlineLiftRequestConfig(version, null))
    }

    // ---------------------------------------------------------------- members and teams

    /** Add a member, or change their role, approved with the caller's [passkey]. */
    @Deprecated(
        "Use the generated facade: orgs.setMember",
        ReplaceWith("orgs.setMember(org = org, account = account, role = role.api(), passkey = passkey)"),
    )
    suspend fun setMember(
        org: String,
        account: String,
        role: OrgRole,
        passkey: PasskeyProvider,
    ) {
        raw(defaultApi.organizations.orgSetMemberRequestConfig(org, OrgSetMemberRequest(account = account, role = role.api())), passkey)
    }

    /** Remove a member, or leave ([account] the caller's own), approved with the caller's [passkey]. */
    @Deprecated(
        "Use the generated facade: orgs.removeMember",
        ReplaceWith("orgs.removeMember(org = org, account = account, passkey = passkey)"),
    )
    suspend fun removeMember(
        org: String,
        account: String,
        passkey: PasskeyProvider,
    ): JsonElement = raw(defaultApi.organizations.orgRemoveMemberRequestConfig(org, account, ConfirmOnly()), passkey)

    /**
     * Set capability bits ([OrgCapabilities]): for a member ([account]), for a member of one team
     * ([account] and [team]), or the organization's default (neither). Approved with the caller's
     * [passkey].
     */
    @Deprecated(
        "Use the generated facade: orgs.capabilities",
        ReplaceWith("orgs.capabilities(org = org, capabilities = capabilities.toLong(), account = account, team = team, passkey = passkey)"),
    )
    suspend fun setCapabilities(
        org: String,
        capabilities: Int,
        passkey: PasskeyProvider,
        account: String? = null,
        team: String? = null,
    ) {
        val request = OrgCapabilitiesRequest(capabilities = capabilities.toLong(), account = account, team = team)
        raw(defaultApi.organizations.orgCapabilitiesRequestConfig(org, request), passkey)
    }

    /** Create a team; answers its id. */
    @Deprecated(
        "Use the generated facade: orgs.teamCreate",
        ReplaceWith("orgs.teamCreate(org = org, name = name, join = join.api(), parent = parent).teamId"),
    )
    suspend fun createTeam(
        org: String,
        name: String,
        join: TeamJoin,
        parent: String? = null,
    ): String {
        val request = TeamCreateRequest(name = name, join = join.api(), parent = parent)
        return (raw(defaultApi.organizations.teamCreateRequestConfig(org, request)) as JsonObject).string("teamId").orEmpty()
    }

    @Deprecated(
        "Use the generated facade: orgs.teamGet",
        ReplaceWith("orgs.teamGet(org = org, team = team)"),
    )
    suspend fun getTeam(
        org: String,
        team: String,
    ): TeamInfo = wire.decodeFromJsonElement(TeamInfo.serializer(), raw(defaultApi.organizations.teamGetRequestConfig(org, team)))

    /**
     * Rename, change who may join, set the team's metadata, or move it: [parent] moves it under
     * another team, [toTop] to the top; neither leaves it.
     */
    @Deprecated(
        "Use the generated facade: orgs.teamUpdate (parent = Explicit(null) moves it to the top)",
        ReplaceWith("orgs.teamUpdate(org = org, team = team, name = name, join = join?.api(), parent = if (toTop) Explicit(null) else parent?.let(::Explicit), metadata = metadata)"),
    )
    @Suppress("LongParameterList")
    suspend fun updateTeam(
        org: String,
        team: String,
        name: String? = null,
        join: TeamJoin? = null,
        parent: String? = null,
        toTop: Boolean = false,
        metadata: JsonElement? = null,
    ) {
        val request = TeamUpdateRequest(name = name, join = join?.api(), parent = if (toTop) null else parent, metadata = metadata)
        // To the top is `"parent": null`, which the generated request leaves out.
        val extra = if (toTop) buildJsonObject { put("parent", JsonNull) } else null
        raw(defaultApi.organizations.teamUpdateRequestConfig(org, team, request), extra = extra)
    }

    /** Delete a team, the teams below it and their apps. */
    @Deprecated(
        "Use the generated facade: orgs.teamDelete",
        ReplaceWith("orgs.teamDelete(org = org, team = team)"),
    )
    suspend fun deleteTeam(
        org: String,
        team: String,
    ) {
        raw(defaultApi.organizations.teamDeleteRequestConfig(org, team, null))
    }

    /** Add a member to a team, or change their row: role, lead, capability bits. Approved with the caller's [passkey]. */
    @Deprecated(
        "Use the generated facade: orgs.teamSetMember",
        ReplaceWith("orgs.teamSetMember(org = org, team = team, account = account, lead = lead, role = role?.api(), capabilities = capabilities?.toLong(), passkey = passkey)"),
    )
    @Suppress("LongParameterList")
    suspend fun setTeamMember(
        org: String,
        team: String,
        account: String,
        passkey: PasskeyProvider,
        role: TeamRole? = null,
        lead: Boolean? = null,
        capabilities: Int? = null,
    ) {
        val request = TeamSetMemberRequest(account = account, lead = lead, role = role?.api(), capabilities = capabilities?.toLong())
        raw(defaultApi.organizations.teamSetMemberRequestConfig(org, team, request), passkey)
    }

    /** Remove a member from a team, approved with the caller's [passkey]. */
    @Deprecated(
        "Use the generated facade: orgs.teamRemoveMember",
        ReplaceWith("orgs.teamRemoveMember(org = org, team = team, account = account, passkey = passkey)"),
    )
    suspend fun removeTeamMember(
        org: String,
        team: String,
        account: String,
        passkey: PasskeyProvider,
    ) {
        raw(defaultApi.organizations.teamRemoveMemberRequestConfig(org, team, account, ConfirmOnly()), passkey)
    }

    /**
     * Make an invite token (`inv_…`) into [org] with [role], into [teams], approved with the
     * caller's [passkey]; one use and 7 days unless said otherwise. [redeemInvite] takes it (no
     * approval there).
     */
    @Deprecated(
        "Use the generated facade: orgs.inviteCreate",
        ReplaceWith("orgs.inviteCreate(org = org, role = role.api(), teams = teams, ttlSecs = ttlSecs, uses = uses?.toLong(), passkey = passkey).token"),
    )
    @Suppress("LongParameterList")
    suspend fun invite(
        org: String,
        role: OrgRole,
        passkey: PasskeyProvider,
        teams: List<String> = emptyList(),
        ttlSecs: Long? = null,
        uses: Int? = null,
    ): String {
        require(role != OrgRole.Owner) { "an invite cannot make an owner" }
        val request = InviteCreateRequest(role = role.api(), teams = teams, ttlSecs = ttlSecs, uses = uses?.toLong())
        return (raw(defaultApi.organizations.inviteCreateRequestConfig(org, request), passkey) as JsonObject).string("token").orEmpty()
    }

    /** Redeem an invite token (no approval): the account joins its organization. Answers `{ org }`. */
    @Deprecated(
        "Use the generated facade: orgs.inviteRedeem",
        ReplaceWith("orgs.inviteRedeem(token = token)"),
    )
    suspend fun redeemInvite(token: String): JsonObject = raw(defaultApi.organizations.inviteRedeemRequestConfig(token)) as JsonObject

    // ---------------------------------------------------------------- package origins

    /**
     * The web origins [pkg]'s managers registered so a frontend they host themselves can sign people
     * in with passkeys ([SignInOptions.origin]). [NotFound] for an unknown package; `403` to anyone
     * not managing it.
     */
    @Deprecated(
        "Use the generated facade: packages.originsList",
        ReplaceWith("packages.originsList(`package` = pkg).origins"),
    )
    suspend fun packageOrigins(pkg: String): List<PackageOrigin> = listOf("origins", PackageOrigin.serializer(), raw(defaultApi.packages.originsListRequestConfig(pkg)))

    /**
     * Register [origin] (`scheme://host[:port]`) for [pkg], approved with [passkey]. [rpId] defaults
     * to the node's choice for the origin (its host). Refusals: [BadOrigin] (`BAD_ORIGIN`,
     * `BAD_RP_ID`), [OriginTaken], [QuotaExceeded].
     */
    @Deprecated(
        "Use the generated facade: packages.originsAdd",
        ReplaceWith("packages.originsAdd(`package` = pkg, origin = origin, rpId = rpId, passkey = passkey)"),
    )
    suspend fun addPackageOrigin(
        pkg: String,
        origin: String,
        passkey: PasskeyProvider,
        rpId: String? = null,
    ): PackageOriginAdded =
        wire.decodeFromJsonElement(
            PackageOriginAdded.serializer(),
            raw(defaultApi.packages.originsAddRequestConfig(pkg, OriginsAddRequest(origin = origin, rpId = rpId)), passkey),
        )

    /**
     * Remove [origin] from [pkg], approved with [passkey]. Sessions opened there stop working
     * (`401`). [NotFound] if it was not registered.
     */
    @Deprecated(
        "Use the generated facade: packages.originsRemove",
        ReplaceWith("packages.originsRemove(`package` = pkg, origin = origin, passkey = passkey)"),
    )
    suspend fun removePackageOrigin(
        pkg: String,
        origin: String,
        passkey: PasskeyProvider,
    ): PackageOriginRemoved =
        wire.decodeFromJsonElement(
            PackageOriginRemoved.serializer(),
            raw(defaultApi.packages.originsRemoveRequestConfig(pkg, OriginsRemoveRequest(origin = origin)), passkey),
        )

    // ---------------------------------------------------------------- deployers

    /**
     * Let an account or organization install apps where the cluster's deployments are `granted`,
     * until [until] (null: the grant does not end). The cluster's owner grants it, approved with
     * [passkey], where governance says `deployers: owner`; elsewhere the operator grants with its
     * key, and this is refused `403`.
     */
    @Deprecated(
        "Use the generated facade: deployments.deployerGrant",
        ReplaceWith("deployments.deployerGrant(deployer = deployer, until = until?.toString(), passkey = passkey)"),
    )
    suspend fun grantDeployer(
        deployer: Deployer,
        passkey: PasskeyProvider,
        until: Instant? = null,
    ): DeployerGranted {
        val request = DeployerGrantRequest(deployer = deployer, until = until?.toString())
        return wire.decodeFromJsonElement(DeployerGranted.serializer(), raw(defaultApi.signUpAndDeployments.deployerGrantRequestConfig(request), passkey))
    }

    /**
     * Who may install apps on the cluster: its deployments rule, who grants deployers, its owner
     * and every grant. For the cluster's owner, or the operator (its token, [useApiKey]).
     */
    @Deprecated(
        "Use the generated facade: deployments.deployerList",
        ReplaceWith("deployments.deployerList()"),
    )
    suspend fun deployers(): DeployersInfo = wire.decodeFromJsonElement(DeployersInfo.serializer(), raw(defaultApi.signUpAndDeployments.deployerListRequestConfig()))

    /**
     * End a deployer's grant, as the cluster's owner, approved with [passkey]. Apps it installed,
     * and the apps running them, stay.
     */
    @Deprecated(
        "Use the generated facade: deployments.deployerRevoke",
        ReplaceWith("deployments.deployerRevoke(key = deployer.key, passkey = passkey)"),
    )
    suspend fun revokeDeployer(
        deployer: Deployer,
        passkey: PasskeyProvider,
    ) {
        raw(defaultApi.signUpAndDeployments.deployerRevokeRequestConfig(deployer.key, ConfirmOnly()), passkey)
    }

    /** [revokeDeployer] by the grant's key (`account:{id}` or `org:{id}`). */
    @Deprecated(
        "Use the generated facade: deployments.deployerRevoke",
        ReplaceWith("deployments.deployerRevoke(key = key, passkey = passkey)"),
    )
    suspend fun revokeDeployer(
        key: String,
        passkey: PasskeyProvider,
    ) {
        raw(defaultApi.signUpAndDeployments.deployerRevokeRequestConfig(key, ConfirmOnly()), passkey)
    }

    // ---------------------------------------------------------------- deployments

    /**
     * Create a deployment: [owner]'s (the caller's own account, or an organization where it holds
     * `MANAGE_APPLICATION` org-wide) list of the apps it offers, named [name] (1 to 64 characters,
     * one line) for customers. Approved with [passkey]; where deployments are `granted` the caller
     * needs a deployer grant. Answers the deployment's id.
     */
    @Deprecated(
        "Use the generated facade: deployments.create",
        ReplaceWith("deployments.create(name = name, owner = owner, passkey = passkey)[\"id\"]"),
    )
    suspend fun createDeployment(
        name: String,
        owner: Deployer,
        passkey: PasskeyProvider,
    ): String {
        val request = DeploymentCreateRequest(name = name, owner = owner)
        return (raw(defaultApi.signUpAndDeployments.deploymentCreateRequestConfig(request), passkey) as JsonObject).string("id").orEmpty()
    }

    /** The deployments the caller manages: its account's, and its organizations' where it holds `MANAGE_APPLICATION`. */
    @Deprecated(
        "Use the generated facade: deployments.list",
        ReplaceWith("deployments.list().deployments"),
    )
    suspend fun deployments(): List<DeploymentInfo> = listOf("deployments", DeploymentInfo.serializer(), raw(defaultApi.signUpAndDeployments.deploymentListRequestConfig()))

    /** A deployment as anyone may read it: its name, its owner's kind and name, and the apps it offers. Public. */
    @Deprecated(
        "Use the generated facade: deployments.get",
        ReplaceWith("deployments.get(id = id)"),
    )
    suspend fun deployment(id: String): PublicDeployment =
        wire.decodeFromJsonElement(PublicDeployment.serializer(), raw(defaultApi.signUpAndDeployments.deploymentGetRequestConfig(id)))

    /** Delete a deployment that offers nothing and has no instances, approved with [passkey]. */
    @Deprecated(
        "Use the generated facade: deployments.delete",
        ReplaceWith("deployments.delete(id = id, passkey = passkey)"),
    )
    suspend fun deleteDeployment(
        id: String,
        passkey: PasskeyProvider,
    ) {
        raw(defaultApi.signUpAndDeployments.deploymentDeleteRequestConfig(id, ConfirmOnly()), passkey)
    }

    /**
     * Offer `pkg@version` (installed, and seen by the caller) as [app] (1 to 64 letters, digits,
     * `.`, `_`, `-`) in deployment [id], or move an offered app to another release of its package.
     * Approved with [passkey].
     */
    @Deprecated(
        "Use the generated facade: deployments.offer",
        ReplaceWith("deployments.offer(id = id, app = app, `package` = pkg, version = version, passkey = passkey)"),
    )
    @Suppress("LongParameterList")
    suspend fun offerApp(
        id: String,
        app: String,
        pkg: String,
        version: String,
        passkey: PasskeyProvider,
    ): DeploymentOffered {
        val request = DeploymentOfferRequest(app = app, `package` = pkg, version = version)
        return wire.decodeFromJsonElement(DeploymentOffered.serializer(), raw(defaultApi.signUpAndDeployments.deploymentOfferRequestConfig(id, request), passkey))
    }

    /** Withdraw [app] from deployment [id], approved with [passkey]; refused while an instance created from the deployment still runs it. */
    @Deprecated(
        "Use the generated facade: deployments.withdraw",
        ReplaceWith("deployments.withdraw(id = id, app = app, passkey = passkey)"),
    )
    suspend fun withdrawApp(
        id: String,
        app: String,
        passkey: PasskeyProvider,
    ) {
        raw(defaultApi.signUpAndDeployments.deploymentWithdrawRequestConfig(id, app, ConfirmOnly()), passkey)
    }

    /**
     * The organizations with instances created from deployment [id], for its managers: each one's
     * id, name, instance count, apps and releases, and first instance. Never members, teams or app
     * data.
     */
    @Deprecated(
        "Use the generated facade: deployments.orgs",
        ReplaceWith("deployments.orgs(id = id)"),
    )
    suspend fun deploymentOrgs(id: String): DeploymentOrgs =
        wire.decodeFromJsonElement(DeploymentOrgs.serializer(), raw(defaultApi.signUpAndDeployments.deploymentOrgsRequestConfig(id)))

    // ---------------------------------------------------------------- helpers

    private fun <T> listOf(
        key: String,
        item: kotlinx.serialization.KSerializer<T>,
        answer: JsonElement,
    ): List<T> = ((answer as? JsonObject)?.get(key) as? JsonArray)?.let { wire.decodeFromJsonElement(ListSerializer(item), it) }.orEmpty()

    private fun orgInfo(o: JsonObject): OrgInfo {
        fun map(key: String) = (o[key] as? JsonObject).orEmpty()
        return OrgInfo(
            raw = o,
            orgId = o.string("orgId").orEmpty(),
            name = o.string("name").orEmpty(),
            members = map("members").mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() },
            teams = map("teams").mapValues { wire.decodeFromJsonElement(TeamInfo.serializer(), it.value) },
            metadata = o["metadata"] ?: JsonNull,
            memberMetadata = map("memberMetadata"),
            releaseApprovers = (o["releaseApprovers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            publishes = (o["publishes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            newWorkStopped = (o["newWorkStopped"] as? JsonObject)?.let { wire.decodeFromJsonElement(NewWorkStopped.serializer(), it) },
            recovery = (o["recovery"] as? JsonObject)?.let { wire.decodeFromJsonElement(OrgRecovery.serializer(), it) },
        )
    }

    companion object {
        /**
         * A client for the Calimero Cloud cluster named [CalimeroConnectOptions.cluster]: its node
         * list read from the public node directory, those in `zone` first, each group in
         * weighted-random order. The directory only picks which nodes to try: each is attested under
         * `policy` before anything is sent to it. Throws [DirectoryError] when the directory names no
         * usable node.
         *
         * The list is read again in the background after a failover, and at the first request once
         * the directory's `refreshAfterSeconds` has passed.
         */
        suspend fun connect(options: CalimeroConnectOptions): CalimeroClient {
            val listing = readDirectory(options.directory, options.cluster, options.httpClient)
            val order = orderNodes(listing.nodes, options.zone, options.random)
            val client =
                CalimeroClient(
                    CalimeroClientOptions(
                        baseUrl = order.first(),
                        nodes = order.drop(1),
                        policy = options.policy,
                        httpClient = options.httpClient,
                        retry = options.retry,
                        onSession = options.onSession,
                    ),
                )
            client.source = DirectorySource(options, options.now() + (listing.refreshAfterSeconds * 1000).toLong())
            return client
        }

        /**
         * Throw [CalimeroError] `CONSENT_CODE_MISMATCH` when the node answered a consent request with
         * a `code` that is not the one its page shows for that request ([confirmationCode]). A node
         * that predates the code (`""`) passes.
         */
        fun checkConsentCode(started: ConsentStarted) {
            if (started.code.isEmpty()) return
            val expected = confirmationCode(started.requestId)
            if (started.code != expected) {
                throw CalimeroError(
                    200,
                    "CONSENT_CODE_MISMATCH",
                    "the consent page code does not match the request",
                    buildJsonObject {
                        put("requestId", started.requestId)
                        put("code", started.code)
                        put("expected", expected)
                    },
                )
            }
        }

        internal const val OCTET_STREAM = "application/octet-stream"

        private const val JSON = "application/json"

        /** The bytes a generated binary upload's config is made with when [binary] sends its own. */
        private val NO_BYTES = ByteArray(0)

        /**
         * [url]'s path and query relative to the node, each path segment and query part encoded as
         * [seg] encodes it, whichever encoding the URL arrived in. An empty segment (an id that was
         * empty, `.` or `..`) is refused before anything is sent, so an id reaches no other route.
         */
        internal fun relativePath(url: HttpUrl): String {
            val segments = url.pathSegments
            require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "an empty, '.' or '..' id in ${url.encodedPath}" }
            val path = segments.joinToString("/") { seg(it) }
            val query =
                (0 until url.querySize).joinToString("&") { i ->
                    val value = url.queryParameterValue(i)
                    if (value == null) enc(url.queryParameterName(i)) else "${enc(url.queryParameterName(i))}=${enc(value)}"
                }
            return if (query.isEmpty()) path else "$path?$query"
        }

        /** A generated [RequestConfig]'s route, relative to the node ([relativePath]). */
        internal fun configPath(config: RequestConfig<*>): String {
            val url =
                (MeroApi.API_BASE + config.path)
                    .toHttpUrl()
                    .newBuilder()
                    .apply { config.query.forEach { (name, values) -> values.forEach { addQueryParameter(name, it) } } }
                    .build()
            return relativePath(url)
        }

        internal val wire =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                explicitNulls = false
                encodeDefaults = false
            }

        /** One URL path segment, so an id cannot reach another route. */
        internal fun seg(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        internal fun enc(value: String): String = seg(value)

        internal fun parseOrNull(text: String): JsonElement? = runCatching { wire.parseToJsonElement(text) }.getOrNull()

        private val JSON_LITERAL = Regex("^(true|false|null|-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?)$")

        /** [text] as JSON only when it is JSON (the parser takes a bare word as a literal). */
        internal fun strictJson(text: String): JsonElement? =
            parseOrNull(text)?.takeUnless { it is JsonPrimitive && !it.isString && !JSON_LITERAL.matches(it.content) }

        /** The ceremonies that mint a session, which [exchange] never repeats by itself. */
        internal fun ceremony(path: String): Boolean = path == "auth/refresh" || path.startsWith("auth/passkey/")

        /**
         * A key for a call the caller gave none, made once so every retry shares it: `node:c-` and
         * 16 random bytes in base64url, as the Rust client mints it (kept 10 minutes by the node).
         */
        internal fun newIdempotencyKey(): String = "node:c-${b64url(randomBytes(16))}"

        internal fun callBody(
            method: String,
            args: JsonElement,
            key: String,
        ): JsonObject =
            buildJsonObject {
                put("method", method)
                put("args", args)
                put("idempotencyKey", key)
            }

        internal fun accessJson(access: AppAccess): JsonObject =
            buildJsonObject {
                when (access) {
                    AppAccess.Owner -> put("kind", "owner")
                    AppAccess.Org -> put("kind", "org")
                    AppAccess.Public -> put("kind", "public")
                    is AppAccess.Accounts -> {
                        put("kind", "accounts")
                        put("accounts", JsonArray(access.accounts.map(::JsonPrimitive)))
                    }
                    is AppAccess.Team -> {
                        put("kind", "team")
                        put("team", access.team)
                    }
                }
            }

        /** Read a refused response's body and map it ([errorFrom]). */
        internal fun refusal(response: Response): CalimeroError =
            response.use {
                val text = runCatching { it.body?.string().orEmpty() }.getOrDefault("")
                val body = if (text.isBlank()) JsonObject(emptyMap()) else parseOrNull(text) ?: buildJsonObject { put("message", text) }
                errorFrom(it.code, body, it.header("retry-after"))
            }

        /** Server-sent event frames: `{ id, kind, data }` each, `data` parsed as JSON when it is. */
        internal suspend fun sseFrames(
            source: BufferedSource,
            emit: suspend (CloudEvent) -> Unit,
        ) {
            var id = ""
            var kind = ""
            val data = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: return
                if (line.isEmpty()) {
                    if (kind.isNotEmpty() || data.isNotEmpty()) {
                        val text = data.toString()
                        emit(CloudEvent(id, kind, strictJson(text) ?: JsonPrimitive(text)))
                    }
                    id = ""
                    kind = ""
                    data.setLength(0)
                    continue
                }
                when {
                    line.startsWith("id:") -> id = line.substring(3).trim()
                    line.startsWith("event:") -> kind = line.substring(6).trim()
                    line.startsWith("data:") -> data.append(line.substring(5).trim())
                }
            }
        }
    }
}
