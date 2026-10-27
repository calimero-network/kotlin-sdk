package com.calimero.mero.compose

import com.calimero.mero.account.CloudAccount
import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.relay.DcapRelayKeyVerifier
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A password login is a session like an SSO one: remembered, restorable after a relaunch, followed
 * over SSE. And a node that cannot be reached at launch is not a node that refused the token.
 * Port of the Swift `MeroClientNodeSessionTests`.
 */
class MeroClientNodeSessionTest {
    private lateinit var node: FakeNode
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val scopes = mutableListOf<CoroutineScope>()
    private val clients = mutableListOf<MeroClient>()

    /** Answer `/auth/validate` and `/sse` with this status instead (a `5xx`). */
    @Volatile private var validateStatus: Int? = null
    private val sseConnects = AtomicInteger()

    @Before
    fun setUp() {
        node = FakeNode()
        val fake = node.dispatcher()
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = (request.path ?: "").substringBefore('?')
                    validateStatus?.let { if (path == "/auth/validate" || path == "/sse") return MockResponse().setResponseCode(it) }
                    return if (path == "/sse") stream(request) else fake.dispatch(request)
                }
            }
        server.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        runBlocking { clients.forEach { runCatching { it.mero?.sse?.close() } } }
        scopes.forEach { it.cancel() }
        // A held-open stream is still sleeping in its throttle; MockWebServer gives up on it.
        runCatching { server.shutdown() }
    }

    /** `GET /sse` for the node's current token: a connect frame, then held open. */
    private fun stream(request: RecordedRequest): MockResponse {
        if (request.getHeader("Authorization") != "Bearer ${node.accessToken}") return MockResponse().setResponseCode(401)
        sseConnects.incrementAndGet()
        val frame = "data: {\"type\":\"connect\",\"session_id\":\"s1\"}\n\n"
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("$frame: held open\n\n")
            .throttleBody(frame.encodeToByteArray().size.toLong(), 2, TimeUnit.SECONDS)
    }

    private fun client(
        storage: MeroAppStorage,
        tokens: MemoryTokenStore,
        httpClient: OkHttpClient? = null,
    ): MeroClient {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val account = CloudAccount(MemorySecureStore(), relayKeyVerifier = DcapRelayKeyVerifier(allowMock = true), cloudBaseUrl = base, walletUrl = "https://wallet.example/account-enroll")
        return MeroClient(account, tokens, config = MeroAppConfig(httpClient = httpClient), storage = storage, scope = scope).also { clients += it }
    }

    private suspend fun eventually(
        timeoutMs: Long = 2_000,
        condition: () -> Boolean,
    ): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(20)
            true
        } ?: false

    // ---- login(nodeUrl, username, password) -----------------------------------------------

    @Test
    fun `login follows the node's event stream`() =
        runBlocking {
            val c = client(MeroAppStorage(MemoryKeyValueStore()), MemoryTokenStore())
            assertTrue(c.login("$base/", "admin", "secret"))
            assertEquals("the trailing slash is dropped", base, c.state.value.nodeUrl)
            assertTrue("a password session follows the stream", eventually { c.state.value.isOnline && sseConnects.get() > 0 })
            assertFalse(c.state.value.isOfflineSession)
        }

    // ---- resumeNodeSession() ----------------------------------------------------------------

    /** A password login, and the end of that launch without logging out (that would forget the session). */
    private suspend fun signedIn(): Pair<MeroAppStorage, MemoryTokenStore> {
        val storage = MeroAppStorage(MemoryKeyValueStore())
        val tokens = MemoryTokenStore()
        val first = client(storage, tokens)
        assertTrue(first.login(base, "admin", "secret"))
        first.mero!!.sse.close()
        return storage to tokens
    }

    @Test
    fun `resume restores an accepted token`() =
        runBlocking {
            val (storage, tokens) = signedIn()
            val c = client(storage, tokens)
            assertEquals(NodeSessionRestore.RESTORED, c.resumeNodeSession())
            assertTrue(c.state.value.isAuthenticated)
            assertTrue(c.state.value.isDevNodeSession)
            assertFalse(c.state.value.isOfflineSession)
        }

    @Test
    fun `resume keeps the session when the network is down`() =
        runBlocking {
            val (storage, tokens) = signedIn()
            val offline = OkHttpClient.Builder().addInterceptor { throw IOException("no network") }.build()
            val c = client(storage, tokens, offline)

            assertEquals(NodeSessionRestore.OFFLINE, c.resumeNodeSession())
            val state = c.state.value
            assertTrue("the held tokens are used", state.isAuthenticated)
            assertTrue(state.isOfflineSession)
            assertFalse(state.isOnline)
            assertNotNull(c.admin)
            assertNotNull(c.session.value.rpc)
            assertNotNull("nothing was cleared", c.mero!!.getTokenData())

            // `restoreNodeSession` keeps its answer: no validated session.
            val strict = client(storage, tokens, offline)
            assertFalse(strict.restoreNodeSession())
            assertFalse(strict.state.value.isAuthenticated)
        }

    @Test
    fun `resume keeps the session when the node answers 5xx`() =
        runBlocking {
            val (storage, tokens) = signedIn()
            validateStatus = 503
            val c = client(storage, tokens)
            assertEquals(NodeSessionRestore.OFFLINE, c.resumeNodeSession())
            assertTrue(c.state.value.isAuthenticated)
        }

    @Test
    fun `the offline flag clears when the node comes back`() =
        runBlocking {
            val (storage, tokens) = signedIn()
            validateStatus = 503
            val c = client(storage, tokens)
            c.resumeNodeSession()
            assertTrue(c.state.value.isOfflineSession)
            validateStatus = null
            // The stream retries on its own backoff (3 s, with jitter).
            val back = eventually(timeoutMs = 8_000) { !c.state.value.isOfflineSession && c.state.value.isOnline }
            assertTrue("the event stream connected with the held token", back)
            assertTrue(c.state.value.isAuthenticated)
        }

    @Test
    fun `resume reports a rejected token`() =
        runBlocking {
            val (storage, tokens) = signedIn()
            node.revokeFamily()
            val c = client(storage, tokens)
            assertEquals(NodeSessionRestore.REJECTED, c.resumeNodeSession())
            assertFalse(c.state.value.isAuthenticated)
            assertFalse(c.state.value.isOfflineSession)
        }

    @Test
    fun `resume with nothing stored`() =
        runBlocking {
            assertEquals(NodeSessionRestore.NO_SESSION, client(MeroAppStorage(MemoryKeyValueStore()), MemoryTokenStore()).resumeNodeSession())
        }

    @Test
    fun `resume with a stored node but no token`() =
        runBlocking {
            val storage = MeroAppStorage(MemoryKeyValueStore())
            storage.nodeUrl = base
            val c = client(storage, MemoryTokenStore())
            assertEquals(NodeSessionRestore.NO_SESSION, c.resumeNodeSession())
            assertFalse(c.state.value.isAuthenticated)
        }
}
