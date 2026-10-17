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
 *
 * No node serves a lookup of that window, so the client's own counter is the only record
 * of where the sequence stands: persist it ([PersistedNonceSource]). [advanceTo] lets a
 * caller that learns a higher spent nonce out of band — a refusal naming it — skip ahead.
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
