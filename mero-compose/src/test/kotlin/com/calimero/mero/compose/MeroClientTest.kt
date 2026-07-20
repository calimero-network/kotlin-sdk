package com.calimero.mero.compose

import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeroClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: MeroClient
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
        val mero = Mero(MeroConfig(baseUrl = baseUrl, tokenStore = MemoryTokenStore()))
        client = MeroClient(mero, baseUrl, allowedNodeUrls = listOf("https://trusted.example.com"))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `login flips authenticated state`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"access_token":"a","refresh_token":"r"}}"""))
        client.login("alice", "pw")
        val state = client.state.value
        assertTrue(state.isAuthenticated)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `login failure surfaces an error and stays logged out`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        client.login("alice", "wrong")
        val state = client.state.value
        assertFalse(state.isAuthenticated)
        assertTrue(state.error != null)
    }

    @Test
    fun `handleAuthCallback adopts tokens from a trusted node`() {
        val url = "$baseUrl#access_token=aaa&refresh_token=bbb&application_id=app1&node_url=$baseUrl"
        val adopted = client.handleAuthCallback(url)
        assertTrue(adopted)
        assertTrue(client.state.value.isAuthenticated)
        assertEquals("app1", client.state.value.applicationId)
    }

    @Test
    fun `handleAuthCallback rejects an untrusted node_url`() {
        val url = "$baseUrl#access_token=aaa&refresh_token=bbb&node_url=https%3A%2F%2Fevil.example.com"
        val adopted = client.handleAuthCallback(url)
        assertFalse(adopted)
        assertFalse(client.state.value.isAuthenticated)
        assertTrue(client.state.value.error != null)
    }

    @Test
    fun `logout resets state`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"access_token":"a","refresh_token":"r"}}"""))
        client.login("alice", "pw")
        assertTrue(client.state.value.isAuthenticated)

        client.logout()
        assertFalse(client.state.value.isAuthenticated)
        assertNull(client.mero.getTokenData())
    }
}
