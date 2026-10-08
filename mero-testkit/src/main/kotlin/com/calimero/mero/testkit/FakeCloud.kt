package com.calimero.mero.testkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Calimero Cloud half of [FakeNode]: one server playing both the **cloud manager**
 * (`/api/cloud/…` routing reads) and the **hosted relay** the account is routed to
 * (`/admin-api/tee/attest`, `/auth/challenge`, intents, query, admit). The relay URL it
 * reports is the server's own, so a client follows the routing straight back to it.
 *
 * Deliberately HTTP-only and unverifying: it records what arrives (headers, bodies) for
 * tests to assert on, and answers with well-formed shapes. Signature checking belongs to
 * the SDK's own golden-vector tests, not to a fake.
 */
class FakeCloud internal constructor(
    private val rpcOutputs: () -> Map<String, JsonElement>,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var challenges = 0
    private var roots = 0

    /** The relay's node key (what the mock attestation binds), 64 hex. */
    var nodeKey: String = "ab".repeat(32)

    /** The relay's own account: the executor warrants name. */
    var relayAccount: String = "cd".repeat(32)

    /** The relay's executor signing key. */
    var executorKey: String = "ef".repeat(32)

    /** When false, the cloud reports no relay for the account (a brand-new account). */
    var accountHasRelay: Boolean = true

    /** Methods `/query` refuses with 409 (not a view), so a client falls back to a warrant. */
    val writeMethods: MutableSet<String> = mutableSetOf("send_message", "set")

    /** Per-method return values for intents and queries; falls back to [FakeNode.rpcOutputs]. */
    val outputs: MutableMap<String, JsonElement> = mutableMapOf()

    /** When non-null, the next intent is refused 400 with this reason (e.g. a stale nonce). */
    @Volatile var refuseNextIntent: String? = null

    /** The `nextNonce` the warrant-nonce route reports. */
    @Volatile var nextNonce: Long = 1

    /** Everything that arrived on a cloud or relay route, in order. */
    val requests: MutableList<Recorded> = CopyOnWriteArrayList()

    /** One recorded request. */
    data class Recorded(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    ) {
        val bodyJson: JsonObject? get() = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    /** The recorded requests whose path ends with [suffix]. */
    fun recorded(suffix: String): List<Recorded> = requests.filter { it.path.endsWith(suffix) }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    internal fun handle(
        method: String,
        path: String,
        request: RecordedRequest,
        guard: (() -> MockResponse) -> MockResponse,
    ): MockResponse? {
        val body = request.body.readUtf8()
        val headers = request.headers.names().associateWith { request.getHeader(it).orEmpty() }
        val base = request.requestUrl?.let { "${it.scheme}://${it.host}:${it.port}" } ?: ""
        val seg = path.trim('/').split('/')
        val recorded = Recorded(method, path, headers, body)

        fun record(): Recorded = recorded.also { requests.add(it) }
        return when {
            // ---- cloud manager ------------------------------------------------------------
            method == "GET" && seg.size == 5 && seg.take(3) == listOf("api", "cloud", "accounts") && seg[4] == "challenge" -> {
                record()
                ok(
                    buildJsonObject {
                        put("account_id", seg[3])
                        put("nonce", nextChallenge())
                        put("expires_at_ms", 4_102_444_800_000)
                    },
                )
            }
            method == "GET" && seg.size == 5 && seg.take(3) == listOf("api", "cloud", "accounts") && seg[4] == "relays" -> {
                record()
                if (headers["X-Calimero-Signature"].isNullOrEmpty()) return MockResponse().setResponseCode(401)
                ok(
                    buildJsonObject {
                        put("account_id", seg[3])
                        put(
                            "relays",
                            buildJsonArray {
                                if (accountHasRelay) {
                                    add(
                                        buildJsonObject {
                                            put("peer_id", "12D3KooWFakeRelay")
                                            put("relay_url", base)
                                            put("fresh", true)
                                            put("executor_account", relayAccount)
                                            put("assigned", false)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
            method == "GET" && seg.size == 5 && seg.take(3) == listOf("api", "cloud", "namespaces") && seg[4] == "challenge" -> {
                record()
                ok(
                    buildJsonObject {
                        put("namespace_id", seg[3])
                        put("nonce", nextChallenge())
                        put("expires_at_ms", 4_102_444_800_000)
                    },
                )
            }
            method == "GET" && seg.size == 5 && seg.take(3) == listOf("api", "cloud", "namespaces") && seg[4] == "admitters" -> {
                record()
                ok(
                    buildJsonObject {
                        put("namespace_id", seg[3])
                        put(
                            "admitters",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("peer_id", "12D3KooWFakeRelay")
                                        put("account", relayAccount)
                                        put("relay_url", base)
                                        put("admit_url", "$base/admin-api/namespaces/${seg[3]}/admit")
                                        put("status", "active")
                                        put("fresh", true)
                                        put("can_admit", true)
                                        put("authorship_ready", true)
                                        put("can_execute", true)
                                    },
                                )
                            },
                        )
                        put("servable", true)
                        put("writable", true)
                    },
                )
            }

            // ---- relay: attestation + login --------------------------------------------------
            method == "POST" && path == "/admin-api/tee/attest" -> {
                record()
                val nonceHex =
                    recorded.bodyJson
                        ?.get("nonce")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                val quote = MOCK_QUOTE_HEADER + hexBytes(nonceHex) + keyBinding(hexBytes(nodeKey))
                ok(
                    buildJsonObject {
                        put(
                            "data",
                            buildJsonObject {
                                put("quoteB64", base64(quote))
                                put("boundPublicKey", nodeKey)
                            },
                        )
                    },
                )
            }
            method == "GET" && path == "/auth/challenge" -> {
                record()
                ok(buildJsonObject { put("data", buildJsonObject { put("challenge", "%064x".format(nextChallengeNumber())) }) })
            }

            // ---- relay: intents ----------------------------------------------------------------
            seg.size == 4 && seg[0] == "admin-api" && seg[1] == "contexts" && seg[3] == "intents" && method == "GET" -> {
                record()
                ok(
                    buildJsonObject {
                        put(
                            "data",
                            buildJsonObject {
                                put("executorAccount", relayAccount)
                                put("executorKey", executorKey)
                                put("canAuthorOnBehalf", true)
                                put("groupId", "99".repeat(32))
                                put("releaseBytecodeId", "44".repeat(32))
                                put("releaseVersion", "1.0.0")
                            },
                        )
                    },
                )
            }
            seg.size == 4 && seg[0] == "admin-api" && seg[1] == "contexts" && seg[3] == "intents" && method == "POST" -> {
                record()
                refuseNextIntent?.let { reason ->
                    refuseNextIntent = null
                    return MockResponse().setResponseCode(400).setBody(buildJsonObject { put("error", reason) }.toString())
                }
                val m =
                    recorded.bodyJson
                        ?.get("method")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                ok(
                    buildJsonObject {
                        put(
                            "data",
                            buildJsonObject {
                                put("rootHash", "root-${nextRoot()}")
                                put("returns", outputFor(m))
                            },
                        )
                    },
                )
            }
            seg.size == 4 && seg[0] == "admin-api" && seg[1] == "contexts" && seg[3] == "query" && method == "POST" ->
                guard {
                    record()
                    val m =
                        recorded.bodyJson
                            ?.get("method")
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .orEmpty()
                    if (m in writeMethods) {
                        MockResponse().setResponseCode(409).setBody("{\"error\":\"not a view\"}")
                    } else {
                        ok(buildJsonObject { put("data", buildJsonObject { put("returns", outputFor(m)) }) })
                    }
                }
            seg.size == 4 && seg[0] == "admin-api" && seg[1] == "contexts" && seg[3] == "warrant-nonce" && method == "POST" -> {
                record()
                ok(
                    JsonObject(
                        mapOf(
                            "data" to
                                buildJsonObject {
                                    put("contextId", seg[2])
                                    put("authorDeviceKey", "")
                                    put("seen", true)
                                    put("nextNonce", nextNonce)
                                    put("windowWidth", 64)
                                },
                        ),
                    ),
                )
            }
            seg.size == 4 && seg[0] == "admin-api" && seg[1] == "namespaces" && seg[3] == "admit" && method == "POST" -> {
                record()
                ok(buildJsonObject { put("data", buildJsonObject { put("published", true) }) })
            }
            else -> null
        }
    }

    private fun outputFor(method: String): JsonElement = outputs[method] ?: rpcOutputs()[method] ?: JsonNull

    private fun nextChallenge(): String = synchronized(lock) { "fake-challenge-${++challenges}" }

    private fun nextChallengeNumber(): Int = synchronized(lock) { ++challenges }

    private fun nextRoot(): Int = synchronized(lock) { ++roots }

    private fun ok(body: JsonElement): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(json.encodeToString(JsonElement.serializer(), body))

    private companion object {
        val MOCK_QUOTE_HEADER = "MOCK_TDX_QUOTE_V1".encodeToByteArray()
        val KEY_BINDING_DOMAIN = "calimero.tee-attest.key-binding.v1".encodeToByteArray()
        const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        fun keyBinding(nodeKey: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(KEY_BINDING_DOMAIN + ByteArray(32) + nodeKey)

        fun hexBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) or Character.digit(hex[it * 2 + 1], 16)).toByte() }

        /** Standard base64 by hand: `java.util.Base64` is API 26+, and this runs in the sample app too. */
        fun base64(bytes: ByteArray): String {
            val out = StringBuilder()
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
                out.append(B64[b0 shr 2]).append(B64[((b0 and 3) shl 4) or (b1 shr 4)])
                out.append(if (i + 1 < bytes.size) B64[((b1 and 0xF) shl 2) or (b2 shr 6)] else '=')
                out.append(if (i + 2 < bytes.size) B64[b2 and 0x3F] else '=')
                i += 3
            }
            return out.toString()
        }
    }
}
