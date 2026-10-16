package com.calimero.mero.cloud

import com.calimero.mero.account.resolveRoot
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.relay.MemoryNonceSource
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** mero-js `connect.test.ts`, against [FakeNode]'s cloud + relay routes. */
class ConnectCloudTest {
    private lateinit var node: FakeNode
    private lateinit var server: MockWebServer
    private lateinit var base: String

    private val ns = "01".repeat(32)
    private val otherNs = "02".repeat(32)
    private val context = "03".repeat(32)
    private val author = "0e".repeat(32)
    private val deviceSecret = "77".repeat(32)

    @Before
    fun setUp() {
        node = FakeNode()
        server = node.start()
        base = server.url("/").toString().trimEnd('/')
        node.cloud.ownedNamespaces[ns] = "enabled"
    }

    @After
    fun tearDown() = server.shutdown()

    private fun options(
        sessionToken: String? = "fake-session",
        googleIdToken: String? = null,
        namespaceId: String? = null,
        deviceSecret: String? = this.deviceSecret,
        signer: SeedSigner? = null,
        relayNodeKey: String? = null,
    ) = ConnectCloudOptions(
        authorAccount = author,
        authorProof = "aa",
        deviceSecret = deviceSecret,
        signer = signer,
        sessionToken = sessionToken,
        googleIdToken = googleIdToken,
        cloudBaseUrl = base,
        namespaceId = namespaceId,
        nonces = MemoryNonceSource(),
        relayNodeKey = relayNodeKey,
    )

    private fun notReady(block: suspend () -> Unit): String =
        runBlocking {
            try {
                block()
                fail("expected CloudNotReadyException")
                error("unreachable")
            } catch (e: CloudNotReadyException) {
                e.message!!
            }
        }

    private fun relay(
        teeRole: String?,
        authorshipReady: Boolean,
        canExecute: Boolean? = null,
        executor: String = "4d".repeat(32),
    ) = buildJsonObject {
        put("peer_id", "12D3KooW$teeRole")
        put("relay_url", base)
        put("executor_account", executor)
        put("status", "active")
        put("authorship_ready", authorshipReady)
        teeRole?.let { put("tee_role", it) }
        canExecute?.let { put("can_execute", it) }
    }

    @Test
    fun `signs in, finds the namespace and its relay, and writes straight to the relay`() =
        runBlocking {
            val connection = connectCloud(options())
            assertEquals(ns, connection.namespaceId)
            assertEquals(node.cloud.relayAccount, connection.relayInfo.executorAccount)
            assertEquals(base, connection.relay.relayUrl)
            assertEquals("enabled", connection.namespace.haStatus)
            val result = connection.execute(context, "set", buildJsonObject { put("key", "k") })
            assertTrue(result.rootHash.startsWith("root-"))
            val intent = node.cloud.requests.single { it.method == "POST" && it.path.endsWith("/$context/intents") }
            assertEquals("set", intent.bodyJson!!["method"]!!.jsonPrimitive.content)
            assertEquals(
                "Bearer fake-session",
                node.cloud
                    .recorded("/api/cloud/me/namespaces")
                    .single()
                    .headers["Authorization"],
            )
            // The relay connection is the same RelayConnection a device sign-in yields.
            assertEquals(author, connection.client.session.account)
            assertFalse(connection.canSubscribe)
            assertFalse(connection.client.ensureSession())
        }

    @Test
    fun `exchanges a Google ID token when no session token is held`() =
        runBlocking {
            val seen = mutableListOf<String?>()
            val connection =
                connectCloud(
                    ConnectCloudOptions(
                        authorAccount = author,
                        authorProof = "aa",
                        deviceSecret = deviceSecret,
                        googleIdToken = "google-id-token",
                        cloudBaseUrl = base,
                        onSession = { seen += it?.sessionToken },
                        nonces = MemoryNonceSource(),
                    ),
                )
            assertEquals(1, node.cloud.recorded("/api/auth/google").size)
            assertEquals(listOf(connection.cloud.getSession()?.sessionToken), seen)
        }

    @Test
    fun `needs one cloud credential, and exactly one device key, before any request`() =
        runBlocking {
            for (o in listOf(options(sessionToken = null), options(deviceSecret = null), options(signer = SeedSigner(deviceSecret)))) {
                try {
                    connectCloud(o)
                    fail("accepted")
                } catch (_: IllegalArgumentException) {
                }
            }
            assertTrue(node.cloud.requests.isEmpty())
        }

    @Test
    fun `signs with a device key held as a signer`() =
        runBlocking {
            val connection = connectCloud(options(deviceSecret = null, signer = SeedSigner(deviceSecret)))
            assertEquals(SeedSigner(deviceSecret).publicKey, connection.relay.authorSigner.publicKey)
        }

