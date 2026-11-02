package com.calimero.mero

import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.randomBytes
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
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

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

    @Volatile private var auth: String? = null

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
        auth = key
        current = null
    }

    /** Use a session kept from before (an `onSession` callback's). */
    fun useSession(session: CalimeroSession) {
        auth = session.sessionToken
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
        auth = null
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
            auth?.let { builder.header("authorization", "Bearer $it") }
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
            val token = auth
            val from = nodeUrl
            val response = request(method, path, body, stream)
            if (response.isSuccessful) return response
            val error = refusal(response)
            if (error is WrongNode && !moved) {
                moved = true
                if (moveOn(from) && !ceremony(path)) continue
            } else if (error is LoginRequired && !refreshed && token != null && current != null && !ceremony(path)) {
                refreshed = true
                if (auth == token) refresh()
                continue
            }
            throw error
        }
    }

    private suspend fun json(
        method: String,
        path: String,
        body: Body? = null,
    ): JsonElement {
        val response = exchange(method, path, body)
        val text = withContext(Dispatchers.IO) { response.use { it.body?.string().orEmpty() } }
        return if (text.isBlank()) JsonObject(emptyMap()) else wire.parseToJsonElement(text)
    }

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
    private suspend fun retried(
        path: String,
        body: JsonObject,
        once: Boolean = false,
    ): JsonElement {
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
                        return json("POST", path, jsonBody(body))
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

    /** POST [body] to [path] (relative to the node, no leading slash) and answer the JSON. */
    suspend fun post(
        path: String,
        body: JsonElement = JsonObject(emptyMap()),
    ): JsonElement = json("POST", path, jsonBody(body))

    suspend fun get(path: String): JsonElement = json("GET", path)

    suspend fun put(
        path: String,
        body: JsonElement = JsonObject(emptyMap()),
    ): JsonElement = json("PUT", path, jsonBody(body))

    suspend fun delete(
        path: String,
        body: JsonElement? = null,
    ): JsonElement = json("DELETE", path, body?.let(::jsonBody))

    // ---------------------------------------------------------------- identity

    /** Create an account with a new passkey, and sign in. */
    suspend fun signUp(
        passkey: PasskeyProvider,
        name: String,
    ): CalimeroSession {
        val begin = post("auth/passkey/register/begin", buildJsonObject { put("name", name) }) as JsonObject
        val registration = passkey.create(begin["options"] as? JsonObject ?: JsonObject(emptyMap()))
        val session =
            typed(
                CalimeroSession.serializer(),
                "POST",
                "auth/passkey/register/finish",
                jsonBody(
                    buildJsonObject {
                        put("challengeId", begin.string("challengeId").orEmpty())
                        put("registration", wire.encodeToJsonElement(registration))
                    },
                ),
            )
        takeSession(session)
        return session
    }

    /** Sign in with an existing passkey. */
    suspend fun signIn(passkey: PasskeyProvider): CalimeroSession {
        val begin = post("auth/passkey/login/begin") as JsonObject
        val opts = begin["options"] as? JsonObject ?: JsonObject(emptyMap())
        val assertion = passkey.get(fromB64url(opts.string("challenge").orEmpty()), opts.string("rpId").orEmpty())
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
        post("auth/logout")
        dropSession()
    }

    /** End every session of the signed-in account, on every device, this one too. */
    suspend fun signOutEverywhere() {
        post("auth/logout-all")
        dropSession()
    }

    /** Forget the session here without telling the node (it lapses by itself). */
    fun forget() = dropSession()

    /** The signed-in account: its passkeys, its organizations. */
    suspend fun me(): AccountInfo {
        val o = get("auth/me") as JsonObject
        return AccountInfo(
            raw = o,
            accountId = o.string("accountId").orEmpty(),
            name = o.string("name").orEmpty(),
            passkeys = (o["passkeys"] as? JsonArray)?.let { wire.decodeFromJsonElement(ListSerializer(PasskeyInfo.serializer()), it) }.orEmpty(),
            orgs = (o["orgs"] as? JsonArray)?.let { wire.decodeFromJsonElement(ListSerializer(OrgMembership.serializer()), it) }.orEmpty(),
            via = o.string("via").orEmpty(),
            passkeyId = o.string("passkeyId"),
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
    suspend fun removePasskey(
        credentialId: String,
        passkey: PasskeyProvider,
    ) {
        deleteConfirmed("auth/passkeys/${seg(credentialId)}", passkey)
    }

    /** Name one of the account's passkeys (a device label); `""` clears the name. */
    suspend fun labelPasskey(
        credentialId: String,
        label: String,
    ) {
        post("auth/passkeys/${seg(credentialId)}", buildJsonObject { put("label", label) })
    }

    /** What this account uses (apps, bundle bytes, API keys, owned orgs), with the cluster's quotas. */
    suspend fun usage(): JsonObject = get("auth/usage") as JsonObject

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
    suspend fun listApiKeys(): List<ApiKeyInfo> = listOf("keys", ApiKeyInfo.serializer(), get("auth/apikeys"))

    suspend fun revokeApiKey(keyId: String) {
        delete("auth/apikeys/${seg(keyId)}")
    }

    /**
     * Create an API key for an agent or server, approved with the account's [passkey]
     * (`412 CONFIRM_REQUIRED`); needs a passkey session (an API key is refused `403`). The key is
     * returned once.
     */
    suspend fun createApiKey(
        options: ApiKeyOptions,
        passkey: PasskeyProvider,
    ): ApiKeyCreated = wire.decodeFromJsonElement(ApiKeyCreated.serializer(), postConfirmed("auth/apikeys", wire.encodeToJsonElement(options) as JsonObject, passkey))

    // ---------------------------------------------------------------- apps

    /** Upload a raw WASM bundle (development clusters only); answers its hash. */
    suspend fun uploadBundle(wasm: ByteArray): String = (json("POST", "bundles", Body(wasm, "application/wasm")) as JsonObject).string("hash").orEmpty()

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
    ): BlobInfo = wire.decodeFromJsonElement(BlobInfo.serializer(), json("POST", "apps/${seg(app)}/blobs", Body(data, contentType)))

    /** One of [app]'s blobs, by id (base58 or hex). */
    suspend fun downloadBlob(
        app: String,
        id: String,
    ): ByteArray = blobContent(app, id).data

    /**
     * Download a component app's blob with a grant one of its runs handed out (`BlobRef::grant`,
     * up to 24 h). No session needed: the grant is the permission. A bad or expired grant answers
     * [NotFound], as a missing blob.
     */
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
        val query = grant?.let { "?grant=${enc(it)}" }.orEmpty()
        val response = exchange("GET", "apps/${seg(app)}/blobs/${seg(id)}$query")
        return withContext(Dispatchers.IO) {
            response.use { BlobContent(it.body?.bytes() ?: ByteArray(0), it.header("content-type") ?: OCTET_STREAM) }
        }
    }

    /** [app]'s blobs. */
    suspend fun listBlobs(app: String): List<BlobInfo> = listOf("blobs", BlobInfo.serializer(), get("apps/${seg(app)}/blobs"))

    /** One of [app]'s blobs' id and size; [NotFound] if the app has no such blob. */
    suspend fun blobInfo(
        app: String,
        id: String,
    ): BlobInfo = typed(BlobInfo.serializer(), "GET", "apps/${seg(app)}/blobs/${seg(id)}/info")

    /** Delete one of [app]'s blobs (its owner, or an admin of its organization). */
    suspend fun deleteBlob(
        app: String,
        id: String,
    ) {
        delete("apps/${seg(app)}/blobs/${seg(id)}")
    }

    // ---------------------------------------------------------------- packages

    /** Install a signed `.mpk` package file (development clusters only, else `403 UPLOADS_DISABLED`). */
    suspend fun uploadPackage(mpk: ByteArray): PackageInstalled =
        wire.decodeFromJsonElement(PackageInstalled.serializer(), json("POST", "packages", Body(mpk, "application/octet-stream")))

    /** Install `pkg@version` from the cluster's registry; every node that runs it verifies it. */
    suspend fun installPackage(
        pkg: String,
        version: String,
    ): PackageInstalled =
        typed(
            PackageInstalled.serializer(),
            "POST",
            "packages/install",
            jsonBody(
                buildJsonObject {
                    put("package", pkg)
                    put("version", version)
                },
            ),
        )

    /** Installed packages and their versions. */
    suspend fun packages(): List<PackageInfo> = listOf("packages", PackageInfo.serializer(), get("packages"))

    /** One installed package and its versions. */
    suspend fun `package`(pkg: String): PackageInfo = typed(PackageInfo.serializer(), "GET", "packages/${seg(pkg)}")

    /** Uninstall one version, or the whole package. Refused while an app runs it. */
    suspend fun uninstallPackage(
        pkg: String,
        version: String? = null,
    ): JsonElement = delete(if (version == null) "packages/${seg(pkg)}" else "packages/${seg(pkg)}/${seg(version)}")

    // ---------------------------------------------------------------- app instances

    /** Apps this caller reaches, optionally only those of one package or bundle. */
    suspend fun listApps(
        pkg: String? = null,
        bundle: String? = null,
    ): List<AppInfo> {
        val query = listOfNotNull(pkg?.let { "package=${enc(it)}" }, bundle?.let { "bundle=${enc(it)}" }).joinToString("&")
        return listOf("apps", AppInfo.serializer(), get(if (query.isEmpty()) "apps" else "apps?$query"))
    }

    /** One app instance: its record (`name`, `owner`, `org`, `access`, `metadata`, ...). */
    suspend fun getApp(app: String): JsonObject = get("apps/${seg(app)}") as JsonObject

    /** Delete an app instance (its owner, or an admin of its organization). */
    suspend fun deleteApp(app: String) {
        delete("apps/${seg(app)}")
    }

    /** Who may reach the app. */
    suspend fun setAccess(
        app: String,
        access: AppAccess,
    ) {
        post("apps/${seg(app)}/access", buildJsonObject { put("access", accessJson(access)) })
    }

    /** Set the app instance's metadata (its owner, or an admin of its organization); at most 16 KiB. */
    suspend fun setAppMetadata(
        app: String,
        metadata: JsonElement,
    ) {
        post("apps/${seg(app)}/metadata", buildJsonObject { put("metadata", metadata) })
    }

    /**
     * A component app's xcalls: how many messages wait in its outbox, and the dead letters (never
     * delivered), newest last. Its owner's or an org admin's (`403` to anyone else);
     * `409 NOT_SUPPORTED` for an app on the wasmer runtime, whose xcalls are follow-ups.
     */
    suspend fun appXcalls(app: String): AppXcalls = typed(AppXcalls.serializer(), "GET", "apps/${seg(app)}/xcalls")

    /** What the app's storage holds: `{ keys, bytes, blobs, blobBytes }`. */
    suspend fun appStorage(app: String): JsonObject = get("apps/${seg(app)}/storage") as JsonObject

    /** The ABI the app's bundle embeds: `{ bundle, abi }`. */
    suspend fun appAbi(app: String): JsonObject = get("apps/${seg(app)}/abi") as JsonObject

    /** Hand the app to another account. The owner approves with [passkey]. */
    suspend fun transferApp(
        app: String,
        to: String,
        passkey: PasskeyProvider,
    ) {
        postConfirmed("apps/${seg(app)}/transfer", buildJsonObject { put("to", to) }, passkey)
    }

    /**
     * Move an app to another release of its package, another package, or a bundle hash, running
     * its migration. Where owners hold app upgrades, the node asks for the approval of whoever the
     * package's manifest names (`412 CONFIRM_REQUIRED`), which [passkey] gives. Refused (`403`) to
     * a session opened on an app's frontend: ask with [requestConsent] instead.
     */
    suspend fun upgradeApp(
        app: String,
        target: AppUpgradeTarget,
        passkey: PasskeyProvider? = null,
    ): JsonObject {
        val path = "apps/${seg(app)}/upgrade"
        val body = wire.encodeToJsonElement(target) as JsonObject
        return (if (passkey != null) postConfirmed(path, body, passkey) else post(path, body)) as JsonObject
    }

    /** POST [body] to [path], answering a `412 CONFIRM_REQUIRED` with [passkey]'s assertion over the node's challenge. */
    suspend fun postConfirmed(
        path: String,
        body: JsonObject,
        passkey: PasskeyProvider,
    ): JsonElement = confirmed(body, passkey) { json("POST", path, jsonBody(it)) }

    /** [postConfirmed] for a DELETE (deleting an organization). */
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
        return send(
            JsonObject(
                body +
                    (
                        "confirm" to
                            buildJsonObject {
                                put("challengeId", challenge.string("challengeId").orEmpty())
                                put("assertion", wire.encodeToJsonElement(assertion))
                            }
                    ),
            ),
        )
    }

    /**
     * Create an app instance from a bundle hash, or from a package release (installed from the
     * registry first if the cluster lacks it), or from what a deployment offers
     * ([AppSource.Package.deployment]). Answers its app id. A failed init throws code
     * `INIT_FAILED`.
     */
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
        val body =
            buildJsonObject {
                put("name", name)
                when (source) {
                    is AppSource.Bundle -> put("bundle", source.hash)
                    is AppSource.Package -> {
                        put("package", source.`package`)
                        source.version?.let { put("version", it) }
                        source.service?.let { put("service", it) }
                        source.deployment?.let { put("deployment", it) }
                    }
                }
                org?.let { put("org", it) }
                access?.let { put("access", accessJson(it)) }
                confirmMethods?.let { list -> put("confirmMethods", JsonArray(list.map(::JsonPrimitive))) }
                initArgs?.let { put("initArgs", it) }
                metadata?.let { put("metadata", it) }
            }
        return (post("apps", body) as JsonObject).string("appId").orEmpty()
    }

    /**
     * Call a method. A retry with the same [idempotencyKey] is answered once. Without one, the
     * client makes one, so its own retries of a 503 share it.
     */
    suspend fun call(
        app: String,
        method: String,
        args: JsonElement = JsonObject(emptyMap()),
        idempotencyKey: String? = null,
    ): CallResult {
        val body = callBody(method, args, idempotencyKey ?: newIdempotencyKey())
        return wire.decodeFromJsonElement(CallResult.serializer(), retried("apps/${seg(app)}/intents", body))
    }

    /**
     * Call a method that needs the account holder's approval: the node returns a challenge over
     * this exact call, which [passkey] signs. A declined prompt throws [ConfirmCancelled].
     */
    suspend fun callConfirmed(
        app: String,
        method: String,
        args: JsonElement,
        passkey: PasskeyProvider,
        idempotencyKey: String? = null,
    ): CallResult {
        val path = "apps/${seg(app)}/intents"
        val body = callBody(method, args, idempotencyKey ?: newIdempotencyKey())
        // The confirmed attempt goes out exactly once: its challenge is single-use, so a resend
        // would be refused on the challenge, not answered from the key.
        val answer = confirmed(body, passkey) { b -> retried(path, b, once = b.containsKey("confirm")) }
        return wire.decodeFromJsonElement(CallResult.serializer(), answer)
    }

    /** Run a read-only method: strong by default, or [stale] from any replica. */
    suspend fun view(
        app: String,
        method: String,
        args: JsonElement = JsonObject(emptyMap()),
        stale: Boolean = false,
        minIndex: Long = 0,
    ): ViewResult {
        val body =
            buildJsonObject {
                put("method", method)
                put("args", args)
                put("consistency", if (stale) "stale" else "strong")
                put("minIndex", minIndex)
            }
        return wire.decodeFromJsonElement(ViewResult.serializer(), retried("apps/${seg(app)}/views", body))
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
    suspend fun publishPresence(
        app: String,
        seq: Long,
        state: ByteArray?,
    ) {
        post(
            "apps/${seg(app)}/presence",
            buildJsonObject {
                put("seq", seq)
                put("state", state?.let { JsonPrimitive(Hex.encode(it)) } ?: JsonNull)
            },
        )
    }

    /** Who is present in [app] now. */
    suspend fun presence(app: String): List<PresenceEntry> = listOf("present", PresenceEntry.serializer(), get("apps/${seg(app)}/presence"))

    /** Presence as a stream: a `snapshot` event (the entries present), then a `presence` event per change. */
    fun presenceStream(app: String): Flow<CloudEvent> = stream("apps/${seg(app)}/presence/stream")

    // ---------------------------------------------------------------- aliases

    /** Name an app for this account. */
    suspend fun setAlias(
        alias: String,
        app: String,
    ) {
        put("auth/aliases/${seg(alias)}", buildJsonObject { put("app", app) })
    }

    /** This account's aliases, alias -> app id. */
    suspend fun aliases(): Map<String, String> =
        ((get("auth/aliases") as JsonObject)["aliases"] as? JsonObject)
            ?.let { wire.decodeFromJsonElement(MapSerializer(String.serializer(), String.serializer()), it) }
            .orEmpty()

    /** The app an alias names. */
    suspend fun alias(alias: String): String = (get("auth/aliases/${seg(alias)}") as JsonObject).string("appId").orEmpty()

    suspend fun deleteAlias(alias: String) {
        delete("auth/aliases/${seg(alias)}")
    }

    // ---------------------------------------------------------------- organizations

    /** Create an organization; answers its id. */
    suspend fun createOrg(name: String): String = (post("orgs", buildJsonObject { put("name", name) }) as JsonObject).string("orgId").orEmpty()

    /** The organization, with the teams this caller reaches or manages. */
    suspend fun getOrg(org: String): OrgInfo = orgInfo(get("orgs/${seg(org)}") as JsonObject)

    /** Delete an organization with its teams, invites and apps (its owner only, approved with [passkey]). */
    suspend fun deleteOrg(
        org: String,
        passkey: PasskeyProvider,
    ) {
        deleteConfirmed("orgs/${seg(org)}", passkey)
    }

    /** Set the organization's metadata (an admin, or a holder of `MANAGE_METADATA`); at most 16 KiB. */
    suspend fun setOrgMetadata(
        org: String,
        metadata: JsonElement,
    ) {
        post("orgs/${seg(org)}/metadata", buildJsonObject { put("metadata", metadata) })
    }

    /** Set a member's metadata (theirs, or an admin's); [JsonNull] clears it. */
    suspend fun setMemberMetadata(
        org: String,
        account: String,
        metadata: JsonElement,
    ) {
        post("orgs/${seg(org)}/members/${seg(account)}/metadata", buildJsonObject { put("metadata", metadata) })
    }

    /** Upgrade every app of [org] that runs `target.package` to `target.version`, approved with [passkey]. */
    suspend fun upgradeOrg(
        org: String,
        target: OrgUpgradeTarget,
        passkey: PasskeyProvider,
    ): UpgradeRun =
        wire.decodeFromJsonElement(
            UpgradeRun.serializer(),
            postConfirmed("orgs/${seg(org)}/upgrade", wire.encodeToJsonElement(target) as JsonObject, passkey),
        )

    /** An organization-wide upgrade run: its apps, those done and those that failed. */
    suspend fun upgradeRun(
        org: String,
        run: String,
    ): UpgradeRun = typed(UpgradeRun.serializer(), "GET", "orgs/${seg(org)}/upgrades/${seg(run)}")

    /** Run the apps that failed in [run] again, as a new run, approved with [passkey]. */
    suspend fun retryUpgradeRun(
        org: String,
        run: String,
        passkey: PasskeyProvider,
    ): UpgradeRun =
        wire.decodeFromJsonElement(UpgradeRun.serializer(), postConfirmed("orgs/${seg(org)}/upgrades/${seg(run)}/retry", JsonObject(emptyMap()), passkey))

    /**
     * Approve a mero-tee release to hold the apps of [org], or of this account outside any
     * organization ([org] null); [approved] false withdraws it. Refused (`403`) to a session
     * opened on an app's frontend: ask with [requestConsent] instead.
     */
    suspend fun approveRelease(
        org: String?,
        version: String,
        passkey: PasskeyProvider,
        approved: Boolean = true,
    ): JsonObject {
        val path = if (org == null) "auth/releases" else "orgs/${seg(org)}/releases"
        return postConfirmed(
            path,
            buildJsonObject {
                put("version", version)
                put("approved", approved)
            },
            passkey,
        ) as JsonObject
    }

    /**
     * Approve the URL prefixes apps from [pkg]'s publisher may call out to, for [org]'s apps or this
     * account's own ([org] null). [prefixes] null approves every prefix the package's latest
     * release declares; [replace] sets exactly [prefixes].
     */
    suspend fun approveOutbound(
        org: String?,
        pkg: String,
        passkey: PasskeyProvider,
        prefixes: List<String>? = null,
        replace: Boolean = false,
    ): JsonObject {
        val path = if (org == null) "auth/outbound" else "orgs/${seg(org)}/outbound"
        return postConfirmed(
            path,
            buildJsonObject {
                put("package", pkg)
                put("prefixes", prefixes?.let { list -> JsonArray(list.map(::JsonPrimitive)) } ?: JsonPrimitive("manifest"))
                put("replace", replace)
            },
            passkey,
        ) as JsonObject
    }

    // ---------------------------------------------------------------- approvals

    /** One page of the report of a scope's apps: an organization's, or ([org] null) the account's own. */
    suspend fun appsReport(
        org: String?,
        page: PageOptions = PageOptions(),
    ): AppsReport {
        val path = if (org == null) "auth/apps" else "orgs/${seg(org)}/apps"
        return typed(AppsReport.serializer(), "GET", "$path${pageQuery(page)}")
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
    suspend fun releaseRules(): ReleaseRules = typed(ReleaseRules.serializer(), "GET", "releases")

    /** What waits for a decision in a scope ([org], or the account's own apps with null). */
    suspend fun pendingApprovals(org: String? = null): PendingApprovals = pendingApprovalsFrom(appsReportAll(org), releaseRules())

    /**
     * Ask the node to ask the user: a release, upgrade or outbound approval that only the account
     * holder may make, decided on the node's own consent page, never in the app. Send the person to
     * the answer's `url` (a Custom Tab: mero-compose's `ConsentLauncher`); the page sends them back
     * to `returnTo?request_id=…&result=approved|declined` ([consentReturn]), and [consentResult]
     * confirms the decision.
     */
    suspend fun requestConsent(request: ConsentRequest): ConsentStarted = typed(ConsentStarted.serializer(), "POST", "consent/requests", jsonBody(request.toJson()))

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
    suspend fun consentResult(requestId: String): ConsentResult = typed(ConsentResult.serializer(), "GET", "consent/${seg(requestId)}/result")

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
    suspend fun publisherBinding(signerId: String): PublisherBindingInfo = typed(PublisherBindingInfo.serializer(), "GET", "publishers/${seg(signerId)}")

    /** Bind a package signing key to [org] as its publisher, approved with [passkey]. */
    suspend fun bindPublisher(
        org: String,
        proof: PublisherProof,
        passkey: PasskeyProvider,
    ): JsonObject = postConfirmed("orgs/${seg(org)}/publishers", wire.encodeToJsonElement(proof) as JsonObject, passkey) as JsonObject

    /** Upgrade every instance of [pkg] whose release declares `upgrades: publisher`, as its publisher [org]. */
    suspend fun upgradePublished(
        org: String,
        pkg: String,
        version: String,
        passkey: PasskeyProvider,
        migrate: String? = null,
    ): UpgradeRun {
        val body =
            buildJsonObject {
                put("version", version)
                migrate?.let { put("migrate", it) }
            }
        return wire.decodeFromJsonElement(UpgradeRun.serializer(), postConfirmed("orgs/${seg(org)}/packages/${seg(pkg)}/upgrade", body, passkey))
    }

    // ---------------------------------------------------------------- operator

    /** `GET /ops/apps`: what every app runs, without names or accounts. Operator token ([useApiKey]). */
    suspend fun opsApps(page: PageOptions = PageOptions()): OpsAppsReport = typed(OpsAppsReport.serializer(), "GET", "ops/apps${pageQuery(page)}")

    /** `GET /ops/orgs`: each organization's approved releases. Operator token. */
    suspend fun opsOrgs(page: PageOptions = PageOptions()): OpsOrgsReport = typed(OpsOrgsReport.serializer(), "GET", "ops/orgs${pageQuery(page)}")

    /** Announce when work on images older than [version] ends. Operator token. */
    suspend fun setReleaseDeadline(
        version: String,
        after: Instant,
    ) {
        post("ops/releases/${seg(version)}/deadline", buildJsonObject { put("after", after.toString()) })
    }

    /** Lift a release's deadline. Operator token. */
    suspend fun liftReleaseDeadline(version: String) {
        delete("ops/releases/${seg(version)}/deadline")
    }

    // ---------------------------------------------------------------- members and teams

    /** Add a member, or change their role, approved with the caller's [passkey]. */
    suspend fun setMember(
        org: String,
        account: String,
        role: OrgRole,
        passkey: PasskeyProvider,
    ) {
        postConfirmed(
            "orgs/${seg(org)}/members",
            buildJsonObject {
                put("account", account)
                put("role", role.wire)
            },
            passkey,
        )
    }

    /** Remove a member, or leave ([account] the caller's own), approved with the caller's [passkey]. */
    suspend fun removeMember(
        org: String,
        account: String,
        passkey: PasskeyProvider,
    ): JsonElement = deleteConfirmed("orgs/${seg(org)}/members/${seg(account)}", passkey)

    /**
     * Set capability bits ([OrgCapabilities]): for a member ([account]), for a member of one team
     * ([account] and [team]), or the organization's default (neither). Approved with the caller's
     * [passkey].
     */
    suspend fun setCapabilities(
        org: String,
        capabilities: Int,
        passkey: PasskeyProvider,
        account: String? = null,
        team: String? = null,
    ) {
        postConfirmed(
            "orgs/${seg(org)}/capabilities",
            buildJsonObject {
                put("capabilities", capabilities)
                account?.let { put("account", it) }
                team?.let { put("team", it) }
            },
            passkey,
        )
    }

    /** Create a team; answers its id. */
    suspend fun createTeam(
        org: String,
        name: String,
        join: TeamJoin,
        parent: String? = null,
    ): String =
        (
            post(
                "orgs/${seg(org)}/teams",
                buildJsonObject {
                    put("name", name)
                    put("join", join.wire)
                    parent?.let { put("parent", it) }
                },
            ) as JsonObject
        ).string("teamId").orEmpty()

    suspend fun getTeam(
        org: String,
        team: String,
    ): TeamInfo = typed(TeamInfo.serializer(), "GET", "orgs/${seg(org)}/teams/${seg(team)}")

    /**
     * Rename, change who may join, set the team's metadata, or move it: [parent] moves it under
     * another team, [toTop] to the top; neither leaves it.
     */
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
        post(
            "orgs/${seg(org)}/teams/${seg(team)}",
            buildJsonObject {
                name?.let { put("name", it) }
                join?.let { put("join", it.wire) }
                if (toTop) put("parent", JsonNull) else parent?.let { put("parent", it) }
                metadata?.let { put("metadata", it) }
            },
        )
    }

    /** Delete a team, the teams below it and their apps. */
    suspend fun deleteTeam(
        org: String,
        team: String,
    ) {
        delete("orgs/${seg(org)}/teams/${seg(team)}")
    }

    /** Add a member to a team, or change their row: role, lead, capability bits. Approved with the caller's [passkey]. */
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
        postConfirmed(
            "orgs/${seg(org)}/teams/${seg(team)}/members",
            buildJsonObject {
                put("account", account)
                role?.let { put("role", it.wire) }
                lead?.let { put("lead", it) }
                capabilities?.let { put("capabilities", it) }
            },
            passkey,
        )
    }

    /** Remove a member from a team, approved with the caller's [passkey]. */
    suspend fun removeTeamMember(
        org: String,
        team: String,
        account: String,
        passkey: PasskeyProvider,
    ) {
        deleteConfirmed("orgs/${seg(org)}/teams/${seg(team)}/members/${seg(account)}", passkey)
    }

    /**
     * Make an invite token (`inv_…`) into [org] with [role], into [teams], approved with the
     * caller's [passkey]; one use and 7 days unless said otherwise. [redeemInvite] takes it (no
     * approval there).
     */
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
        val body =
            buildJsonObject {
                put("role", role.wire)
                put("teams", JsonArray(teams.map(::JsonPrimitive)))
                ttlSecs?.let { put("ttlSecs", it) }
                uses?.let { put("uses", it) }
            }
        return (postConfirmed("orgs/${seg(org)}/invites", body, passkey) as JsonObject).string("token").orEmpty()
    }

    /** Redeem an invite token (no approval): the account joins its organization. Answers `{ org }`. */
    suspend fun redeemInvite(token: String): JsonObject = post("invites/${seg(token)}/redeem") as JsonObject

    // ---------------------------------------------------------------- deployers

    /**
     * Let an account or organization install apps where the cluster's deployments are `granted`,
     * until [until] (null: the grant does not end). The cluster's owner grants it, approved with
     * [passkey], where governance says `deployers: owner`; elsewhere the operator grants with its
     * key, and this is refused `403`.
     */
    suspend fun grantDeployer(
        deployer: Deployer,
        passkey: PasskeyProvider,
        until: Instant? = null,
    ): DeployerGranted {
        val body =
            buildJsonObject {
                put("deployer", wire.encodeToJsonElement(Deployer.serializer(), deployer))
                until?.let { put("until", it.toString()) }
            }
        return wire.decodeFromJsonElement(DeployerGranted.serializer(), postConfirmed("deployers", body, passkey))
    }

    /**
     * Who may install apps on the cluster: its deployments rule, who grants deployers, its owner
     * and every grant. For the cluster's owner, or the operator (its token, [useApiKey]).
     */
    suspend fun deployers(): DeployersInfo = typed(DeployersInfo.serializer(), "GET", "deployers")

    /**
     * End a deployer's grant, as the cluster's owner, approved with [passkey]. Apps it installed,
     * and the apps running them, stay.
     */
    suspend fun revokeDeployer(
        deployer: Deployer,
        passkey: PasskeyProvider,
    ) {
        revokeDeployer(deployer.key, passkey)
    }

    /** [revokeDeployer] by the grant's key (`account:{id}` or `org:{id}`). */
    suspend fun revokeDeployer(
        key: String,
        passkey: PasskeyProvider,
    ) {
        deleteConfirmed("deployers/${seg(key)}", passkey)
    }

    // ---------------------------------------------------------------- deployments

    /**
     * Create a deployment: [owner]'s (the caller's own account, or an organization where it holds
     * `MANAGE_APPLICATION` org-wide) list of the apps it offers, named [name] (1 to 64 characters,
     * one line) for customers. Approved with [passkey]; where deployments are `granted` the caller
     * needs a deployer grant. Answers the deployment's id.
     */
    suspend fun createDeployment(
        name: String,
        owner: Deployer,
        passkey: PasskeyProvider,
    ): String {
        val body =
            buildJsonObject {
                put("name", name)
                put("owner", wire.encodeToJsonElement(Deployer.serializer(), owner))
            }
        return (postConfirmed("deployments", body, passkey) as JsonObject).string("id").orEmpty()
    }

    /** The deployments the caller manages: its account's, and its organizations' where it holds `MANAGE_APPLICATION`. */
    suspend fun deployments(): List<DeploymentInfo> = listOf("deployments", DeploymentInfo.serializer(), get("deployments"))

    /** A deployment as anyone may read it: its name, its owner's kind and name, and the apps it offers. Public. */
    suspend fun deployment(id: String): PublicDeployment = typed(PublicDeployment.serializer(), "GET", "deployments/${seg(id)}")

    /** Delete a deployment that offers nothing and has no instances, approved with [passkey]. */
    suspend fun deleteDeployment(
        id: String,
        passkey: PasskeyProvider,
    ) {
        deleteConfirmed("deployments/${seg(id)}", passkey)
    }

    /**
     * Offer `pkg@version` (installed, and seen by the caller) as [app] (1 to 64 letters, digits,
     * `.`, `_`, `-`) in deployment [id], or move an offered app to another release of its package.
     * Approved with [passkey].
     */
    @Suppress("LongParameterList")
    suspend fun offerApp(
        id: String,
        app: String,
        pkg: String,
        version: String,
        passkey: PasskeyProvider,
    ): DeploymentOffered {
        val body =
            buildJsonObject {
                put("app", app)
                put("package", pkg)
                put("version", version)
            }
        return wire.decodeFromJsonElement(DeploymentOffered.serializer(), postConfirmed("deployments/${seg(id)}/apps", body, passkey))
    }

    /** Withdraw [app] from deployment [id], approved with [passkey]; refused while an instance created from the deployment still runs it. */
    suspend fun withdrawApp(
        id: String,
        app: String,
        passkey: PasskeyProvider,
    ) {
        deleteConfirmed("deployments/${seg(id)}/apps/${seg(app)}", passkey)
    }

    /**
     * The organizations with instances created from deployment [id], for its managers: each one's
     * id, name, instance count, apps and releases, and first instance. Never members, teams or app
     * data.
     */
    suspend fun deploymentOrgs(id: String): DeploymentOrgs = typed(DeploymentOrgs.serializer(), "GET", "deployments/${seg(id)}/orgs")

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
