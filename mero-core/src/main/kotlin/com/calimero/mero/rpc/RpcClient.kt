package com.calimero.mero.rpc

import com.calimero.mero.http.HttpClient
import com.calimero.mero.http.MeroException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A JSON-RPC 2.0 error returned by the node. Port of mero-js `RpcError`.
 */
class RpcException(
    val code: Int,
    message: String,
    val type: String? = null,
    val data: JsonElement? = null,
) : MeroException(message)

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
        executorPublicKey: String? = null,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val output = executeRaw(contextId, method, argsJson, executorPublicKey)
        return http.json.decodeFromJsonElement(deserializer, output)
    }

    /** Ergonomic reified overload. */
    suspend inline fun <reified T> execute(
        contextId: String,
        method: String,
        argsJson: JsonObject = JsonObject(emptyMap()),
        executorPublicKey: String? = null,
    ): T = http.json.decodeFromJsonElement(executeRaw(contextId, method, argsJson, executorPublicKey))

    /**
     * Execute and return the raw `result.output` [JsonElement] without decoding.
     *
     * [executorPublicKey] is the context identity executing the call; omitted from the request when
     * null (the node then uses the context's default/owning identity). Apps like curb key their
     * state on the caller identity and require it. (== mero-swift-sdk `RpcClient.execute`.)
     */
    suspend fun executeRaw(
        contextId: String,
        method: String,
        argsJson: JsonObject = JsonObject(emptyMap()),
        executorPublicKey: String? = null,
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
                        if (executorPublicKey != null) put("executorPublicKey", executorPublicKey)
                    },
                )
            }

        val res =
            http
                .execute("POST", "/jsonrpc", http.json.encodeToString(JsonObject.serializer(), body))
                .ensureSuccessful()
        val parsed = http.json.decodeFromString<JsonRpcResponse>(res.body)

        parsed.error?.let { err ->
            throw RpcException(
                code = err.code ?: -1,
                message = err.message ?: err.type ?: "RPC error",
                type = err.type,
                data = err.data,
            )
        }
        return parsed.result?.get("output") ?: JsonObject(emptyMap())
    }

    /** One-tap owner-driven convert: re-signs the caller's identity-gated entries to the schema. */
    suspend fun migrateMyEntries(contextId: String): MigrateMyEntriesSummary =
        execute(contextId, "migrate_my_entries")

    /** Read-only count of the caller's entries still below the target schema. */
    suspend fun countMyPending(contextId: String): Int =
        executeRaw(contextId, "count_my_pending").jsonPrimitive.int
}
