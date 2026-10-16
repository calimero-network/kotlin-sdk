package com.calimero.mero.account

import com.calimero.mero.cloud.AccountHaRefusedException
import com.calimero.mero.crypto.Hex
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import com.calimero.mero.relay.ApplicationTarget
import com.calimero.mero.relay.CreateContextInput
import com.calimero.mero.relay.FoundNamespaceInput
import com.calimero.mero.relay.FoundedNamespace
import com.calimero.mero.relay.GovernResult
import com.calimero.mero.relay.GovernanceMemberRole
import com.calimero.mero.relay.GovernanceOp
import com.calimero.mero.relay.GovernanceOps
import com.calimero.mero.relay.NonceSource
import com.calimero.mero.relay.RelayClient
import com.calimero.mero.relay.RelayExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.net.URLEncoder

/*
 * An account's governance through the relay that serves its namespace — port of mero-js
 * `src/account/create-context.ts` and `src/account/govern.ts`: create a context, the group ops
 * a private context (a DM) needs, any delegable op on a group, and founding a namespace.
 *
 * A thin layer over [RelayClient.createContext] / [RelayClient.govern], adding what only the
 * account layer knows: which relay serves the namespace ([RelayMap]), which namespace a
 * subgroup is in, and the nonce bookkeeping across restarts. Nodes check the AUTHOR's rights
 * (CAN_CREATE_CONTEXT, CAN_CREATE_SUBGROUP, MANAGE_MEMBERS…), never the relay's.
 */

/**
 * What an account asks for when it creates a context: the fields of a node's
 * `CreateContextRequest` with a place in the creation warrant, plus which namespace to find the
 * relay by — mero-js `CreateDelegatedContextRequest`. No `identitySecret`: the account IS the
 * member identity here (`init` runs as the author's account).
 */
data class CreateDelegatedContextRequest(
    val namespaceId: String,
    val applicationId: String,
    /** The group to create it in; the namespace itself when null. */
    val groupId: String? = null,
    /** Which service of a multi-service bundle; signed into the warrant, so the relay runs that service's `init`. */
    val serviceName: String? = null,
    /** The seed the context id is derived from, hex (32 bytes); random when null. */
    val contextSeed: String? = null,
    /** `init`'s arguments as the JSON bytes a node request carries; `{}` when null or empty. */
    val initializationParams: List<Int>? = null,
    val name: String? = null,
)

/** A context created through a relay, and the group it is in. */
data class CreatedDelegatedContext(
    val contextId: String,
    val groupId: String,
)

/** What an app can tell a person whose account the cloud cannot place. mero-js `HA_ACCOUNT_NOT_LINKED_MESSAGE`. */
const val HA_ACCOUNT_NOT_LINKED_MESSAGE =
    "link this account to your cloud user in the wallet so the cloud can host this namespace on its fleet"

/**
 * What to tell a person for each refusal the cloud names, keyed by its code — mero-js
 * `HA_REFUSAL_MESSAGES`. Every one leaves the namespace founded; only hosting was refused.
 */
val HA_REFUSAL_MESSAGES: Map<String, String> =
    mapOf(
        AccountHaRefusedException.ACCOUNT_NOT_LINKED to HA_ACCOUNT_NOT_LINKED_MESSAGE,
        AccountHaRefusedException.ACCOUNT_LINKED_TO_SEVERAL_USERS to
            "this account is linked to more than one cloud user, so the cloud cannot tell whose plan hosts the namespace: " +
            "unlink it from all but one in the wallet",
        AccountHaRefusedException.HA_REQUEST_PENDING to
            "another namespace of this account is still waiting to be hosted; the cloud hosts one new namespace at a time " +
            "without a cloud sign-in. Sign in to the cloud and turn off hosting for the waiting namespace, or wait until it is hosted",
        AccountHaRefusedException.UNKNOWN_RELAY to "the cloud does not run the relay this namespace was founded on, so it cannot host it",
        AccountHaRefusedException.RELAY_NOT_DIALABLE to
            "the relay this namespace was founded on has not reported its address to the cloud yet; try enabling hosting again shortly",
    )

private const val GOVERNANCE_NONCE_PREFIX = "calimero.governance-nonce."
private val governanceNonceLock = Mutex()

/**
 * A governance warrant's nonce for one (relay, group) — mero-js `governanceNonce`: spent in a
 * sliding per-(group, device) window, so it must never go backwards. A stored counter, floored
 * at the clock (ms) so a cleared store resumes above anything spent before.
 */
