package com.calimero.mero

import com.calimero.mero.http.AuthRevokedException
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class MeroAuthTest {

    private lateinit var server: MockWebServer
    private lateinit var store: MemoryTokenStore
    private lateinit var mero: Mero

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
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

    @Test
    fun `authenticate posts the mero request shape and stores tokens`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"data":{"access_token":"access1","refresh_token":"refresh1"}}"""),
        )

        val data = mero.authenticate(Credentials("alice", "secret", bootstrapSecret = "boot-123"))

        assertEquals("access1", data.accessToken)
        assertEquals("refresh1", data.refreshToken)
        assertEquals("access1", store.getTokens()?.accessToken)

        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/auth/token", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"client_name\":\"mero-kotlin-sdk\""))
        assertTrue(body.contains("\"auth_method\":\"user_password\""))
        assertTrue(body.contains("\"username\":\"alice\""))
        assertTrue(body.contains("\"password\":\"secret\""))
        assertTrue(body.contains("\"bootstrap_secret\":\"boot-123\""))
    }

    @Test
    fun `omits bootstrap_secret when not provided`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"data":{"access_token":"a","refresh_token":"r"}}"""),
        )
        mero.authenticate(Credentials("bob", "pw"))
        val body = server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()
        assertTrue(!body.contains("bootstrap_secret"))
    }

    @Test
    fun `401 token_expired triggers a single refresh then retries the request`() = runBlocking {
        mero.setTokenData(TokenData("old.access", "refresh1", Long.MAX_VALUE))

        server.enqueue(MockResponse().setResponseCode(401).addHeader("x-auth-error", "token_expired"))
        server.enqueue(
            MockResponse().setBody("""{"data":{"access_token":"new.access","refresh_token":"refresh2"}}"""),
        )
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"output":true}}"""))

        val result = mero.rpc.execute<Boolean>("ctx", "method")

        assertTrue(result)
        assertEquals("new.access", store.getTokens()?.accessToken)
        assertEquals("refresh2", store.getTokens()?.refreshToken)

        assertEquals("/jsonrpc", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        assertEquals("/auth/refresh", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        assertEquals("/jsonrpc", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun `terminal x-auth-error clears the store and throws AuthRevokedException`() = runBlocking {
        mero.setTokenData(TokenData("old.access", "refresh1", Long.MAX_VALUE))
        server.enqueue(MockResponse().setResponseCode(401).addHeader("x-auth-error", "token_reuse"))

        try {
            mero.rpc.executeRaw("ctx", "method")
            fail("expected AuthRevokedException")
        } catch (e: AuthRevokedException) {
            assertEquals("token_reuse", e.reason)
        }
        assertNull(store.getTokens())
    }
}
