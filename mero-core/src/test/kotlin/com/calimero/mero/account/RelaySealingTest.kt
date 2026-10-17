package com.calimero.mero.account

import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.base64
import com.calimero.mero.relay.AttestingRelayKeyVerifier
import com.calimero.mero.relay.RelayAttestation
import com.calimero.mero.tee.FakeNet
import com.calimero.mero.tee.StandInNode
import com.calimero.mero.tee.VerifyTransportQuote
import com.calimero.mero.tee.ok
import com.calimero.mero.tee.transportKeyBinding
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.util.encoders.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A delegated session seals every request to a hosted relay (mero-js `relayTransportFetch`). */
class RelaySealingTest {
    private val relayUrl = "https://relay.example"
    private val acc = "0e".repeat(32)
    private val executor = "4d".repeat(32)
    private val context = "01".repeat(32)

    /** Trusts a "quote" that is exactly `nonce ‖ binding` (the stand-in TD's), and nothing else. */
    private val echo =
        object : AttestingRelayKeyVerifier {
            override suspend fun verify(
                relayUrl: String,
                nonce: ByteArray,
                attestation: RelayAttestation,
            ): String = error("not used")

            override fun quoteVerifier(relayUrl: String) = VerifyTransportQuote { q -> Hex.encode(Base64.decode(q.quoteB64)) == q.nonce + q.reportDataSuffix }
        }

    private val node =
        StandInNode(relayUrl) { head, _ ->
            if (head["method"]!!.jsonPrimitive.content == "GET") {
                ok(
                    """{"data":{"executorAccount":"$executor","executorKey":"${"ec".repeat(32)}","canAuthorOnBehalf":true,""" +
                        """"groupId":"${"ab".repeat(32)}","releaseBytecodeId":"${"b1".repeat(32)}","releaseVersion":"1.2.0"}}""",
                )
            } else {
                ok("""{"data":{"rootHash":"root-1","returns":null}}""")
            }
        }

    private val net =
        FakeNet { req, body ->
            if (req.url.toString() == "$relayUrl/admin-api/tee/attest") {
                val nonce =
                    Json
                        .parseToJsonElement(String(body))
                        .jsonObject["nonce"]!!
                        .jsonPrimitive.content
                val quote = Hex.decode(nonce + Hex.encode(transportKeyBinding(ByteArray(32), node.transportKey)), "quote", 64)
                FakeNet.respond(req, 200, """{"data":{"quoteB64":"${base64(quote)}","transportPublicKey":"${Hex.encode(node.transportKey)}"}}""")
            } else {
                node.handle(req, body)
            }
        }

    private val session = DelegatedSession(acc, "dd".repeat(32), "c0ffee", relayUrl = relayUrl, executorAccount = executor)

    @After
    fun tearDown() = forgetRelaySealing()

    @Test
    fun `a delegated client's writes reach a hosted relay only sealed`() {
        val account = CloudAccount(MemorySecureStore(), relayKeyVerifier = echo, httpClient = net.client)
        val client = checkNotNull(account.buildDelegatedClient(session))
        val result = runBlocking { client.relay.execute(context, "send_secret_message", JsonPrimitive("hello-4242")) }
        assertEquals("root-1", result.rootHash)
        assertEquals(listOf("GET", "POST"), node.requests.map { it.first["method"]!!.jsonPrimitive.content })
        assertEquals(
            "/admin-api/contexts/$context/intents",
            node.requests[1]
                .first["path"]!!
                .jsonPrimitive.content,
        )
        val wire = net.bodies.joinToString("") { String(it, Charsets.ISO_8859_1) }
        assertFalse(wire.contains("send_secret_message"))
        assertFalse(wire.contains("hello-4242"))
        // Only the attestation and the envelopes crossed the wire.
        assertTrue(net.requests.all { it.endsWith("/admin-api/tee/attest") || it.endsWith("/sealed/v2/handshake") || it.endsWith("/sealed/v2") })
    }

    @Test
    fun `one sealed transport per relay, shared by every client built for it`() {
        val account = CloudAccount(MemorySecureStore(), relayKeyVerifier = echo, httpClient = net.client)
        assertTrue(account.relayTransportClient(relayUrl) === account.relayTransportClient("$relayUrl/"))
        forgetRelaySealing()
        assertFalse(account.relayTransportClient(relayUrl) === account.relayTransportClient("https://other.example"))
    }

    @Test
    fun `a loopback relay, or an account that opted out, is not sealed`() {
        val account = CloudAccount(MemorySecureStore(), relayKeyVerifier = echo, httpClient = net.client)
        assertTrue(account.relayTransportClient("http://127.0.0.1:2428") === net.client)
        val plain = CloudAccount(MemorySecureStore(), relayKeyVerifier = echo, httpClient = net.client, sealRelays = false)
        assertTrue(plain.relayTransportClient(relayUrl) === net.client)
    }
}
