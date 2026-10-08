package com.calimero.mero.rpc

import com.calimero.mero.http.HttpClient
import com.calimero.mero.http.MeroException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A JSON-RPC 2.0 error returned by the node. Port of mero-js `RpcError`.
 *
 * [type] / [data] are core's `ExecutionError` tag and payload, e.g.
 * `FunctionCallError` with the method's message. Since core 0.11.0-rc.83 a
 * scoped token also needs `context:execute` for the call, or the refusal is a
 * `FunctionCallError` saying so.
 */
open class RpcException(
    val code: Int,
    message: String,
    val type: String? = null,
    val data: JsonElement? = null,
) : MeroException(message)

/**
 * The call wrote state, and this node's role in the context is read-only
 * (`ReadOnly`, `ReadOnlyTee` or `RelayTee`), so the writes were **discarded**:
 * nothing was committed, signed or published (core 0.11.0-rc.83,
 * `{"type":"ReadOnlyWriteRefused","data":{"context_id":…}}`). Reads on the same
 * node still succeed. Retrying here cannot help — write through a node holding a
 * writing role, or through a relay under a warrant.
 *
 * A subclass of [RpcException], so existing handlers keep catching it.
 */
class ReadOnlyWriteRefusedException(
    /** The context the write was refused in, as the node named it. */
    val contextId: String?,
    code: Int,
    message: String,
    data: JsonElement?,
) : RpcException(code, message, TYPE, data) {
    companion object {
        /** The `type` tag core sends. */
        const val TYPE = "ReadOnlyWriteRefused"
    }
}

/**
 * The result of [RpcClient.executeWithMetadata]: the decoded output plus which
 * transport carried it. Mirrors mero-js's `{ returns, transport }`, so code
 * written against a node-or-relay abstraction reads the same shape.
 */
data class RpcExecution<T>(
    val returns: T,
    /** Always [TRANSPORT_NODE] here: this client talks to a node's `/jsonrpc`. */
    val transport: String = TRANSPORT_NODE,
) {
    companion object {
        const val TRANSPORT_NODE = "node"
    }
}

/** Summary of the owner-driven `migrate_my_entries` convert (counts are u32). */
@Serializable
data class MigrateMyEntriesSummary(
    val converted: Int,
    val remaining: Int,
)

@Serializable
private data class RpcErrorBody(
    val code: Int? = null,
    val message: String? = null,
    val type: String? = null,
    val data: JsonElement? = null,
)

@Serializable
private data class JsonRpcResponse(
    val jsonrpc: String? = null,
    val id: Int? = null,
    val result: JsonObject? = null,
    val error: RpcErrorBody? = null,
)

/**
 * JSON-RPC 2.0 client. `execute` posts to `/jsonrpc`, unwraps `result.output`, and maps `error`
 * to [RpcException]. Port of mero-js `rpc/index.ts`.
 */
class RpcClient(
    @PublishedApi internal val http: HttpClient,
) {
    /** Execute a contract method and decode `result.output` with [deserializer] (dynamic types). */
    suspend fun <T> execute(
        contextId: String,
        method: String,
        argsJson: JsonObject = JsonObject(emptyMap()),
        deserializer: DeserializationStrategy<T>,
    ): T {
        val output = executeRaw(contextId, method, argsJson)
        return http.json.decodeFromJsonElement(deserializer, output)
    }

    /** Ergonomic reified overload. */
    suspend inline fun <reified T> execute(
        contextId: String,
        method: String,
        argsJson: JsonObject = JsonObject(emptyMap()),
    ): T = http.json.decodeFromJsonElement(executeRaw(contextId, method, argsJson))

    /**
     * Execute and return the raw `result.output` [JsonElement] without decoding.
     *
     * There is no `executorPublicKey`: core's execute request is `deny_unknown_fields` with only
     * `contextId`, `method` and `argsJson`, so naming one was a refusal. The call runs as the
     * identity the token is bound to. (== mero-swift-sdk `RpcClient.execute`.)
     */
    suspend fun executeRaw(
        contextId: String,
        method: String,
        argsJson: JsonObject = JsonObject(emptyMap()),
    ): JsonElement {
        val body =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "execute")
                put(
                    "params",
                    buildJsonObject {
                        put("contextId", contextId)
                        put("method", method)
                        put("argsJson", argsJson)
                    },
                )
            }

        val res =
            http
                .execute("POST", "/jsonrpc", http.json.encodeToString(JsonObject.serializer(), body))
                .ensureSuccessful()
        val parsed = http.json.decodeFromString<JsonRpcResponse>(res.body)

        parsed.error?.let { err ->
            val code = err.code ?: -1
            val message = err.message ?: err.type ?: "RPC error"
            if (err.type == ReadOnlyWriteRefusedException.TYPE) {
                val contextId =
                    (err.data as? JsonObject)
                        ?.let { it["context_id"] ?: it["contextId"] }
                        ?.let { (it as? JsonPrimitive)?.contentOrNull }
                val readable =
                    err.message ?: "write refused on context '$contextId': this node's role in the context is " +
                        "read-only, so its writes are discarded"
                throw ReadOnlyWriteRefusedException(contextId, code, readable, err.data)
            }
            throw RpcException(code = code, message = message, type = err.type, data = err.data)
        }
        return parsed.result?.get("output") ?: JsonObject(emptyMap())
    }

    /**
     * [execute], returned with the transport that carried it — the shape mero-js's
     * `executeWithMetadata` returns, for code shared with a relay transport.
     */
    suspend inline fun <reified T> executeWithMetadata(
        contextId: String,
        method: String,
        argsJson: JsonObject = JsonObject(emptyMap()),
    ): RpcExecution<T> = RpcExecution(execute<T>(contextId, method, argsJson))

    /** One-tap owner-driven convert: re-signs the caller's identity-gated entries to the schema. */
    suspend fun migrateMyEntries(contextId: String): MigrateMyEntriesSummary =
        execute(contextId, "migrate_my_entries")

    /** Read-only count of the caller's entries still below the target schema. */
    suspend fun countMyPending(contextId: String): Int =
        executeRaw(contextId, "count_my_pending").jsonPrimitive.int
}
