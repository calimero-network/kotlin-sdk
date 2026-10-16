package com.calimero.mero.transport

import com.calimero.mero.relay.MethodKinds
import com.calimero.mero.relay.NotAViewException
import com.calimero.mero.relay.RelayClient
import com.calimero.mero.rpc.MigrateMyEntriesSummary
import com.calimero.mero.rpc.RpcExecution
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The relay, wearing the node's `execute` shape — port of mero-js `RelayTransport`.
 *
 * An adapter and nothing else: [RelayClient] mints the warrant, holds the key and is the place
 * to look when a write is refused. What lives here is the unwrap — an `IntentResult` becomes
 * the method's return value, so a call site cannot tell which transport ran it. A `null`
 * return stays [JsonNull], as the node reports a null output; the root hash the relay also
 * reports comes back from [executeWithMetadata].
 */
class RelayTransport(
    /** The underlying client, for `describe()` and anything else relay-specific. */
    val client: RelayClient,
    /** How this transport observes, or `null` when nobody supplied the relay node's signing key. */
    val events: RelayObserver? = null,
) : ExecuteTransport {
    override val kind: String = RpcExecution.TRANSPORT_RELAY

    /** Whether this relay can be observed: a fact about the configuration (a node key was given), not a constant. */
    override val canSubscribe: Boolean get() = events != null

    override suspend fun execute(
        contextId: String,
        method: String,
        argsJson: JsonObject,
    ): JsonElement = executeWithMetadata(contextId, method, argsJson).returns

    override suspend fun executeWithMetadata(
        contextId: String,
        method: String,
        argsJson: JsonObject,
    ): ExecuteResult {
        val result = client.execute(contextId, method, argsJson)
        return ExecuteResult(result.returns ?: JsonNull, kind, result.rootHash.ifEmpty { null })
    }

    /** A plain context method, so it runs through a relay exactly as on a node, attributed to the author. */
    override suspend fun migrateMyEntries(contextId: String): MigrateMyEntriesSummary =
        migrateSummaryOf(execute(contextId, "migrate_my_entries", JsonObject(emptyMap())))

    override suspend fun countMyPending(contextId: String): Int = pendingCountOf(execute(contextId, "count_my_pending", JsonObject(emptyMap())))
}

/**
 * [inner] with reads routed through the relay's `POST /admin-api/contexts/{ctx}/query` — the
 * session-authenticated query for a method the app's ABI declares read-only, the warrant for
 * everything else (mero-js `readsThroughQuery`). A call carries no marker of which it is, so
 * the node's answer decides: a method is tried as a query first, a `409` says it is a write
 * (remembered per relay, context and method, so the probe is paid once), and any other
 * failure of the query falls back to the warrant, which reads too. Only for a client whose
 * [RelayClient] has a session: the query route wants one. `migrateMyEntries` and
 * `countMyPending` go to [inner] unchanged, as in mero-js.
 */
internal class ReadsThroughQuery(
    private val inner: RelayTransport,
) : ExecuteTransport by inner {
    override suspend fun execute(
        contextId: String,
        method: String,
        argsJson: JsonObject,
    ): JsonElement = executeWithMetadata(contextId, method, argsJson).returns

    override suspend fun executeWithMetadata(
        contextId: String,
        method: String,
        argsJson: JsonObject,
    ): ExecuteResult {
        val key = MethodKinds.key(inner.client.relayUrl, contextId, method)
        if (!MethodKinds.isWrite(key)) {
            try {
                val returns = inner.client.query(contextId, method, argsJson)
                MethodKinds.markRead(key)
                // No root hash: a read changes nothing, and `null` is "unknown here".
                return ExecuteResult(returns ?: JsonNull, inner.kind)
            } catch (_: NotAViewException) {
                MethodKinds.markWrite(key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not an answer about the method: the warrant reads too.
            }
        }
        return inner.executeWithMetadata(contextId, method, argsJson)
    }
}
