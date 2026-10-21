package com.calimero.mero.account

import com.calimero.mero.admin.ContextWithGroup
import com.calimero.mero.admin.Namespace
import com.calimero.mero.expiresAtFromJwt
import com.calimero.mero.http.trimBase
import com.calimero.mero.relay.RelayClient
import com.calimero.mero.relay.RelayLogin
import com.calimero.mero.relay.isLoopbackRelay
import com.calimero.mero.request.ProofCredential
import com.calimero.mero.transport.MeroTransportClient
import com.calimero.mero.transport.MeroTransportConfig
import com.calimero.mero.transport.RelayObserveConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * The client a delegated session talks through — port of mero-js `buildDelegatedClient` and
 * the multi-relay listings of `src/account/session.ts`.
 */

/**
 * Build the client [session] talks through: a relay-transport [MeroTransportClient] whose
 * `rpc.execute` takes the same arguments a node client's does, and whose `admin` and `events`
 * point at the relay **as the account**, so the caller-scoped reads answer this account's own
 * contexts and namespaces — mero-js `buildDelegatedClient`.
 *
 * - Every request to a hosted relay is sealed to its attested TD ([relayTransportClient]).
 * - Writes are warrants, with this device's persisted per-relay nonces ([persistedNonces]).
 * - Requests are authenticated by the device certificate (a request proof). With the relay's
 *   node key pinned ([CloudAccount.readPinnedRelayNodeKey]) the client also logs in to the
 *   relay — a hosted relay's forward-auth answers only a Bearer token — and **reads go through
 *   `/query` first**: a `409` marks the method a write (remembered per relay, context and
 *   method) and falls back to a warrant.
 * - On a hosted relay whose key is not known yet, events are inert: [MeroTransportClient.canSubscribe]
 *   is false and [MeroTransportClient.events] opens nothing, rather than reconnecting forever
 *   on a `401`. Rebuild the client once [CloudAccount.learnRelayNodeKey] has the key. A
 *   loopback relay (a dev rig) takes the proof as a node does.
 *
 * Returns `null` without a relay — deliberately no placeholder: a brand-new account is signed
 * in and a member of nothing, and a stand-in URL would make every write a refused warrant.
 * [contextId] is kept for callers; the client does not depend on the chosen context.
 */
fun CloudAccount.buildDelegatedClient(
    session: DelegatedSession,
    @Suppress("UNUSED_PARAMETER") contextId: String? = null,
): MeroTransportClient? {
    val relayUrl = session.relayUrl?.trimBase() ?: return null
    val nodeKey = readPinnedRelayNodeKey(relayUrl)
    val signer = deviceKeys.loadOrCreate().signer()
    // Every request sealed to the relay's attested TD: intents, admin reads, queries, the login
    // and events ([relayTransportClient]).
    val transport = relayTransportClient(relayUrl)
    val bearer = nodeKey?.let { relaySessionSource(session.credential, relayUrl, it) }
    val relay =
        RelayClient(
            relayUrl = relayUrl,
            authorAccount = session.account,
            authorProof = session.credential,
            signer = signer,
            // One sequence per relay, whatever context is chosen.
            nonces = persistedNonces(relayUrl),
            session = bearer,
            httpClient = transport,
        )
    return MeroTransportClient(
        MeroTransportConfig.Relay(
            relay = relay,
            session = bearer,
            observe = nodeKey?.let { RelayObserveConfig(it, audience = audience, clientName = clientName) },
            proof = ProofCredential(session.credential, signer = signer),
            httpClient = transport,
        ),
        readsByQuery = nodeKey != null,
        eventsDormant = nodeKey == null && !isLoopbackRelay(relayUrl),
    )
}

/**
 * A lazily minted `account_proof` session on [relayUrl] (mero-js `relaySession`): minted on
 * first use, reused while it has more than 30 s left (or its lifetime cannot be read), and
 * re-minted once it has lapsed. A failed login yields `null`, the request goes out without a
 * token, and the next call tries again.
 */
