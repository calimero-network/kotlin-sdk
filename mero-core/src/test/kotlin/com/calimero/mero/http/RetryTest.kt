package com.calimero.mero.http

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun http(
    status: Int,
    headers: Map<String, String> = emptyMap(),
) = HttpException(status, "", headers, "u")

/** [withRetry] under virtual time. (Swift `WithRetryTests`.) */
class RetryTest {
    @Test
    fun `retries a retryable failure until it succeeds`() =
        runTest {
            var calls = 0
            val value =
                withRetry(attempts = 3) {
                    if (++calls < 3) throw http(503)
                    calls
                }
            assertEquals(3, value)
            assertEquals(3, calls)
            // Two backoffs, 250 ms and 500 ms, each ±20%.
            assertTrue(currentTime in 600L..900L)
        }

    @Test
    fun `does not retry a refusal`() =
        runTest {
            var calls = 0
            try {
                withRetry(attempts = 3) {
                    calls++
                    throw http(400)
                }
                fail("expected a throw")
            } catch (e: HttpException) {
                assertEquals(400, e.status)
            }
            assertEquals(1, calls)
        }

    @Test
    fun `gives up after the last attempt`() =
        runTest {
            var calls = 0
            runCatching {
                withRetry(attempts = 2) {
                    calls++
                    throw NetworkException("offline")
                }
            }
            assertEquals(2, calls)
        }

    @Test
    fun `honours Retry-After`() =
        runTest {
            var calls = 0
            withRetry(attempts = 2) {
                if (++calls == 1) throw http(429, mapOf("Retry-After" to "7"))
            }
            assertEquals(7_000L, currentTime)
        }

    @Test
    fun `Retry-After reads an HTTP date capped at a minute`() {
        val now = 1_700_000_000_000L // Tue, 14 Nov 2023 22:13:20 GMT
        val soon = http(503, mapOf("retry-after" to "Tue, 14 Nov 2023 22:13:30 GMT"))
        assertEquals(10_000L, retryAfterMs(soon, now))
        val late = http(503, mapOf("retry-after" to "Wed, 15 Nov 2023 22:13:20 GMT"))
        assertEquals(60_000L, retryAfterMs(late, now))
        assertNull(retryAfterMs(http(503), now))
    }
}
