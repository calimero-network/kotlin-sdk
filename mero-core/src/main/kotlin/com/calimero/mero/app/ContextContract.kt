package com.calimero.mero.app

import com.calimero.mero.account.NoRelayException
import com.calimero.mero.relay.RelayClient
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

// A context's contract, as an app on a phone reaches it. There is no node on the phone, so there
// is no JSON-RPC: a read is the relay's account-scoped query, a write is a warrant the device key
// signs. Every app makes the same two calls; only the method names differ, and those belong to the
// app (or to the client mero-devtools-js generates for it, over [ContextContractCalling]).

/**
 * The two ways an app reaches the contract in a context. (Swift `MeroKit.ContextContract`.)
 *
 * [RelayContextContract] is the real one; [NoRelayContextContract] stands in for an account no
 * relay serves yet. [bound] fixes the context, for a generated client.
 */
interface ContextContract {
    /** Run a view (`#[app::view]`) as the account, through the relay's query path. */
    suspend fun query(
        contextId: String,
        method: String,
        args: JsonElement,
    ): JsonElement?

    /** Run a mutation as the account: a warrant posted to the relay's intents route. */
    suspend fun execute(
        contextId: String,
        method: String,
        args: JsonElement,
    ): JsonElement?

    /** This contract with [contextId] fixed: what a generated client calls. */
    fun bound(contextId: String): BoundContextContract = BoundContextContract(this, contextId)
}

/**
 * [ContextContract] over the account's [RelayClient]. (Swift `RelayContextContract`.)
 *
 * - `query` is `POST /admin-api/contexts/{id}/query` with the relay session's Bearer token
 *   ([RelayClient.call]). Without a session, or when the relay says the method is a write, it
 *   falls back to a warrant, which reads too.
 * - `execute` is `POST /admin-api/contexts/{id}/intents` carrying a warrant the device key signs
 *   ([RelayClient.execute]); the method's return value is its `returns`.
 */
class RelayContextContract(
    val relay: RelayClient,
) : ContextContract {
    override suspend fun query(
        contextId: String,
        method: String,
        args: JsonElement,
    ): JsonElement? = relay.call(contextId, method, args)

    override suspend fun execute(
        contextId: String,
        method: String,
        args: JsonElement,
    ): JsonElement? = relay.execute(contextId, method, args).returns
}

/**
 * The contract of an account no relay serves yet: every call throws [NoRelayException], naming
 * the method. Redeeming an invitation is how the account gets a relay. (Swift `NoRelayContextContract`.)
 */
object NoRelayContextContract : ContextContract {
    override suspend fun query(
        contextId: String,
        method: String,
        args: JsonElement,
    ): JsonElement? = throw NoRelayException(method)

    override suspend fun execute(
        contextId: String,
        method: String,
        args: JsonElement,
    ): JsonElement? = throw NoRelayException(method)
}

/**
 * One context's contract, in the shape a generated client calls: JSON in, JSON out, as strings.
 * (Swift `ContextContractCalling`, which takes and returns UTF-8 `Data`.)
 *
 * [BoundContextContract] is the SDK's; [ContextContract.bound] makes one.
 */
interface ContextContractCalling {
    /** Run a view: [argsJson] is the argument object; the result is the method's return value as JSON (`null` when it returns nothing). */
    suspend fun query(
        method: String,
        argsJson: String,
    ): String

    /** Run a mutation: as [query], through a warrant. */
    suspend fun execute(
        method: String,
        argsJson: String,
    ): String
}

/**
 * A [ContextContract] with its context fixed. (Swift `BoundContextContract`.)
 *
 * Blank [argsJson][query] is read as `{}`, and anything that is not JSON is refused
 * ([IllegalArgumentException]) before a request is made.
 */
class BoundContextContract(
    val contract: ContextContract,
    val contextId: String,
) : ContextContractCalling {
    override suspend fun query(
        method: String,
        argsJson: String,
    ): String = encode(contract.query(contextId, method, args(argsJson)))

    override suspend fun execute(
        method: String,
        argsJson: String,
    ): String = encode(contract.execute(contextId, method, args(argsJson)))

    private fun encode(result: JsonElement?): String = (result ?: JsonNull).toString()

    private fun args(json: String): JsonElement {
        if (json.isBlank()) return JsonObject(emptyMap())
        return try {
            Json.parseToJsonElement(json)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("argsJson is not JSON: ${e.message}", e)
        }
    }
}
