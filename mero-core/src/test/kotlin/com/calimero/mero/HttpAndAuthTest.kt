package com.calimero.mero

import com.calimero.mero.http.MeroStateException
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

/**
 * Port of the parts of the Swift `HttpAndAuthTests` not already covered by [MeroAuthTest]
 * (authenticate body/refresh/terminal-revoke) and [RefreshCoordinatorTest] (single-flight refresh).
 *
 * Fills the remaining gaps: no-credentials, logout, and `auth.validateToken` valid/invalid.
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

            mero.logout()
            assertFalse(mero.isAuthenticated)
            assertNull(store.getTokens())
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
}
