package com.calimero.mero.request

import com.calimero.mero.ExperimentalMeroApi
import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
import com.calimero.mero.TokenData
import com.calimero.mero.http.ProofRedirectRefusedException
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.ws.WsClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * `MeroConfig.requestProof` on the wire: every request carries a proof over exactly the
 * method, path and query, and body it sends; a proof never follows a redirect (mero-js
 * `refuseProofRedirect`); SSE and the WebSocket authenticate with it when there is no token.
 */
class RequestProofTransportTest {
    private lateinit var server: MockWebServer
    private val seen = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile private var respond: (RecordedRequest) -> MockResponse = { MockResponse().setBody("{}") }

    private val options = ProofSignerOptions(credential = "ab".repeat(4), signerSecret = "03".repeat(32), now = { 1_700_000_000L })
    private val signer = createProofSigner(options)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += request
                    return respond(request)
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun mero(
        proof: ProofSigner? = signer,
        store: MemoryTokenStore = MemoryTokenStore(),
    ) = Mero(MeroConfig(baseUrl = server.url("/node1").toString(), tokenStore = store, timeoutMs = 5_000, requestProof = proof))

    private fun expected(req: RecordedRequest): String =
        runBlocking {
            signer(
                ProofRequest(
                    req.method!!, req.path!!,
                    req.body
                        .clone()
                        .readByteArray()
                        .takeIf { it.isNotEmpty() },
                ),
            )
        }!!

    @Test
    fun `signs the method, the path with its query, and the exact body`() =
        runBlocking {
            val mero = mero()
            mero.http.execute("GET", "/admin-api/contexts?limit=5&q=a%2Fb")
            mero.http.execute("POST", "/admin-api/groups", """{"applicationId":"app"}""")
            val (get, post) = seen.toList()
            assertEquals("/node1/admin-api/contexts?limit=5&q=a%2Fb", get.path)
            assertEquals(expected(get), get.getHeader(REQUEST_PROOF_HEADER))
            assertEquals(expected(post), post.getHeader(REQUEST_PROOF_HEADER))
            // The credential prefix, then the two-link `None` tag.
            assertTrue(post.getHeader(REQUEST_PROOF_HEADER)!!.startsWith(options.credential + "00"))
        }

    @Test
    fun `sends no proof when the signer returns none, and none without a signer`() =
        runBlocking {
            mero(proof = { null }).http.execute("GET", "/admin-api/contexts")
            mero(proof = null).http.execute("GET", "/admin-api/contexts")
            seen.forEach { assertNull(it.getHeader(REQUEST_PROOF_HEADER)) }
        }

    @Test
    fun `refuses to follow a redirect with a proof attached`() {
        respond = { MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.example/steal") }
        val e = assertThrows(ProofRedirectRefusedException::class.java) { runBlocking { mero().http.execute("GET", "/admin-api/contexts") } }
        assertEquals(0, e.status)
        assertEquals(302, e.redirectStatus)
        assertEquals("https://elsewhere.example/steal", e.location)
        assertEquals(1, seen.size)
    }

    @Test
    fun `a signing failure surfaces as itself, not as a network error`() {
        val e =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { mero(proof = { error("hardware key unplugged") }).http.execute("GET", "/admin-api/contexts") }
            }
        assertEquals("hardware key unplugged", e.message)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `SSE signs the stream and each subscription POST when there is no token`() =
        runBlocking {
            respond = { req ->
                if (req.path!!.endsWith("/sse")) {
                    MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("data: {\"type\":\"connect\",\"session_id\":\"s-1\"}\n\n: held\n\n")
                        .throttleBody(50, 2, TimeUnit.SECONDS)
                } else {
                    MockResponse().setBody("{}")
                }
            }
            val mero = mero()
            mero.sse.connect()
            withTimeout(5_000) { mero.sse.connects.first() }
            mero.sse.subscribe(contextIds = listOf("ctx-1"))
            mero.sse.close()
            val stream = seen.first { it.path == "/node1/sse" }
            val sub = seen.first { it.path == "/node1/sse/subscription" }
            assertNull(stream.getHeader("Authorization"))
            assertEquals(expected(stream), stream.getHeader(REQUEST_PROOF_HEADER))
            assertEquals(expected(sub), sub.getHeader(REQUEST_PROOF_HEADER))
        }

    @Test
    fun `SSE presents the session token instead when there is one`() =
        runBlocking {
            respond = { MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"type\":\"connect\",\"session_id\":\"s\"}\n\n") }
            val store = MemoryTokenStore()
            val mero = mero(store = store)
            mero.setTokenData(TokenData("tok", "ref", Long.MAX_VALUE))
            mero.sse.connect()
            withTimeout(5_000) { mero.sse.connects.first() }
            mero.sse.close()
            val stream = seen.first { it.path == "/node1/sse" }
            assertEquals("Bearer tok", stream.getHeader("Authorization"))
            assertNull(stream.getHeader(REQUEST_PROOF_HEADER))
        }

    @OptIn(ExperimentalMeroApi::class)
    @Test
    fun `the WebSocket upgrade carries a proof over GET ws`() =
        runBlocking {
            respond = {
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) = Unit
                    },
                )
            }
            val authorize = proofAuthorizerOf(signer)
            val ws = WsClient(server.url("/node1").toString(), token = { null }, initialBackoffMs = 50, authorize = authorize)
            ws.connect()
            withTimeout(5_000) { ws.connects.first() }
            ws.close()
            val upgrade = seen.first()
            assertEquals("/node1/ws", upgrade.path)
            assertNull(upgrade.getHeader("Authorization"))
            assertEquals(expected(upgrade), upgrade.getHeader(REQUEST_PROOF_HEADER))
        }
}
