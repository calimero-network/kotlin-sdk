package com.calimero.mero.sse

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * How long an event client waits before reconnecting after a drop.
 *
 * The delay for the `n`th consecutive failed attempt (counting from 0) is
 * `min(initialDelayMs · multiplierⁿ, maxDelayMs)`, spread by `±jitter` (a fraction) and never
 * more than [maxDelayMs]. A stream that opens resets the count, so a stream that ran for a
 * while and then dropped waits [initialDelayMs] again.
 *
 * - [SSE]: 3 s first, as mero-js's `reconnectDelayMs` default, then doubling to 30 s while the
 *   node keeps refusing or is unreachable, ±20% jitter. mero-js waits a flat 3 s forever; the
 *   backoff only differs once a reconnect has failed, and keeps a fleet of clients from
 *   retrying in step against a node that is down. [fixed] restores the flat delay.
 * - [WEB_SOCKET]: mero-js `WsClient`'s `1 s · 2ⁿ`, capped at 30 s, no jitter — the schedule
 *   [com.calimero.mero.ws.WsClient.backoffMs] computes.
 *
 * Same policy as the Swift SDK's `ReconnectBackoff`.
 */
data class ReconnectBackoff(
    /** The first delay, in milliseconds. */
    val initialDelayMs: Long,
    /** What each further failed attempt multiplies the delay by. */
    val multiplier: Double = 2.0,
    /** The longest delay, in milliseconds. */
    val maxDelayMs: Long = 30_000L,
    /** The spread, as a fraction of the delay: `0.2` is ±20%. */
    val jitter: Double = 0.0,
) {
    /**
     * The wait, in milliseconds, before reconnect attempt [attempt] (0-based). [random] is a
     * uniform draw in `[0, 1)`, injectable so tests are deterministic; `0.5` means no spread.
     */
    fun delayMs(
        attempt: Int,
        random: Double = kotlin.random.Random.nextDouble(),
    ): Long {
        val exponent = attempt.coerceIn(0, MAX_EXPONENT).toDouble()
        val max = maxDelayMs.toDouble()
        val base = minOf(initialDelayMs.coerceAtLeast(0).toDouble() * multiplier.pow(exponent), max)
        val spread = base * jitter * (2 * random - 1)
        return (base + spread).coerceIn(0.0, max).roundToLong()
    }

    companion object {
        private const val MAX_EXPONENT = 32

        /** The SSE default: 3 s, doubling to 30 s, ±20% jitter. */
        val SSE = ReconnectBackoff(initialDelayMs = 3_000L, multiplier = 2.0, maxDelayMs = 30_000L, jitter = 0.2)

        /** The WebSocket schedule (mero-js `WsClient`): 1 s, doubling to 30 s, no jitter. */
        val WEB_SOCKET = ReconnectBackoff(initialDelayMs = 1_000L, multiplier = 2.0, maxDelayMs = 30_000L, jitter = 0.0)

        /** The same delay every time — mero-js `SseClient`'s `reconnectDelayMs`. */
        fun fixed(delayMs: Long): ReconnectBackoff = ReconnectBackoff(initialDelayMs = delayMs, multiplier = 1.0, maxDelayMs = delayMs, jitter = 0.0)
    }
}
