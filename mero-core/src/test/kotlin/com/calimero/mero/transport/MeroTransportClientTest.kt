package com.calimero.mero.transport

import com.calimero.mero.Credentials
import com.calimero.mero.ExperimentalMeroApi
import com.calimero.mero.MeroConfig
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.relay.MemoryNonceSource
import com.calimero.mero.relay.RelayClient
import com.calimero.mero.request.ProofCredential
import com.calimero.mero.rpc.MigrateMyEntriesSummary
import com.calimero.mero.rpc.RpcExecution
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** mero-js `transport/mero-client.test.ts`: one call site, two transports. */
@OptIn(ExperimentalMeroApi::class)
class MeroTransportClientTest {
    private lateinit var node: FakeNode
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val ctx = "c0".repeat(32)
    private val credential = "00".repeat(40)
    private val deviceSecret = "11".repeat(32)

    @Before
    fun setUp() {
        node = FakeNode()
        server = node.start()
        base = server.url("/").toString().trimEnd('/')
        node.rpcOutputs["get"] = JsonPrimitive(42)
        node.rpcOutputs["nothing"] = JsonNull
        node.rpcOutputs["migrate_my_entries"] =
            buildJsonObject {
                put("converted", 2)
                put("remaining", 1)
            }
        node.rpcOutputs["count_my_pending"] = JsonPrimitive(5)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun relayClient() = RelayClient(base, "aa".repeat(32), credential, deviceSecret, MemoryNonceSource())

    private suspend fun nodeClient(): MeroTransportClient {
        val client = createMeroClient(MeroTransportConfig.Node(MeroConfig(baseUrl = base, credentials = Credentials("u", "p"))))
        client.node.authenticate()
        return client
    }

    private fun relay(
        observe: RelayObserveConfig? = null,
        session: (suspend () -> String?)? = null,
        proof: ProofCredential? = null,
    ) = MeroTransportClient(MeroTransportConfig.Relay(relayClient(), session = session, observe = observe, proof = proof))

    @Test
    fun `returns the same value, defaults and nulls through the node and through a relay`() =
        runBlocking {
            val clients = listOf(nodeClient(), relay())
            for (client in clients) {
                assertEquals(JsonPrimitive(42), client.rpc.execute(ctx, "get"))
                assertEquals(42, client.rpc.executeAs<Int>(ctx, "get"))
                assertEquals(JsonNull, client.rpc.execute(ctx, "nothing"))
                assertEquals(MigrateMyEntriesSummary(2, 1), client.rpc.migrateMyEntries(ctx))
                assertEquals(5, client.rpc.countMyPending(ctx))
            }
            assertEquals(RpcExecution.TRANSPORT_NODE, clients[0].transport)
            assertEquals(RpcExecution.TRANSPORT_RELAY, clients[1].transport)
            // The relay's intent got the method and `{}` for no arguments.
            val intent =
                node.cloud
                    .recorded("/intents")
                    .first { it.method == "POST" }
                    .bodyJson!!
            assertEquals("get", intent["method"]!!.jsonPrimitive.content)
            assertEquals(buildJsonObject { }, intent["argsJson"])
        }

    @Test
    fun `surfaces rootHash through executeWithMetadata on the relay only`() =
        runBlocking {
            val onRelay = relay().rpc.executeWithMetadata(ctx, "get")
            assertNotNull(onRelay.rootHash)
            assertEquals(RpcExecution.TRANSPORT_RELAY, onRelay.transport)
            val onNode = nodeClient().rpc.executeWithMetadata(ctx, "get")
            assertNull(onNode.rootHash)
            assertEquals(JsonPrimitive(42), onNode.returns)
        }

    @Test
    fun `a node client refuses relay and a relay client refuses node, auth and cloud`() =
        runBlocking {
            val n = nodeClient()
            assertTrue(runCatching { n.relay }.exceptionOrNull() is MeroStateException)
            assertTrue(n.canSubscribe)
            assertTrue(n.ephemeral is TransportEphemeral.Node)
            n.cloud
            val r = relay()
            assertTrue(runCatching { r.node }.exceptionOrNull() is MeroStateException)
            assertTrue(runCatching { r.auth }.exceptionOrNull() is MeroStateException)
            assertTrue(runCatching { r.cloud }.exceptionOrNull() is MeroStateException)
            n.close()
        }

    @Test
    fun `a relay client with no node key cannot subscribe, and says the key is what is missing`() {
        val r = relay()
        assertFalse(r.canSubscribe)
        assertFalse(relay(observe = RelayObserveConfig(null)).canSubscribe)
        val e = runCatching { r.events }.exceptionOrNull()!!
        assertTrue(e.message!!.contains("node key"))
        assertTrue(runCatching { r.ws }.exceptionOrNull()!!.message!!.contains("node key"))
        assertTrue(runCatching { r.admin }.exceptionOrNull()!!.message!!.contains("session"))
        // Presence publishes without a key.
        assertTrue(r.ephemeral is TransportEphemeral.Relay)
        assertSame(relayClientOf(r), r.relay)
        r.close()
    }

    private fun relayClientOf(r: MeroTransportClient) = r.relay

    @Test
    fun `serves admin once a session is supplied, and sends that token`() =
        runBlocking {
            nodeClient() // mints a session on the fake
            val r = relay(session = { node.accessToken })
            r.admin.getContexts()
            assertEquals(
                "Bearer ${node.accessToken}",
                node.cloud
                    .recorded("/admin-api/contexts")
                    .single()
                    .headers["Authorization"],
            )
        }

    @Test
    fun `a proof makes admin and events available, but not the WebSocket`() =
        runBlocking {
            val r = relay(proof = ProofCredential(credential, deviceSecret = deviceSecret))
            assertTrue(r.canSubscribe)
            r.admin.getContexts()
            assertNotNull(
                node.cloud
                    .recorded("/admin-api/contexts")
                    .single()
                    .headers["X-Calimero-Proof"],
            )
            assertSame(r.events, r.events)
            assertTrue(runCatching { r.ws }.exceptionOrNull()!!.message!!.contains("proof"))
            r.close()
        }

    @Test
    fun `given the node key it logs in with the account proof, once, however many surfaces ask`() =
        runBlocking {
            val r = relay(observe = RelayObserveConfig(node.cloud.nodeKey))
            assertTrue(r.canSubscribe)
            assertTrue(r.rpc.canSubscribe)
            assertSame(r.events, r.events)
            val observer = (r.rpc as RelayTransport).events!!
            val tokens = (1..4).map { async { observer.accessToken() } }.awaitAll()
            assertEquals(1, node.tokenCalls)
            assertEquals(1, tokens.toSet().size)
            assertTrue(node.lastTokenRequestBody!!.contains("account_proof"))
            r.close()
        }
}