internal fun CloudAccount.governanceNonce(
    relayUrl: String,
    group: String,
): NonceSource =
    object : NonceSource {
        private val key = "$GOVERNANCE_NONCE_PREFIX${relayUrl.trimBase()}.$group"

        override suspend fun next(): ULong =
            governanceNonceLock.withLock {
                val last = runCatching { store.get(key)?.toULongOrNull() }.getOrNull() ?: 0u
                val next = maxOf(last + 1u, System.currentTimeMillis().toULong())
                runCatching { store.put(key, next.toString()) }
                next
            }

        override suspend fun advanceTo(floor: ULong) =
            governanceNonceLock.withLock {
                val last = runCatching { store.get(key)?.toULongOrNull() }.getOrNull() ?: 0u
                if (floor > 0u && floor - 1u > last) runCatching { store.put(key, (floor - 1u).toString()) }
                Unit
            }
    }

/** The relay that serves [namespaceId] for [session]'s account: learned when it joined or founded it, else the session's. */
internal fun CloudAccount.relayFor(
    session: DelegatedSession,
    namespaceId: String,
): String =
    (readRelayMap(session.account).namespaces[namespaceId] ?: session.relayUrl)?.trimBase()
        ?: throw MeroStateException("no relay is known for this namespace, so there is nowhere to send this")

/** A warrant client for [session] on [relayUrl], drawing nonces from [nonces]. */
internal fun CloudAccount.delegatedRelayClient(
    session: DelegatedSession,
    relayUrl: String,
    nonces: NonceSource,
): RelayClient =
    RelayClient(
        relayUrl = relayUrl,
        authorAccount = session.account,
        authorProof = session.credential,
        signer = deviceKeys.loadOrCreate().signer(),
        nonces = nonces,
        httpClient = relayTransportClient(relayUrl),
    )

private val initJson = Json { ignoreUnknownKeys = true }

/** `init`'s arguments as the JSON the route takes, from the bytes a node request carries. */
private fun initArgsOf(params: List<Int>?): JsonElement {
    if (params.isNullOrEmpty()) return JsonObject(emptyMap())
    return initJson.parseToJsonElement(ByteArray(params.size) { params[it].toByte() }.decodeToString())
}

/**
 * Create a context through the relay that serves [request]'s namespace — mero-js
 * `createDelegatedContext`. The creation warrant's nonce is spent in the NEW context's
 * per-device window (empty until now), so it is `0`; the context's writes then start above it
 * ([markContextNonceSpent]). The relay is remembered as the one serving the new context.
 */
suspend fun CloudAccount.createDelegatedContext(
    session: DelegatedSession,
    request: CreateDelegatedContextRequest,
): CreatedDelegatedContext {
    val relayUrl = relayFor(session, request.namespaceId)
    val creationNonce: ULong = 0u
    val fixed =
        object : NonceSource {
            override suspend fun next(): ULong = creationNonce

            override suspend fun advanceTo(floor: ULong) = Unit
        }
    val groupId = request.groupId ?: request.namespaceId
    val created =
        delegatedRelayClient(session, relayUrl, fixed).createContext(
            CreateContextInput(
                groupId = groupId,
                applicationId = request.applicationId,
                initArgs = initArgsOf(request.initializationParams),
                serviceName = request.serviceName,
                name = request.name,
                seed = request.contextSeed,
            ),
        )
    markContextNonceSpent(relayUrl, created.contextId, creationNonce)
    rememberRelay(session.account, relayUrl, namespaceId = request.namespaceId, contextId = created.contextId)
    return CreatedDelegatedContext(created.contextId, created.groupId.ifEmpty { groupId })
}

/** Publish one delegable governance [op] on [group] through [namespaceId]'s relay. mero-js `delegatedGovernance`. */
suspend fun CloudAccount.delegatedGovernance(
    session: DelegatedSession,
    namespaceId: String,
    group: String,
    op: GovernanceOp,
): GovernResult {
    val relayUrl = relayFor(session, namespaceId)
    return delegatedRelayClient(session, relayUrl, governanceNonce(relayUrl, group)).govern(group, op)
}

/**
 * A context only the author and [members] are in (a DM, a small room) — mero-js
 * `createDelegatedPrivateContext`: a restricted subgroup of the namespace (a root op, posted to
 * the namespace), each member added to it directly (they are in the namespace already, so
 * nobody is invited), and the context created inside it.
 */