internal fun CloudAccount.relaySessionSource(
    credential: String,
    relayUrl: String,
    nodeKey: String,
): suspend () -> String? {
    val mutex = Mutex()
    var token: String? = null
    return {
        mutex.withLock {
            val current = token
            val exp = current?.let { expiresAtFromJwt(it, UNKNOWN_EXPIRY) }
            if (current != null && (exp == UNKNOWN_EXPIRY || exp!! - System.currentTimeMillis() > SESSION_MARGIN_MS)) {
                current
            } else {
                token =
                    try {
                        // Sealed: the token is the session, and the TLS ends outside the TD.
                        RelayLogin(relayKeyVerifier, clientName, audience, httpClient = relayTransportClient(relayUrl))
                            .login(relayUrl, nodeKey, credential, deviceKeys.loadOrCreate().signer())
                            .accessToken
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                token
            }
        }
    }
}

private const val UNKNOWN_EXPIRY = -1L
private const val SESSION_MARGIN_MS = 30_000L

/** A context one of the account's relays listed, tagged with that relay. */
data class DelegatedContext(
    val context: ContextWithGroup,
    val relayUrl: String,
)

/** A namespace one of the account's relays listed, tagged with that relay. */
data class DelegatedNamespace(
    val namespace: Namespace,
    val relayUrl: String,
)

/**
 * [session]'s contexts on every relay it knows ([knownRelays]), each tagged with the relay
 * that listed it — and that relay recorded as the one serving it ([relayForContext]). mero-js
 * `listDelegatedContexts`. Each relay answers only for itself and this caller, so the union is
 * exactly the account's contexts; a relay whose key cannot be learned, or that cannot be
 * reached, is skipped rather than fatal.
 */
suspend fun CloudAccount.listDelegatedContexts(session: DelegatedSession): List<DelegatedContext> {
    val out = mutableListOf<DelegatedContext>()
    val learned = mutableMapOf<String, String>()
    val relays = knownRelays(session)
    val failures =
        relays.mapNotNull { relayUrl ->
            listingOn(session, relayUrl) { client ->
                client.admin.getContexts().contexts.forEach { c ->
                    out += DelegatedContext(c, relayUrl)
                    learned[c.id] = relayUrl
                }
            }
        }
    if (relays.isNotEmpty() && failures.size == relays.size) throw RelaysUnavailableException(failures)
    updateRelayMap(session.account) { it.copy(contexts = it.contexts + learned) }
    return out
}

/**
 * [session]'s namespaces on every relay it knows, each tagged with the relay that listed it
 * and recorded as that namespace's relay when none was — mero-js `listDelegatedNamespaces`.
 * The first relay to list a namespace wins; same skipping rules as [listDelegatedContexts].
 */
suspend fun CloudAccount.listDelegatedNamespaces(session: DelegatedSession): List<DelegatedNamespace> {
    val out = mutableListOf<DelegatedNamespace>()
    val seen = mutableSetOf<String>()
    val relays = knownRelays(session)
    val failures =
        relays.mapNotNull { relayUrl ->
            listingOn(session, relayUrl) { client ->
                client.admin.listNamespaces().forEach { ns ->
                    if (seen.add(ns.namespaceId)) out += DelegatedNamespace(ns, relayUrl)
                }
            }
        }
    if (relays.isNotEmpty() && failures.size == relays.size) throw RelaysUnavailableException(failures)
    updateRelayMap(session.account) { map ->
        val added = out.filter { it.namespace.namespaceId !in map.namespaces }.associate { it.namespace.namespaceId to it.relayUrl }
        map.copy(namespaces = map.namespaces + added)
    }
    return out
}

/**
 * Run [read] against a delegated client on [relayUrl]. A relay whose key or listing fails is
 * skipped, and returned as a failure: one relay down leaves the others' answer standing, but
 * the callers throw when **every** relay failed, so a dead relay never reads as an empty account.
 */
private suspend fun CloudAccount.listingOn(
    session: DelegatedSession,
    relayUrl: String,
    read: suspend (MeroTransportClient) -> Unit,
): RelayListingFailure? {
    try {
        when (val attempt = attemptRelayNodeKey(relayUrl)) {
            is RelayNodeKeyAttempt.Learned -> Unit
            is RelayNodeKeyAttempt.Refused -> return RelayListingFailure(relayUrl, RelayListingFailure.Kind.REFUSED, attempt.error)
            is RelayNodeKeyAttempt.Unavailable ->
                return RelayListingFailure(relayUrl, RelayListingFailure.Kind.UNAVAILABLE, attempt.error)
        }
        val client =
            buildDelegatedClient(session.copy(relayUrl = relayUrl))
                ?: return RelayListingFailure(relayUrl, RelayListingFailure.Kind.UNAVAILABLE, IllegalStateException("no client"))
        try {
            read(client)
        } finally {
            client.close()
        }
        return null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return RelayListingFailure(relayUrl, RelayListingFailure.Kind.UNAVAILABLE, e)
    }
}

/** One relay a delegated listing could not read — mero-js `RelayListingFailure`. */
data class RelayListingFailure(
    val relayUrl: String,
    val kind: Kind,
    val error: Exception,
) {
    enum class Kind {
        /** The relay's attestation did not hold: asking again will not help. */
        REFUSED,

        /** The relay, its login or its listing did not answer. */
        UNAVAILABLE,
    }
}

/**
 * Every relay the account knows failed to list — mero-js `RelaysUnavailableError`. Thrown by
 * [listDelegatedContexts] and [listDelegatedNamespaces] instead of an empty list, which an app
 * would read as an account with nothing in it (and offer to create what already exists).
 */
class RelaysUnavailableException(
    val failures: List<RelayListingFailure>,
) : Exception(
        "none of this account's relays answered: " +
            failures.joinToString("; ") { "${it.relayUrl} (${it.kind.name.lowercase()}: ${it.error.message ?: it.error})" },
        failures.firstOrNull()?.error,
    ) {
    /** True when every relay refused its attestation, so retrying will not help. */
    val refused: Boolean get() = failures.isNotEmpty() && failures.all { it.kind == RelayListingFailure.Kind.REFUSED }
}
