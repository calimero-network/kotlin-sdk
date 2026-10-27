package com.calimero.mero

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `validateToken` never throws, so it must say whether the node judged the token at all: an app
 * that keeps working offline treats "no answer" and "refused" very differently. Port of the Swift
 * `TokenValidationTests`.
 */
class TokenValidationTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun mero(baseUrl: String = server.url("/").toString().trimEnd('/')) = Mero(MeroConfig(baseUrl = baseUrl, timeoutMs = 5_000))

    private fun answer(status: Int) = server.enqueue(MockResponse().setResponseCode(status))

    @Test
    fun `an accepted token`() =
        runBlocking {
            answer(200)
            val validation = mero().auth.validateToken("t")
            assertTrue(validation.valid)
            assertFalse(validation.isUnreachable)
        }

    @Test
    fun `a refused token is a refusal`() =
        runBlocking {
            answer(403)
            val validation = mero().auth.validateToken("t")
            assertFalse(validation.valid)
            assertFalse("the node answered, and said no", validation.isUnreachable)
        }

    @Test
    fun `a 5xx is no verdict`() =
        runBlocking {
            answer(503)
            val validation = mero().auth.validateToken("t")
            assertFalse(validation.valid)
            assertTrue(validation.isUnreachable)
            assertEquals(503, validation.status)
        }

    @Test
    fun `no network is no verdict`() =
        runBlocking {
            val closed = server.url("/").toString().trimEnd('/')
            server.shutdown()
            val validation = mero(closed).auth.validateToken("t")
            assertFalse(validation.valid)
            assertTrue(validation.isUnreachable)
            assertEquals("unchanged for callers that read the status", 401, validation.status)
        }
}
