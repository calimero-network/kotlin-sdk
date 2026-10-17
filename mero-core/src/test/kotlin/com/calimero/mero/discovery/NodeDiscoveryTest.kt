package com.calimero.mero.discovery

import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** Port of mero-js `src/nodeDiscovery.test.ts`, against real local sockets. */
class NodeDiscoveryTest {
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() = servers.forEach { it.shutdown() }

    /** A server answering `/admin-api/health` with [response] (and 404 elsewhere). */
    private fun serve(response: () -> MockResponse): MockWebServer =
        MockWebServer()
            .apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            if (request.path?.endsWith("/admin-api/health") == true) response() else MockResponse().setResponseCode(404)
                    }
                start()
            }.also { servers += it }

    private fun json(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    private fun alive() = serve { json("""{"data":{"status":"alive"}}""") }

    private fun base(server: MockWebServer) = server.url("/").toString().trimEnd('/')

    /** A port with nothing listening on it. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `localNodeUrl builds a localhost base URL, or one on the host given`() {
        assertEquals("http://localhost:2428", localNodeUrl(2428))
        assertEquals("http://10.0.2.2:2428", localNodeUrl(2428, ANDROID_EMULATOR_HOST))
        assertEquals("http://[::1]:2428", localNodeUrl(2428, "::1"))
    }

    @Test
    fun `nodeEndpoint appends the path and keeps a path prefix`() {
        assertEquals("http://localhost:2428/admin-api/health", nodeEndpoint("http://localhost:2428", "admin-api/health"))
        assertEquals("http://host/node1/admin-api/health", nodeEndpoint("http://host/node1", "admin-api/health"))
        assertEquals("http://host/node1/admin-api/health", nodeEndpoint("http://host/node1/", "admin-api/health"))
    }

    @Test
    fun `the default ports are mero-js's`() {
        assertEquals(listOf(2428, 2429, 2528, 2529), DEFAULT_LOCAL_NODE_PORTS)
    }

    @Test
    fun `probe is true for a 2xx with status alive, on the health path`() =
        runBlocking {
            val server = alive()
            assertTrue(probeNodeHealth(base(server)))
            val request = server.takeRequest()
            assertEquals("/admin-api/health", request.path)
            assertEquals("GET", request.method)
        }

    @Test
    fun `probe keeps the base url's path prefix`() =
        runBlocking {
            val server = alive()
            assertTrue(probeNodeHealth(server.url("/node1").toString()))
            assertEquals("/node1/admin-api/health", server.takeRequest().path)
        }

    @Test
    fun `probe reads the body the way mero-js does`() =
        runBlocking {
            assertTrue(probeNodeHealth(base(serve { MockResponse().setBody("ok") })))
            assertTrue(probeNodeHealth(base(serve { json("{}") })))
            assertTrue(probeNodeHealth(base(serve { json("""{"status":"ALIVE"}""") })))
            assertFalse(probeNodeHealth(base(serve { json("""{"data":{"status":"not_alive"}}""") })))
            assertFalse(probeNodeHealth(base(serve { json("""{"data":{"status":null}}""") })))
            assertFalse(probeNodeHealth(base(serve { json("""{"data":{"status":null},"status":"alive"}""") })))
        }

    @Test
    fun `probe is false on a non-2xx, a closed port and a hung server`() =
        runBlocking {
            assertFalse(probeNodeHealth(base(serve { json("{}").setResponseCode(503) })))
            assertFalse(probeNodeHealth(localNodeUrl(closedPort())))
            val hung = serve { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
            val started = System.nanoTime()
            assertFalse(probeNodeHealth(base(hung), timeoutMs = 300))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        }

    @Test
    fun `FakeNode answers the health route`() =
        runBlocking {
            val server = FakeNode().start().also { servers += it }
            assertTrue(probeNodeHealth(base(server)))
        }

    @Test
    fun `discover returns only the healthy nodes, in the order of the ports given`() =
        runBlocking {
            val a = alive()
            val b = alive()
            val sick = serve { json("""{"data":{"status":"dead"}}""") }
            val closed = closedPort()
            val ports = listOf(b.port, closed, sick.port, a.port)
            assertEquals(
                listOf(localNodeUrl(b.port), localNodeUrl(a.port)),
                discoverLocalNodes(ports = ports, host = "localhost"),
            )
        }

    @Test
    fun `discover returns an empty list when nothing local is running`() =
        runBlocking {
            assertEquals(emptyList<String>(), discoverLocalNodes(ports = listOf(closedPort(), closedPort())))
        }

    @Test
    fun `discover probes in parallel`() =
        runBlocking {
            val slow = (1..4).map { serve { json("""{"data":{"status":"alive"}}""").setHeadersDelay(400, TimeUnit.MILLISECONDS) } }
            val started = System.nanoTime()
            val found = discoverLocalNodes(ports = slow.map { it.port }, timeoutMs = 1_500)
            assertEquals(4, found.size)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_400)
        }
}
