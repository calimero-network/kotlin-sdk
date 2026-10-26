package com.calimero.mero.sse

import com.calimero.mero.http.AuthRevokedException
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.NetworkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** [SseConnection]: mero-js `SseClient` parity (`events/sse.test.ts`). */
class SseConnectionTest {
    private lateinit var server: MockWebServer
    private val subscriptions = CopyOnWriteArrayList<JsonObject>()
    private val streamTimes = CopyOnWriteArrayList<Long>()
    private val streams = AtomicInteger()

    /** What `/sse` answers on each attempt (1-based); the default keeps a session open. */
    @Volatile private var streamFor: (Int) -> MockResponse = { n -> stream(connect("s-$n")) }

    @Volatile private var subscriptionResponse: () -> MockResponse = { MockResponse().setBody("{}") }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path?.substringBefore('?')) {
                        "/sse" -> {
                            streamTimes += System.currentTimeMillis()
                            streamFor(streams.incrementAndGet())
                        }
                        "/sse/subscription" -> {
                            subscriptions += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                            subscriptionResponse()
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
            }
        server.start()
    }

    @After
    fun tearDown() {
        // A held-open stream is still sleeping in its throttle; MockWebServer gives up on it.
        runCatching { server.shutdown() }
    }

    private fun connection(
        delayMs: Long = 150,
        now: () -> Long = System::currentTimeMillis,
        backoff: ReconnectBackoff = ReconnectBackoff.fixed(delayMs),
    ) = SseConnection(server.url("/").toString(), token = { "tok" }, backoff = backoff, now = now)

    private fun ids(
        body: JsonObject,
        key: String,
    ): List<String> =
        body
            .getValue("params")
            .jsonObject[key]
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            .orEmpty()

    private fun waitFor(
        what: String,
        cond: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `subscribe and unsubscribe change a live connection, sending only what changed`() =
        runBlocking {
            val sse = connection()
            sse.connect()
            withTimeout(5_000) { sse.connects.first() }
            assertEquals("s-1", sse.sessionId)

            sse.subscribe(contextIds = listOf("ctx-1", "ctx-2"), groupIds = listOf("g-1"))
            sse.subscribe(contextIds = listOf("ctx-1", "ctx-3"))
            assertTrue(sse.isSubscribed("ctx-3"))
            assertTrue(sse.isSubscribedToGroup("g-1"))
            sse.unsubscribe(contextIds = listOf("ctx-2", "never-held"))
            assertFalse(sse.isSubscribed("ctx-2"))

            assertEquals(3, subscriptions.size)
            val (first, second, third) = subscriptions
            assertEquals("s-1", first.getValue("id").jsonPrimitive.content)
            assertEquals("subscribe", first.getValue("method").jsonPrimitive.content)
            assertEquals(listOf("ctx-1", "ctx-2"), ids(first, "contextIds"))
            assertEquals(listOf("g-1"), ids(first, "groupIds"))
            // Only the id not already held; an empty list is left off the wire.
            assertEquals(listOf("ctx-3"), ids(second, "contextIds"))
            assertFalse(second.getValue("params").jsonObject.containsKey("groupIds"))
            assertEquals("unsubscribe", third.getValue("method").jsonPrimitive.content)
            assertEquals(listOf("ctx-2"), ids(third, "contextIds"))
            sse.close()
        }

    @Test
    fun `ids subscribed before a session go out with the connect frame`() =
        runBlocking {
            val sse = connection()
            sse.subscribe(contextIds = listOf("ctx-1"))
            assertTrue("nothing is posted without a session", subscriptions.isEmpty())
            sse.connect()
            withTimeout(5_000) { sse.connects.first() }
            assertEquals(listOf("ctx-1"), ids(subscriptions.single(), "contextIds"))
            sse.close()
        }

    @Test
    fun `a reconnect re-subscribes every remembered id, then announces the session`() =
        runBlocking {
            // The first stream ends right after its connect frame; the second stays.
            streamFor = { n -> if (n == 1) MockResponse().setHeader("Content-Type", "text/event-stream").setBody(connect("s-1")) else stream(connect("s-$n")) }
            val sse = connection()
            sse.subscribe(contextIds = listOf("ctx-1"), groupIds = listOf("g-1"))
            sse.connect()
            val second = withTimeout(5_000) { sse.connects.first { it == "s-2" } }
            assertEquals("s-2", second)
            // Announced only after the re-subscribe landed.
            val resubscribe = subscriptions.last()
            assertEquals("s-2", resubscribe.getValue("id").jsonPrimitive.content)
            assertEquals(listOf("ctx-1"), ids(resubscribe, "contextIds"))
            assertEquals(listOf("g-1"), ids(resubscribe, "groupIds"))
            sse.close()
        }

    @Test
    fun `a fixed backoff reconnects on the same delay, not a growing one`() {
        streamFor = { MockResponse().setResponseCode(500) }
        val sse = connection(delayMs = 200)
        sse.connect()
        waitFor("four attempts") { streamTimes.size >= 4 }
        sse.close()
        val gaps = streamTimes.zipWithNext { a, b -> b - a }.take(3)
        assertTrue("every gap waits the delay: $gaps", gaps.all { it >= 190 })
        // The default doubling would wait 200, 400, 800.
        assertTrue("the delay does not grow: $gaps", gaps.last() < 600)
    }

    @Test
    fun `backs off while the node refuses and resets once a stream opens`() {
        // Three refusals, a stream that opens and ends, then one that stays.
        val refused = MockResponse().setResponseCode(503)
        streamFor = { n ->
            when {
                n <= 3 -> refused
                n == 4 -> MockResponse().setHeader("Content-Type", "text/event-stream").setBody(connect("s-4"))
                else -> stream(connect("s-$n"))
            }
        }
        val delays = CopyOnWriteArrayList<Long>()
        val sse = connection(backoff = ReconnectBackoff.SSE)
        sse.random = { 0.5 } // no jitter
        sse.sleep = { delays += it }
        val errors = CopyOnWriteArrayList<Throwable>()
        runBlocking {
            val collecting = launch(Dispatchers.Unconfined) { sse.errors.collect { errors += it } }
            val seen = async(Dispatchers.Unconfined) { sse.connects.first { it == "s-5" } }
            sse.connect()
            withTimeout(5_000) { seen.await() }
            collecting.cancel()
        }
        sse.close()
        assertEquals("3 s doubling, back to 3 s after a stream opened", listOf(3_000L, 6_000L, 12_000L, 3_000L), delays.toList())
        assertEquals(3, errors.count { (it as? HttpException)?.status == 503 })
    }

    @Test
    fun `a line over the limit drops the stream, is reported and reconnects`() {
        val huge = "data: " + "x".repeat(SseConnection.MAX_LINE_BYTES.toInt()) + "\n\n"
        // Both streams stay open: only the limit can end the first.
        streamFor = { n -> stream(if (n == 1) huge else connect("s-$n")) }
        val sse = connection()
        sse.sleep = {}
        val errors = CopyOnWriteArrayList<Throwable>()
        runBlocking {
            val collecting = launch(Dispatchers.Unconfined) { sse.errors.collect { errors += it } }
            val seen = async(Dispatchers.Unconfined) { sse.connects.first() }
            sse.connect()
            assertEquals("s-2", withTimeout(5_000) { seen.await() })
            collecting.cancel()
        }
        sse.close()
        val dropped = errors.first()
        assertTrue("$dropped", dropped is NetworkException && dropped.message.orEmpty().contains("SSE line exceeds"))
        assertEquals(null, sse.failure.value)
    }

    @Test
    fun `a 401 naming a dead family is terminal, a token_expired 401 is not`() =
        runBlocking {
            streamFor = { MockResponse().setResponseCode(401).setHeader("x-auth-error", "token_revoked") }
            val sse = connection()
            sse.connect()
            val failure = withTimeout(5_000) { sse.failure.filterNotNull().first() }
            assertEquals("token_revoked", (failure as AuthRevokedException).reason)
            Thread.sleep(500)
            assertEquals("no reconnect after a terminal refusal", 1, streams.get())

            streams.set(0)
            streamFor = { MockResponse().setResponseCode(401).setHeader("x-auth-error", "token_expired") }
            val retrying = connection()
            retrying.connect()
            waitFor("a retry") { streams.get() >= 2 }
            retrying.close()
        }

    @Test
    fun `a 403 on a live subscribe stops the connection`() =
        runBlocking {
            subscriptionResponse = { MockResponse().setResponseCode(403).setHeader("x-auth-error", "token_revoked") }
            val sse = connection()
            sse.connect()
            withTimeout(5_000) { sse.connects.first() }
            sse.subscribe(contextIds = listOf("ctx-1"))
            val failure = sse.failure.value
            assertTrue("expected AuthRevokedException, got $failure", failure is AuthRevokedException)
            assertEquals(null, sse.sessionId)
        }

    @Test
    fun `a 500 on a subscribe is reported, and the connection stays`() =
        runBlocking {
            subscriptionResponse = { MockResponse().setResponseCode(500) }
            val sse = connection()
            sse.connect()
            withTimeout(5_000) { sse.connects.first() }
            val error =
                async { withTimeout(5_000) { sse.errors.first() } }
            yield()
            sse.subscribe(contextIds = listOf("ctx-1"))
            assertEquals(500, (error.await() as HttpException).status)
            assertEquals(null, sse.failure.value)
            assertEquals("s-1", sse.sessionId)
            sse.close()
        }

    @Test
    fun `presenceSeed keeps upserts, drops removals and advances ages`() =
        runBlocking {
            val clock = AtomicLong(10_000)
            streamFor = { n ->
                stream(
                    connect("s-$n") +
                        ephemeral("ctx-1", """{"author":"A","state":[49],"ageMs":500}""") +
                        ephemeral("ctx-1", """{"author":"B","state":[50]}""") +
                        ephemeral("ctx-1", """{"author":"B","removed":true}""") +
                        ephemeral("ctx-other", """{"author":"C","state":[51]}""") +
                        // A marker so the test knows every frame before it was handled.
                        "data: {\"result\":{\"contextId\":\"ctx-1\",\"type\":\"SyncStatus\",\"data\":{}}}\n\n",
                )
            }
            val sse = connection(now = { clock.get() })
            sse.subscribe(contextIds = listOf("ctx-1"))
            val marker = async { withTimeout(5_000) { sse.contextEvents.first { it.kind == "SyncStatus" } } }
            yield()
            sse.connect()
            marker.await()
            clock.addAndGet(1_000)
            val seed = sse.presenceSeed("ctx-1")
            assertEquals(1, seed.size)
            assertEquals("A", seed[0].getValue("author").jsonPrimitive.content)
            assertEquals(1_500, seed[0].getValue("ageMs").jsonPrimitive.long)
            assertTrue("a context it does not hold keeps nothing", sse.presenceSeed("ctx-other").isEmpty())

            sse.unsubscribe(contextIds = listOf("ctx-1"))
            assertTrue("an unsubscribed context is forgotten", sse.presenceSeed("ctx-1").isEmpty())
            sse.close()
        }

    @Test
    fun `typed flows decode app-version and migration events`() =
        runBlocking {
            streamFor = { n ->
                stream(
                    connect("s-$n") +
                        "data: {\"result\":{\"contextId\":\"ctx-1\",\"type\":\"AppVersionChanged\",\"data\":{\"fromVersion\":\"1.0.0\",\"toVersion\":\"2.0.0\"}}}\n\n" +
                        "data: {\"result\":{\"groupId\":\"g-1\",\"type\":\"MigrationProgress\",\"data\":{\"migrated\":1,\"inProgress\":2,\"unknown\":0,\"failed\":0,\"total\":3}}}\n\n",
                )
            }
            val sse = connection()
            val seen = CopyOnWriteArrayList<AppVersionChangedEvent>()
            val stop = sse.onAppVersionChanged { seen += it }
            val migration = async { withTimeout(5_000) { sse.migrationEvents.first() } }
            yield()
            Thread.sleep(50) // the callback collector starts on its own dispatcher
            sse.connect()
            assertEquals(GroupMigrationEvent.MigrationProgress("g-1", 1, 2, 0, 0, 3), migration.await())
            waitFor("the callback") { seen.isNotEmpty() }
            assertEquals(AppVersionChangedEvent("ctx-1", "1.0.0", "2.0.0"), seen.single())
            stop()
            sse.close()
        }

    @Test
    fun `close forgets every subscription`() =
        runBlocking {
            val sse = connection()
            sse.subscribe(contextIds = listOf("ctx-1"), groupIds = listOf("g-1"))
            sse.close()
            assertFalse(sse.isSubscribed("ctx-1"))
            assertTrue(sse.subscribedGroupIds.isEmpty())
        }

    private companion object {
        fun connect(session: String) = "data: {\"type\":\"connect\",\"session_id\":\"$session\"}\n\n"

        fun ephemeral(
            contextId: String,
            data: String,
        ) = "data: {\"result\":{\"contextId\":\"$contextId\",\"type\":\"Ephemeral\",\"data\":$data}}\n\n"

        /** An event stream that sends [frames] at once, then holds the connection open. */
        fun stream(frames: String): MockResponse {
            val head = frames.encodeToByteArray().size.toLong()
            return MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(frames + ": held open\n\n")
                .throttleBody(head, 2, TimeUnit.SECONDS)
        }
    }
}
