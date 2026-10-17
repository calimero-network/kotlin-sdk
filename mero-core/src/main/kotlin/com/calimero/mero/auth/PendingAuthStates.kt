package com.calimero.mero.auth

import com.calimero.mero.account.EncryptedPrefsSecureStore
import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.account.SecureStore
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.randomBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Node logins this app began, so an auth callback can be matched to one — the native
 * counterpart of the `localStorage` store mero-js keeps under `calimero.auth.login.pending`
 * (`src/cloud/link-state.ts` `beginState` / `takeState`).
 *
 * Why it exists: anyone can send the app a deep link with tokens of their choosing in the
 * fragment, and adopting them signs the person in to the **sender's** node and account.
 * [buildAuthLoginUrl] records a fresh random `auth_state` here and puts it in the callback
 * URL; [parseAuthCallback] adopts tokens only when the callback carries a state recorded
 * here, and spends it.
 *
 * Each state is **single use** and **expires** after [LIFETIME_MS] (10 minutes). At most
 * [MAX_PENDING] are kept, oldest dropped first. A store that fails to read or write fails
 * closed: the callback is refused.
 *
 * The default ([shared]) is in memory, so a login whose process died while the Custom Tab
 * was open is refused and must be started again. To survive that, back it with the
 * Keystore-encrypted store:
 *
 * ```kotlin
 * PendingAuthStates.shared = PendingAuthStates(EncryptedPrefsSecureStore(context, "mero_auth_state"))
 * ```
 *
 * or pass an instance to [buildAuthLoginUrl] / [parseAuthCallback] explicitly.
 *
 * @param store where the pending states live (see [EncryptedPrefsSecureStore]).
 * @param clock epoch milliseconds; injectable for tests.
 */
class PendingAuthStates(
    private val store: SecureStore = MemorySecureStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val key: String = DEFAULT_KEY,
) {
    private val lock = Any()

    /** Record a fresh state (16 random bytes, hex) and return it. */
    fun begin(): String =
        synchronized(lock) {
            val now = clock()
            val pending = read(now)
            val state = Hex.encode(randomBytes(STATE_BYTES))
            pending[state] = now + LIFETIME_MS
            write(
                pending.entries
                    .toList()
                    .takeLast(MAX_PENDING)
                    .associate { it.key to it.value },
            )
            state
        }

    /**
     * Spend [state]: true when this app began it and it has not expired or been used.
     * Always false for a null or empty state.
     */
    fun take(state: String?): Boolean =
        synchronized(lock) {
            if (state.isNullOrEmpty()) return false
            val pending = read(clock())
            if (pending.remove(state) == null) return false
            write(pending)
        }

    /** Drop every pending state (e.g. on sign-out). */
    fun clear() {
        synchronized(lock) { runCatching { store.remove(key) } }
    }

    // Unexpired entries, in insertion order. Malformed or unreadable storage reads as empty.
    private fun read(now: Long): LinkedHashMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        val raw = runCatching { store.get(key) }.getOrNull() ?: return out
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return out
        for ((state, entry) in obj) {
            val expiresAt = ((entry as? JsonObject)?.get("expiresAt") as? JsonPrimitive)?.longOrNull ?: continue
            if (expiresAt > now) out[state] = expiresAt
        }
        return out
    }

    // Same shape mero-js stores: `{ "<state>": { "expiresAt": <ms> } }`.
    private fun write(pending: Map<String, Long>): Boolean =
        runCatching {
            val body = buildJsonObject { pending.forEach { (state, at) -> put(state, buildJsonObject { put("expiresAt", at) }) } }
            store.put(key, body.toString())
        }.isSuccess

    companion object {
        /** The storage key, the one mero-js uses. */
        const val DEFAULT_KEY = "calimero.auth.login.pending"

        /** How long a begun login can be answered: 10 minutes. */
        const val LIFETIME_MS = 10 * 60 * 1000L

        /** At most this many logins are remembered at once. */
        const val MAX_PENDING = 20

        private const val STATE_BYTES = 16

        /**
         * The store [buildAuthLoginUrl] and [parseAuthCallback] use by default: in memory.
         * Replace it at startup with an [EncryptedPrefsSecureStore]-backed instance so a
         * login survives the process dying while the browser is open.
         */
        @Volatile
        @JvmStatic
        var shared: PendingAuthStates = PendingAuthStates()
    }
}
