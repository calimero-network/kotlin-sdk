package com.calimero.mero.http

import kotlinx.coroutines.delay
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * Retry helper with exponential backoff + jitter. Retries on [NetworkException] and 5xx/429
 * [HttpException]s only; 4xx (including auth) and [AuthRevokedException] are never retried.
 *
 * Port of mero-js `retry.ts`. OkHttp's own `retryOnConnectionFailure` covers connection-level
 * retries; this wraps whole logical operations where the caller wants app-level retries.
 * A `Retry-After` on an HTTP failure lengthens the wait ([retryAfterMs]).
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
            delay(maxOf((backoff + jitter).toLong().coerceAtLeast(0), retryAfterMs(e) ?: 0))
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

/**
 * How long a `Retry-After` header asks the caller to wait, in milliseconds: delta-seconds or an
 * HTTP date, the date capped at 60 s. `null` when the error carries none. (Swift `retryAfter`;
 * the `Retry-After` handling in mero-js `withRetry`.)
 */
internal fun retryAfterMs(
    error: Throwable,
    now: Long = System.currentTimeMillis(),
): Long? {
    val http = error as? HttpException ?: return null
    val value =
        http.headers.entries
            .firstOrNull { it.key.equals("retry-after", ignoreCase = true) }
            ?.value
    val trimmed = value?.trim() ?: return null
    trimmed.toLongOrNull()?.let { return it.coerceAtLeast(0) * 1000 }
    val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
    format.timeZone = TimeZone.getTimeZone("GMT")
    val date =
        try {
            format.parse(trimmed)
        } catch (_: ParseException) {
            null
        } ?: return null
    return (date.time - now).coerceIn(0, MAX_RETRY_AFTER_MS)
}

private const val MAX_RETRY_AFTER_MS = 60_000L