suspend fun CloudAccount.createDelegatedPrivateContext(
    session: DelegatedSession,
    request: CreateDelegatedContextRequest,
    members: List<String>,
): CreatedDelegatedContext {
    // The id is derived from a fresh salt; a node refuses any other.
    val creation = GovernanceOps.subgroupCreation(request.namespaceId, restricted = true, admin = session.account)
    val groupId = delegatedGovernance(session, request.namespaceId, request.namespaceId, creation.op).groupId.ifEmpty { creation.groupId }
    rememberGroupNamespace(session.account, groupId, request.namespaceId)
    for (member in members) {
        delegatedGovernance(session, request.namespaceId, groupId, GovernanceOps.memberAddedOp(member, GovernanceMemberRole.Member))
    }
    return createDelegatedContext(session, request.copy(groupId = groupId))
}

private fun CloudAccount.requireNamespace(
    session: DelegatedSession,
    groupId: String,
): String =
    namespaceOfGroup(session, groupId) ?: throw MeroStateException(
        "the namespace of group $groupId is not known to this account: it can manage only groups in namespaces it joined " +
            "or subgroups it created",
    )

/** A group op (member, role, capabilities, metadata, visibility, detach) on [groupId]. mero-js `governGroup`. */
suspend fun CloudAccount.governGroup(
    session: DelegatedSession,
    groupId: String,
    op: GovernanceOp,
): GovernResult = delegatedGovernance(session, requireNamespace(session, groupId), groupId, op)

/** A root op (create, delete, move a subgroup; join an open one), posted to the namespace [groupId] belongs to. mero-js `governRoot`. */
suspend fun CloudAccount.governRoot(
    session: DelegatedSession,
    groupId: String,
    op: GovernanceOp,
): GovernResult {
    val namespaceId = requireNamespace(session, groupId)
    return delegatedGovernance(session, namespaceId, namespaceId, op)
}

/**
 * Found a namespace as the account, through the first relay it knows ([knownRelays]) —
 * mero-js `foundDelegatedNamespace`. The account becomes founder, owner and admin; the relay
 * is seated in it (a TEE relay admits itself as its first TEE), so contexts can be created in
 * it straight away; the relay is remembered as the namespace's. Then, best-effort and only
 * when the relay attested the founding, the cloud is asked to host it ([enableHa]).
 *
 * The relay's executor cannot be asked about a namespace that does not exist yet: its account
 * is the session's when the session is on that relay (the cloud names it), else learned from a
 * namespace the account already uses there ([knownNamespaceId], or the relay map) through
 * discovery, which may name the account but never the key. The key is always the relay's node
 * key **attested now** — a pin does not stand in for it, because the relay founded through
 * holds the namespace's keys.
 *
 * @throws MeroStateException when no relay or executor is known, the attestation did not
 *   answer or was refused, or the namespace was founded but could not be given its [application].
 */
@Suppress("LongParameterList")
suspend fun CloudAccount.foundDelegatedNamespace(
    session: DelegatedSession,
    defaultCapabilities: Long? = null,
    application: ApplicationTarget? = null,
    knownNamespaceId: String? = null,
    enableHa: Boolean = true,
): FoundedDelegatedNamespace {
    val relayUrl =
        knownRelays(session).firstOrNull()
            ?: throw MeroStateException("no relay is known for this account, so there is nowhere to found a namespace")
    val executor = foundingExecutor(session, relayUrl, knownNamespaceId)
    // The founding warrant and its follow-ups are spent in the NEW namespace's window, which
    // is empty: any rising run will do, floored at the clock like every governance nonce.
    val run = Mutex()
    var next = System.currentTimeMillis().toULong()
    val rising =
        object : NonceSource {
            override suspend fun next(): ULong = run.withLock { next++ }

            override suspend fun advanceTo(floor: ULong) = run.withLock { if (floor > next) next = floor }
        }
    val founded =
        delegatedRelayClient(session, relayUrl, rising)
            .foundNamespace(FoundNamespaceInput(executor, defaultCapabilities = defaultCapabilities, application = application))
    runCatching { store.put("$GOVERNANCE_NONCE_PREFIX$relayUrl.${founded.namespaceId}", next.toString()) }
    rememberRelay(session.account, relayUrl, namespaceId = founded.namespaceId)
    if (application != null && founded.applicationSet != true) {
        // Founded, but no context can be created in it yet; the same choice can be retried.
        throw MeroStateException(
            "founded ${founded.namespaceId} but could not give it its application: ${founded.applicationError ?: "unknown reason"}",
        )
    }
    // HA's fleet node is admitted by the founding relay, which can vouch for it only once it
    // attested itself as the namespace's first TEE; without that the request would hold the
    // account's one pending slot in the cloud for good.
    val (haEnabled, haError) =
        when {
            !enableHa -> false to null
            founded.teeEnabled -> enableHaBestEffort(session, founded, relayUrl)
            else ->
                false to
                    "the relay did not attest the founding, so no fleet node could be admitted for HA" +
                    (founded.teeError?.let { ": $it" } ?: "")
        }
    return FoundedDelegatedNamespace(founded, haEnabled, haError)
}

