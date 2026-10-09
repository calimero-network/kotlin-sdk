package com.calimero.mero.relay

import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.crypto.randomBytes
import com.calimero.mero.http.HttpResponse
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Talking to a hosted relay as an account that holds no node: every write is a warrant
 * the device signs and the relay spends.
 *
 * Port of mero-js `RelayClient` (`src/relay/relay-client.ts`) plus the read-by-query
 * routing of `src/account/session.ts`.
 *
 * | method | endpoint |
 * |---|---|
 * | [describe] | `GET /admin-api/contexts/{ctx}/intents` |
 * | [execute] | `POST /admin-api/contexts/{ctx}/intents` `{method, argsJson, warrant, authorProof}` |
 * | [query] | `POST /admin-api/contexts/{ctx}/query` `{method, argsJson}` (Bearer) |
 * | [call] | [query], falling back to [execute] when the node answers 409 (not a view) |
 * | [describeCreation] / [createContext] | `GET`/`POST /admin-api/groups/{g}/context-intents` |
 * | [describeGovernance] / [govern] | `GET`/`POST /admin-api/groups/{g}/governance-intents` |
 * | [foundNamespace] | `POST /admin-api/groups/{foundedNamespaceId}/governance-intents` (genesis) |
 * | [presenceIntent] | `POST /admin-api/contexts/{ctx}/presence-intents` |
 * | [getWarrantNonce] / [getWarrantNonceAsAuthor] | `GET /…/warrant-nonce/{key}` / `POST /…/warrant-nonce` |
 *
 * Intents carry their own authority (the warrant), so they go out without a Bearer token.
 * Reads by query and nonce lookups use [session] when given — the token minted by
 * [RelayLogin] — because a hosted relay's forward-auth answers only Bearer there.
 *
 * **Nonce recovery.** A warrant refused for its nonce (another install of this device
 * spent it, or local storage was lost) is answered by asking the relay where this
 * device's sequence stands, advancing [nonces] past it and signing once more.
 */
