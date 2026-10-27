package com.calimero.mero.testkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * A stateful, in-memory fake Calimero node backed by OkHttp [MockWebServer] — the Android analog of
 * the Swift SDK's `FakeNode` (which used a `URLProtocol`), and the JVM equivalent of nock / msw.
 * Model a whole login → call → refresh → logout journey without a live node.
 *
 * Usage:
 * ```kotlin
 * val node = FakeNode()
 * val server = node.start()                 // MockWebServer with this node's dispatcher
 * val mero = Mero(MeroConfig(baseUrl = server.url("/").toString()))
 * // …exercise mero…
 * server.shutdown()
 * ```
 *
 * Beyond auth and JSON-RPC it keeps the state an app's own tests usually need, in core's wire
 * shapes: scoped client keys (`POST /admin/client-key`), contexts, installed applications, blobs
 * (raw bytes) and group / member / context metadata. Seed it with [addApplication], [addContext]
 * and [putBlob]. And [goOffline] fails every request at the transport, as a phone with no network
 * does.
 *
 * Thread-safe: the dispatcher is invoked on MockWebServer's worker threads.
 */
class FakeNode {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    // Rotating token state.
    private var version = 0
    var accessToken = ""
        private set
    var refreshToken = ""
        private set
    private var accessExpired = false

    /** Refresh tokens already consumed — replaying one revokes the family. */
    private val consumedRefreshTokens = mutableSetOf<String>()
    private var familyRevoked = false

    // Call counters, for assertions.
    var tokenCalls = 0
        private set
    var refreshCalls = 0
        private set
    var logoutCalls = 0
        private set
    var rpcCalls = 0
        private set
    var protectedCalls = 0
        private set
    var clientKeyCalls = 0
        private set

    /**
     * Per-protected-call response delays in milliseconds, consumed in arrival order (missing
     * entries mean no delay).
     *
     * The auth check still happens when the request arrives, so a call that presented a now-stale
     * token is judged 401 at t=0 and *delivered* later — the shape needed to land a 401 after a
     * concurrent refresh has already rotated the bundle.
     */
    private var protectedDelaysMs: List<Long> = emptyList()

    // Admin state (contexts, applications, metadata, client keys).
    private var counter = 0
    private val contextRecords = linkedMapOf<String, JsonObject>()
    private val applicationRecords = linkedMapOf<String, JsonObject>()
    private val metadataRecords = mutableMapOf<String, JsonObject>()
    private val clientKeyRecords = mutableListOf<MintedClientKey>()
    private var offlinePolicy: SocketPolicy? = null

    /**
     * A client key minted through `POST /admin/client-key`: what was asked for, and the tokens
     * handed back (which the node then accepts).
     */
    data class MintedClientKey(
        val contextId: String?,
        val contextIdentity: String?,
        val applicationId: String?,
        val permissions: List<String>,
        val accessToken: String,
        val refreshToken: String,
    )

    /** Canned contract output for `/jsonrpc`, keyed by method. */
    var rpcOutputs: MutableMap<String, JsonElement> = mutableMapOf("get" to JsonPrimitive(42))

    /**
     * The blob store behind `/admin-api/blobs`, keyed by blob id, holding exactly the bytes the
     * client sent (assert on these to check a binary body reached the node unmodified).
     */
    val blobs: MutableMap<String, ByteArray> = java.util.concurrent.ConcurrentHashMap()

    /**
     * When true, an `account_proof` login whose statement names another node key than
     * [FakeCloud.nodeKey] is refused `401`, as a relay rebuilt with a new key refuses a login
     * made under the key pinned before.
     */
    @Volatile var refuseLoginsNamingOtherNodeKey = false

    /** The last `POST /auth/token` body, verbatim (asserts the `account_proof` login shape). */
    @Volatile var lastTokenRequestBody: String? = null
        private set

    /** The last `POST /admin/client-key` body, verbatim. */
    @Volatile var lastClientKeyRequestBody: String? = null
        private set

    /**
     * The Calimero Cloud side of the fake: the cloud manager's routing reads and a hosted
     * relay's attest / intents / query routes, answered by this same server. See [FakeCloud].
     */
    val cloud: FakeCloud = FakeCloud(rpcOutputs = { rpcOutputs })

    /** Start a [MockWebServer] wired to this node's [dispatcher]. Caller owns shutdown. */
    fun start(): MockWebServer =
        MockWebServer().apply {
            dispatcher = this@FakeNode.dispatcher()
            start()
        }

