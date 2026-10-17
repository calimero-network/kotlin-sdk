package com.calimero.mero.sse

import com.calimero.mero.ws.WsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ReconnectBackoff]: the Swift SDK's `testBackoffPolicy`, in milliseconds. */
class ReconnectBackoffTest {
    @Test
    fun `the SSE default is 3 s doubling to 30 s with 20 percent jitter`() {
        val sse = ReconnectBackoff.SSE
        assertEquals(3_000L, sse.delayMs(0, random = 0.5))
        assertEquals(24_000L, sse.delayMs(3, random = 0.5))
        assertEquals("capped", 30_000L, sse.delayMs(4, random = 0.5))
        assertEquals("-20%", 2_400L, sse.delayMs(0, random = 0.0))
        assertEquals("+20%", 3_600L, sse.delayMs(0, random = 0.999_999))
        assertEquals("never past the cap", 30_000L, sse.delayMs(10, random = 0.999))
        assertEquals(30_000L, sse.delayMs(1000, random = 0.5))
    }

    @Test
    fun `jitter stays within 20 percent of the base`() {
        repeat(200) {
            val d = ReconnectBackoff.SSE.delayMs(1)
            assertTrue("$d", d in 4_800L..7_200L)
        }
    }

    @Test
    fun `the WebSocket schedule is WsClient's 1 s times 2 to the n`() {
        val ws = ReconnectBackoff.WEB_SOCKET
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        assertEquals(expected, (0..6).map { ws.delayMs(it, random = 0.9) })
        assertEquals(expected, (0..6).map { WsClient.backoffMs(it) })
    }

    @Test
    fun `fixed is mero-js reconnectDelayMs`() {
        val fixed = ReconnectBackoff.fixed(3_000L)
        assertEquals(listOf(3_000L, 3_000L, 3_000L, 3_000L), (0..3).map { fixed.delayMs(it) })
    }

    @Test
    fun `a negative attempt or delay never waits less than nothing`() {
        assertEquals(3_000L, ReconnectBackoff.SSE.delayMs(-5, random = 0.5))
        assertEquals(0L, ReconnectBackoff(initialDelayMs = -1).delayMs(0))
    }
}
