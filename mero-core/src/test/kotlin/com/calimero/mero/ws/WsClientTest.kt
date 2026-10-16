package com.calimero.mero.ws

import com.calimero.mero.ExperimentalMeroApi
import com.calimero.mero.http.HttpException
import com.calimero.mero.sse.AppVersionChangedEvent
import com.calimero.mero.sse.ContextEvent
import com.calimero.mero.sse.GroupEvent
import com.calimero.mero.sse.GroupMembershipEvent
import com.calimero.mero.sse.asAppVersionChanged
import com.calimero.mero.sse.asMembershipEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** [WsClient] against MockWebServer's WebSocket upgrade; port of mero-js `events/ws.test.ts`. */
@OptIn(ExperimentalMeroApi::class)
class WsClientTest {
    private lateinit var server: MockWebServer

    /** Text frames the server received, across connections. */
    private val received = LinkedBlockingQueue<String>()

    /** The server side of each accepted socket, in order. */
    private val sockets = LinkedBlockingQueue<WebSocket>()

    private val serverSide =
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                sockets.put(webSocket)
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                received.put(text)
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                webSocket.close(1000, null)
            }
        }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun client() = WsClient(server.url("/").toString(), token = { "test-token" }, initialBackoffMs = 50)

    private fun nextMessage(): String = received.poll(5, TimeUnit.SECONDS) ?: error("no message reached the server")

    private fun nextSocket(): WebSocket = sockets.poll(5, TimeUnit.SECONDS) ?: error("no socket opened")

    @Test
    fun `connects to ws with a Bearer header, subscribes, and delivers events`() =
        runBlocking {
            server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))
            val ws = client()
            val connected = async { withTimeout(5_000) { ws.connects.first() } }
            val events = async { withTimeout(5_000) { ws.events.take(2).toList() } }
            yield()
            ws.connect()
            connected.await()
            val upgrade = server.takeRequest()
            assertEquals("/ws", upgrade.path)
            assertEquals("Bearer test-token", upgrade.getHeader("Authorization"))

            ws.subscribe(groupIds = listOf("grp-1"))
            assertEquals("""{"id":null,"method":"subscribe","params":{"groupIds":["grp-1"]}}""", nextMessage())
            assertTrue(ws.isSubscribed("ctx-none").not())

            val socket = nextSocket()
            socket.send("""{"id":null,"result":{"contextId":"ctx-1","type":"AppVersionChanged","data":{"fromVersion":"1.0.0","toVersion":"2.0.0"}}}""")
            socket.send("""{"id":null,"result":{"groupId":"grp-1","type":"MemberJoined","data":{"memberAccount":"acct-1","role":"Member"}}}""")
            val (first, second) = events.await()
            assertEquals(AppVersionChangedEvent("ctx-1", "1.0.0", "2.0.0"), (first as ContextEvent).asAppVersionChanged())
            assertEquals(GroupMembershipEvent.MemberJoined("grp-1", "acct-1", "Member"), (second as GroupEvent).asMembershipEvent())

            ws.unsubscribe(groupIds = listOf("grp-1"))
            assertEquals("""{"id":null,"method":"unsubscribe","params":{"groupIds":["grp-1"]}}""", nextMessage())
            ws.close()
        }

    @Test
    fun `re-subscribes both context and group ids on every reconnect`() =
        runBlocking {
            server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))
            server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))
            val ws = client()
            ws.subscribe(contextIds = listOf("ctx-1"), groupIds = listOf("grp-1"))
            ws.connect()
            val resubscribe = """{"id":null,"method":"subscribe","params":{"contextIds":["ctx-1"],"groupIds":["grp-1"]}}"""
            assertEquals(resubscribe, nextMessage())
            nextSocket().close(1001, "going away")
            assertEquals(resubscribe, nextMessage())
            assertEquals(2, server.requestCount)
            ws.close()
        }

    @Test
    fun `backs off 1 s times 2 to the n, capped at 30 s`() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map { WsClient.backoffMs(it) },
        )
        assertEquals(30_000L, WsClient.backoffMs(64))
    }

    @Test
    fun `a 403 on the upgrade stops the client instead of retrying`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(403))
            val ws = client()
            ws.connect()
            val failure = withTimeout(5_000) { ws.failure.filterNotNull().first() }
            assertEquals(403, (failure as HttpException).status)
            Thread.sleep(300)
            assertEquals(1, server.requestCount)
            assertFalse(ws.isOpen)
        }

    @Test
    fun `a failed upgrade that is not a refusal is retried`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))
            val ws = client()
            ws.connect()
            nextSocket()
            assertEquals(2, server.requestCount)
            ws.close()
        }

    @Test
    fun `close stops reconnecting and forgets the ids`() =
        runBlocking {
            server.enqueue(MockResponse().withWebSocketUpgrade(serverSide))
            val ws = client()
            ws.subscribe(contextIds = listOf("ctx-1"))
            ws.connect()
            nextSocket()
            ws.close()
            assertFalse(ws.isSubscribed("ctx-1"))
            Thread.sleep(300)
            assertEquals(1, server.requestCount)
        }
}
