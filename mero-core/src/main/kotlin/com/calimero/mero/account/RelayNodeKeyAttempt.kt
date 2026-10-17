package com.calimero.mero.account

/**
 * One attempt at a relay's node key, and what became of it — mero-js `RelayNodeKeyAttempt`
 * ([CloudAccount.attemptRelayNodeKey]).
 */
sealed class RelayNodeKeyAttempt {
    /** Verified (or already pinned), and pinned now. */
    data class Learned(
        val nodeKey: String,
    ) : RelayNodeKeyAttempt()

    /** Something on the way did not answer. Nothing was decided: try again. */
    data class Unavailable(
        val error: Exception,
    ) : RelayNodeKeyAttempt()

    /** The relay answered and its proof did not hold. Trying again cannot turn that into a yes, and must not. */
    data class Refused(
        val error: Exception,
    ) : RelayNodeKeyAttempt()
}

/**
 * How long [CloudAccount.learnRelayNodeKey] waits before each retry of an unavailable relay
 * key, in ms: quickly at first, for a blip or a cold cache, then every 30 s for as long as it
 * takes. mero-js `RELAY_NODE_KEY_RETRY_MS`.
 */
val RELAY_NODE_KEY_RETRY_MS: List<Long> = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