private suspend fun CloudAccount.foundingExecutor(
    session: DelegatedSession,
    relayUrl: String,
    knownNamespaceId: String?,
): RelayExecutor {
    val named = session.executorAccount?.takeIf { session.relayUrl?.trimBase() == relayUrl }
    if (named != null) return RelayExecutor(named, attestedNodeKey(relayUrl))
    val known =
        knownNamespaceId
            ?: readRelayMap(session.account)
                .namespaces.entries
                .firstOrNull { it.value.trimBase() == relayUrl }
                ?.key
            ?: throw MeroStateException(
                "the executor account of this relay is not known: join a namespace on it first, or connect with the relay's " +
                    "executor account (the cloud shows it beside the relay)",
            )
    val executorKey = attestedNodeKey(relayUrl)
    // Discovery is unauthenticated: it may name the account, never the key.
    val described = delegatedRelayClient(session, relayUrl, governanceNonce(relayUrl, known)).describeGovernance(known)
    if (described.executorKey?.lowercase() != executorKey) {
        throw MeroStateException(
            "the relay's discovery names signing key ${described.executorKey}, not the relay's node key $executorKey: refusing to found through it",
        )
    }
    return RelayExecutor(described.executorAccount.orEmpty(), executorKey)
}

/** The relay's node key, attested now (never the pin alone); any failed attestation throws. */
private suspend fun CloudAccount.attestedNodeKey(relayUrl: String): String =
    when (val attempt = attemptRelayNodeKey(relayUrl, fresh = true)) {
        is RelayNodeKeyAttempt.Learned -> attempt.nodeKey
        is RelayNodeKeyAttempt.Unavailable ->
            throw MeroStateException("the relay's signing key could not be learned: its attestation did not answer, try again")
        is RelayNodeKeyAttempt.Refused ->
            throw MeroStateException(
                "the relay's attestation was refused, so founding through it is not possible: ${attempt.error.message}",
            )
    }

/**
 * Ask the cloud to host a namespace this account just founded, with no cloud session: the
 * account proves it is the founder (its id and the salt reproduce the namespace id). Best-effort:
 * a refusal is reported in [HA_REFUSAL_MESSAGES]' words, never thrown.
 */
private suspend fun CloudAccount.enableHaBestEffort(
    session: DelegatedSession,
    founded: FoundedNamespace,
    relayUrl: String,
): Pair<Boolean, String?> =
    try {
        cloud(session).enableHaAsAccount(
            namespaceId = founded.namespaceId,
            salt = founded.salt,
            accountId = session.account,
            credential = session.credential,
            relayUrl = relayUrl,
        )
        true to null
    } catch (e: AccountHaRefusedException) {
        val message = HA_REFUSAL_MESSAGES[e.code] ?: e.advice
        val blocking = if (e.code == AccountHaRefusedException.HA_REQUEST_PENDING) blockingNamespace(e.bodyText) else null
        false to (blocking?.let { "$message (waiting: $it)" } ?: message)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false to (e.message ?: e.toString())
    }

private fun blockingNamespace(body: String): String? =
    runCatching {
        val o = Json.parseToJsonElement(body).jsonObject
        val id = ((o["detail"] as? JsonObject)?.get("namespace_id") ?: o["namespace_id"])?.jsonPrimitive?.contentOrNull
        id?.lowercase()?.takeIf { Hex.isHex32(it) }
    }.getOrNull()

/**
 * The latest version of [packageName] the registry publishes: the newest that is not yanked
 * ([ApplicationIds.selectLatestBundle]) — mero-js `latestPublishedVersion`. Used when an app
 * names its package but not a version.
 */
suspend fun latestPublishedVersion(
    registryUrl: String,
    packageName: String,
    httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
): String {
    val url = "${registryUrl.trimBase()}/api/v2/bundles?package=${URLEncoder.encode(packageName, "UTF-8")}"
    val res = PlainJsonHttp(httpClient).execute("GET", url)
    if (!res.isSuccessful) throw MeroStateException("the registry has no $packageName (HTTP ${res.status})")
    val bundles = ApplicationIds.parseBundles(res.body)
    return ApplicationIds.selectLatestBundle(bundles)?.appVersion ?: throw MeroStateException("the registry lists no version of $packageName")
}