    @Test
    fun `refuses to guess between several owned namespaces, and honours an explicit one`() {
        node.cloud.ownedNamespaces[otherNs] = "enabled"
        assertTrue(notReady { connectCloud(options()) }.contains("pass namespaceId"))
        runBlocking { assertEquals(otherNs, connectCloud(options(namespaceId = otherNs)).namespaceId) }
        val wrong = notReady { connectCloud(options(namespaceId = "ff".repeat(32))) }
        assertTrue(wrong.contains("owns no namespace") && wrong.contains(ns))
    }

    @Test
    fun `says to claim a namespace when the login owns none`() {
        node.cloud.ownedNamespaces.clear()
        assertTrue(notReady { connectCloud(options()) }.contains("claim one first"))
    }

    @Test
    fun `says to enable HA, or to retry, when there is no relay`() {
        node.cloud.namespaceRelays[ns] = emptyList()
        assertTrue(notReady { connectCloud(options()) }.contains("retry shortly"))
        node.cloud.ownedNamespaces[ns] = "none"
        assertTrue(notReady { connectCloud(options()) }.contains("enable HA"))
    }

    @Test
    fun `names the account an admin must grant when every relay lacks the capability`() {
        node.cloud.namespaceRelays[ns] = listOf(relay(null, authorshipReady = false))
        val message = notReady { connectCloud(options()) }
        assertTrue(message.contains("CAN_AUTHOR_ON_BEHALF to ${"4d".repeat(32)}"))
        assertTrue(message.contains("grantAuthorship") && message.contains("openToDelegatedExecution"))
    }

    @Test
    fun `points at the TEE admission policy when every node is a replica`() {
        node.cloud.namespaceRelays[ns] = listOf(relay("ReadOnlyTee", authorshipReady = true, canExecute = false))
        val message = notReady { connectCloud(options()) }
        assertTrue(message.contains("ReadOnlyTee") && message.contains("mode: 'relay'"))
    }

    @Test
    fun `connects through a RelayTee that holds no grant`() =
        runBlocking {
            node.cloud.namespaceRelays[ns] = listOf(relay("RelayTee", authorshipReady = false, canExecute = true, executor = node.cloud.relayAccount))
            assertEquals("RelayTee", connectCloud(options()).relayInfo.teeRole)
        }

    @Test
    fun `observes as well as writes when the relay's node key is known`() =
        runBlocking {
            val connection = connectCloud(options(relayNodeKey = node.cloud.nodeKey))
            assertTrue(connection.canSubscribe)
            assertTrue(connection.client.ensureSession())
        }

    // ---- connectCloudWithAccount ---------------------------------------------------------

    private val rootSecret = "5b6b8a1e9f2c47d3a80e6f14c2b9d75380af4e21c6d3b95f7e08a1c4d2f63b97"

    @Test
    fun `signs in with the root, writes as the account it derives, and reports the session`() =
        runBlocking {
            val seen = mutableListOf<String?>()
            val connection =
                connectCloudWithAccount(
                    ConnectCloudWithAccountOptions(
                        authorProof = "aa",
                        rootSecret = rootSecret,
                        deviceSecret = deviceSecret,
                        cloudBaseUrl = base,
                        onSession = { seen += it?.sessionToken },
                        nonces = MemoryNonceSource(),
                    ),
                )
            assertEquals(resolveRoot(rootSecret).accountId, connection.relay.authorAccount)
            assertEquals(1, node.cloud.recorded("/api/auth/account").size)
            val token = connection.cloud.getSession()!!.sessionToken
            assertEquals(token, seen.first())
            assertEquals(
                "Bearer $token",
                node.cloud
                    .recorded("/api/cloud/me/namespaces")
                    .single()
                    .headers["Authorization"],
            )
            val signed =
                node.cloud
                    .recorded("/api/auth/account")
                    .single()
                    .bodyJson!!
            assertEquals(JsonPrimitive(resolveRoot(rootSecret).publicKey), signed["root_public_key"])
        }

    @Test
    fun `lets an explicit authorAccount override the signing root`() =
        runBlocking {
            val connection =
                connectCloudWithAccount(
                    ConnectCloudWithAccountOptions(
                        authorProof = "aa",
                        root = SeedSigner(rootSecret),
                        authorAccount = author,
                        deviceSecret = deviceSecret,
                        cloudBaseUrl = base,
                        nonces = MemoryNonceSource(),
                    ),
                )
            assertEquals(author, connection.relay.authorAccount)
        }

    @Test
    fun `still needs a device key of its own, the root is not one`() =
        runBlocking {
            try {
                connectCloudWithAccount(ConnectCloudWithAccountOptions(authorProof = "aa", rootSecret = rootSecret, cloudBaseUrl = base))
                fail("accepted")
            } catch (_: IllegalArgumentException) {
            }
            assertTrue(node.cloud.requests.isEmpty())
        }

    @Test
    fun `explains the mixed case as not ready yet`() {
        val message =
            explainNoRelay(
                CloudNamespace(ns, ns, "enabled"),
                listOf(
                    CloudRelay("p", null, null, null, "assigned", true, "RelayTee", false),
                ),
            )
        assertTrue(message.contains("none is ready to relay yet"))
    }
}
