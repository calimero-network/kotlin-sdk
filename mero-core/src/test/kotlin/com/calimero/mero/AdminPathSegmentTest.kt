package com.calimero.mero

import com.calimero.mero.admin.JoinNamespaceRequest
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * SEC-02: an id is exactly one path segment, whatever it holds. A `/`, `?` or `#` in it is
 * encoded rather than read as structure, and a dot segment is refused before anything is sent.
 * Mirrors the Swift `AdminPathSegmentTests`.
 */
class AdminPathSegmentTest {
    private lateinit var server: MockWebServer
    private lateinit var mero: Mero
    private val invitation = SignedGroupOpenInvitation(JsonObject(emptyMap()))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        mero =
            Mero(
                MeroConfig(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    tokenStore = MemoryTokenStore(),
                    timeoutMs = 5_000,
                ),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun capture(block: suspend () -> Unit): RecordedRequest {
        server.enqueue(MockResponse().setBody("{}").setHeader("Content-Type", "application/json"))
        runCatching { runBlocking { block() } }
        return server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request captured")
    }

    @Test
    fun `a slash or query in an id cannot change the route`() {
        val join = capture { mero.admin.joinNamespace("abc/leave?x=", JoinNamespaceRequest(invitation)) }
        assertEquals("POST", join.method)
        assertEquals("/admin-api/namespaces/abc%2Fleave%3Fx%3D/join", join.path)
        assertNull(join.requestUrl?.query)

        assertEquals("/admin-api/contexts/..%2Fcontexts%2Fx", capture { mero.admin.getContext("../contexts/x") }.path)
        assertEquals("/admin-api/blobs/b%23frag", capture { mero.admin.deleteBlob("b#frag") }.path)
    }

    @Test
    fun `a dot segment is refused before anything is sent`() {
        for (bad in listOf("", ".", "..")) {
            val error = runCatching { runBlocking { mero.admin.joinNamespace(bad, JoinNamespaceRequest(invitation)) } }.exceptionOrNull()
            assertEquals(IllegalArgumentException::class.java, error?.javaClass)
            assertEquals("Not a path segment: \"$bad\"", error?.message)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a real id is sent as is`() {
        val ns = "ab".repeat(32)
        assertEquals("/admin-api/namespaces/$ns", capture { mero.admin.getNamespace(ns) }.path)
    }
}
