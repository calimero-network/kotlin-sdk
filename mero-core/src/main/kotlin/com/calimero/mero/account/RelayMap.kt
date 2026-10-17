package com.calimero.mero.account

import com.calimero.mero.http.trimBase
import com.calimero.mero.relay.PersistedNonceSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/*
 * An account's namespaces may be served by different relays — port of the relay map in mero-js
 * `src/account/session.ts` and the group→namespace memory of `src/account/govern.ts`.
 *
 * A relay admits the account to a namespace and then serves that namespace's contexts, so the
 * client keeps, per account, which relay serves which namespace and context:
 *
 *   namespaces: { <namespace id>: <relay url> }  — learned when a join (or a founding) succeeds
 *   contexts:   { <context id>:   <relay url> }  — learned from each relay's caller-scoped listing
 *
 * Kept in the account's [SecureStore] (mero-js uses localStorage, beside the pinned node keys):
 * URLs and ids only, under the same keys. Losing it costs a re-listing, not access.
 */

/** Which relay serves which of an account's namespaces and contexts. mero-js `RelayMap`. */
@Serializable
data class RelayMap(
    val namespaces: Map<String, String> = emptyMap(),
    val contexts: Map<String, String> = emptyMap(),
)

private val mapJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
private val groupNamespacesSerializer = MapSerializer(String.serializer(), String.serializer())

internal const val RELAY_MAP_PREFIX = "calimero.delegated.relays."
internal const val GROUP_NAMESPACE_PREFIX = "calimero.delegated.group-namespace."

/** The relay map of [account] (empty when none is stored, or it does not decode). mero-js `readRelayMap`. */
fun CloudAccount.readRelayMap(account: String): RelayMap =
    runCatching { store.get(RELAY_MAP_PREFIX + account)?.let { mapJson.decodeFromString(RelayMap.serializer(), it) } }
        .getOrNull() ?: RelayMap()

internal fun CloudAccount.writeRelayMap(
    account: String,
    map: RelayMap,
) {
    // Unpersisted is not fatal: this process still routes from what it lists.
    runCatching { store.put(RELAY_MAP_PREFIX + account, mapJson.encodeToString(RelayMap.serializer(), map)) }
}

/** Read-modify-write [account]'s map under this account's lock. */
internal fun CloudAccount.updateRelayMap(
    account: String,
    change: (RelayMap) -> RelayMap,
) = synchronized(relayMapLock) { writeRelayMap(account, change(readRelayMap(account))) }

/**
 * Record the relay that admitted [account] to a namespace and, when known, the context it came
 * for. mero-js `rememberRelay`.
 */
fun CloudAccount.rememberRelay(
    account: String,
    relayUrl: String,
    namespaceId: String? = null,
    contextId: String? = null,
) {
    val url = relayUrl.trimBase()
    updateRelayMap(account) { map ->
        RelayMap(
            namespaces = if (namespaceId != null) map.namespaces + (namespaceId to url) else map.namespaces,
            contexts = if (contextId != null) map.contexts + (contextId to url) else map.contexts,
        )
    }
}

/** The relay that serves [contextId], if [account] has learned it. mero-js `relayForContext`. */
fun CloudAccount.relayForContext(
    account: String,
    contextId: String,
): String? = readRelayMap(account).contexts[contextId]

/** Every relay [session]'s account is known to use: the session's own first, then each in its map. mero-js `knownRelays`. */
fun CloudAccount.knownRelays(session: DelegatedSession): List<String> {
    val map = readRelayMap(session.account)
    return (listOfNotNull(session.relayUrl) + map.namespaces.values + map.contexts.values)
        .filter { it.isNotEmpty() }
        .map { it.trimBase() }
        .distinct()
}

/**
 * A session moving to [next], keeping the relay's executor account [prev] knew when both are
 * on the same relay — mero-js `carryExecutorAccount`. A join hands back a session built from
 * the admitting node alone, with no executor; on the relay the account was already on, the old
 * executor is still right, and on another relay it is the wrong account, so it is left behind.
 */
fun carryExecutorAccount(
    prev: DelegatedSession?,
    next: DelegatedSession,
): DelegatedSession {
    val prevExecutor = prev?.executorAccount
    val prevRelay = prev?.relayUrl
    val nextRelay = next.relayUrl
    if (next.executorAccount != null || prevExecutor == null || prevRelay == null || nextRelay == null) return next
    if (prevRelay.trimBase() != nextRelay.trimBase()) return next
    return next.copy(executorAccount = prevExecutor)
}

/**
 * Record that [groupId] is a subgroup of [namespaceId], for [account] — mero-js
 * `rememberGroupNamespace`. Held in memory too, so a store that cannot be written does not
 * turn a known subgroup back into an unknown one.
 */
fun CloudAccount.rememberGroupNamespace(
    account: String,
    groupId: String,
    namespaceId: String,
) {
    learnedGroupNamespaces.getOrPut(account) { ConcurrentHashMap() }[groupId] = namespaceId
    synchronized(relayMapLock) {
        runCatching {
            val all = storedGroupNamespaces(account) + (groupId to namespaceId)
            store.put(GROUP_NAMESPACE_PREFIX + account, mapJson.encodeToString(groupNamespacesSerializer, all))
        }
    }
}

/**
 * The namespace [groupId] belongs to, for [session]'s account: itself when it is a namespace
 * the account is in (its relay map names it), else what was remembered — mero-js `namespaceOfGroup`.
 */
fun CloudAccount.namespaceOfGroup(
    session: DelegatedSession,
    groupId: String,
): String? {
    if (readRelayMap(session.account).namespaces.containsKey(groupId)) return groupId
    return learnedGroupNamespaces[session.account]?.get(groupId) ?: storedGroupNamespaces(session.account)[groupId]
}

private fun CloudAccount.storedGroupNamespaces(account: String): Map<String, String> =
    runCatching { store.get(GROUP_NAMESPACE_PREFIX + account)?.let { mapJson.decodeFromString(groupNamespacesSerializer, it) } }
        .getOrNull()
        .orEmpty()

/**
 * Record that [nonce] is spent for this device on [relayUrl], so later intents start above it
 * — mero-js `markContextNonceSpent`. One rising sequence per relay ([PersistedNonceSource.forRelay])
 * covers a context's creation nonce, spent in the new context's own window, like any other.
 */
suspend fun CloudAccount.markContextNonceSpent(
    relayUrl: String,
    @Suppress("UNUSED_PARAMETER") contextId: String,
    nonce: ULong,
) {
    PersistedNonceSource.forRelay(store, relayUrl).advanceTo(nonce + 1u)
}

/** The per-relay persisted warrant-nonce sequence a delegated client on [relayUrl] draws from (mero-js `persistedNonces`). */
fun CloudAccount.persistedNonces(relayUrl: String): PersistedNonceSource = PersistedNonceSource.forRelay(store, relayUrl)
