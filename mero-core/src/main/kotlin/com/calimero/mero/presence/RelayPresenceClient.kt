package com.calimero.mero.presence

import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Hex
import com.calimero.mero.ephemeral.Codec
import com.calimero.mero.ephemeral.EphemeralEntry
import com.calimero.mero.ephemeral.jsonCodec
import com.calimero.mero.ephemeral.subscribePresence
import com.calimero.mero.http.HttpException
import com.calimero.mero.relay.IntentRefusedException
import com.calimero.mero.sse.SseConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * What [RelayPresenceClient] needs from a relay: the device that signs, its certificate,
 * and the `presence-intents` POST. [com.calimero.mero.relay.RelayClient] is one.
 */
interface PresenceRelay {
    /** The author's `AccountProof<DeviceCert>`, hex, carried by every update. */
    val authorProof: String

    /** The device key that signs each statement. */
    val authorSigner: DeviceSigner

    /**
     * `POST /admin-api/contexts/{ctx}/presence-intents` with an already-signed statement.
     * A refusal throws with its status ([IntentRefusedException] for 400/403,
     * [HttpException] otherwise); not retried on 429.
     */
    suspend fun presenceIntent(
        contextId: String,
        state: String?,
        seq: Long,
        sentAtMs: Long,
        signature: String,
    )
}

/**
 * An account's ephemeral presence, through its relay. Port of mero-js
 * `RelayPresenceClient` (`src/presence/relay-presence.ts`).
 *
 * A node publishes and heartbeats its own presence; an account has no node, so this
 * client signs each update with the device key ([PresenceStatement]), posts it to the
 * relay's `presence-intents`, and resends the last state every [heartbeatMs] (2.5 s, the
 * node's own interval, so an entry never outlives the 7 s TTL) until `set(contextId, null)`
 * or [close]. The relay checks the update, then seals and gossips it; other members verify
 * the device's signature and certificate themselves, so the relay cannot invent or alter
 * an account's presence.
 *
 * `seq` is seeded from the wall clock and strictly rising per context, so a relay restart
 * never sees an old one.
 */
class RelayPresenceClient(
    private val relay: PresenceRelay,
    /** The relay's event stream, asked for only on [subscribe]: publishing needs none. */
    private val events: () -> SseConnection,
    val heartbeatMs: Long = HEARTBEAT_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = Any()
    private val timers = HashMap<String, Job>()
    private val lastSeq = HashMap<String, Long>()
    private val refused = HashSet<String>()

    /**
     * Bumped by every [set] and [close], per context. A `set` installs its resends only if
     * nothing newer happened while its first send was in flight, so a retract (or a newer
     * state) is never overtaken by an older one.
     */
    private val generation = HashMap<String, Long>()

    /**
     * Set this account's presence in [contextId], or retract it with `null`. Throws when the
     * relay refuses this first send for good (a 4xx other than 429), which also stops the
     * resends. A rate limit, a network failure or a 5xx is not thrown: the next resend covers it.
     */
    suspend fun <T> set(
        contextId: String,
        state: T?,
        codec: Codec<T>,
    ) {
        val gen = bump(contextId)
        synchronized(lock) { refused.remove(contextId) }
        val bytes = state?.let { codec.encode(it) }
        send(contextId, bytes)
        if (bytes == null) return
        synchronized(lock) {
            if (contextId in refused || generation[contextId] != gen) return // refused, or a newer set / close won
            timers[contextId] =
                scope.launch {
                    while (true) {
                        delay(heartbeatMs)
                        try {
                            send(contextId, bytes)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // A permanent refusal already stopped this loop; nothing to report to.
                        }
                    }
                }
        }
    }

    /** [set] with the default [com.calimero.mero.ephemeral.JsonCodec]. */
    suspend inline fun <reified T> set(
        contextId: String,
        state: T?,
    ) = set(contextId, state, jsonCodec<T>())

    /** Retract this account's presence in [contextId] (`set(contextId, null)`). */
    suspend fun clear(contextId: String) = set<ByteArray>(contextId, null, com.calimero.mero.ephemeral.RawCodec)

    /** Everyone's presence in [contextId], read off the relay's event stream. */
    fun <T> subscribe(
        contextId: String,
        codec: Codec<T>,
    ): Flow<EphemeralEntry<T>> = subscribePresence(events(), contextId, codec)

    /** [subscribe] with the default JSON codec. */
    inline fun <reified T> subscribe(contextId: String): Flow<EphemeralEntry<T>> = subscribe(contextId, jsonCodec<T>())

    /** Whether [contextId] is being resent now. */
    fun isActive(contextId: String): Boolean = synchronized(lock) { timers[contextId]?.isActive == true }

    /** Stop every resend. Entries expire on the relay and its peers within 7 s. */
    fun close() {
        val contexts = synchronized(lock) { timers.keys + generation.keys }
        contexts.forEach { bump(it) }
    }

    /** Stop the context's resends and invalidate any [set] still in flight. */
    private fun bump(contextId: String): Long =
        synchronized(lock) {
            stop(contextId)
            val next = (generation[contextId] ?: 0L) + 1
            generation[contextId] = next
            next
        }

    private fun stop(contextId: String) {
        synchronized(lock) { timers.remove(contextId) }?.cancel()
    }

    /** Wall-clock seeded and strictly rising, so a relay restart never sees an old seq. */
    private fun nextSeq(contextId: String): Long =
        synchronized(lock) {
            val seq = maxOf(now(), (lastSeq[contextId] ?: 0L) + 1)
            lastSeq[contextId] = seq
            seq
        }

    private suspend fun send(
        contextId: String,
        state: ByteArray?,
    ) {
        if (synchronized(lock) { contextId in refused }) return
        val signer = relay.authorSigner
        val seq = nextSeq(contextId)
        val sentAtMs = now()
        val signature = PresenceStatement.sign(signer, contextId, seq, sentAtMs, state)
        try {
            relay.presenceIntent(contextId, state?.let { Hex.encode(it) }, seq, sentAtMs, signature)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status =
                when (e) {
                    is IntentRefusedException -> e.status
                    is HttpException -> e.status
                    else -> null
                }
            // Rate limited, a network failure, or the relay unable right now (5xx, e.g. no
            // current key mid-rotation): the next resend covers it.
            if (status == null || status == 0 || status == HTTP_TOO_MANY || status >= HTTP_SERVER_ERROR) return
            // Any other refusal (403: not a member or revoked; 400: malformed or a clock
            // outside the relay's window) cannot be fixed by resending.
            synchronized(lock) { refused.add(contextId) }
            stop(contextId)
            throw e
        }
    }

    companion object {
        /** The node's own heartbeat interval, so a relayed entry never outlives the 7 s TTL. */
        const val HEARTBEAT_MS = 2_500L
        private const val HTTP_TOO_MANY = 429
        private const val HTTP_SERVER_ERROR = 500
    }
}
