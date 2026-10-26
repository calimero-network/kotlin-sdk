package com.calimero.mero

import com.calimero.mero.http.NetworkException
import com.calimero.mero.http.OkHttpTransport
import com.calimero.mero.sse.LineLimitedSource
import com.calimero.mero.sse.SseConnection
import com.calimero.mero.sse.SseLimitExceeded
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * SEC-08: a response body and an SSE line are read up to a limit, and a larger one is refused
 * rather than buffered. Mirrors the Swift `ResponseLimitTests`.
 */
class ResponseLimitTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun transport(limit: Long) =
        OkHttpTransport(server.url("/").toString().trimEnd('/'), OkHttpClient(), Json, maxResponseBytes = limit)

    private suspend fun assertTooLarge(work: suspend () -> Unit) {
        try {
            work()
            fail("expected the body to be refused")
        } catch (e: NetworkException) {
            assertTrue(e.message, e.message.orEmpty().contains("exceeds"))
        }
    }

    // HTTP bodies

    @Test
    fun `the default limit is sixteen mebibytes`() {
        assertEquals(16L * 1024 * 1024, OkHttpTransport.DEFAULT_MAX_RESPONSE_BYTES)
        assertEquals(OkHttpTransport.DEFAULT_MAX_RESPONSE_BYTES, MeroConfig(baseUrl = "https://node.test").maxResponseBytes)
    }

    @Test
    fun `a body at the limit is read`() =
        runBlocking {
            server.enqueue(MockResponse().setChunkedBody("a".repeat(1024), 100))
            val res = transport(1024).execute("GET", "/x")
            assertEquals(1024, res.body.length)
            assertEquals(200, res.status)
        }

    @Test
    fun `a body over the limit is refused`() =
        runBlocking {
            server.enqueue(MockResponse().setChunkedBody("a".repeat(1025), 100))
            assertTooLarge { transport(1024).execute("GET", "/x") }
        }

    @Test
    fun `a content length over the limit is refused before the body is read`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("a".repeat(2048)))
            assertTooLarge { transport(1024).execute("GET", "/x") }
        }

    @Test
    fun `an error body is held to the limit too`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500).setChunkedBody("a".repeat(4096), 100))
            assertTooLarge { transport(1024).execute("GET", "/x") }
        }

    @Test
    fun `MeroConfig sets the limit and a blob is not held to it`() =
        runBlocking {
            val blob = ByteArray(4096) { 0x7f }
            server.enqueue(MockResponse().setBody(Buffer().write(blob)))
            server.enqueue(MockResponse().setBody("""{"data":{"blobs":[]}}""" + " ".repeat(4096)))
            val mero = Mero(MeroConfig(baseUrl = server.url("/").toString().trimEnd('/'), maxResponseBytes = 1024))

            assertArrayEquals("a blob download is not limited", blob, mero.admin.getBlob("b1", null))
            assertTooLarge { mero.admin.listBlobs() }
        }

    // SSE lines

    private fun lines(
        text: String,
        limit: Long,
    ): List<String> {
        val source = LineLimitedSource(Buffer().writeUtf8(text), limit).buffer()
        // Count every byte through the limiter, then split the way the SSE spec does.
        val read = source.readUtf8()
        return read.split(Regex("\r\n|\r|\n")).let { if (read.endsWith("\n") || read.endsWith("\r")) it.dropLast(1) else it }
    }

    private fun assertSseTooLarge(work: () -> Unit) {
        try {
            work()
            fail("expected the stream to be dropped")
        } catch (e: SseLimitExceeded) {
            assertTrue(e is IOException)
        }
    }

    @Test
    fun `splits lines on LF, CRLF and CR`() {
        val read = lines("data: a\nid: 1\r\n\rdata: b\r\n\ndata: c", limit = 64)
        assertEquals(listOf("data: a", "id: 1", "", "data: b", "", "data: c"), read)
    }

    @Test
    fun `a line at the limit is read`() {
        val line = "data:" + "x".repeat(59)
        assertEquals(listOf(line), lines(line + "\n", limit = 64))
    }

    @Test
    fun `a line over the limit drops the stream`() {
        val line = "data:" + "x".repeat(60)
        assertSseTooLarge { lines(line + "\n", limit = 64) }
        assertSseTooLarge { lines(":" + "x".repeat(64), limit = 64) }
    }

    @Test
    fun `an event over the limit drops the stream even in short lines`() {
        val short = "data:" + "x".repeat(20) + "\n"
        assertSseTooLarge { lines(short.repeat(4), limit = 64) }
    }

    @Test
    fun `a blank line ends the event and comments do not count`() {
        val short = "data:" + "x".repeat(20) + "\n"
        val keepAlive = ":" + "k".repeat(40) + "\n"
        val stream = (short + short + keepAlive + "\n").repeat(10)
        assertEquals(40, lines(stream, limit = 64).size)
    }

    @Test
    fun `the connection limit is one mebibyte`() {
        assertEquals(1024L * 1024, SseConnection.MAX_LINE_BYTES)
    }
}