class RelayClient
    @Suppress("LongParameterList")
    internal constructor(
        relayUrl: String,
        /** The author's account, 64 hex. */
        val authorAccount: String,
        /** The device certificate (`AccountProof<DeviceCert>`, hex) every write carries. */
        val authorProof: String,
        private val signer: DeviceSigner,
        private val nonces: NonceSource,
        private val ttlSeconds: Long,
        private val executorAccount: String?,
        private val session: (suspend () -> String?)?,
        private val http: PlainJsonHttp,
        private val rateLimitRetries: Int,
    ) {
        @Suppress("LongParameterList")
        constructor(
            relayUrl: String,
            authorAccount: String,
            authorProof: String,
            signer: DeviceSigner,
            nonces: NonceSource,
            ttlSeconds: Long = DEFAULT_TTL_SECONDS,
            executorAccount: String? = null,
            session: (suspend () -> String?)? = null,
            httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
            rateLimitRetries: Int = 3,
        ) : this(
            relayUrl, authorAccount, authorProof, signer, nonces, ttlSeconds, executorAccount, session,
            PlainJsonHttp(httpClient), rateLimitRetries,
        )

        /** Convenience for a seed held in memory. */
        constructor(
            relayUrl: String,
            authorAccount: String,
            authorProof: String,
            deviceSecret: String,
            nonces: NonceSource,
        ) : this(relayUrl, authorAccount, authorProof, SeedSigner(deviceSecret), nonces)

        /** The relay's base URL, without a trailing slash. */
        val relayUrl: String = relayUrl.trimBase()

        private val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }

        /** What the node told us each `context|method` is, so the 409 probe is paid once. */
        private val methodKinds = ConcurrentHashMap<String, Boolean>()

        // ---- Data writes ---------------------------------------------------------------------

        /** Who would execute in [contextId], and what a warrant there must pin. */
        suspend fun describe(contextId: String): RelayDescription = data(request("GET", "/admin-api/contexts/${enc(contextId)}/intents"))

        /**
         * Run [method] in [contextId] under a freshly signed warrant. The relay executes it
         * as the account; the result carries the new root hash and the method's return value.
         */
        suspend fun execute(
            contextId: String,
            method: String,
            argsJson: JsonElement = JsonObject(emptyMap()),
        ): IntentResult =
            withNonceRecovery(contextId) {
                val described = describe(contextId)
                val executor = checkedExecutor(described.executorAccount, described.executorKey)
                val releaseBytecodeId = Hex.encode(Hex.decode(described.releaseBytecodeId.orEmpty(), "the relay's releaseBytecodeId", 32))
                val warrant =
                    Warrants.signWarrant(
                        WarrantInput(
                            context = contextId,
                            authorAccount = authorAccount,
                            executor = executor.first,
                            executorKey = executor.second,
                            releaseBytecodeId = releaseBytecodeId,
                            releaseVersion = described.releaseVersion.orEmpty(),
                            method = method,
                            argsJson = argsJson,
                            nonce = nonces.next(),
                            notAfter = notAfter(),
                            signer = signer,
                        ),
                    )
                val body =
                    buildJsonObject {
                        put("method", method)
                        put("argsJson", argsJson)
                        put("warrant", warrant)
                        put("authorProof", authorProof)
                    }
                val data = dataObject(request("POST", "/admin-api/contexts/${enc(contextId)}/intents", body))
                IntentResult(
                    rootHash = (data["rootHash"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    returns = data["returns"]?.takeUnless { it is JsonNull },
                )
            }

        // ---- Reads ---------------------------------------------------------------------------

        /**
         * Run a view method without a warrant: `POST /admin-api/contexts/{ctx}/query`. Reads
         * as the account the [session] token names. Throws [NotAViewException] on 409 — the
         * app's ABI does not declare [method] read-only.
         */
        suspend fun query(
            contextId: String,
            method: String,
            argsJson: JsonElement = JsonObject(emptyMap()),
        ): JsonElement? {
            val body =
                buildJsonObject {
                    put("method", method)
                    put("argsJson", argsJson)
                }
            val res = raw("POST", "/admin-api/contexts/${enc(contextId)}/query", body, authed = true)
            if (res.status == HTTP_CONFLICT) throw NotAViewException(contextId, method)
            res.ensureSuccessful()
            return dataObject(res.body)["returns"]?.takeUnless { it is JsonNull }
        }

        /**
         * Read or write, whichever [method] is: tried as a [query] first, and on a 409 (or any
         * failure of the query) sent as a warranted [execute], which reads too. What the node
         * said is remembered, so a write pays the probe once. Without a [session] this is
         * always [execute].
         */
        suspend fun call(
            contextId: String,
            method: String,
            argsJson: JsonElement = JsonObject(emptyMap()),
        ): JsonElement? {
            val key = "$contextId|$method"
            if (session != null && methodKinds[key] != true) {
                try {
                    return query(contextId, method, argsJson).also { methodKinds[key] = false }
                } catch (_: NotAViewException) {
                    methodKinds[key] = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Not an answer about the method: the warrant reads too.
                }
            }
            return execute(contextId, method, argsJson).returns
        }

        // ---- Context creation ----------------------------------------------------------------

        /** Who would create a context in [groupId] on the author's behalf. */
        suspend fun describeCreation(
            groupId: String,
            author: String? = authorAccount,
        ): CreationDescription {
            val query = author?.let { "?author=${enc(it)}" }.orEmpty()
            return data(request("GET", "/admin-api/groups/${enc(groupId)}/context-intents$query"))
        }

        /** Create a context in a group under a creation warrant. Checks standing before spending a nonce. */
        suspend fun createContext(input: CreateContextInput): CreatedContext {
            val described = describeCreation(input.groupId)
            if (!described.canCreateOnBehalf) {
                throw IntentRefusedException(
                    "the relay (${described.executorAccount}) has no standing to act for members of group ${described.groupId}; " +
                        "an admin must admit it as a relay or grant it CAN_AUTHOR_ON_BEHALF (checked before signing — no nonce was spent)",
                    retryable = false,
                    status = 403,
                )
            }
            if (described.authorMayCreate == false) {
                throw IntentRefusedException(
                    "the author ($authorAccount) may not create contexts in group ${described.groupId}; it needs CAN_CREATE_CONTEXT " +
                        "or admin (checked before signing — no nonce was spent)",
                    retryable = false,
                    status = 403,
                )
            }
            val executor = checkedExecutor(described.executorAccount, described.executorKey)
            val initArgs = input.initArgs ?: JsonObject(emptyMap())
            val signed =
                Warrants.signCreationWarrant(
                    CreationWarrantInput(
                        group = input.groupId,
                        authorAccount = authorAccount,
                        executor = executor.first,
                        executorKey = executor.second,
                        applicationId = input.applicationId,
                        initArgs = initArgs,
                        nonce = nonces.next(),
                        notAfter = notAfter(),
                        signer = signer,
                        seed = input.seed,
                        serviceName = input.serviceName,
                        name = input.name,
                    ),
                )
            val body =
                buildJsonObject {
                    put("warrant", signed.warrant)
                    put("authorProof", authorProof)
                    put("initArgs", initArgs)
                }
            return data(request("POST", "/admin-api/groups/${enc(input.groupId)}/context-intents", body))
        }

        // ---- Governance ----------------------------------------------------------------------

        /** Who would act on governance in [groupId] on the author's behalf. */
        suspend fun describeGovernance(groupId: String): GovernanceDescription =
            data(request("GET", "/admin-api/groups/${enc(groupId)}/governance-intents"))

        /** Apply a governance [op] to [groupId] under a governance warrant. */
        suspend fun govern(
            groupId: String,
            op: GovernanceOp,
        ): GovernResult {
            val described = describeGovernance(groupId)
            if (!described.canActOnBehalf) {
                throw IntentRefusedException(
                    "the relay (${described.executorAccount}) has no standing to act for members of group ${described.groupId}; " +
                        "an admin must admit it as a relay or grant it CAN_AUTHOR_ON_BEHALF (checked before signing, no nonce was spent)",
                    retryable = false,
                    status = 403,
                )
            }
            val executor = checkedExecutor(described.executorAccount, described.executorKey)
            val warrant =
                Warrants.signGovernanceWarrant(
                    GovernanceWarrantInput(
                        scope = groupId,
                        op = op,
                        authorAccount = authorAccount,
                        executor = executor.first,
                        executorKey = executor.second,
                        nonce = nonces.next(),
                        notAfter = notAfter(),
                        signer = signer,
                    ),
                )
            return postGovernance(groupId, op, warrant)
        }

        /**
         * Found a namespace through the relay, with the author as its founder, owner and
         * admin: how an account with no node gets a namespace at all. Port of mero-js
         * `RelayClient.foundNamespace`.
         *
         * The author signs the exact genesis ([GovernanceOps.namespaceCreatedOp], carrying its
         * own credential and the salt) under a root-plane governance warrant scoped to
         * [GovernanceOps.foundedNamespaceId]`(author, salt)`, so the relay can neither pick
         * another id nor found it for anyone else. The relay is seated as the founding relay,
         * so later [govern], [createContext] and [execute] calls in the namespace work through
         * it straight away; a TEE relay also admits itself as its first TEE ([FoundedNamespace.teeEnabled]).
         *
         * The executor cannot be learned from the relay first (the namespace does not exist
         * yet), so it comes from [FoundNamespaceInput.executor] only: the relay's account and
         * its attested or pinned node key, never a discovery answer.
         *
         * The application and the default capability mask, when asked for, follow as ordinary
         * [govern] calls on the new namespace. They are reported, not thrown: the namespace is
         * founded whatever happens to them.
         */
        suspend fun foundNamespace(input: FoundNamespaceInput): FoundedNamespace {
            val executor = checkedExecutor(input.executor.executorAccount, input.executor.executorKey)
            val salt = input.salt?.let { Hex.encode(Hex.decode(it, "salt", 32)) } ?: Hex.encode(randomBytes(32))
            val namespaceId = GovernanceOps.foundedNamespaceId(authorAccount, salt)
            val op = GovernanceOps.namespaceCreatedOp(authorAccount, authorProof, salt)
            // Encoded (and so validated) before anything is signed: a refused mask costs no nonce.
            val capabilitiesOp = input.defaultCapabilities?.let { GovernanceOps.defaultCapabilitiesSetOp(it) }
            val applicationOp =
                input.application?.let { GovernanceOps.targetApplicationSetOp(it.applicationId, it.packageName, it.version) }

            val warrant =
                Warrants.signGovernanceWarrant(
                    GovernanceWarrantInput(
                        scope = namespaceId,
                        op = op,
                        authorAccount = authorAccount,
                        executor = executor.first,
                        executorKey = executor.second,
                        nonce = nonces.next(),
                        notAfter = notAfter(),
                        signer = signer,
                    ),
                )
            val data = postGovernance(namespaceId, op, warrant)
            var founded = FoundedNamespace(namespaceId = data.groupId, salt = salt, teeEnabled = data.teeEnabled == true, teeError = data.teeError)
            // The application first: without it the namespace can hold no context at all.
            if (applicationOp != null) {
                founded =
                    runCatching { govern(founded.namespaceId, applicationOp) }.fold(
                        { founded.copy(applicationSet = true) },
                        { founded.copy(applicationSet = false, applicationError = it.message ?: it.toString()) },
                    )
            }
            if (capabilitiesOp != null) {
                founded =
                    runCatching { govern(founded.namespaceId, capabilitiesOp) }.fold(
                        { founded.copy(defaultCapabilitiesSet = true) },
                        { founded.copy(defaultCapabilitiesSet = false, defaultCapabilitiesError = it.message ?: it.toString()) },
                    )
            }
            return founded
        }

        private suspend fun postGovernance(
            groupId: String,
            op: GovernanceOp,
            warrant: String,
        ): GovernResult {
            val body =
                buildJsonObject {
                    put("warrant", warrant)
                    put("authorProof", authorProof)
                    put("op", Hex.encode(op.bytes))
                }
            return data(request("POST", "/admin-api/groups/${enc(groupId)}/governance-intents", body))
        }

        // ---- Presence ------------------------------------------------------------------------

        /** Post a signed presence statement (see core `crates/node/primitives/src/presence.rs`). */
        suspend fun presenceIntent(
            contextId: String,
            state: String?,
            seq: Long,
            sentAtMs: Long,
            signature: String,
        ) {
            val body =
                buildJsonObject {
                    put("state", state)
                    put("seq", seq)
                    put("sentAtMs", sentAtMs)
                    put("signature", signature)
                    put("authorProof", authorProof)
                }
            request("POST", "/admin-api/contexts/${enc(contextId)}/presence-intents", body)
        }

        // ---- Nonce discovery -----------------------------------------------------------------

        /** Where [authorDeviceKey] stands in its warrant-nonce sequence in [contextId]. */
        suspend fun getWarrantNonce(
            contextId: String,
            authorDeviceKey: String = signer.publicKey,
        ): RelayWarrantNonceState {
            val res = raw("GET", "/admin-api/contexts/${enc(contextId)}/warrant-nonce/${enc(authorDeviceKey)}", null, authed = true)
            return RelayWarrantNonceState.parse(res.ensureSuccessful().body)
        }

        /** Where this device stands, proven by the [authorProof] rather than named by key. */
        suspend fun getWarrantNonceAsAuthor(contextId: String): RelayWarrantNonceState {
            val body = buildJsonObject { put("authorProof", authorProof) }
            val res = raw("POST", "/admin-api/contexts/${enc(contextId)}/warrant-nonce", body, authed = true)
            return RelayWarrantNonceState.parse(res.ensureSuccessful().body)
        }

        /** Advance [nonces] past what the relay has seen from this device in [contextId]. */
        suspend fun recoverNonce(contextId: String) {
            when (val state = getWarrantNonceAsAuthor(contextId)) {
                is RelayWarrantNonceState.Open -> nonces.advanceTo(state.nextNonce)
                is RelayWarrantNonceState.Exhausted -> throw WarrantNonceExhaustedException(contextId)
            }
        }

        // ---- Plumbing ------------------------------------------------------------------------

        private suspend fun <T> withNonceRecovery(
            contextId: String,
            block: suspend () -> T,
        ): T =
            try {
                block()
            } catch (e: IntentRefusedException) {
                if (!e.retryable) throw e
                val recovered = runCatching { recoverNonce(contextId) }
                if (recovered.exceptionOrNull() is WarrantNonceExhaustedException) throw recovered.exceptionOrNull()!!
                block()
            }

        private fun checkedExecutor(
            account: String?,
            key: String?,
        ): Pair<String, String> {
            val executor = Hex.encode(Hex.decode(account.orEmpty(), "the relay's executorAccount", 32))
            val executorKey = Hex.encode(Hex.decode(key.orEmpty(), "the relay's executorKey", 32))
            val configured = executorAccount
            require(configured == null || configured.lowercase() == executor) {
                "configured executorAccount $configured is not the relay's account $executor; a warrant naming it would be unspendable"
            }
            return executor to executorKey
        }

        private fun notAfter(): ULong = (System.currentTimeMillis() / 1000 + ttlSeconds).toULong()

        /** A request that maps 400/403 to [IntentRefusedException] and retries 429 with backoff. */
        private suspend fun request(
            method: String,
            path: String,
            body: JsonElement? = null,
        ): String {
            var wait = INITIAL_BACKOFF_MS
            var attempt = 0
            while (true) {
                val res = raw(method, path, body, authed = false)
                if (res.status == HTTP_TOO_MANY && attempt < rateLimitRetries) {
                    delay(
                        res.headers.entries
                            .firstOrNull { it.key.equals("Retry-After", true) }
                            ?.value
                            ?.toLongOrNull()
                            ?.times(1000) ?: wait,
                    )
                    wait *= 2
                    attempt++
                    continue
                }
                if (res.status == 400 || res.status == 403) {
                    val reason = extractReason(res.body)
                    throw IntentRefusedException(reason, retryable = reason.contains("nonce", ignoreCase = true), status = res.status)
                }
                return res.ensureSuccessful().body
            }
        }

        private suspend fun raw(
            method: String,
            path: String,
            body: JsonElement?,
            authed: Boolean,
        ): HttpResponse {
            val headers = mutableMapOf<String, String>()
            if (authed) session?.invoke()?.let { headers["Authorization"] = "Bearer $it" }
            return http.execute(method, relayUrl + path, body?.let { json.encodeToString(JsonElement.serializer(), it) }, headers)
        }

        private fun dataObject(body: String): JsonObject {
            if (body.isBlank()) return JsonObject(emptyMap())
            val parsed = json.parseToJsonElement(body).jsonObject
            return (parsed["data"] as? JsonObject) ?: parsed
        }

        private inline fun <reified T> data(body: String): T = json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), dataObject(body))

        private fun extractReason(body: String): String {
            if (body.isBlank()) return "no reason given"
            return runCatching {
                val o = json.parseToJsonElement(body).jsonObject
                listOf("error", "message").firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull?.ifEmpty { null } }
            }.getOrNull() ?: body
        }

        private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private companion object {
            const val DEFAULT_TTL_SECONDS = 300L
            const val INITIAL_BACKOFF_MS = 250L
            const val HTTP_CONFLICT = 409
            const val HTTP_TOO_MANY = 429
        }
    }

