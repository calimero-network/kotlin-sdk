package com.calimero.mero.ephemeral

import com.calimero.mero.rpc.RpcClient
import com.calimero.mero.sse.ContextEvent
import com.calimero.mero.sse.SseConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One presence entry from [EphemeralClient.subscribe]. Port of mero-js `EphemeralEntry`.
 *
 * On subscribing to a context the node replays its current presence as ordinary
 * `Ephemeral` events before any live delta. [ageMs] tells them apart: present on a
 * replayed (or seeded) entry — milliseconds since that author was last heard from,
 * measured on the node — and `null` on a live delta. Never synthesized as `0`: absent and
 * zero mean different things. Bounded by the node's 7 s presence TTL.
 */
data class EphemeralEntry<T>(
    /** The author's public key (a node's context identity, or an account's device key). */
    val author: String,
    /** The account a relay-carried update's device certificate names, hex; `null` for a node's own presence. */
    val account: String? = null,
    /** The decoded slice; `null` on a removal. */
    val state: T? = null,
    /** True when the author's slot was removed (core omits the flag on an upsert). */
    val removed: Boolean = false,
    val ageMs: Long? = null,
)

/**
 * Ephemeral presence: transient, encrypted, never-persisted state (cursors, typing,
 * presence) that gossips between nodes without a WASM run or DAG growth. Port of mero-js
 * `EphemeralClient` (`src/ephemeral/index.ts`).
 *
 * The write/read asymmetry is the model. [set] takes no author: you only ever write your
 * OWN slot, and the node resolves the author from its owned context identity. [subscribe]
 * returns everyone's slots, each with its `author`. There is no snapshot RPC: the read
 * path is the event stream, seeded by the node's replay.
 */
class EphemeralClient(
    private val rpc: RpcClient,
    private val events: SseConnection,
) {
    /**
     * Publish your own presence slice, replacing it wholesale: JSON-RPC `set_ephemeral`
     * `{contextId, state: [bytes as numbers]}` (core `SetEphemeralRequest`, at most
     * 16 384 bytes). Throws [com.calimero.mero.rpc.RpcException] carrying core's `type` and
     * `data` — e.g. `SliceTooLarge` — on refusal.
     */
    suspend fun <T> set(
        contextId: String,
        state: T,
        codec: Codec<T>,
    ) {
        val bytes = codec.encode(state)
        rpc.call(
            "set_ephemeral",
            buildJsonObject {
                put("contextId", contextId)
                put("state", buildJsonArray { bytes.forEach { add(it.toInt() and BYTE_MASK) } })
            },
        )
    }

    /** [set] with the default [JsonCodec]. */
    suspend inline fun <reified T> set(
        contextId: String,
        state: T,
    ) = set(contextId, state, jsonCodec<T>())

    /**
     * Presence changes in [contextId], decoded with [codec]. Adds no transport: a typed
     * filter over [events] — see [subscribePresence] for the replay and seed rules.
     */
    fun <T> subscribe(
        contextId: String,
        codec: Codec<T>,
    ): Flow<EphemeralEntry<T>> = subscribePresence(events, contextId, codec)

    /** [subscribe] with the default [JsonCodec]. */
    inline fun <reified T> subscribe(contextId: String): Flow<EphemeralEntry<T>> = subscribe(contextId, jsonCodec<T>())

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}

/**
 * Presence for one context off an event stream: only `Ephemeral` events of [contextId],
 * decoded with [codec]. Shared by a node's [EphemeralClient] and an account's
 * [com.calimero.mero.presence.RelayPresenceClient]. Port of mero-js `subscribePresence`.
 *
 * Collecting it:
 * 1. listens on [events] first, so nothing that arrives next is missed;
 * 2. when the connection already holds [contextId], replays [SseConnection.presenceSeed] —
 *    the node replays a context's presence once per connection, to whichever listener came
 *    first, so a later one is seeded from what the connection kept (ages advanced);
 * 3. connects and subscribes [contextId] (errors surface on [SseConnection.errors]).
 *
 * Cancelling stops delivery; it does not unsubscribe the context (other listeners may share it).
 * An entry whose state fails to decode is skipped.
 */
fun <T> subscribePresence(
    events: SseConnection,
    contextId: String,
    codec: Codec<T>,
): Flow<EphemeralEntry<T>> =
    channelFlow {
        events.events
            .onSubscription {
                if (events.isSubscribed(contextId)) {
                    for (data in events.presenceSeed(contextId)) {
                        emit(ContextEvent(contextId, ContextEvent.KIND_EPHEMERAL, seedPayload(contextId, data)))
                    }
                }
                events.connect()
                launch { events.subscribe(contextIds = listOf(contextId)) }
            }.collect { event ->
                if (event !is ContextEvent || event.kind != ContextEvent.KIND_EPHEMERAL || event.contextId != contextId) return@collect
                presenceEntry((event.payload as? JsonObject)?.get("data") as? JsonObject, codec)?.let { send(it) }
            }
    }

private fun seedPayload(
    contextId: String,
    data: JsonObject,
): JsonObject =
    buildJsonObject {
        put("contextId", contextId)
        put("type", ContextEvent.KIND_EPHEMERAL)
        put("data", data)
    }

/** core `EphemeralPayload` → [EphemeralEntry]; `null` without an author or on an undecodable state. */
internal fun <T> presenceEntry(
    data: JsonObject?,
    codec: Codec<T>,
): EphemeralEntry<T>? {
    val author = (data?.get("author") as? JsonPrimitive)?.contentOrNull ?: return null
    // `removed` is omitted on an upsert, so normalize it to a boolean here.
    val removed = (data["removed"] as? JsonPrimitive)?.contentOrNull == "true"
    val rawState = data["state"] as? JsonArray
    val state =
        if (removed || rawState == null) {
            null
        } else {
            val bytes = rawState.map { (it as? JsonPrimitive)?.intOrNull?.toByte() ?: return null }.toByteArray()
            runCatching { codec.decode(bytes) }.getOrElse { return null }
        }
    return EphemeralEntry(
        author = author,
        account = (data["account"] as? JsonPrimitive)?.contentOrNull,
        state = state,
        removed = removed,
        ageMs = (data["ageMs"] as? JsonPrimitive)?.longOrNull,
    )
}
