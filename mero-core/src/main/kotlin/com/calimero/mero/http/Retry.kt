package com.calimero.mero.http

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Retry helper with exponential backoff + jitter. Retries on [NetworkException] and 5xx/429
 * [HttpException]s only; 4xx (including auth) and [AuthRevokedException] are never retried.
 *
 * Port of mero-js `retry.ts`. OkHttp's own `retryOnConnectionFailure` covers connection-level
 * retries; this wraps whole logical operations where the caller wants app-level retries.
 */
suspend fun <T> withRetry(
    attempts: Int = 3,
    baseDelayMs: Long = 250,
    block: suspend () -> T,
): T {
    var lastError: Throwable? = null
    repeat(attempts) { attempt ->
        try {
            return block()
        } catch (e: Throwable) {
            lastError = e
            if (attempt == attempts - 1 || !isRetryable(e)) throw e
            val backoff = baseDelayMs * (1L shl attempt)
            val jitter = (Random.nextDouble() - 0.5) * 0.4 * backoff
            delay((backoff + jitter).toLong().coerceAtLeast(0))
        }
    }
    val err = lastError
    if (err != null) throw err
    error("Retry failed without error")
}

private fun isRetryable(error: Throwable): Boolean =
    when (error) {
        is AuthRevokedException -> false
        is HttpException -> error.status >= 500 || error.status == 429
        is NetworkException -> true
        else -> false
    }