    /** A [Dispatcher] serving this node — attach it to an existing [MockWebServer]. */
    fun dispatcher(): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }

    // ---- Test controls ---------------------------------------------------------------------

    /**
     * Simulate the access token expiring — the next protected call returns `401 token_expired`,
     * driving a reactive refresh.
     */
    fun expireAccessToken() = synchronized(lock) { accessExpired = true }

    /** Simulate the whole family being revoked — protected calls return `401 token_reuse`. */
    fun revokeFamily() = synchronized(lock) { familyRevoked = true }

    /** Delay the Nth protected response by `delaysMs[N]` milliseconds. */
    fun delayProtectedResponses(delaysMs: List<Long>) = synchronized(lock) { protectedDelaysMs = delaysMs }

    /**
     * Fail every request at the transport with [policy] instead of answering it — by default the
     * connection drops with no HTTP response at all, as a phone with no network sees. Through the
     * SDK it surfaces as a `NetworkException`.
     */
    fun goOffline(policy: SocketPolicy = SocketPolicy.DISCONNECT_AFTER_REQUEST) = synchronized(lock) { offlinePolicy = policy }

    /** Undo [goOffline]. The node's state is as it was. */
    fun goOnline() = synchronized(lock) { offlinePolicy = null }

    // ---- Seeding and inspection ------------------------------------------------------------

    /** Install an application, as `GET /admin-api/applications` lists it. */
    fun addApplication(
        id: String,
        packageName: String = "com.example.app",
        version: String = "1.0.0",
        signerId: String = "signer",
    ) {
        val record =
            buildJsonObject {
                put("id", id)
                put(
                    "blob",
                    buildJsonObject {
                        put("bytecode", "bytecode-$id")
                        put("compiled", "compiled-$id")
                    },
                )
                put("size", 0)
                put("source", "fake://$packageName/$version")
                put("metadata", JsonArray(emptyList()))
                put("signer_id", signerId)
                put("package", packageName)
                put("version", version)
            }
        synchronized(lock) { applicationRecords[id] = record }
    }

    /** Add a context the node already holds. */
    fun addContext(
        id: String,
        applicationId: String,
        groupId: String? = null,
    ) = synchronized(lock) { insertContext(id, applicationId, groupId) }

    /** Store [bytes] as a blob and return its id, as an upload would. */
    fun putBlob(bytes: ByteArray): String = storeBlob(bytes)

    /** The bytes of blob [id], exactly as stored, or `null`. */
    fun blob(id: String): ByteArray? = blobs[id]

    /** The ids of the contexts the node holds, oldest first. */
    val contextIds: List<String>
        get() = synchronized(lock) { contextRecords.keys.toList() }

    /** Every client key minted so far, oldest first. */
    val clientKeys: List<MintedClientKey>
        get() = synchronized(lock) { clientKeyRecords.toList() }

    // ---- Routing ---------------------------------------------------------------------------

    private fun handle(request: RecordedRequest): MockResponse {
        val method = request.method ?: "GET"
        val path = (request.path ?: "").substringBefore('?')
        synchronized(lock) { offlinePolicy }?.let { return MockResponse().setSocketPolicy(it) }
        return when (method to path) {
            "POST" to "/auth/token" -> {
                val body = request.body.readUtf8()
                lastTokenRequestBody = body
                if (refuseLoginsNamingOtherNodeKey && !body.contains("\"login_statement\":\"${cloud.nodeKey}")) {
                    MockResponse().setResponseCode(401)
                } else {
                    issueTokens()
                }
            }
            "POST" to "/auth/refresh" -> refresh(request)
            "POST" to "/admin/client-key" -> guarded(request) { mintClientKey(request) }
            "POST" to "/auth/logout" -> logout()
            "HEAD" to "/auth/validate" -> validate(request)
            "GET" to "/auth/health" ->
                ok(
                    buildJsonObject {
                        put(
                            "data",
                            buildJsonObject {
                                put("status", "alive")
                                put("storage", true)
                                put("uptime_seconds", 1)
                            },
                        )
                    },
                )
            // core's public admin health route (what local-node discovery probes).
            "GET" to "/admin-api/health" -> ok(buildJsonObject { put("data", buildJsonObject { put("status", "alive") }) })
            "GET" to "/auth/providers" -> ok(providersBody())
            "GET" to "/admin/identity" -> ok(identityBody())
            "GET" to "/admin-api/contexts" ->
                guarded(request) {
                    cloud.recordRequest(method, path, request)
                    val contexts = cloud.listedContexts.toList() + synchronized(lock) { contextRecords.values.toList() }
                    ok(buildJsonObject { put("data", buildJsonObject { put("contexts", JsonArray(contexts)) }) })
                }
            "POST" to "/jsonrpc" -> guarded(request) { jsonrpc(request) }
            "PUT" to "/admin-api/blobs" -> guarded(request) { uploadBlob(request) }
            "GET" to "/admin-api/blobs" -> guarded(request) { listBlobs() }
            else ->
                adminRoute(method, path, request)
                    ?: blobRoute(method, path, request)
                    ?: cloud.handle(method, path, request) { guarded(request) { it() } }
                    ?: MockResponse().setResponseCode(404)
        }
    }

    // ---- Handlers --------------------------------------------------------------------------

    /** `POST /auth/logout` (core rc.83): retire the refresh token, so a replay of it is reuse. */
    private fun logout(): MockResponse =
        synchronized(lock) {
            logoutCalls++
            ok(buildJsonObject { put("data", buildJsonObject { put("success", true) }) })
        }

    private fun issueTokens(): MockResponse =
        synchronized(lock) {
            tokenCalls++
            version++
            accessToken = "access-$version"
            refreshToken = "refresh-$version"
            accessExpired = false
            familyRevoked = false
            tokenResponse(accessToken, refreshToken)
        }

    /**
     * `POST /admin/client-key`: mint a client key's token pair, which becomes the live session
     * (this fake keeps one) and is recorded in [clientKeys]. Like core, a `keys:*` grant is
     * refused with a `400`.
     */
    private fun mintClientKey(request: RecordedRequest): MockResponse {
        val body = request.body.readUtf8()
        lastClientKeyRequestBody = body
        val refused =
            runCatching {
                json
                    .parseToJsonElement(body)
                    .jsonObject["permissions"]
                    ?.let { it as? JsonArray }
                    ?.map { it.jsonPrimitive.content }
                    ?.firstOrNull { it.substringBefore(':').substringBefore('[').equals("keys", ignoreCase = true) }
            }.getOrNull()
        if (refused != null) {
            return MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(buildJsonObject { put("error", "Permission cannot be granted to a client key: $refused") }.toString())
        }
        val asked = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return synchronized(lock) {
            clientKeyCalls++
            val response = issueTokens()
            clientKeyRecords +=
                MintedClientKey(
                    contextId = asked.string("context_id"),
                    contextIdentity = asked.string("context_identity"),
                    applicationId = asked.string("application_id"),
                    permissions = (asked["permissions"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                )
            response
        }
    }

    private fun refresh(request: RecordedRequest): MockResponse {
        val presented =
            runCatching {
                json
                    .parseToJsonElement(request.body.readUtf8())
                    .jsonObject["refresh_token"]
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull() ?: ""
        synchronized(lock) {
            refreshCalls++
            // Single-use: replaying a consumed refresh token revokes the family.
            if (consumedRefreshTokens.contains(presented) || presented != refreshToken) {
                familyRevoked = true
                return unauthorized("token_reuse")
            }
            consumedRefreshTokens.add(presented)
            version++
            accessToken = "access-$version"
            refreshToken = "refresh-$version"
            accessExpired = false
            return tokenResponse(accessToken, refreshToken)
        }
    }

    private fun validate(request: RecordedRequest): MockResponse =
        if (authError(request) != null) MockResponse().setResponseCode(401) else MockResponse().setResponseCode(200)

    private fun jsonrpc(request: RecordedRequest): MockResponse {
        synchronized(lock) { rpcCalls++ }
        val methodName =
            runCatching {
                json
                    .parseToJsonElement(request.body.readUtf8())
                    .jsonObject["params"]
                    ?.jsonObject
                    ?.get("method")
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull() ?: ""
        val output = rpcOutputs[methodName] ?: rpcOutputs["get"] ?: JsonPrimitive(0)
        return ok(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("result", buildJsonObject { put("output", output) })
            },
        )
    }

    // ---- Contexts, applications and metadata -----------------------------------------------

    /** The context, application and metadata routes, or `null` for any other. */
    private fun adminRoute(
        method: String,
        path: String,
        request: RecordedRequest,
    ): MockResponse? {
        val forApp = "/admin-api/contexts/for-application/"
        val metadata = path.startsWith("/admin-api/groups/") && path.endsWith("/metadata")
        val contextId = lastSegment(path, "/admin-api/contexts/")
        val applicationId = lastSegment(path, "/admin-api/applications/")
        return when {
            method == "POST" && path == "/admin-api/contexts" -> guarded(request) { createContext(request) }
            method == "GET" && path == "/admin-api/applications" -> guarded(request) { listApplications() }
            method == "GET" && path.startsWith(forApp) -> guarded(request) { listContexts(path.removePrefix(forApp)) }
            metadata && method == "PUT" -> guarded(request) { setMetadata(request, path) }
            metadata && method == "GET" -> guarded(request) { getMetadata(path) }
            contextId != null && method == "GET" -> guarded(request) { getContext(contextId) }
            contextId != null && method == "DELETE" -> guarded(request) { deleteContext(contextId) }
            applicationId != null && method == "GET" -> guarded(request) { getApplication(applicationId) }
            else -> null
        }
    }

    /** The one segment after [prefix], or `null` when there is none or more. */
    private fun lastSegment(
        path: String,
        prefix: String,
    ): String? = path.removePrefix(prefix).takeIf { path.startsWith(prefix) && it.isNotEmpty() && '/' !in it }

    // ---- Contexts --------------------------------------------------------------------------

    /** Caller holds [lock]. */
    private fun insertContext(
        id: String,
        applicationId: String,
        groupId: String?,
    ) {
        contextRecords[id] =
            buildJsonObject {
                put("id", id)
                put("applicationId", applicationId)
                put("contextStateHash", "hash-$id")
                put("dagHeads", JsonArray(emptyList()))
                if (groupId != null) put("groupId", groupId)
            }
    }

    private fun listContexts(app: String): MockResponse {
        val contexts = synchronized(lock) { contextRecords.values.filter { it.string("applicationId") == app } }
        return ok(buildJsonObject { put("data", buildJsonObject { put("contexts", JsonArray(contexts)) }) })
    }

    private fun createContext(request: RecordedRequest): MockResponse {
        val body = runCatching { json.parseToJsonElement(request.body.readUtf8()).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val app = body.string("applicationId")
        if (app.isNullOrEmpty()) return refusal(400, "applicationId is required")
        val groupId = body.string("groupId")
        val (id, member) =
            synchronized(lock) {
                counter++
                insertContext("context-$counter", app, groupId)
                "context-$counter" to "member-$counter"
            }
        return ok(
            buildJsonObject {
                put(
                    "data",
                    buildJsonObject {
                        put("contextId", id)
                        put("memberPublicKey", member)
                        if (groupId != null) put("groupId", groupId)
                    },
                )
            },
        )
    }

    private fun getContext(id: String): MockResponse {
        val record = synchronized(lock) { contextRecords[id] } ?: return refusal(404, "context not found")
        return ok(buildJsonObject { put("data", JsonObject(record - "groupId")) })
    }

    private fun deleteContext(id: String): MockResponse {
        val existed = synchronized(lock) { contextRecords.remove(id) != null }
        return ok(buildJsonObject { put("data", buildJsonObject { put("isDeleted", existed) }) })
    }

    // ---- Applications ----------------------------------------------------------------------

    private fun listApplications(): MockResponse {
        val apps = synchronized(lock) { applicationRecords.values.toList() }
        return ok(buildJsonObject { put("data", buildJsonObject { put("apps", JsonArray(apps)) }) })
    }

    private fun getApplication(id: String): MockResponse {
        val app = synchronized(lock) { applicationRecords[id] }
        return ok(buildJsonObject { put("data", buildJsonObject { put("application", app ?: JsonNull) }) })
    }

    // ---- Metadata (group, member, context) -------------------------------------------------

    /** `PUT …/metadata` replaces the record's `data`; an omitted `name` keeps the current one. */
    private fun setMetadata(
        request: RecordedRequest,
        key: String,
    ): MockResponse {
        val body = runCatching { json.parseToJsonElement(request.body.readUtf8()).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        synchronized(lock) {
            val name = body.string("name") ?: metadataRecords[key]?.string("name")
            metadataRecords[key] =
                buildJsonObject {
                    if (name != null) put("name", name)
                    put("data", body["data"] as? JsonObject ?: JsonObject(emptyMap()))
                    put("updatedAt", System.currentTimeMillis())
                    put("updatedBy", "fake-node")
                }
        }
        return MockResponse().setResponseCode(200)
    }

    /** `{ data: record }`, or `{ data: null }` before anything was set. */
    private fun getMetadata(key: String): MockResponse {
        val record = synchronized(lock) { metadataRecords[key] }
        return ok(buildJsonObject { put("data", record ?: JsonNull) })
    }

    // ---- Blobs -----------------------------------------------------------------------------

    /** `PUT /admin-api/blobs`: the raw request body is the blob, read as bytes (never as text). */
    private fun uploadBlob(request: RecordedRequest): MockResponse {
        val bytes = request.body.readByteArray()
        val id = storeBlob(bytes)
        return ok(buildJsonObject { put("data", blobInfo(id, bytes)) })
    }

    private fun storeBlob(bytes: ByteArray): String {
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        blobs[id] = bytes
        return id
    }

    private fun listBlobs(): MockResponse =
        ok(buildJsonObject { put("data", buildJsonObject { put("blobs", JsonArray(blobs.map { (id, b) -> blobInfo(id, b) })) }) })

    /** `GET` / `HEAD` / `DELETE /admin-api/blobs/{id}`, or `null` for any other route. */
    private fun blobRoute(
        method: String,
        path: String,
        request: RecordedRequest,
    ): MockResponse? {
        val id = path.removePrefix("/admin-api/blobs/").takeIf { path.startsWith("/admin-api/blobs/") && it.isNotEmpty() && '/' !in it }
        if (id == null || method !in setOf("GET", "HEAD", "DELETE")) return null
        return guarded(request) {
            val bytes = blobs[id]
            when {
                method == "DELETE" ->
                    ok(
                        buildJsonObject {
                            put("blob_id", id)
                            put("deleted", blobs.remove(id) != null)
                        },
                    )
                bytes == null -> MockResponse().setResponseCode(404)
                method == "HEAD" ->
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("x-blob-id", id)
                        .setHeader("x-blob-hash", id)
                        .setHeader("Content-Length", bytes.size)
                else ->
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/octet-stream")
                        .setBody(Buffer().write(bytes))
            }
        }
    }

    private fun blobInfo(
        id: String,
        bytes: ByteArray,
    ): JsonElement =
        buildJsonObject {
            put("blob_id", id)
            put("size", bytes.size)
        }

    // ---- Auth guard ------------------------------------------------------------------------

    /** Run [handler] only if the bearer token is currently valid; otherwise a 401. */
    private inline fun guarded(
        request: RecordedRequest,
        handler: () -> MockResponse,
    ): MockResponse {
        val delayMs =
            synchronized(lock) {
                protectedCalls++
                protectedDelaysMs.getOrNull(protectedCalls - 1) ?: 0
            }
        val reason = authError(request)
        val response = if (reason != null) unauthorized(reason) else handler()
        return if (delayMs > 0) response.setHeadersDelay(delayMs, TimeUnit.MILLISECONDS) else response
    }

    /**
     * Returns the `x-auth-error` reason if the request's bearer is not valid, else null. A
     * request with no `Authorization` but an `X-Calimero-Proof` passes when
     * [FakeCloud.acceptProofs] is on (the fake does not verify the proof, only that one came).
     */
    private fun authError(request: RecordedRequest): String? =
        synchronized(lock) {
            if (request.getHeader("Authorization") == null && request.getHeader("X-Calimero-Proof") != null) {
                return if (cloud.acceptProofs) null else "missing_token"
            }
            if (familyRevoked) return "token_reuse"
            val bearer = request.getHeader("Authorization") ?: ""
            if (bearer != "Bearer $accessToken") return "token_expired"
            if (accessExpired) return "token_expired"
            null
        }

    // ---- Response helpers ------------------------------------------------------------------

    private fun ok(body: JsonElement): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(json.encodeToString(JsonElement.serializer(), body))

    private fun refusal(
        status: Int,
        message: String,
    ): MockResponse =
        MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody(buildJsonObject { put("error", message) }.toString())

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun unauthorized(reason: String): MockResponse =
        MockResponse().setResponseCode(401).setHeader("x-auth-error", reason)

    private fun tokenResponse(
        access: String,
        refresh: String,
    ): MockResponse =
        ok(
            buildJsonObject {
                put(
                    "data",
                    buildJsonObject {
                        put("access_token", access)
                        put("refresh_token", refresh)
                    },
                )
            },
        )

    private fun providersBody(): JsonElement =
        buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    put(
                        "providers",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("name", "user_password")
                                    put("type", "password")
                                    put("description", "dev")
                                    put("configured", true)
                                    put("config", buildJsonObject { })
                                },
                            )
                        },
                    )
                    put("count", 1)
                },
            )
        }

    private fun identityBody(): JsonElement =
        buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    put("service", "mero-auth")
                    put("version", "test")
                    put("authentication_mode", "embedded")
                    put("providers", buildJsonArray { add(JsonPrimitive("user_password")) })
                },
            )
        }
}
