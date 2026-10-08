package com.calimero.mero.relay

import com.calimero.mero.account.SecureStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where a warrant's nonce comes from: a monotonic u64 per author device.
 *
 * Port of mero-js `src/relay/nonce-source.ts`. A node keeps a window of seen nonces per
 * (context, device) and refuses a replay; a sequence that only rises — even with gaps —
 * is always accepted.
 */
interface NonceSource {
    /** The next nonce to sign with. Never returns the same value twice. */
    suspend fun next(): ULong

    /** Make every later [next] return at least [floor]. Lowering is a no-op. */
    suspend fun advanceTo(floor: ULong)
}

/** An in-memory sequence, for a process that owns its whole sequence (and for tests). */
class MemoryNonceSource(
    start: ULong = 1u,
) : NonceSource {
    private val mutex = Mutex()
    private var next = start

    init {
        require(start >= 1u) { "nonce sequence starts at 1" }
    }

    override suspend fun next(): ULong = mutex.withLock { next.also { next += 1u } }

    override suspend fun advanceTo(floor: ULong) = mutex.withLock { if (floor > next) next = floor }
}

/**
 * A sequence persisted in a [SecureStore] under [key], surviving restarts: the last value
 * handed out is written (synchronously, for the encrypted-prefs store) before it is
 * returned, so a crash between signing and sending skips a nonce rather than replaying one.
 *
 * Use [forRelay] for the one-sequence-per-relay layout mero-js uses
 * (`calimero.nonce.<relayUrl>`): a single rising sequence shared by every context only
 * ever skips, which is free.
 */
class PersistedNonceSource(
    private val store: SecureStore,
    private val key: String,
) : NonceSource {
    private val mutex = Mutex()

    private fun lastHandedOut(): ULong = store.get(key)?.toULongOrNull() ?: 0u

    override suspend fun next(): ULong =
        mutex.withLock {
            val value = lastHandedOut() + 1u
            store.put(key, value.toString())
            value
        }

    override suspend fun advanceTo(floor: ULong) =
        mutex.withLock {
            val wanted = if (floor > 0u) floor - 1u else 0u
            if (wanted > lastHandedOut()) store.put(key, wanted.toString())
        }

    companion object {
        fun forRelay(
            store: SecureStore,
            relayUrl: String,
        ): PersistedNonceSource = PersistedNonceSource(store, "calimero.nonce.${relayUrl.trimEnd('/')}")
    }
}

/** Where a device stands in its warrant-nonce sequence in one context (core rc.83 `warrant-nonce` route). */
sealed class WarrantNonceState {
    abstract val contextId: String
    abstract val authorDeviceKey: String

    /** The next nonce the node would accept. */
    data class Open(
        override val contextId: String,
        override val authorDeviceKey: String,
        val nextNonce: ULong,
        val seen: Boolean,
        val highWaterNonce: ULong?,
    ) : WarrantNonceState()

    /** Every nonce is spent: the device must re-key to write here again. */
    data class Exhausted(
        override val contextId: String,
        override val authorDeviceKey: String,
    ) : WarrantNonceState()

    companion object {
        /**
         * Parse the route's body. u64 fields are read from the **text**, never through a
         * double, so a nonce past 2^53 survives; an absent `nextNonce` means exhausted.
         */
        fun parse(body: String): WarrantNonceState {
            val data = body.substringAfter("\"data\"", missingDelimiterValue = "")
            require(data.isNotEmpty()) { "warrant-nonce response had no `data`: ${body.take(200)}" }

            fun str(field: String) =
                Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
                    .find(data)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()

            fun u64(field: String): ULong? =
                Regex("\"$field\"\\s*:\\s*\"?(\\d+)\"?")
                    .find(data)
                    ?.groupValues
                    ?.get(1)
                    ?.toULongOrNull()
            val contextId = str("contextId")
            val device = str("authorDeviceKey")
            val next = u64("nextNonce") ?: return Exhausted(contextId, device)
            return Open(
                contextId = contextId,
                authorDeviceKey = device,
                nextNonce = next,
                seen = Regex("\"seen\"\\s*:\\s*true").containsMatchIn(data),
                highWaterNonce = u64("highWaterNonce"),
            )
        }
    }
}

/** The device has spent every nonce in a context. */
class WarrantNonceExhaustedException(
    val contextId: String,
) : Exception("this device has spent every nonce in context $contextId; it must re-key to write again")
