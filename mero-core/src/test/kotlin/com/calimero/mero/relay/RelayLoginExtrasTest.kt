@file:OptIn(DevOnlyMeroApi::class)

package com.calimero.mero.relay

import com.calimero.mero.DevOnlyMeroApi
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

/** [PinnedRelayKeyVerifier] and [RelayLogin.logout] (Swift SDK `RelayLogin.swift` parity). */
class RelayLoginExtrasTest {
    private lateinit var node: FakeNode
    private lateinit var server: MockWebServer
    private lateinit var base: String

    @Before
    fun setUp() {
        node = FakeNode()
        server = node.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a pinned verifier answers with its key, whatever the relay attests to`() =
        runBlocking {
            node.cloud.nodeKey = "ab".repeat(32)
            val pinned = "CD".repeat(32)
            assertEquals("cd".repeat(32), RelayLogin(PinnedRelayKeyVerifier(pinned)).attestNodeKey(base))
            // The TLS-only verifier takes the relay's word for it (a dev rig's mock, when allowed).
            assertEquals("ab".repeat(32), RelayLogin(TlsRelayKeyVerifier(allowMock = true)).attestNodeKey(base))
            assertTrue("a loopback mock is refused by default", runCatching { RelayLogin(TlsRelayKeyVerifier()).attestNodeKey(base) }.isFailure)
        }

    @Test
    fun `a pinned verifier refuses a key that is not 32 bytes of hex`() {
        for (bad in listOf("", "ab".repeat(31), "zz".repeat(32))) {
            assertTrue(runCatching { PinnedRelayKeyVerifier(bad) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `logout retires the refresh token on the relay`() =
        runBlocking {
            val recorder = MockWebServer().apply { start() }
            try {
                recorder.enqueue(MockResponse().setBody("""{"data":{"success":true}}"""))
                assertTrue(RelayLogin().logout(recorder.url("/").toString(), "refresh-1"))
                val req = recorder.takeRequest()
                assertEquals("POST", req.method)
                assertEquals("/auth/logout", req.path)
                assertEquals("""{"refresh_token":"refresh-1"}""", req.body.readUtf8())
            } finally {
                recorder.shutdown()
            }
            assertTrue(RelayLogin().logout(base, "refresh-2"))
            assertEquals(1, node.logoutCalls)
        }

    @Test
    fun `logout is best effort and never throws`() =
        runBlocking {
            assertFalse(RelayLogin().logout(base, ""))
            assertEquals(0, node.logoutCalls)
            val refusing = MockWebServer().apply { start() }
            try {
                refusing.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid refresh token"}"""))
                assertFalse(RelayLogin().logout(refusing.url("/").toString(), "stale"))
            } finally {
                refusing.shutdown()
            }
            val closed = ServerSocket(0).use { it.localPort }
            assertFalse(RelayLogin().logout("http://127.0.0.1:$closed", "refresh"))
        }
}
