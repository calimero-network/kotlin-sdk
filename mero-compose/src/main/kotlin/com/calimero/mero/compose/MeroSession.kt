package com.calimero.mero.compose

import com.calimero.mero.admin.AdminSurface
import com.calimero.mero.admin.ContextWithGroup
import com.calimero.mero.admin.Namespace
import com.calimero.mero.ephemeral.Codec
import com.calimero.mero.ephemeral.EphemeralClient
import com.calimero.mero.ephemeral.EphemeralEntry
import com.calimero.mero.presence.RelayPresenceClient
import com.calimero.mero.sse.NodeEvent
import com.calimero.mero.sse.SseConnection
import com.calimero.mero.transport.ExecuteTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The current connection as the state holders see it — the non-UI half of mero-react's
 * `useMero()` value. [MeroClient.session] publishes a new one whenever the connection changes
 * (sign-in, sign-out, a relay learned, a node switched), and every state holder re-derives its
 * read from it, exactly as a mero-react hook re-runs on a new `admin`.
 *
 * Each surface is an interface so a holder is testable without a node.
 */
data class MeroSession(
    /**
     * The admin API to write against, whatever the session: a node's own admin
     * ([com.calimero.mero.account.createNodeAdmin]) or the account admin
     * ([com.calimero.mero.account.createAccountAdmin]). Null until connected.
     */
    val admin: AdminSurface? = null,
    /**
     * The connected client's own admin API — not the session's. On an account the session
     * [admin] answers for the account, while this answers for the relay's node: what
     * presence self-filtering needs (the key the node stamps as `author`).
     */
    val rawAdmin: AdminSurface? = null,
    /** Contract calls: a node's JSON-RPC, or warrants through the relay. */
    val rpc: ExecuteTransport? = null,
    /** The live event stream, or null when this connection cannot observe (yet). */
    val events: MeroEvents? = null,
    /** Ephemeral presence: a node's `set_ephemeral`, or device-signed presence through the relay. */
    val presence: PresenceSurface? = null,
    /** An account on a relay rather than a node login (mero-react `isDelegated`). */
    val isDelegated: Boolean = false,
    /** The account-only reads and writes that span relays; null on a node. */
    val delegated: DelegatedOps? = null,
    /** What this connection may do. */
    val can: MeroCapabilities = MeroCapabilities.NONE,
) {
    /** Signed in, as far as the holders can tell: there is an admin to ask. */
    val isConnected: Boolean get() = admin != null
}

/**
 * A live event stream, as the state holders and [MeroClient]'s online check use it.
 * [SseConnection] is the real one ([asMeroEvents]).
 */
interface MeroEvents {
    /** Every event of every subscribed context and group. */
    val events: Flow<NodeEvent>

    /** A session id each time the stream (re)connects. */
    val connects: Flow<String>

    /** Each failure the stream recovers from by reconnecting. */
    val errors: Flow<Throwable>

    /** The refusal that stopped the stream for good, or null. */
    val failure: StateFlow<Throwable?>

    /** Whether a session is open right now. */
    val isConnected: Boolean

    /** Open the stream (a no-op while it runs). */
    fun connect()

    /** Add ids to the stream. */
    suspend fun subscribe(
        contextIds: Collection<String> = emptyList(),
        groupIds: Collection<String> = emptyList(),
    )
}

/** This connection as [MeroEvents]. */
fun SseConnection.asMeroEvents(): MeroEvents {
    val sse = this
    return object : MeroEvents {
        override val events: Flow<NodeEvent> get() = sse.events
        override val connects: Flow<String> get() = sse.connects
        override val errors: Flow<Throwable> get() = sse.errors
        override val failure: StateFlow<Throwable?> get() = sse.failure
        override val isConnected: Boolean get() = sse.sessionId != null

        override fun connect() = sse.connect()

        override suspend fun subscribe(
            contextIds: Collection<String>,
            groupIds: Collection<String>,
        ) = sse.subscribe(contextIds, groupIds)
    }
}

/**
 * Ephemeral presence for one context, whichever transport carries it — the shape mero-react's
 * `useEphemeral` reads off `mero.ephemeral`. [EphemeralClient] (a node) and
 * [RelayPresenceClient] (an account) are the real ones.
 */
interface PresenceSurface {
    /** Publish your own slice, replacing it wholesale. */
    suspend fun <T> set(
        contextId: String,
        state: T,
        codec: Codec<T>,
    )

    /** Everyone's slices in [contextId]: the node's replay first, then live changes. */
    fun <T> subscribe(
        contextId: String,
        codec: Codec<T>,
    ): Flow<EphemeralEntry<T>>
}

/** A node's presence as a [PresenceSurface]. */
fun EphemeralClient.asPresenceSurface(): PresenceSurface {
    val client = this
    return object : PresenceSurface {
        override suspend fun <T> set(
            contextId: String,
            state: T,
            codec: Codec<T>,
        ) = client.set(contextId, state, codec)

        override fun <T> subscribe(
            contextId: String,
            codec: Codec<T>,
        ): Flow<EphemeralEntry<T>> = client.subscribe(contextId, codec)
    }
}

/** An account's relay presence as a [PresenceSurface]. */
fun RelayPresenceClient.asPresenceSurface(): PresenceSurface {
    val client = this
    return object : PresenceSurface {
        override suspend fun <T> set(
            contextId: String,
            state: T,
            codec: Codec<T>,
        ) = client.set(contextId, state, codec)

        override fun <T> subscribe(
            contextId: String,
            codec: Codec<T>,
        ): Flow<EphemeralEntry<T>> = client.subscribe(contextId, codec)
    }
}

/**
 * What an account does across its relays — the reads and writes mero-react's hooks route around
 * the account admin: an account's namespaces may each be served by a different relay, and each
 * relay lists only its own.
 */
interface DelegatedOps {
    /** The account's contexts on every relay it uses (mero-js `listDelegatedContexts`). */
    suspend fun listContexts(): List<ContextWithGroup>

    /** The account's namespaces on every relay it uses (mero-js `listDelegatedNamespaces`). */
    suspend fun listNamespaces(): List<Namespace>

    /**
     * A context only the account and [CreatePrivateContextRequest.members] are in, each step
     * under its own warrant (mero-js `createDelegatedPrivateContext`). Returns the context id.
     */
    suspend fun createPrivateContext(request: CreatePrivateContextRequest): String
}

/** mero-react `CreatePrivateContextRequest`: a DM or small room inside [namespaceId]. */
data class CreatePrivateContextRequest(
    val namespaceId: String,
    val applicationId: String,
    /** The accounts that share it with you, and nobody else — e.g. the other person of a DM. */
    val members: List<String>,
    val initializationParams: List<Int>? = null,
)
