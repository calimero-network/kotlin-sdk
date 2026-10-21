package com.calimero.mero.testkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /** When non-null, `GET /api/cloud/accounts/{a}/relays` answers this status (a failed lookup). */
    @Volatile var accountRelaysStatus: Int? = null

    /** Methods `/query` refuses with 409 (not a view), so a client falls back to a warrant. */
    val writeMethods: MutableSet<String> = mutableSetOf("send_message", "set")

    /** Per-method return values for intents and queries; falls back to [FakeNode.rpcOutputs]. */
    val outputs: MutableMap<String, JsonElement> = mutableMapOf()

    /** When non-null, the next intent is refused 400 with this reason (e.g. a stale nonce). */
    @Volatile var refuseNextIntent: String? = null

    /** Whether the relay reports it attested a founding (`teeEnabled`), which gates HA. */
    @Volatile var teeOnFounding: Boolean = true

    /** When non-null, `enable-ha` is refused 409 with this `error` code. */
    @Volatile var haRefusal: String? = null

    /** When false, the cloud routes namespaces to nobody (not hosted). */
    @Volatile var namespacesRouted: Boolean = true

    /** What `GET /admin-api/groups/{g}/members` lists (besides the relay itself, a `RelayTee`). */
    val groupMembers: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    /** The application a group reports (`targetApplicationId`) and its `appKey`. */
    var groupApplicationId: String = "88".repeat(32)
    var groupAppKey: String = "99".repeat(32)

    // ---- cloud login session (`/api/auth/*`, `/api/cloud/me/*`) ---------------------------

    /** Session tokens the fake accepts as `Authorization: Bearer`; sign-ins add to it. */
    val sessionTokens: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf("fake-session"))

    /** The user every sign-in reports. */
    @Volatile var sessionEmail: String = "someone@example.com"

    /** When non-null, the next authenticated answer rotates the session to this token (`x-mdma-session-refresh`). */
    @Volatile var rollingRefresh: String? = null

    /** When non-null, `POST /api/auth/account` is refused 403 with this `detail` (an unlinked account). */
    @Volatile var accountLoginRefusal: String? = null

    /** Namespaces the signed-in login owns (`GET /api/cloud/me/namespaces`), with their HA status. */
    val ownedNamespaces: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Relay rows per owned namespace (`GET /api/cloud/me/namespaces/{ns}/relays`). A namespace
     * with no entry reports one ready `RelayTee` at this server's own URL, executing as
     * [relayAccount].
     */
    val namespaceRelays: MutableMap<String, List<JsonObject>> = java.util.concurrent.ConcurrentHashMap()

    /** Accounts linked to the login (`/api/cloud/me/accounts`), in order. */
    val linkedAccounts: MutableList<String> = CopyOnWriteArrayList()

    /** The plan's linked-account limit; null for none. */
    @Volatile var accountLimit: Int? = 3

    /** When non-null, `POST /api/cloud/accounts/link` is refused 403 with this `detail` (a spent grant). */
    @Volatile var grantRefusal: String? = null

    // ---- delegated accounts: listings, discovery and failure modes ------------------------

    /** Whether a request carrying only an `X-Calimero-Proof` (no Bearer) passes the guard. Unverified. */
    @Volatile var acceptProofs: Boolean = true

    /** What `GET /admin-api/contexts` lists (raw `ContextWithGroup` objects): the caller's contexts. */
    val listedContexts: MutableList<JsonObject> = CopyOnWriteArrayList()

    /** What `GET /admin-api/namespaces` lists (raw `Namespace` objects): the caller's namespaces. */
    val listedNamespaces: MutableList<JsonObject> = CopyOnWriteArrayList()

    /** `GET /admin-api/contexts/{ctx}/group` answers: context → group. */
    val contextGroups: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    /** `GET /admin-api/groups/{g}` reports this namespace for a group (else the group itself). */
    val groupNamespaces: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    /** When non-null, `GET /admin-api/namespaces` answers this status instead of [listedNamespaces]. */
    @Volatile var namespacesStatus: Int? = null

    /** When non-null, `POST /admin-api/tee/attest` answers this status (a 5xx: unavailable). */
    @Volatile var attestStatus: Int? = null

    /** When true, the attestation binds another key than [nodeKey] (refused by the verifier). */
    @Volatile var attestBindsWrongKey: Boolean = false

    /** When non-null, the next governance intent is refused with this status and body `{"error": second}`. */
    @Volatile var refuseNextGovernance: Pair<Int, String>? = null

    /** What `GET /api/v2/bundles?package=…` lists (the app registry), raw. */
    val registryBundles: MutableList<JsonObject> = CopyOnWriteArrayList()

    /** Record a request handled outside [handle] (for [FakeNode]'s own guarded routes). */
    internal fun recordRequest(
        method: String,
        path: String,
        request: RecordedRequest,
    ) {
        requests.add(Recorded(method, path, request.headers.names().associateWith { request.getHeader(it).orEmpty() }, request.body.readUtf8()))
    }

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

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
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
        sessionRoute(method, seg, recorded, base)?.let { response ->
            record()
            return response
        }
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
                accountRelaysStatus?.let { return MockResponse().setResponseCode(it) }
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
            method == "POST" &&
                seg.size == 7 &&
                seg.take(3) == listOf("api", "cloud", "accounts") &&
                seg[4] == "namespaces" &&
                seg[6] == "enable-ha" -> {
                record()
                haRefusal?.let { code ->
                    return MockResponse().setResponseCode(409).setBody(buildJsonObject { put("error", code) }.toString())
                }
                ok(
                    buildJsonObject {
                        put("status", "enabled")
                        put("namespace_id", seg[5])
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
                                if (namespacesRouted) {
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
                                }
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
                attestStatus?.let { return MockResponse().setResponseCode(it) }
                val nonceHex =
                    recorded.bodyJson
                        ?.get("nonce")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                val bound = if (attestBindsWrongKey) "00".repeat(32) else nodeKey
                val quote = MOCK_QUOTE_HEADER + hexBytes(nonceHex) + keyBinding(hexBytes(bound))
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
            // ---- relay: governance + group reads ------------------------------------------------
            seg.size == 4 && seg[0] == "admin-api" && seg[1] == "groups" && seg[3] == "governance-intents" && method == "GET" -> {
                record()
                ok(
                    buildJsonObject {
                        put(
                            "data",
                            buildJsonObject {
                                put("executorAccount", relayAccount)
                                put("executorKey", executorKey)
                                put("groupId", seg[2])
                                put("canActOnBehalf", true)
                            },
                        )
                    },
                )
            }
            seg.size == 4 && seg[0] == "admin-api" && seg[1] == "groups" && seg[3] == "governance-intents" && method == "POST" -> {
                record()
                refuseNextGovernance?.let { (status, reason) ->
                    refuseNextGovernance = null
                    return MockResponse().setResponseCode(status).setBody(buildJsonObject { put("error", reason) }.toString())
                }
                val op =
                    recorded.bodyJson
                        ?.get("op")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                val genesis = op.startsWith("09")
                // A subgroup's create (`RootOp::GroupCreated`, tag 0, 129 bytes) answers the new group, as core does.
                val created = if (op.startsWith("00") && op.length == GROUP_CREATED_HEX_LEN) op.substring(2, 66) else null
                ok(
                    buildJsonObject {
                        put(
                            "data",
                            buildJsonObject {
                                put("groupId", created ?: seg[2])
                                if (genesis) put("teeEnabled", teeOnFounding)
                            },
                        )
                    },
                )
            }
            seg.size == 3 && seg[0] == "admin-api" && seg[1] == "groups" && method == "GET" ->
                guard {
                    record()
                    ok(
                        buildJsonObject {
                            put(
                                "data",
                                buildJsonObject {
                                    put("groupId", seg[2])
                                    put("namespaceId", groupNamespaces[seg[2]] ?: seg[2])
                                    put("appKey", groupAppKey)
                                    put("targetApplicationId", groupApplicationId)
                                    put("memberCount", groupMembers.size + 1)
                                    put("contextCount", 0)
                                    put("defaultCapabilities", 231)
                                    put("subgroupVisibility", "open")
                                },
                            )
                        },
                    )
                }
            seg.size == 4 && seg[0] == "admin-api" && seg[1] == "groups" && seg[3] == "members" && method == "GET" ->
                guard {
                    record()
                    ok(
                        buildJsonObject {
                            put(
                                "members",
                                buildJsonArray {
                                    groupMembers.forEach { (identity, role) ->
                                        add(
                                            buildJsonObject {
                                                put("identity", identity)
                                                put("role", role)
                                            },
                                        )
                                    }
                                    add(
                                        buildJsonObject {
                                            put("identity", relayAccount)
                                            put("role", "RelayTee")
                                        },
                                    )
                                },
                            )
                        },
                    )
                }
            // ---- relay: delegated context creation ------------------------------------------
            seg.size == 4 && seg[0] == "admin-api" && seg[1] == "groups" && seg[3] == "context-intents" && method == "GET" -> {
                record()
                ok(
                    buildJsonObject {
                        put(
                            "data",
                            buildJsonObject {
                                put("executorAccount", relayAccount)
                                put("executorKey", executorKey)
                                put("groupId", seg[2])
                                put("canCreateOnBehalf", true)
                                put("authorMayCreate", true)
                            },
                        )
                    },
                )
            }
            seg.size == 4 && seg[0] == "admin-api" && seg[1] == "groups" && seg[3] == "context-intents" && method == "POST" -> {
                record()
                val contextId = "%064x".format(nextRoot())
                ok(
                    buildJsonObject {
                        put(
                            "data",
                            buildJsonObject {
                                put("contextId", contextId)
                                put("groupId", seg[2])
                                put("memberPublicKey", "")
                            },
                        )
                    },
                )
            }
            seg.size == 2 && seg[0] == "admin-api" && seg[1] == "namespaces" && method == "GET" ->
                guard {
                    record()
                    namespacesStatus?.let { MockResponse().setResponseCode(it) }
                        ?: ok(buildJsonObject { put("data", JsonArray(listedNamespaces.toList())) })
                }
            seg.size == 4 && seg[0] == "admin-api" && seg[1] == "contexts" && seg[3] == "group" && method == "GET" ->
                guard {
                    record()
                    ok(buildJsonObject { put("data", contextGroups[seg[2]]?.let { JsonPrimitive(it) } ?: JsonNull) })
                }
            method == "GET" && path == "/api/v2/bundles" -> {
                record()
                ok(JsonArray(registryBundles.toList()))
            }
            seg.size == 4 && seg[0] == "admin-api" && seg[1] == "namespaces" && seg[3] == "admit" && method == "POST" -> {
                record()
                ok(buildJsonObject { put("data", buildJsonObject { put("published", true) }) })
            }
            else -> null
        }
    }

    // ---- the cloud login half -------------------------------------------------------------

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private fun sessionRoute(
        method: String,
        seg: List<String>,
        recorded: Recorded,
        base: String,
    ): MockResponse? {
        if (seg.size < 3 || seg[0] != "api") return null
        val auth = seg[1] == "auth"
        val anonymous =
            (auth && !(seg.size == 3 && seg[2] in setOf("refresh", "logout"))) ||
                (seg == listOf("api", "cloud", "accounts", "link"))
        val known =
            auth ||
                (seg[1] == "cloud" && seg[2] == "me") ||
                seg == listOf("api", "cloud", "namespaces", "claim") ||
                (method == "POST" && seg.size == 5 && seg[2] == "namespaces" && seg[4] in setOf("enable-ha", "disable-ha")) ||
                seg == listOf("api", "cloud", "accounts", "link")
        if (!known) return null
        if (!anonymous) {
            val token =
                recorded.headers.entries
                    .firstOrNull { it.key.equals("Authorization", true) }
                    ?.value
                    ?.removePrefix("Bearer ")
            if (token == null || token !in sessionTokens) return MockResponse().setResponseCode(401).setBody("{\"detail\":\"not signed in\"}")
        }
        val body = recorded.bodyJson
        val response: MockResponse =
            when {
                method == "POST" && seg == listOf("api", "auth", "google") -> sessionBody()
                method == "GET" && seg == listOf("api", "auth", "account", "challenge") ->
                    ok(
                        buildJsonObject {
                            put("nonce", nextChallenge())
                            put("expires_at_ms", 4_102_444_800_000)
                        },
                    )
                method == "POST" && seg == listOf("api", "auth", "account") ->
                    accountLoginRefusal?.let { detail ->
                        MockResponse().setResponseCode(403).setBody(buildJsonObject { put("detail", detail) }.toString())
                    } ?: sessionBody()
                method == "POST" && seg == listOf("api", "auth", "refresh") -> sessionBody()
                method == "POST" && seg == listOf("api", "auth", "logout") -> MockResponse().setResponseCode(204)
                method == "GET" && seg == listOf("api", "cloud", "me", "namespaces") ->
                    ok(
                        buildJsonArray {
                            ownedNamespaces.forEach { (ns, ha) ->
                                add(
                                    buildJsonObject {
                                        put("group_id", ns)
                                        put("namespace_id", ns)
                                        put("ha_status", ha)
                                        put("ha_enabled_at", JsonNull)
                                        put("contexts", buildJsonArray { })
                                        put(
                                            "fleet_replicas",
                                            buildJsonObject {
                                                put("current", 1)
                                                put("limit", 2)
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                method == "GET" && seg.size == 6 && seg.subList(2, 4) == listOf("me", "namespaces") && seg[5] == "relays" -> {
                    if (seg[4] !in ownedNamespaces) return MockResponse().setResponseCode(404).setBody("{\"detail\":\"not yours\"}")
                    ok(buildJsonObject { put("relays", buildJsonArray { relaysFor(seg[4], base).forEach { add(it) } }) })
                }
                method == "GET" && seg == listOf("api", "cloud", "me", "machines") ->
                    ok(
                        buildJsonObject {
                            put(
                                "machines",
                                buildJsonArray {
                                    if (ownedNamespaces.isNotEmpty()) {
                                        add(
                                            buildJsonObject {
                                                put("peer_id", "12D3KooWFakeRelay")
                                                put("relay_url", base)
                                                put("executor_account", relayAccount)
                                                put("can_execute", true)
                                                put(
                                                    "namespaces",
                                                    buildJsonArray {
                                                        ownedNamespaces.keys.forEach { ns ->
                                                            add(
                                                                buildJsonObject {
                                                                    put("namespace_id", ns)
                                                                    put("status", "active")
                                                                    put("authorship_ready", true)
                                                                    put("tee_role", "RelayTee")
                                                                    put("fresh", true)
                                                                },
                                                            )
                                                        }
                                                    },
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                method == "POST" && seg == listOf("api", "cloud", "namespaces", "claim") -> {
                    val ns =
                        body
                            ?.get("namespace_id")
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .orEmpty()
                    ownedNamespaces.putIfAbsent(ns, "none")
                    ok(buildJsonObject { put("namespace_id", ns) })
                }
                method == "POST" && seg.size == 5 && seg[2] == "namespaces" && seg[4] in setOf("enable-ha", "disable-ha") -> {
                    val status = if (seg[4] == "enable-ha") "enabled" else "disabled"
                    ownedNamespaces[seg[3]] = status
                    ok(
                        buildJsonObject {
                            put("namespace_id", seg[3])
                            put("ha_status", status)
                        },
                    )
                }
                method == "POST" && seg.size == 6 && seg.subList(2, 4) == listOf("me", "namespaces") && seg[5] == "enable-ha" -> {
                    ownedNamespaces[seg[4]] = "enabled"
                    ok(
                        buildJsonObject {
                            put("namespace_id", seg[4])
                            put("ha_status", "enabled")
                        },
                    )
                }
                method == "GET" && seg == listOf("api", "cloud", "me", "accounts", "challenge") ->
                    ok(
                        buildJsonObject {
                            put("nonce", nextChallenge())
                            put("expires_at_ms", 4_102_444_800_000)
                        },
                    )
                method == "GET" && seg == listOf("api", "cloud", "me", "accounts") ->
                    ok(
                        buildJsonObject {
                            put(
                                "accounts",
                                buildJsonArray {
                                    linkedAccounts.forEach { a ->
                                        add(
                                            buildJsonObject {
                                                put("account_id", a)
                                                put("linked_at", "2026-01-01T00:00:00")
                                                put("has_recovery_envelope", false)
                                            },
                                        )
                                    }
                                },
                            )
                            put("limit", accountLimit?.let { JsonPrimitive(it) } ?: JsonNull)
                        },
                    )
                method == "POST" && seg == listOf("api", "cloud", "me", "accounts") -> {
                    val account =
                        body
                            ?.get("account_id")
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .orEmpty()
                    val already = account in linkedAccounts
                    if (!already) linkedAccounts.add(account)
                    ok(
                        buildJsonObject {
                            put("account_id", account)
                            put("linked_at", "2026-01-01T00:00:00")
                            put("already_linked", already)
                        },
                    )
                }
                method == "POST" && seg == listOf("api", "cloud", "accounts", "link") ->
                    grantRefusal?.let { detail ->
                        MockResponse().setResponseCode(403).setBody(buildJsonObject { put("detail", detail) }.toString())
                    } ?: ok(
                        buildJsonObject {
                            put("linked_at", "2026-01-01T00:00:00")
                            put("already_linked", false)
                        },
                    )
                else -> MockResponse().setResponseCode(404)
            }
        if (!anonymous) {
            rollingRefresh?.let { next ->
                rollingRefresh = null
                sessionTokens.add(next)
                response.setHeader("x-mdma-session-refresh", next).setHeader("x-mdma-session-expires", "1900000000")
            }
        }
        return response
    }

    private fun relaysFor(
        namespaceId: String,
        base: String,
    ): List<JsonObject> =
        namespaceRelays[namespaceId] ?: listOf(
            buildJsonObject {
                put("peer_id", "12D3KooWFakeRelay")
                put("relay_url", base)
                put("executor_account", relayAccount)
                put("status", "active")
                put("authorship_ready", true)
                put("tee_role", "RelayTee")
                put("can_execute", true)
                put("admission", "confirmed")
            },
        )

    private fun sessionBody(): MockResponse {
        val token = "fake-session-${nextChallengeNumber()}"
        sessionTokens.add(token)
        return ok(
            buildJsonObject {
                put("session_token", token)
                put("expires_at", 1_800_000_000)
                put(
                    "user",
                    buildJsonObject {
                        put("email", sessionEmail)
                        put("name", "Someone")
                    },
                )
            },
        )
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
        const val GROUP_CREATED_HEX_LEN = 2 * (1 + 32 + 32 + 1 + 32 + 32)
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