/** `GET /admin-api/contexts/{ctx}/intents`. */
@Serializable
data class RelayDescription(
    val executorAccount: String? = null,
    val executorKey: String? = null,
    val canAuthorOnBehalf: Boolean = false,
    val groupId: String? = null,
    val releaseBytecodeId: String? = null,
    val releaseVersion: String? = null,
)

/** `GET /admin-api/groups/{g}/context-intents`. */
@Serializable
data class CreationDescription(
    val executorAccount: String? = null,
    val executorKey: String? = null,
    val groupId: String? = null,
    val canCreateOnBehalf: Boolean = false,
    /** Present when asked with `?author=`. */
    val authorMayCreate: Boolean? = null,
)

/** `GET /admin-api/groups/{g}/governance-intents`. */
@Serializable
data class GovernanceDescription(
    val executorAccount: String? = null,
    val executorKey: String? = null,
    val groupId: String? = null,
    val canActOnBehalf: Boolean = false,
)

/** What a warranted write returned. */
data class IntentResult(
    val rootHash: String,
    val returns: JsonElement?,
)

/** Input to [RelayClient.createContext]. */
data class CreateContextInput(
    val groupId: String,
    val applicationId: String,
    val initArgs: JsonElement? = null,
    val serviceName: String? = null,
    val name: String? = null,
    val seed: String? = null,
)

