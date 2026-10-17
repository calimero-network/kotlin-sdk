package com.calimero.mero.cloud

import com.calimero.mero.account.AccountLinkInput
import com.calimero.mero.account.AccountLoginInput
import com.calimero.mero.account.ResolvedRoot
import com.calimero.mero.account.resolveRoot
import com.calimero.mero.account.resolveRootPair
import com.calimero.mero.account.signAccountLink
import com.calimero.mero.account.signAccountLogin
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Hex
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.HttpResponse
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.net.URLEncoder

/**
 * The Calimero cloud manager (MDMA) API — port of mero-js `CloudClient`
 * (`src/cloud/cloud-client.ts`).
 *
 * Two kinds of caller use it. A **device** proves itself with its certificate
 * ([RoutingCredential]) on the routing reads and holds no cloud session. A **cloud login**
 * (Google, or an account root signing itself in) holds a [CloudLoginSession] and reads what
 * it owns. The routes that need a session refuse locally, before any request, when none is
 * held ([MeroStateException]).
 *
 * | method | endpoint | auth |
 * |---|---|---|
 * | [signInWithGoogle] | `POST /api/auth/google` | anonymous |
 * | [getAccountLoginChallenge] / [submitAccountLogin] / [signInWithAccount] | `GET /api/auth/account/challenge`, `POST /api/auth/account` | anonymous |
 * | [refreshSession] / [signOut] | `POST /api/auth/refresh`, `POST /api/auth/logout` | session |
 * | [getMyNamespaces] | `GET /api/cloud/me/namespaces` | session |
 * | [getNamespaceRelays] / [findExecutingRelay] | `GET /api/cloud/me/namespaces/{ns}/relays` | session |
 * | [getMyMachines] | `GET /api/cloud/me/machines` | session |
 * | [claimNamespace] | `POST /api/cloud/namespaces/claim` | session |
 * | [enableNamespaceHa] / [disableNamespaceHa] | `POST /api/cloud/namespaces/{ns}/enable-ha` · `disable-ha` | session |
 * | [enableFoundedNamespaceHa] | `POST /api/cloud/me/namespaces/{ns}/enable-ha` | session |
 * | [getAccountLinkChallenge] / [submitAccountLink] / [linkAccount] | `GET /api/cloud/me/accounts/challenge`, `POST /api/cloud/me/accounts` | session |
 * | [getMyAccounts] | `GET /api/cloud/me/accounts` | session |
 * | [linkAccountWithGrant] | `POST /api/cloud/accounts/link` | anonymous |
 * | [getAccountRelaysChallenge] / [getAccountRelays] | `GET /api/cloud/accounts/{acc}/challenge` · `relays` + routing proof | anonymous |
 * | [getRoutingChallenge] / [getNamespaceRouting] / [findAdmitter] | `GET /api/cloud/namespaces/{ns}/challenge` · `admitters` | anonymous |
 * | [enableHaAsAccount] | `POST /api/cloud/accounts/{acc}/namespaces/{ns}/enable-ha` + founder's claim | anonymous |
 *
 * mero-js `getMyNode` (`GET /api/cloud/me/node`) is not ported: it is deprecated there and
 * reads a ledger nothing has written since the v1 retirement, so it answers null for every
 * namespace-native account. Use [getMyMachines].
 *
 * **Sessions.** MDMA rotates the token on activity and hands the replacement back in the
 * `x-mdma-session-refresh` / `x-mdma-session-expires` response headers; the client adopts
 * it on every response. Every change — sign-in, refresh, rolling refresh, sign-out — is
 * written to [sessionStore] and reported to [onSession] (whose failures are swallowed so a
 * storage error cannot mask the response).
 *
 * @param sessionToken a stored session token to start out signed in with (expiry unknown:
 *   `expiresAt = 0`). Wins over [sessionStore]'s.
 * @param onSession called with the new session (or null on sign-out) whenever it changes.
 * @param sessionStore where the session persists ([CloudSessionStore] over an
 *   [com.calimero.mero.account.EncryptedPrefsSecureStore] on a device); its stored session
 *   is loaded at construction when no [sessionToken] is given.
 */
