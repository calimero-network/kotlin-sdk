package com.calimero.mero.ephemeral

import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
import com.calimero.mero.TokenData
import com.calimero.mero.rpc.RpcException
import com.calimero.mero.sse.ReconnectBackoff
import com.calimero.mero.sse.SseConnection
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Port of mero-js `ephemeral/ephemeral.test.ts`. */
class EphemeralClientTest {
    @Serializable
    data class Cursor(
        val x: Int,
    )

    private lateinit var server: MockWebServer
    private val rpcBodies = CopyOnWriteArrayList<JsonObject>()
    private val streams = AtomicInteger()

    @Volatile private var rpcAnswer = """{"jsonrpc":"2.0","id":1,"result":{}}"""

    @Volatile private var streamFor: (Int) -> MockResponse = { n -> stream(connect("s-$n")) }

    private lateinit var mero: Mero

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path?.substringBefore('?')) {
                        "/jsonrpc" -> {
                            rpcBodies += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                            MockResponse().setBody(rpcAnswer)
                        }
                        "/sse" -> streamFor(streams.incrementAndGet())
                        "/sse/subscription" -> MockResponse().setBody("{}")
                        else -> MockResponse().setResponseCode(404)
                    }
            }
        server.start()
        mero = Mero(MeroConfig(baseUrl = server.url("/").toString().trimEnd('/'), tokenStore = MemoryTokenStore()))
        mero.setTokenData(TokenData(accessToken = "access", refreshToken = "refresh", expiresAt = Long.MAX_VALUE))
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun sse(now: () -> Long = System::currentTimeMillis) =
        SseConnection(server.url("/").toString(), token = { "access" }, backoff = ReconnectBackoff.fixed(100), now = now)

    @Test
    fun `jsonCodec round-trips a value through bytes, as JSON text`() {
        val codec = jsonCodec<Cursor>()
        val bytes = codec.encode(Cursor(7))
        assertEquals("""{"x":7}""", bytes.decodeToString())
        assertEquals(Cursor(7), codec.decode(bytes))
    }

    @Test
    fun `set posts set_ephemeral with the state as byte numbers and no author`() =
        runBlocking {
            mero.ephemeral.set("ctx-1", byteArrayOf(1, 0x7f, 0x80.toByte(), 0xff.toByte()), RawCodec)
            val body = rpcBodies.single()
            assertEquals("set_ephemeral", body.getValue("method").jsonPrimitive.content)
            val params = body.getValue("params").jsonObject
            assertEquals("ctx-1", params.getValue("contextId").jsonPrimitive.content)
            // Unsigned, like a JS number[] / Rust Vec<u8>.
            assertEquals(listOf(1, 127, 128, 255), params.getValue("state").jsonArray.map { it.jsonPrimitive.int })
            // The author is resolved server-side; a client cannot set it.
            assertEquals(setOf("contextId", "state"), params.keys)

            mero.ephemeral.set("ctx-1", Cursor(1))
            assertEquals(
                """{"x":1}""",
                rpcBodies
                    .last()
                    .getValue("params")
                    .jsonObject
                    .getValue("state")
                    .jsonArray
                    .map { it.jsonPrimitive.int.toByte() }
                    .toByteArray()
                    .decodeToString(),
            )
        }

    @Test
    fun `set throws a typed RpcException carrying the server type and data`() =
        runBlocking {
            rpcAnswer = """{"jsonrpc":"2.0","id":1,"error":{"type":"SliceTooLarge","data":{"size":20000,"max":16384}}}"""
            val error = runCatching { mero.ephemeral.set("ctx-1", Cursor(1)) }.exceptionOrNull() as RpcException
            assertEquals("SliceTooLarge", error.type)
            assertEquals("SliceTooLarge", error.message)
            assertEquals(16384, (error.data as JsonObject).getValue("max").jsonPrimitive.int)
        }

    @Test
    fun `subscribe delivers only Ephemeral events of its context, normalized`() =
        runBlocking {
            streamFor = { n ->
                stream(
                    connect("s-$n") +
                        result("ctx-1", "AppVersionChanged", "{}") +
                        result("ctx-2", "Ephemeral", """{"author":"A","state":${encoded("""{"x":9}""")}}""") +
                        result("ctx-1", "Ephemeral", """{"author":"A","state":${encoded("""{"x":1}""")}}""") +
                        result("ctx-1", "Ephemeral", """{"author":"A","removed":true}""") +
                        result("ctx-1", "Ephemeral", """{"author":"B","account":"${"cd".repeat(32)}","state":${encoded("""{"x":2}""")},"ageMs":447}"""),
                )
            }
            val client = EphemeralClient(mero.rpc, sse())
            val seen = withTimeout(5_000) { client.subscribe<Cursor>("ctx-1").take(3).toList() }
            assertEquals(EphemeralEntry("A", state = Cursor(1)), seen[0])
            // Core omits `ageMs` on a live delta: absent, never 0.
            assertNull(seen[0].ageMs)
            assertEquals(EphemeralEntry<Cursor>("A", removed = true), seen[1])
            assertEquals(EphemeralEntry("B", account = "cd".repeat(32), state = Cursor(2), ageMs = 447), seen[2])
        }

    @Test
    fun `subscribing connects and subscribes the context itself`() =
        runBlocking {
            val events = sse()
            val client = EphemeralClient(mero.rpc, events)
            val job = async { client.subscribe<Cursor>("ctx-1").first() }
            withTimeout(5_000) { events.connects.first() }
            assertTrue(events.isSubscribed("ctx-1"))
            job.cancel()
            events.close()
        }

    @Test
    fun `a late listener gets the current presence, ages advanced, then live deltas`() =
        runBlocking {
            val clock = AtomicLong(50_000)
            streamFor = { n ->
                stream(
                    connect("s-$n") +
                        result("ctx-1", "Ephemeral", """{"author":"A","state":${encoded("1")},"ageMs":500}""") +
                        result("ctx-1", "Ephemeral", """{"author":"B","state":${encoded("2")}}""") +
                        result("ctx-1", "Ephemeral", """{"author":"B","removed":true}"""),
                )
            }
            val events = sse(now = { clock.get() })
            val client = EphemeralClient(mero.rpc, events)
            // The first listener takes the node's one-time replay.
            withTimeout(5_000) { client.subscribe<Int>("ctx-1").take(3).toList() }
            clock.addAndGet(1_000)

            val late = withTimeout(5_000) { client.subscribe<Int>("ctx-1").first() }
            assertEquals("A", late.author)
            assertEquals(1, late.state)
            assertFalse(late.removed)
            assertEquals(1_500L, late.ageMs)
            events.close()
        }

    @Test
    fun `a reconnect drops what the old connection said`() =
        runBlocking {
            streamFor = { n ->
                if (n == 1) {
                    MockResponse().setHeader("Content-Type", "text/event-stream").setBody(connect("s-1") + result("ctx-1", "Ephemeral", """{"author":"A","state":${encoded("1")}}"""))
                } else {
                    stream(connect("s-$n"))
                }
            }
            val events = sse()
            events.subscribe(contextIds = listOf("ctx-1"))
            val first = async { withTimeout(5_000) { events.contextEvents.first() } }
            yield()
            events.connect()
            first.await()
            withTimeout(5_000) { events.connects.first { it == "s-2" } }
            assertTrue(events.presenceSeed("ctx-1").isEmpty())
            events.close()
        }

    private companion object {
        fun connect(session: String) = "data: {\"type\":\"connect\",\"session_id\":\"$session\"}\n\n"

        fun result(
            contextId: String,
            type: String,
            data: String,
        ) = "data: {\"result\":{\"contextId\":\"$contextId\",\"type\":\"$type\",\"data\":$data}}\n\n"

        fun encoded(json: String) = json.encodeToByteArray().joinToString(",", "[", "]") { (it.toInt() and 0xFF).toString() }

        fun stream(frames: String): MockResponse =
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(frames + ": held open\n\n")
                .throttleBody(frames.encodeToByteArray().size.toLong(), 2, TimeUnit.SECONDS)
    }
}
