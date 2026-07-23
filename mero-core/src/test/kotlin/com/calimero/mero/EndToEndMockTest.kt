package com.calimero.mero

import com.calimero.mero.http.AuthRevokedException
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
 * Full end-to-end journeys driven entirely by [FakeNode] (no live node) — the Kotlin port of the
 * Swift `EndToEndMockTests`. These exercise the whole SDK the way an app would and assert the auth
 * state machine behaves across a realistic sequence of calls, using the node's own call counters.
 */
class EndToEndMockTest {

    private lateinit var node: FakeNode
    private lateinit var server: MockWebServer
    private lateinit var store: MemoryTokenStore
    private lateinit var mero: Mero

    @Before
    fun setUp() {
        node = FakeNode()
        server = node.start()
        store = MemoryTokenStore()
        mero = Mero(
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

    /** Login → probe node → authenticate → identity → list contexts → rpc → logout. */
    @Test
    fun `happy path journey`() = runBlocking {
        // Pre-auth, node metadata is reachable.
        assertEquals("alive", mero.auth.getHealth().status)
        assertEquals(1, mero.auth.getProviders().count)

        // Authenticate.
        val tokens = mero.authenticate(Credentials("dev", "dev-password"))
        assertEquals("access-1", tokens.accessToken)
        assertTrue(mero.isAuthenticated)

        // The stored access token validates.
        assertTrue(mero.auth.validateToken(tokens.accessToken).valid)

        // Identity + a protected admin read.
        assertEquals("embedded", mero.auth.getIdentity().authenticationMode)
        assertEquals(0, mero.admin.getContexts().contexts.size)

        // A contract call.
        val value = mero.rpc.execute<Int>("ctx", "get")
        assertEquals(42, value)

        // Logout clears everything.
        mero.logout()
        assertFalse(mero.isAuthenticated)
        assertNull(store.getTokens())
        assertEquals("no refresh on the happy path", 0, node.refreshCalls)
    }

    /**
     * Access token expires mid-journey → a protected call transparently triggers exactly one
     * refresh and then succeeds; the rotated bundle is persisted.
     */
    @Test
    fun `refresh mid journey`() = runBlocking {
        mero.authenticate(Credentials("dev", "pw"))
        assertEquals("access-1", store.getTokens()?.accessToken)

        // Simulate the access token expiring on the node.
        node.expireAccessToken()

        // Next protected call: 401 token_expired → refresh → retry succeeds.
        val value = mero.rpc.execute<Int>("ctx", "get")
        assertEquals(42, value)

        assertEquals("exactly one refresh", 1, node.refreshCalls)
        assertEquals("access-2", store.getTokens()?.accessToken)
        assertEquals("refresh-2", store.getTokens()?.refreshToken)
    }

    /**
     * Eight concurrent protected calls after expiry must share a single refresh (single-use refresh
     * tokens — a double refresh would revoke the family).
     */
    @Test
    fun `concurrent calls share one refresh`() = runBlocking {
        mero.authenticate(Credentials("dev", "pw"))
        node.expireAccessToken()

        val results = (1..8).map {
            async(Dispatchers.Default) { mero.rpc.execute<Int>("ctx", "get") }
        }.awaitAll()

        results.forEach { assertEquals(42, it) }
        assertEquals("concurrent 401s must share one refresh", 1, node.refreshCalls)
    }

    /** A revoked family surfaces as [AuthRevokedException] and clears the local bundle. */
    @Test
    fun `revoked family forces re-login`() = runBlocking {
        mero.authenticate(Credentials("dev", "pw"))
        node.revokeFamily()

        try {
            mero.rpc.execute<Int>("ctx", "get")
            fail("expected AuthRevokedException")
        } catch (e: AuthRevokedException) {
            assertEquals("token_reuse", e.reason)
        }

        assertFalse(mero.isAuthenticated)
        assertNull(store.getTokens())
    }

    /** Re-authenticating after a revoke recovers a working session. */
    @Test
    fun `re-authenticate after revoke`() = runBlocking {
        mero.authenticate(Credentials("dev", "pw"))
        node.revokeFamily()
        runCatching { mero.rpc.execute<Int>("ctx", "get") }

        // Fresh login mints a new family; protected calls work again.
        val tokens = mero.authenticate(Credentials("dev", "pw"))
        assertEquals("access-2", tokens.accessToken)
        assertEquals(42, mero.rpc.execute<Int>("ctx", "get"))
    }
}
