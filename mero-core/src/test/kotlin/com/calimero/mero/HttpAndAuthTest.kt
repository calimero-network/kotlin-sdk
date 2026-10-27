package com.calimero.mero

import com.calimero.mero.auth.LoginFailureReason
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.NetworkException
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * Port of the parts of the Swift `HttpAndAuthTests` not already covered by [MeroAuthTest]
 * (authenticate body/refresh/terminal-revoke) and [RefreshCoordinatorTest] (single-flight refresh).
 *
 * Fills the remaining gaps: no-credentials, logout, `auth.validateToken` valid/invalid, and
 * telling a lockout or a missing login from a wrong password ([LoginFailureReason]).
 */
class HttpAndAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemoryTokenStore
    private lateinit var mero: Mero

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = MemoryTokenStore()
        mero =
            Mero(
                MeroConfig(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    tokenStore = store,
                    timeoutMs = 5_000,
                ),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `authenticate with no credentials throws`() =
        runBlocking {
            try {
                mero.authenticate()
                fail("expected MeroStateException")
            } catch (_: MeroStateException) {
                // expected — no credentials supplied on the call or in config.
            }
            Unit
        }

    @Test
    fun `logout clears the stored bundle`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody("""{"data":{"access_token":"A","refresh_token":"R"}}"""),
            )
            mero.authenticate(Credentials("a", "b"))
            assertTrue(mero.isAuthenticated)
            server.takeRequest()

            server.enqueue(MockResponse().setBody("""{"data":{"success":true}}"""))
            mero.logout()
            assertFalse(mero.isAuthenticated)
            assertNull(store.getTokens())

            // The refresh token was retired server-side first (core rc.83 `POST /auth/logout`).
            val logout = server.takeRequest()
            assertEquals("POST", logout.method)
            assertEquals("/auth/logout", logout.path)
            assertEquals("""{"refresh_token":"R"}""", logout.body.readUtf8())
        }

    @Test
    fun `logout still clears locally when the node refuses`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody("""{"data":{"access_token":"A","refresh_token":"R"}}"""),
            )
            mero.authenticate(Credentials("a", "b"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Invalid refresh token"}"""))
            mero.logout()
            assertFalse(mero.isAuthenticated)
        }

    @Test
    fun `validateToken reports a valid token`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200))
            val result = mero.auth.validateToken("good.token")
            assertTrue(result.valid)
            assertEquals(200, result.status)
        }

    @Test
    fun `validateToken reports an invalid token`() =
        runBlocking {
            // Bare 401 (no x-auth-error) — an invalid token is a normal result, never a throw and
            // never a reactive refresh.
            server.enqueue(MockResponse().setResponseCode(401))
            val result = mero.auth.validateToken("bad.token")
            assertFalse(result.valid)
            assertEquals(401, result.status)
        }

    /**
     * A lockout is not a wrong password: core answers `429` with `Retry-After` once too many
     * logins failed, and the caller has to be able to say "try again in 30 s" rather than
     * "check your password".
     */
    @Test
    fun `a lockout stays a 429 with its Retry-After`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Retry-After", "30")
                    .setBody("""{"error":"Too many failed login attempts; please try again later"}"""),
            )
            try {
                mero.authenticate(Credentials("bob", "pw"))
                fail("expected a throw")
            } catch (e: HttpException) {
                assertEquals(429, e.status)
                assertEquals(LoginFailureReason.RateLimited(retryAfterSeconds = 30.0), LoginFailureReason.of(e))
            }
            Unit
        }

    @Test
    fun `no login at the URL is not found`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            try {
                mero.authenticate(Credentials("bob", "pw"))
                fail("expected a throw")
            } catch (e: HttpException) {
                assertEquals(LoginFailureReason.NotFound, LoginFailureReason.of(e))
            }
            Unit
        }

    @Test
    fun `login failure reasons`() {
        fun http(
            status: Int,
            headers: Map<String, String> = emptyMap(),
        ) = HttpException(status, "", headers, "https://node.test/auth/token")
        assertEquals(LoginFailureReason.InvalidCredentials, LoginFailureReason.of(MeroStateException("No credentials provided")))
        assertEquals(LoginFailureReason.InvalidCredentials, LoginFailureReason.of(http(401)))
        assertEquals(LoginFailureReason.RateLimited(null), LoginFailureReason.of(http(429)))
        assertEquals(LoginFailureReason.RateLimited(null), LoginFailureReason.of(http(429, mapOf("retry-after" to "nonsense"))))
        assertEquals(LoginFailureReason.NotFound, LoginFailureReason.of(http(404)))
        assertEquals(LoginFailureReason.ServerError(502), LoginFailureReason.of(http(502)))
        assertEquals(LoginFailureReason.Unreachable, LoginFailureReason.of(NetworkException("offline")))
        assertEquals(LoginFailureReason.Unreachable, LoginFailureReason.of(SocketTimeoutException("timed out")))
        assertEquals(LoginFailureReason.Other, LoginFailureReason.of(IllegalStateException("undecodable")))
    }

    @Test
    fun `Retry-After reads seconds and an HTTP date`() {
        assertEquals(30.0, LoginFailureReason.retryAfter(" 30 ")!!, 0.0)
        assertNull(LoginFailureReason.retryAfter("-5"))
        val now = 784_111_777_000L // Sun, 06 Nov 1994 08:49:37 GMT
        assertEquals(60.0, LoginFailureReason.retryAfter("Sun, 06 Nov 1994 08:50:37 GMT", nowMs = now)!!, 0.0)
        assertEquals(0.0, LoginFailureReason.retryAfter("Sun, 06 Nov 1994 08:48:37 GMT", nowMs = now)!!, 0.0)
    }
}