/** `POST /admin-api/groups/{g}/context-intents` → `data`. */
@Serializable
data class CreatedContext(
    val contextId: String,
    val groupId: String,
    val memberPublicKey: String = "",
)

/** `POST /admin-api/groups/{g}/governance-intents` → `data`. */
@Serializable
data class GovernResult(
    val groupId: String,
    val teeEnabled: Boolean? = null,
    val teeError: String? = null,
)

/** A relay's account and the one key of it a warrant may name. */
data class RelayExecutor(
    val executorAccount: String,
    val executorKey: String,
)

/** The application a founded namespace runs: its id, and the registry `package@version` the relay resolves. */
data class ApplicationTarget(
    val applicationId: String,
    val packageName: String,
    val version: String,
)

/** Input to [RelayClient.foundNamespace]. */
data class FoundNamespaceInput(
    /** The relay's account and node key (pinned or attested). Never a discovery answer. */
    val executor: RelayExecutor,
    /** 32 bytes hex; random when null (one account founds many namespaces by varying it). */
    val salt: String? = null,
    /**
     * The namespace root's default capability mask, set right after founding. A namespace
     * is founded with core's minimal default (`CAN_JOIN_OPEN_SUBGROUPS` only).
     * [GovernanceOps.CAN_AUTHOR_ON_BEHALF] is refused before anything is signed.
     */
    val defaultCapabilities: Long? = null,
    /** The application the namespace runs; without one no context can be created in it. */
    val application: ApplicationTarget? = null,
)

/** A namespace founded through a relay. */
data class FoundedNamespace(
    /** `foundedNamespaceId(author, salt)`, hex. */
    val namespaceId: String,
    /** The salt it was derived with. `(founder, salt)` reproduces the id: keep it to prove founding (HA). */
    val salt: String,
    /** Whether the relay admitted itself as the namespace's first TEE. */
    val teeEnabled: Boolean,
    val teeError: String? = null,
    /** Present only when a mask was asked for. */
    val defaultCapabilitiesSet: Boolean? = null,
    val defaultCapabilitiesError: String? = null,
    /** Present only when an application was asked for. */
    val applicationSet: Boolean? = null,
    val applicationError: String? = null,
)

/** The relay refused an intent (400/403). [retryable] when the reason names the nonce. */
class IntentRefusedException(
    val reason: String,
    val retryable: Boolean,
    val status: Int,
) : Exception("relay refused the intent (HTTP $status): $reason")

/** `/query` answered 409: [method] is not a view in the app's ABI. */
class NotAViewException(
    val contextId: String,
    val method: String,
) : Exception("$method is not a view method in context $contextId")
