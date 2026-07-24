package com.calimero.mero.testkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
    var rpcCalls = 0
        private set
    var protectedCalls = 0
        private set

    /** Canned contract output for `/jsonrpc`, keyed by method. */
    var rpcOutputs: MutableMap<String, JsonElement> = mutableMapOf("get" to JsonPrimitive(42))

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

    // ---- Routing ---------------------------------------------------------------------------

    private fun handle(request: RecordedRequest): MockResponse {
        val method = request.method ?: "GET"
        val path = (request.path ?: "").substringBefore('?')
        return when (method to path) {
            "POST" to "/auth/token" -> issueTokens()
            "POST" to "/auth/refresh" -> refresh(request)
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
            "GET" to "/auth/providers" -> ok(providersBody())
            "GET" to "/admin/identity" -> ok(identityBody())
            "GET" to "/admin-api/contexts" -> guarded(request) { ok(buildJsonObject { put("data", buildJsonObject { put("contexts", buildJsonArray { }) }) }) }
            "POST" to "/jsonrpc" -> guarded(request) { jsonrpc(request) }
            else -> MockResponse().setResponseCode(404)
        }
    }

    // ---- Handlers --------------------------------------------------------------------------

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

    // ---- Auth guard ------------------------------------------------------------------------

    /** Run [handler] only if the bearer token is currently valid; otherwise a 401. */
    private inline fun guarded(
        request: RecordedRequest,
        handler: () -> MockResponse,
    ): MockResponse {
        synchronized(lock) { protectedCalls++ }
        val reason = authError(request)
        return if (reason != null) unauthorized(reason) else handler()
    }

    /** Returns the `x-auth-error` reason if the request's bearer is not valid, else null. */
    private fun authError(request: RecordedRequest): String? =
        synchronized(lock) {
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