class CloudClient internal constructor(
    baseUrl: String,
    private val routingCredential: RoutingCredential?,
    private val http: PlainJsonHttp,
    sessionToken: String? = null,
    private val onSession: ((CloudLoginSession?) -> Unit)? = null,
    private val sessionStore: CloudSessionStore? = null,
) {
    @Suppress("LongParameterList")
    constructor(
        baseUrl: String = DEFAULT_BASE_URL,
        routingCredential: RoutingCredential? = null,
        httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
        sessionToken: String? = null,
        onSession: ((CloudLoginSession?) -> Unit)? = null,
        sessionStore: CloudSessionStore? = null,
    ) : this(baseUrl, routingCredential, PlainJsonHttp(httpClient), sessionToken, onSession, sessionStore)

    /** The manager API base, without a trailing slash. */
    val baseUrl: String = baseUrl.trimBase()
    private val base get() = baseUrl
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var session: CloudLoginSession? =
        if (!sessionToken.isNullOrEmpty()) {
            CloudLoginSession(sessionToken, 0, CloudUser(""))
        } else {
            runCatching { sessionStore?.load() }.getOrNull()
        }

    // ---- Session -------------------------------------------------------------------------

    /** The current session, or null when nobody is signed in. */
    fun getSession(): CloudLoginSession? = session

    /** Whether a session token is held. Says nothing about the server agreeing. */
    val isSignedIn: Boolean get() = session != null

    /**
     * Exchange a Google OAuth2 ID token for a session. The app owns the Google flow
     * (Credential Manager, a Custom Tab) and hands the ID token here once.
     */
    suspend fun signInWithGoogle(idToken: String): CloudLoginSession =
        adoptSession(request("POST", "/api/auth/google", buildJsonObject { put("id_token", idToken) }, anonymous = true))

    /**
     * Sign in as a Calimero account, proving it with its root ([rootSecret], 64 hex): fetch
     * a challenge, sign it ([signAccountLogin]), post the proof. Only a signature over the
     * cloud's nonce travels.
     *
     * A `403` means the signature did not verify **or** the account is not linked to a
     * cloud login; the ownership claim is recorded either way.
     */
    suspend fun signInWithAccount(rootSecret: String): CloudLoginSession = signInWithAccount(resolveRoot(rootSecret))

    /** [signInWithAccount] with a root held as a signer this process cannot read (an [com.calimero.mero.account.AccountRootSigner]). */
    suspend fun signInWithAccount(root: DeviceSigner): CloudLoginSession = signInWithAccount(resolveRoot(root))

    private suspend fun signInWithAccount(root: ResolvedRoot): CloudLoginSession {
        val nonce = getAccountLoginChallenge().nonce
        return submitAccountLogin(
            AccountLoginProof(root.publicKey, nonce, signAccountLogin(AccountLoginInput(nonce, signer = root.signer))),
        )
    }

    /** A single-use challenge for a root to sign in with. Public: obtaining a session is what it is for. */
    suspend fun getAccountLoginChallenge(): AccountLoginChallenge {
        val body = obj(request("GET", "/api/auth/account/challenge", anonymous = true))
        return AccountLoginChallenge(body.str("nonce").orEmpty(), body.long("expires_at_ms") ?: 0)
    }

    /** Post a login proof assembled elsewhere (a hardware key, another device). */
    suspend fun submitAccountLogin(proof: AccountLoginProof): CloudLoginSession =
        adoptSession(
            request(
                "POST",
                "/api/auth/account",
                buildJsonObject {
                    put("root_public_key", proof.rootPublicKey)
                    put("nonce", proof.nonce)
                    put("signature", proof.signature)
                },
                anonymous = true,
            ),
        )

    /** Refresh the session explicitly. Rarely needed: rolling refreshes are adopted on every call. */
    suspend fun refreshSession(): CloudLoginSession = adoptSession(request("POST", "/api/auth/refresh"))

    /**
     * Revoke this session server-side and forget it locally. Local state is cleared even
     * when the server call fails (which then still throws).
     */
    suspend fun signOut() {
        try {
            if (session != null) request("POST", "/api/auth/logout")
        } finally {
            setSession(null)
        }
    }

    // ---- What the login owns -------------------------------------------------------------

    /** Namespaces the signed-in user owns. */
    suspend fun getMyNamespaces(): List<CloudNamespace> =
        rows(parse(request("GET", "/api/cloud/me/namespaces"))).map { row ->
            CloudNamespace(
                namespaceId = row.str("namespace_id") ?: row.str("group_id").orEmpty(),
                groupId = row.str("group_id") ?: row.str("namespace_id").orEmpty(),
                haStatus = row.str("ha_status") ?: "none",
                haEnabledAt = row.str("ha_enabled_at"),
                contexts = (row["contexts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                fleetReplicas = row["fleet_replicas"]?.takeIf { it !is JsonNull },
            )
        }

    /**
     * The relays serving one owned namespace: where to present an intent, and as whom.
     * Filter on [CloudRelay.canExecute] before signing — a warrant presented to a relay that
     * does not relay is refused after the nonce is spent. See [findExecutingRelay].
     */
    suspend fun getNamespaceRelays(namespaceId: String): List<CloudRelay> {
        val body = obj(request("GET", "/api/cloud/me/namespaces/${enc(namespaceId)}/relays"))
        return rows(body["relays"]).map { row ->
            val relayUrl = row.str("relay_url")
            val executorAccount = row.str("executor_account")
            val authorshipReady = row.bool("authorship_ready") == true
            CloudRelay(
                peerId = row.str("peer_id").orEmpty(),
                relayUrl = relayUrl,
                executorAccount = executorAccount,
                nodeKey = row.str("node_key") ?: row.str("node_public_key"),
                status = row.str("status").orEmpty(),
                authorshipReady = authorshipReady,
                teeRole = teeRoleOf(row),
                canExecute = row.bool("can_execute") ?: (authorshipReady && relayUrl != null && executorAccount != null),
                lastSeenAt = row.str("last_seen_at"),
                confirmedAt = row.str("confirmed_at"),
                admission = row.str("admission"),
                admissionHint = row.str("admission_hint"),
                join = joinOf(row["join"]),
            )
        }
    }

    /** The first relay for [namespaceId] that can run an intent now, or null. See [pickExecutingRelay]. */
    suspend fun findExecutingRelay(namespaceId: String): CloudRelay? = pickExecutingRelay(getNamespaceRelays(namespaceId))

    /** Every attested machine serving something this login owns, keyed by machine. */
    suspend fun getMyMachines(): List<CloudMachine> {
        val body = obj(request("GET", "/api/cloud/me/machines"))
        return rows(body["machines"]).map { row ->
            val relayUrl = row.str("relay_url")
            val executorAccount = row.str("executor_account")
            val namespaces =
                rows(row["namespaces"]).map { ns ->
                    CloudMachineNamespace(
                        namespaceId = ns.str("namespace_id").orEmpty(),
                        status = ns.str("status").orEmpty(),
                        authorshipReady = ns.bool("authorship_ready") == true,
                        teeRole = teeRoleOf(ns),
                        fresh = ns.bool("fresh") == true,
                        confirmedAt = ns.str("confirmed_at"),
                        lastSeenAt = ns.str("last_seen_at"),
                    )
                }
            CloudMachine(
                peerId = row.str("peer_id").orEmpty(),
                relayUrl = relayUrl,
                executorAccount = executorAccount,
                canExecute =
                    row.bool("can_execute")
                        ?: (relayUrl != null && executorAccount != null && namespaces.any { it.authorshipReady }),
                namespaces = namespaces,
            )
        }
    }

    // ---- HA ------------------------------------------------------------------------------

    /**
     * Claim a namespace under this login, proving ownership. Pass merod's
     * `issueNamespaceOwnershipProof` answer **verbatim** (the cloud accepts core's camelCase;
     * the `founding` and `credential` it carries are what the cloud checks). Idempotent for
     * the same account; another owner's namespace is a `409`, a replayed nonce a `403`.
     */
    suspend fun claimNamespace(
        namespaceId: String,
        ownershipProof: JsonElement,
    ) {
        request(
            "POST",
            "/api/cloud/namespaces/claim",
            buildJsonObject {
                put("namespace_id", namespaceId)
                put("ownership_proof", ownershipProof)
            },
        )
    }

    /**
     * Ask the fleet to host a claimed namespace. [admitterAddrs] is where the owner's node
     * can be dialed — [admitterAddrsFromNetworkStatus] of its network status; null leaves
     * what the cloud holds (an empty body), a list replaces it. Not the web flow
     * ([enableHaUrl]); this is the API call.
     */
    suspend fun enableNamespaceHa(
        namespaceId: String,
        admitterAddrs: List<String>? = null,
    ): JsonElement {
        val body =
            buildJsonObject {
                if (admitterAddrs != null) put("admitter_addrs", buildJsonArray { admitterAddrs.forEach { add(JsonPrimitive(it)) } })
            }
        return parse(request("POST", "/api/cloud/namespaces/${enc(namespaceId)}/enable-ha", body))
    }

    /**
     * Enable HA for a namespace this **account** founded through a relay, with the founder's
     * claim ([signAccountOwnershipClaim], whose `subject` is this login's email) instead of a
     * node's ownership proof. The login must be linked to that account.
     */
    suspend fun enableFoundedNamespaceHa(
        namespaceId: String,
        ownershipProof: AccountOwnershipProof,
    ): JsonElement {
        val body =
            buildJsonObject {
                put("groups", JsonArray(emptyList()))
                put("ownership_proof", ownershipProof.toJson())
            }
        return parse(request("POST", "/api/cloud/me/namespaces/${enc(namespaceId)}/enable-ha", body))
    }

    /**
     * Stop hosting a claimed namespace. Each fleet node assigned to it then self-leaves,
     * which makes core evict it and purge its data and keys — not just a billing flip.
     */
    suspend fun disableNamespaceHa(namespaceId: String): JsonElement =
        parse(request("POST", "/api/cloud/namespaces/${enc(namespaceId)}/disable-ha", JsonObject(emptyMap())))

    /**
     * The cloud's `/enable-ha` page for a person to complete by hand:
     * `{baseUrl}/enable-ha?group_id=…&context_id=…[&redirect_url=…]` (mero-js `enableHA`,
     * which `window.open`s it). Open it with `CloudWebLauncher.enableHA`, or any browser.
     */
    fun enableHaUrl(options: HaPageOptions): String = haPageUrl("/enable-ha", options)

    /** The cloud's `/disable-ha` page. See [enableHaUrl]. */
    fun disableHaUrl(options: HaPageOptions): String = haPageUrl("/disable-ha", options)

    private fun haPageUrl(
        path: String,
        options: HaPageOptions,
    ): String {
        val params = mutableListOf("group_id" to options.groupId, "context_id" to options.contextId)
        options.redirectUrl?.takeIf { it.isNotEmpty() }?.let { params += "redirect_url" to it }
        return "$base$path?" + params.joinToString("&") { (k, v) -> "${form(k)}=${form(v)}" }
    }

    /**
     * Ask the cloud to host (HA) a namespace this account founded, with no cloud session:
     * `POST /api/cloud/accounts/{account}/namespaces/{ns}/enable-ha` `{ownership_proof}`,
     * signed by [AccountOwnership.signAccountHaClaim]. The cloud bills the user the account
     * is linked to in the wallet.
     *
     * [signer] defaults to this client's routing credential's.
     *
     * @throws AccountHaRefusedException (one of its five subclasses, [HaRefusal]) for the
     *   refusals a person can act on; any other failure as [HttpException].
     */
    @Suppress("LongParameterList")
    suspend fun enableHaAsAccount(
        namespaceId: String,
        salt: String,
        accountId: String,
        credential: String,
        signer: DeviceSigner? = routingCredential?.signer,
        relayUrl: String? = null,
        ttlMs: Long = AccountOwnership.DEFAULT_TTL_MS,
    ): JsonObject {
        val key = signer ?: throw MeroStateException("enableHaAsAccount needs the device signer")
        val proof = AccountOwnership.signAccountHaClaim(namespaceId, accountId, salt, credential, key, relayUrl, ttlMs)
        // The path carries the ids as the claim does: lowercase hex.
        val path =
            "/api/cloud/accounts/${enc(accountId.lowercase())}/namespaces/${enc(namespaceId.lowercase())}/enable-ha"
        val body = buildJsonObject { put("ownership_proof", proof.toJson()) }
        val res =
            try {
                request("POST", path, body, anonymous = true)
            } catch (e: HttpException) {
                throw AccountHaRefusedException.from(e) ?: e
            }
        return obj(res)
    }

    // ---- Account linking -----------------------------------------------------------------

    /**
     * Link a Calimero account to this login, proving its root ([rootSecret], 64 hex): fetch a
     * single-use challenge, sign it ([signAccountLink]), post the proof. Idempotent for the
     * same login ([CloudAccountLink.alreadyLinked]); another login's account is a `409`, a
     * spent challenge a `403`, a full plan a `402`.
     */
    suspend fun linkAccount(rootSecret: String): CloudAccountLink = linkAccount(resolveRoot(rootSecret))

    /** [linkAccount] with a root held as a signer. */
    suspend fun linkAccount(root: DeviceSigner): CloudAccountLink = linkAccount(resolveRoot(root))

    private suspend fun linkAccount(root: ResolvedRoot): CloudAccountLink {
        val nonce = getAccountLinkChallenge().nonce
        return submitAccountLink(
            AccountLinkProof(root.accountId, root.publicKey, nonce, signAccountLink(AccountLinkInput(nonce, signer = root.signer))),
        )
    }

    /** A single-use link challenge, bound to this login. Fetching and abandoning one spends it. */
    suspend fun getAccountLinkChallenge(): AccountLinkChallenge {
        val body = obj(request("GET", "/api/cloud/me/accounts/challenge"))
        return AccountLinkChallenge(body.str("nonce").orEmpty(), body.long("expires_at_ms"))
    }

    /**
     * Post a link proof assembled elsewhere. [AccountLinkProof.accountId] must be the
     * account [AccountLinkProof.rootPublicKey] names — derive both from one key.
     */
    suspend fun submitAccountLink(proof: AccountLinkProof): CloudAccountLink {
        val body =
            obj(
                request(
                    "POST",
                    "/api/cloud/me/accounts",
                    buildJsonObject {
                        put("account_id", proof.accountId)
                        put("root_public_key", proof.rootPublicKey)
                        put("nonce", proof.nonce)
                        put("signature", proof.signature)
                    },
                ),
            )
        return linkOf(body, proof.accountId)
    }

    /**
     * Spend a link grant ([readAccountLinkCallback] / [CloudLink.readCloudLinkCallback]): sign
     * it with the account root and post both halves, **anonymously** — no session is sent
     * even when one is held. Exactly one of [rootSecret] and [signer].
     *
     * A `403` means the grant was bad, expired, spent, or signed by a key naming another
     * account; `409` that the account belongs to another login; `402` a full plan.
     */
    suspend fun linkAccountWithGrant(
        grant: String,
        rootSecret: String? = null,
        signer: DeviceSigner? = null,
    ): CloudAccountLink {
        val root = resolveRootPair(rootSecret, signer)
        val body =
            obj(
                request(
                    "POST",
                    "/api/cloud/accounts/link",
                    buildJsonObject {
                        put("grant", grant)
                        put("root_public_key", root.publicKey)
                        put("signature", signAccountLink(AccountLinkInput(grant, signer = root.signer)))
                    },
                    anonymous = true,
                ),
            )
        return linkOf(body, root.accountId)
    }

    /** The accounts linked to this login, and how many the plan allows. */
    suspend fun getMyAccounts(): CloudLinkedAccounts {
        val body = obj(request("GET", "/api/cloud/me/accounts"))
        return CloudLinkedAccounts(
            accounts =
                rows(body["accounts"]).map { row ->
                    CloudLinkedAccount(row.str("account_id").orEmpty(), row.str("linked_at"), row.bool("has_recovery_envelope") == true)
                },
            limit = body.long("limit")?.toInt(),
        )
    }

    private fun linkOf(
        body: JsonObject,
        fallbackAccount: String,
    ) = CloudAccountLink(body.str("account_id") ?: fallbackAccount, body.str("linked_at"), body.bool("already_linked") == true)

    // ---- Device-proven routing reads -----------------------------------------------------

    /** A fresh account-bound nonce to sign. Unauthenticated; nonces live about two minutes. */
    suspend fun getAccountRelaysChallenge(accountId: String): DiscoveryChallenge {
        val body = obj(request("GET", "/api/cloud/accounts/${enc(accountId)}/challenge", anonymous = true))
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
        val body =
            obj(
                request("GET", "/api/cloud/accounts/${enc(accountId)}/relays", anonymous = true, headers = RoutingProof.headers(challenge.nonce, credential)),
            )
        return rows(body["relays"]).map { row ->
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
        val body = obj(request("GET", "/api/cloud/namespaces/${enc(namespaceId)}/challenge", anonymous = true))
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
        val body = obj(request("GET", "/api/cloud/namespaces/${enc(namespaceId)}/admitters", anonymous = true, headers = headers))
        val nodes =
            rows(body["admitters"]).map { row ->
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
                    teeRole = teeRoleOf(row),
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

    /**
     * The node to send a signed join to, among those the invitation's [admitters] name
     * (its signed, mint-time snapshot); any admitting node when it names none. mero-js
     * `findAdmitter`.
     */
    suspend fun findAdmitter(
        namespaceId: String,
        admitters: List<String>? = null,
    ): CloudNamespaceNode? {
        val nodes = getNamespaceRouting(namespaceId).nodes
        val allowed = admitters?.takeIf { it.isNotEmpty() }?.map { it.lowercase() }?.toSet()
        return nodes.firstOrNull { n -> n.canAdmit && (allowed == null || n.account?.lowercase() in allowed) }
    }

    // ---- Transport -----------------------------------------------------------------------

    private suspend fun request(
        method: String,
        path: String,
        body: JsonElement? = null,
        anonymous: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val current = session
        if (!anonymous && current == null) {
            throw MeroStateException(
                "Not signed in to Calimero Cloud: call signInWithGoogle() or signInWithAccount(), or construct the client with a sessionToken",
            )
        }
        // Caller headers first, so a per-call header can never replace the session's.
        val all = headers.filterKeys { !it.equals("Authorization", ignoreCase = true) }.toMutableMap()
        if (!anonymous && current != null) all["Authorization"] = "Bearer ${current.sessionToken}"
        val res = http.execute(method, base + path, body?.toString(), all)
        adoptRollingRefresh(res.headers)
        return res.ensureSuccessful()
    }

    private fun adoptRollingRefresh(headers: Map<String, String>) {
        val token = header(headers, REFRESH_HEADER)?.takeIf { it.isNotEmpty() } ?: return
        val previous = session ?: return
        val expires = header(headers, REFRESH_EXPIRES_HEADER)?.toDoubleOrNull()
        val expiresAt = if (expires != null && expires.isFinite() && expires > 0) expires.toLong() else previous.expiresAt
        setSession(CloudLoginSession(token, expiresAt, previous.user))
    }

    private fun adoptSession(res: HttpResponse): CloudLoginSession {
        val body = obj(res)
        val user = body["user"] as? JsonObject
        val adopted =
            CloudLoginSession(
                sessionToken = body.str("session_token") ?: throw MeroStateException("the cloud answered a sign-in with no session_token"),
                expiresAt = body.long("expires_at") ?: 0,
                user = CloudUser(user?.str("email").orEmpty(), user?.str("name"), user?.str("picture")),
            )
        setSession(adopted)
        return adopted
    }

    private fun setSession(next: CloudLoginSession?) {
        session = next
        // A failing store or callback must not mask the call's own result.
        runCatching { sessionStore?.save(next) }
        runCatching { onSession?.invoke(next) }
    }

    private fun parse(res: HttpResponse): JsonElement = if (res.body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(res.body)

    private fun obj(res: HttpResponse): JsonObject = parse(res) as? JsonObject ?: JsonObject(emptyMap())

    companion object {
        /** The hosted cloud manager's API (not `cloud.calimero.network`, the web app). */
        const val DEFAULT_BASE_URL = "https://manager.cloud.calimero.network"

        /** The cloud's web app, where a person consents to a link ([accountLinkHandoff]'s portal). */
        const val DEFAULT_PORTAL_URL = "https://cloud.calimero.network"

        /** Response headers MDMA uses to hand back a rolling-refreshed session. */
        const val REFRESH_HEADER = "x-mdma-session-refresh"
        const val REFRESH_EXPIRES_HEADER = "x-mdma-session-expires"

        /** The `error` codes [enableHaAsAccount] maps to [AccountHaRefusedException]. */
        val ACCOUNT_HA_REFUSAL_CODES: Set<String> = HaRefusal.entries.map { it.code }.toSet()

        /**
         * The first relay a warrant can be spent at — [CloudRelay.canExecute], with a URL and
         * an executor account — or null. mero-js `pickExecutingRelay`, shared with
         * [connectCloud], which already holds the list.
         */
        fun pickExecutingRelay(relays: List<CloudRelay>): CloudRelay? =
            relays.firstOrNull { it.canExecute && it.relayUrl != null && it.executorAccount != null }

        /**
         * Where to send someone to consent to linking [accountId] (64 lowercase hex):
         * `{portal}/?link-account=<id>&callback-url=<callbackUrl carrying link_state>`.
         * Remembers the link in [pending] so [readAccountLinkCallback] can match the answer.
         * Needs no client and no session. mero-js `CloudClient.accountLinkHandoff`.
         *
         * @throws IllegalArgumentException for an id the portal would refuse, or a callback
         *   that is not an absolute URL (nothing is remembered then).
         */
        fun accountLinkHandoff(
            accountId: String,
            callbackUrl: String,
            portalUrl: String = DEFAULT_PORTAL_URL,
            pending: PendingCloudLinks = PendingCloudLinks.shared,
        ): AccountLinkHandoff {
            val callback = CloudLink.beginLinkState(accountId, callbackUrl, pending)
            val query = "link-account=${form(accountId)}&callback-url=${form(callback)}"
            return AccountLinkHandoff("${portalUrl.trimBase()}/?$query", accountId)
        }

        /**
         * Read the portal's answer out of a callback URL's **fragment** (a grant in the query is
         * ignored). Every field null for an ordinary deep link, so it is safe to call on each
         * one. A grant that does not answer a handoff this app began, for the account it
         * named, comes back as `error = "unexpected_callback"` — the callback can be forwarded.
         * The pending link is spent either way. mero-js `CloudClient.readAccountLinkCallback`.
         */
        fun readAccountLinkCallback(
            href: String,
            pending: PendingCloudLinks = PendingCloudLinks.shared,
        ): AccountLinkCallback {
            if (!UrlParts.isAbsolute(href)) return AccountLinkCallback()
            val params = UrlParts.formDecode(UrlParts.fragment(href))
            val state = UrlParts.formDecode(UrlParts.query(href))[CloudLink.STATE_PARAM]
            val grant = params["grant"]?.ifEmpty { null }
            val accountId = params["account"]?.ifEmpty { null }
            val error = params["error"]?.ifEmpty { null }
            if (error != null) pending.take(state)
            if (grant != null && pending.take(state) != accountId) return AccountLinkCallback(error = UNEXPECTED_CALLBACK)
            return AccountLinkCallback(grant, accountId, error)
        }

        /** [AccountLinkCallback.error] for a grant this app never asked for. */
        const val UNEXPECTED_CALLBACK = "unexpected_callback"

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
    }
}

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** `URLSearchParams` encoding: form-urlencoded, spaces as `+`. */
private fun form(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun header(
    headers: Map<String, String>,
    name: String,
): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

private fun teeRoleOf(row: JsonObject): String? = row.str("tee_role")?.ifEmpty { null }

private fun rows(element: JsonElement?): List<JsonObject> = (element as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private fun joinOf(raw: JsonElement?): CloudRelayJoin? {
    val join = raw as? JsonObject ?: return null
    val state = join.str("state") ?: return null
    return CloudRelayJoin(
        state = state,
        attempts = join.long("attempts")?.toInt() ?: 0,
        refusals =
            rows(join["refusals"]).map { r ->
                CloudRelayRefusal(textOf(r["peer"]), textOf(r["reason"]))
            },
        error = join.str("error"),
        reportedAt = join.str("reported_at"),
    )
}

// `String(x ?? '')`: a string as is, any other JSON value as its text.
private fun textOf(element: JsonElement?): String =
    when (element) {
        null, JsonNull -> ""
        is JsonPrimitive -> element.content
        else -> element.toString()
    }

/**
 * The addresses a fleet node can dial to ask this node for admission, from its
 * `GET /admin-api/network/status` ([com.calimero.mero.admin.AdminApi.getNetworkStatus]; the
 * `{data}` wrapper is accepted): each `externalAddrs` entry made to end in
 * `/p2p/<localPeerId>`, non-multiaddrs dropped, duplicates removed. Empty when the status
 * names no peer id or no external address. mero-js `admitterAddrsFromNetworkStatus`.
 */
fun admitterAddrsFromNetworkStatus(status: JsonElement?): List<String> {
    val outer = status as? JsonObject ?: return emptyList()
    val record = (outer["data"] as? JsonObject) ?: outer
    val peerId = record.str("localPeerId")?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val external = record["externalAddrs"] as? JsonArray ?: return emptyList()
    val suffix = "/p2p/$peerId"
    return external
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
        .filter { it.startsWith("/") }
        .map { if (it.endsWith(suffix)) it else it + suffix }
        .distinct()
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

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
