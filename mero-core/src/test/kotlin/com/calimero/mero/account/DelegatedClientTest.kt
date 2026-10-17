package com.calimero.mero.account

import com.calimero.mero.relay.forgetMethodKinds
import com.calimero.mero.rpc.RpcExecution
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** mero-js `delegated-client.test.ts` and the multi-relay listings of `session.ts`. */
class DelegatedClientTest {
    private val rig = DelegatedRig()
    private val account = rig.account
    private val cloud = rig.node.cloud
    private val ctx = "c0".repeat(32)

    @Before
    fun setUp() {
        forgetMethodKinds()
        cloud.outputs["get"] = JsonPrimitive(7)
        cloud.outputs["set"] = JsonPrimitive("ok")
    }

    @After
    fun tearDown() = rig.close()

    private fun queries() = cloud.recorded("/query").size

    private fun intents() = cloud.recorded("/intents").count { it.method == "POST" }

    @Test
    fun `no relay, no client`() {
        assertNull(account.buildDelegatedClient(rig.session.copy(relayUrl = null)))
    }

    @Test
    fun `a method the node answers as a view is read with the session, and no warrant is spent`() =
        runBlocking {
            account.pinRelayNodeKey(rig.base, cloud.nodeKey)
            val client = account.buildDelegatedClient(rig.session)!!
            assertEquals(JsonPrimitive(7), client.rpc.execute(ctx, "get"))
            assertEquals(1, queries())
            assertEquals(0, intents())
            assertTrue(
                cloud
                    .recorded("/query")
                    .single()
                    .headers["Authorization"]!!
                    .startsWith("Bearer "),
            )
            // A call with no arguments sends the {} a warrant would.
            assertEquals(buildJsonObject { }, cloud.recorded("/query").single().bodyJson!!["argsJson"])
            val meta = client.rpc.executeWithMetadata(ctx, "get")
            assertEquals(RpcExecution.TRANSPORT_RELAY, meta.transport)
            assertNull(meta.rootHash)
        }

    @Test
    fun `a write (409) goes out as a warrant and is not asked about again, even after a rebuild`() =
        runBlocking {
            account.pinRelayNodeKey(rig.base, cloud.nodeKey)
            val args = buildJsonObject { put("v", 1) }
            val first = account.buildDelegatedClient(rig.session)!!.rpc.executeWithMetadata(ctx, "set", args)
            assertEquals(JsonPrimitive("ok"), first.returns)
            assertNotNull(first.rootHash)
            assertEquals(1, queries())
            assertEquals(1, intents())
            account.buildDelegatedClient(rig.session)!!.rpc.execute(ctx, "set", args)
            assertEquals(1, queries())
            assertEquals(2, intents())
        }

    @Test
    fun `any other failure of the query falls back to the warrant, which reads too`() =
        runBlocking {
            account.pinRelayNodeKey(rig.base, cloud.nodeKey)
            val client = account.buildDelegatedClient(rig.session)!!
            client.rpc.execute(ctx, "peek") // mints the session
            rig.node.revokeFamily() // ...which the relay now refuses: not an answer about the method
            assertEquals(JsonPrimitive(7), client.rpc.execute(ctx, "get"))
            assertEquals(1, intents())
        }

    @Test
    fun `with no session on the relay every call stays a warrant`() =
        runBlocking {
            val client = account.buildDelegatedClient(rig.session)!!
            assertEquals(JsonPrimitive(7), client.rpc.execute(ctx, "get"))
            assertEquals(0, queries())
            assertEquals(1, intents())
        }

    @Test
    fun `a hosted relay not yet attested advertises no subscription and its stream opens nothing`() {
        val client = account.buildDelegatedClient(rig.session.copy(relayUrl = "https://relay.example"))!!
        assertFalse(client.canSubscribe)
        val events = client.events
        events.connect()
        Thread.sleep(100)
        assertNull(events.sessionId)
        assertNull(events.failure.value)
        client.close()
    }

    @Test
    fun `with the key known the events are the client's own session-backed stream`() {
        account.pinRelayNodeKey("https://relay.example", cloud.nodeKey)
        assertTrue(account.buildDelegatedClient(rig.session.copy(relayUrl = "https://relay.example"))!!.canSubscribe)
    }

    @Test
    fun `a loopback relay is a dev rig that takes the proof, events as given`() {
        assertTrue(account.buildDelegatedClient(rig.session)!!.canSubscribe)
    }

    @Test
    fun `admin reads go out as the account, by the proof without a key`() =
        runBlocking {
            cloud.listedContexts += DelegatedRig.contextEntry(ctx)
            val contexts =
                account
                    .buildDelegatedClient(rig.session)!!
                    .admin
                    .getContexts()
                    .contexts
            assertEquals(listOf(ctx), contexts.map { it.id })
            val read = cloud.recorded("/admin-api/contexts").single()
            assertNull(read.headers["Authorization"])
            assertNotNull(read.headers["X-Calimero-Proof"])
        }

    @Test
    fun `lists contexts and namespaces on every relay it knows, and records which serves which`() =
        runBlocking {
            val (other, otherUrl) = rig.extra()
            cloud.listedContexts += DelegatedRig.contextEntry(ctx)
            cloud.listedNamespaces += DelegatedRig.namespaceEntry("n1".padEnd(64, '1'))
            other.cloud.listedContexts += DelegatedRig.contextEntry("d0".repeat(32))
            other.cloud.listedNamespaces += DelegatedRig.namespaceEntry("n1".padEnd(64, '1'))
            other.cloud.listedNamespaces += DelegatedRig.namespaceEntry("n2".padEnd(64, '2'))
            account.rememberRelay(rig.session.account, otherUrl, namespaceId = "n2".padEnd(64, '2'))
            // A relay that cannot be reached is skipped, not fatal.
            account.rememberRelay(rig.session.account, "http://127.0.0.1:1", contextId = "e0".repeat(32))

            val contexts = account.listDelegatedContexts(rig.session)
            assertEquals(listOf(ctx to rig.base, "d0".repeat(32) to otherUrl), contexts.map { it.context.id to it.relayUrl })
            assertEquals(otherUrl, account.relayForContext(rig.session.account, "d0".repeat(32)))

            val namespaces = account.listDelegatedNamespaces(rig.session)
            assertEquals(
                listOf("n1".padEnd(64, '1') to rig.base, "n2".padEnd(64, '2') to otherUrl),
                namespaces.map { it.namespace.namespaceId to it.relayUrl },
            )
            assertEquals(rig.base, account.readRelayMap(rig.session.account).namespaces["n1".padEnd(64, '1')])
        }
}
