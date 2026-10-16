package com.calimero.mero.transport

import com.calimero.mero.rpc.MigrateMyEntriesSummary
import com.calimero.mero.rpc.RpcClient
import com.calimero.mero.rpc.RpcExecution
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/*
 * One call shape, two ways of getting there — port of mero-js `src/transport/types.ts`.
 *
 * `RpcClient.execute(contextId, method, argsJson)` is what apps already write, and
 * `RelayClient.execute` (which returns an `IntentResult`) grew independently. This interface
 * declares the node's shape the contract and adapts the relay to it, so switching transports
 * changes no call site. Subscription is not part of it: observation lives on
 * [MeroTransportClient] (`events`, `ws`), and [ExecuteTransport.canSubscribe] reports whether it
 * will work.
 */

/**
 * A method's return value plus whatever the transport knew about the write — mero-js
 * `ExecuteResult`. Carried on a second method rather than a mutable "last intent" slot, which
 * two calls in flight would race for.
 */
data class ExecuteResult(
    /** Exactly what [ExecuteTransport.execute] would have returned. */
    val returns: JsonElement,
    /** Which transport ran it: [RpcExecution.TRANSPORT_NODE] or [RpcExecution.TRANSPORT_RELAY]. */
    val transport: String,
    /**
     * The context's scope root after the run, hex — a relay **write** only. `null` on the node
     * (JSON-RPC does not report it) and for a relay read answered by query: `null` means
     * "unknown here", never "nothing changed".
     */
    val rootHash: String? = null,
)

/**
 * The write surface an app codes against, whichever transport is underneath — mero-js
 * `ExecuteTransport`. [NodeTransport] adapts a node's [RpcClient]; [RelayTransport] a relay.
 *
 * Returns are the method's raw JSON (a `null` output is [kotlinx.serialization.json.JsonNull]
 * on both transports); decode with [executeAs].
 */
interface ExecuteTransport {
    /** Which path this is: [RpcExecution.TRANSPORT_NODE] or [RpcExecution.TRANSPORT_RELAY]. */
    val kind: String

    /**
     * Whether this transport can observe events **as configured**: always on a node; on a
     * relay only when the relay node's signing key is known (see [RelayObserver]). Reported
     * rather than attempted, so an app branches before it subscribes.
     */
    val canSubscribe: Boolean

    /** Run one method in one context. The canonical signature; a missing [argsJson] is `{}` on both transports. */
    suspend fun execute(
        contextId: String,
        method: String,
        argsJson: JsonObject = JsonObject(emptyMap()),
    ): JsonElement

    /** The same call, with whatever the transport knows about it. */
    suspend fun executeWithMetadata(
        contextId: String,
        method: String,
        argsJson: JsonObject = JsonObject(emptyMap()),
    ): ExecuteResult

    /** One-tap owner-driven convert (`migrate_my_entries`). */
    suspend fun migrateMyEntries(contextId: String): MigrateMyEntriesSummary

    /** Read-only count of the caller's entries still below the target schema (`count_my_pending`). */
    suspend fun countMyPending(contextId: String): Int
}

private val decoder = Json { ignoreUnknownKeys = true }

/** [ExecuteTransport.execute], decoded as [T]. */
suspend inline fun <reified T> ExecuteTransport.executeAs(
    contextId: String,
    method: String,
    argsJson: JsonObject = JsonObject(emptyMap()),
): T = transportJson().decodeFromJsonElement(execute(contextId, method, argsJson))

/** The codec [executeAs] decodes with (lenient about unknown keys). */
@PublishedApi
internal fun transportJson(): Json = decoder

/** A node's JSON-RPC as an [ExecuteTransport]: [RpcClient] unchanged underneath. */
class NodeTransport(
    /** The node's JSON-RPC client. */
    val rpc: RpcClient,
) : ExecuteTransport {
    override val kind: String = RpcExecution.TRANSPORT_NODE
    override val canSubscribe: Boolean = true

    override suspend fun execute(
        contextId: String,
        method: String,
        argsJson: JsonObject,
    ): JsonElement = rpc.executeRaw(contextId, method, argsJson)

    override suspend fun executeWithMetadata(
        contextId: String,
        method: String,
        argsJson: JsonObject,
    ): ExecuteResult = ExecuteResult(execute(contextId, method, argsJson), kind)

    override suspend fun migrateMyEntries(contextId: String): MigrateMyEntriesSummary = rpc.migrateMyEntries(contextId)

    override suspend fun countMyPending(contextId: String): Int = rpc.countMyPending(contextId)
}

/** Decode a `migrate_my_entries` return the way [RpcClient] does. */
internal fun migrateSummaryOf(returns: JsonElement): MigrateMyEntriesSummary = decoder.decodeFromJsonElement(returns)

/** Decode a `count_my_pending` return the way [RpcClient] does. */
internal fun pendingCountOf(returns: JsonElement): Int = returns.jsonPrimitive.int
