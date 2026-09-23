package com.calimero.mero

import com.calimero.mero.http.AuthRevokedException
import com.calimero.mero.http.HttpException
import com.calimero.mero.sse.SseClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * A `403` on the event stream must end the flow, not restart it forever.
 *
 * Core 0.11.0-rc.41 (core#3942) put the **`context:subscribe`** permission on `/sse`,
 * `/sse/subscription` and `/sse/session/{id}`. Before it they required no permission at
 * all, so every token could open a stream and this client's one reconnect path was
 * adequate. Now two different tokens are refused outright — one minted with a scoped
 * permission list that never named `context:subscribe`, and one whose refresh family was
 * revoked (which core answers as a 403 with `x-auth-error` and an EMPTY body).
 *
 * Neither is fixed by waiting. Retrying every three seconds forever is not resilience
 * here, it is a silent outage: the app sees a live-looking subscription delivering
 * nothing, and no exception ever reaches the collector. That is the mero-js#166 shape of
 * bug, and these tests are what keeps it from coming back.
 *
 * A non-403 failure must still reconnect — that part was never wrong.
 */
class SseForbiddenTest {
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

    private fun client() =
        SseClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = { "an-access-token" },
        )

    /** A token without `context:subscribe`: refused, with no `x-auth-error` to explain it. */
    @Test
    fun `a bare 403 fails the flow instead of reconnecting`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody(""))

        val error =
            runCatching {
                runBlocking { withTimeout(10_000) { client().events(listOf("ctx-1")).first() } }
            }.exceptionOrNull()

        assertTrue("expected an HttpException, got $error", error is HttpException)
        assertEquals(403, (error as HttpException).status)
        // One attempt, and only one: a second request would mean it reconnected.
        assertEquals(1, server.requestCount)
    }

    /**
     * A revoked family: 403, `x-auth-error`, empty body. It gets the type apps already
     * catch to force a fresh login, rather than a generic transport error.
     */
    @Test
    fun `a revoked family surfaces as AuthRevokedException`() {
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("x-auth-error", "token_revoked").setBody(""),
        )

        val error =
            runCatching {
                runBlocking { withTimeout(10_000) { client().events(listOf("ctx-1")).first() } }
            }.exceptionOrNull()

        assertTrue("expected an AuthRevokedException, got $error", error is AuthRevokedException)
        assertEquals("token_revoked", (error as AuthRevokedException).reason)
    }

    /**
     * Everything that is not a 403 is still a hiccup: a node restarting, a proxy
     * dropping the connection. Those reconnect, exactly as before — losing that would
     * trade one silent failure for another.
     */
    @Test
    fun `a 500 still reconnects`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))

        runCatching {
            runBlocking { withTimeout(10_000) { client().events(listOf("ctx-1")).first() } }
        }

        assertEquals("/sse?token=an-access-token", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertEquals(
            "a non-403 must be retried",
            "/sse?token=an-access-token",
            server.takeRequest(10, TimeUnit.SECONDS)?.path,
        )
    }
}
