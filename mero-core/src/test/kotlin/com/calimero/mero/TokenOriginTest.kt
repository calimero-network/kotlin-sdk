package com.calimero.mero

import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.storage.TokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Token persistence is bound to the issuing node. Port of mero-js
 * `src/mero-js-token-origin.test.ts`: two clients for different nodes sharing one store
 * (the default `localStorage` key there; one [TokenStore] here) never send each other's tokens.
 */
class TokenOriginTest {
    private lateinit var server: MockWebServer
    private val seen: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val refreshed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile private var expireOnce = false

    /** One slot, like mero-js's `mero-tokens` localStorage key. */
    private val shared: TokenStore = MemoryTokenStore()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (path.endsWith("/auth/refresh")) {
                        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        refreshed += body["refresh_token"]!!.jsonPrimitive.content
                        return MockResponse().setBody("""{"data":{"access_token":"b-access-2","refresh_token":"b-refresh-2"}}""")
                    }
                    seen += request.getHeader("Authorization")
                    if (expireOnce) {
                        expireOnce = false
                        return MockResponse().setResponseCode(401).setHeader("x-auth-error", "token_expired").setBody("{}")
                    }
                    return MockResponse().setBody("{}")
                }
            }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun bundle(name: String) = TokenData("$name-access", "$name-refresh", System.currentTimeMillis() + 3_600_000)

    private fun mero(baseUrl: String) = Mero(MeroConfig(baseUrl = baseUrl, tokenStore = shared))

    private fun url(path: String = ""): String = server.url(path).toString().let { if (path.isEmpty()) it.trimEnd('/') else it }

    private fun call(mero: Mero) = runBlocking { mero.http.execute("GET", "/admin-api/contexts") }

    @Test
    fun `does not send node A tokens to node B through a shared store`() {
        mero("https://node-a.example").setTokenData(bundle("a"))
        val b = mero(url())
        call(b)
        assertEquals(1, seen.size)
        assertFalse(seen[0].orEmpty().contains("a-access"))
        assertFalse(b.isAuthenticated)
    }

    @Test
    fun `restores the tokens on the node that issued them`() {
        mero(url()).setTokenData(bundle("a"))
        val again = mero(url())
        call(again)
        assertTrue(again.isAuthenticated)
        assertEquals(listOf("Bearer a-access"), seen)
    }

    @Test
    fun `ignores a stored bundle that carries no node origin`() {
        shared.setTokens(bundle("legacy"))
        val mero = mero(url())
        call(mero)
        assertFalse(seen[0].orEmpty().contains("legacy-access"))
        assertFalse(mero.isAuthenticated)
    }

    @Test
    fun `ignores a foreign-origin bundle when the refresh re-reads the store`() {
        val b = mero(url())
        b.setTokenData(bundle("b"))
        mero("https://node-a.example").setTokenData(bundle("a"))
        expireOnce = true
        call(b)
        assertEquals(listOf("b-refresh"), refreshed)
        assertEquals("b-refresh-2", b.getTokenData()?.refreshToken)
        assertEquals(listOf("Bearer b-access", "Bearer b-access-2"), seen)
    }

    @Test
    fun `does not send tokens to another node on the same origin`() {
        val pairs = listOf("/node1" to "/node2", "/node2" to "/node1", "" to "/node1")
        for ((issuer, other) in pairs) {
            shared.clear()
            seen.clear()
            mero(url(issuer)).setTokenData(bundle("a"))
            val mero = mero(url(other))
            call(mero)
            assertFalse("$issuer -> $other", seen[0].orEmpty().contains("a-access"))
            assertFalse("$issuer -> $other", mero.isAuthenticated)
        }
    }

    @Test
    fun `treats a trailing slash on the base URL as the same node`() {
        mero(url("/node1/")).setTokenData(bundle("a"))
        val mero = mero(url("/node1").trimEnd('/'))
        call(mero)
        assertEquals(listOf("Bearer a-access"), seen)
    }

    @Test
    fun `persists the node origin in snake_case and hands bundles back without it`() {
        val mero = mero("https://Host.Example:443/node1/")
        mero.setTokenData(bundle("a"))
        assertEquals("https://host.example/node1", shared.getTokens()?.nodeOrigin)
        assertNull(mero.getTokenData()?.nodeOrigin)
        val encoded = Json.encodeToString(TokenData.serializer(), shared.getTokens()!!)
        assertTrue(encoded, encoded.contains("\"node_origin\":\"https://host.example/node1\""))
    }

    @Test
    fun `a bundle written before node_origin existed still decodes`() {
        val old = Json.decodeFromString(TokenData.serializer(), """{"access_token":"x","refresh_token":"y","expires_at":1}""")
        assertEquals(TokenData("x", "y", 1), old)
    }

    @Test
    fun `clearing tokens leaves another node's bundle alone`() {
        val b = mero(url())
        b.setTokenData(bundle("b"))
        mero("https://node-a.example").setTokenData(bundle("a"))
        b.clearToken()
        assertFalse(b.isAuthenticated)
        assertEquals("a-access", shared.getTokens()?.accessToken)
    }

    @Test
    fun `nodeOriginOf matches mero-js nodeKeyOf`() {
        assertEquals("https://host.example", nodeOriginOf("https://host.example"))
        assertEquals("https://host.example", nodeOriginOf("https://host.example/"))
        assertEquals("https://host.example/node1", nodeOriginOf("https://host.example/node1//"))
        assertEquals("http://localhost:2428", nodeOriginOf("http://localhost:2428/"))
        assertEquals("http://h.example", nodeOriginOf("http://h.example:80"))
        assertEquals("http://[::1]:2428", nodeOriginOf("http://[::1]:2428"))
        assertEquals("not a url", nodeOriginOf("not a url"))
    }
}
