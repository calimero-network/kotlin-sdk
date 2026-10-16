package com.calimero.mero.relay

import com.calimero.mero.account.CloudAccount
import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.account.RelayNodeKeyAttempt
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.base64
import com.calimero.mero.crypto.concat
import com.calimero.mero.tee.FakeNet
import com.calimero.mero.tee.SignedNodeRelease
import com.calimero.mero.tee.TeeFixtures
import com.calimero.mero.tee.TeeTrustException
import com.calimero.mero.tee.TransportQuote
import com.calimero.mero.tee.VerifyTransportQuote
import com.calimero.mero.tee.assertRefuses
import com.calimero.mero.tee.quoteVerifier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bouncycastle.util.encoders.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of mero-js `src/relay-attestation/attest-node-key.test.ts`, plus [DcapRelayKeyVerifier]. */
class RelayAttestationTest {
    private val key = Hex.decode("3d46b7386e1cfa0a61c94eb3087ca7bb316ab1dd2b71974d9d524ad268088d8a", "key", 32)
    private val mockHeader = "MOCK_TDX_QUOTE_V1".encodeToByteArray()

    /** A relay answering `/tee/attest`, binding [boundKey] but naming [namedKey]. */
    private fun relay(
        namedKey: ByteArray = key,
        boundKey: ByteArray = namedKey,
        mock: Boolean = true,
        wrongNonce: Boolean = false,
        collateral: JsonObject? = null,
        sent: MutableList<JsonObject> = mutableListOf(),
    ): FakeNet =
        FakeNet { req, body ->
            val request = Json.parseToJsonElement(String(body)).jsonObject
            sent += request
            val n = Hex.decode(request["nonce"]!!.jsonPrimitive.content, "nonce", 32)
            if (wrongNonce) n[0] = (n[0].toInt() xor 0xff).toByte()
            val reportData = concat(n, attestKeyBinding(boundKey))
            val quote =
                if (mock) {
                    ByteArray(256).also {
                        mockHeader.copyInto(it)
                        reportData.copyInto(it, mockHeader.size)
                    }
                } else {
                    ByteArray(1024).also { reportData.copyInto(it, 48 + 520) }
                }
            val data =
                buildJsonObject {
                    put("quoteB64", base64(quote))
                    put("boundPublicKey", Hex.encode(namedKey))
                    collateral?.let { put("collateral", it) }
                }
            FakeNet.respond(req, 200, buildJsonObject { put("data", data) }.toString())
        }

    private fun attest(
        net: FakeNet,
        allowMock: Boolean = false,
        verify: VerifyTransportQuote? = null,
        verifyQuote: (suspend (ByteArray) -> Unit)? = null,
    ) = runBlocking { attestRelayNodeKey("http://r", allowMock, verify, verifyQuote, net.client) }

    @Test
    fun `returns the key a mock quote binds, when mock is allowed`() {
        assertEquals(AttestedNodeKey(Hex.encode(key), mock = true), attest(relay(), allowMock = true))
    }

    @Test
    fun `refuses a mock quote unless told otherwise`() {
        assertRefuses("MOCK") { attest(relay()) }
    }

    @Test
    fun `refuses a key the quote does not bind`() {
        assertRefuses("does not bind the key") { attest(relay(namedKey = ByteArray(32) { 7 }, boundKey = key), allowMock = true) }
    }

    @Test
    fun `refuses a quote made for another nonce`() {
        assertRefuses("nonce") { attest(relay(wrongNonce = true), allowMock = true) }
    }

    @Test
    fun `refuses a real quote with no verifier, and runs the verifier when given one`() {
        assertRefuses("verify") { attest(relay(mock = false)) }
        var verified = false
        val got = attest(relay(mock = false), verifyQuote = { verified = true })
        assertTrue(verified)
        assertEquals(AttestedNodeKey(Hex.encode(key), mock = false), got)
    }

    @Test
    fun `checks a real quote with a transport verifier - our nonce, the key binding, the collateral`() {
        val sent = mutableListOf<JsonObject>()
        val seen = mutableListOf<TransportQuote>()
        val verify =
            quoteVerifier { q ->
                seen += q
                true
            }
        val got = attest(relay(mock = false, collateral = TeeFixtures.COLLATERAL.toJson(), sent = sent), verify = verify)
        assertEquals(AttestedNodeKey(Hex.encode(key), mock = false), got)
        assertEquals("true", sent[0]["includeCollateral"].toString())
        assertEquals(sent[0]["nonce"]!!.jsonPrimitive.content, seen.single().nonce)
        assertEquals(Hex.encode(attestKeyBinding(key)), seen.single().reportDataSuffix)
        assertEquals(TeeFixtures.COLLATERAL.tcbInfo, seen.single().collateral!!.tcbInfo)
    }

    @Test
    fun `refuses when the transport verifier does not accept the quote`() {
        assertRefuses("did not verify") { attest(relay(mock = false), verify = { false }) }
    }

    // Captured from a mock-TEE merod (core rc.60, `merod run --mock-tee`) answering
    // POST /admin-api/tee/attest { nonce, bindNodeKey: true }.
    @Test
    fun `reads the nonce and the key binding out of a real core response`() {
        val nonce = "912466d433e9fb59fa3efc199c570f7afa7a0517cc4d868bcceeb324b9f896e6"
        val quoteB64 =
            "TU9DS19URFhfUVVPVEVfVjGRJGbUM+n7Wfo+/BmcVw96+noFF8xNhovM7rMkufiW5oay/bvn9kdYWwIO5cDHwshQazm9T8Mrg7LUZwLXS/TMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
        val (reportData, mock) = reportDataOf(Base64.decode(quoteB64))
        assertTrue(mock)
        assertEquals(nonce, Hex.encode(reportData.copyOfRange(0, 32)))
        assertEquals(Hex.encode(attestKeyBinding(key)), Hex.encode(reportData.copyOfRange(32, 64)))
    }

    // ---- DcapRelayKeyVerifier ----------------------------------------------------------------

    private val relayUrl = "https://relay.example"
    private val releasedImage = "merotee-ubuntu-questing-25-10-locked-read-only-2-3-87"

    private fun signed(version: String) =
        SignedNodeRelease(
            version,
            TeeFixtures.text("sealed/mero-tee-v$version.published-mrtds.json"),
            TeeFixtures.text("sealed/mero-tee-v$version.published-mrtds.json.bundle.json"),
        )

    /** A hosted relay attesting with dcap-qvl's real sample quote, and the mirror serving release 2.3.87. */
    private fun hostedRelay(mirrorUp: Boolean = true): FakeNet =
        FakeNet { req, _ ->
            when (req.url.toString()) {
                "$relayUrl/admin-api/tee/info" -> FakeNet.respond(req, 200, """{"data":{"cloudProvider":"gcp","osImage":"$releasedImage","mrtd":""}}""")
                "https://cloud.calimero.network/api/tee/node-releases/2.3.87" ->
                    if (mirrorUp) {
                        val r = signed("2.3.87")
                        val data =
                            buildJsonObject {
                                put("version", r.version)
                                put("publishedMrtds", r.publishedMrtds)
                                put("bundle", r.bundle)
                            }
                        FakeNet.respond(req, 200, buildJsonObject { put("data", data) }.toString())
                    } else {
                        FakeNet.respond(req, 502, "bad gateway", "text/plain")
                    }
                "$relayUrl/admin-api/tee/attest" -> {
                    val data =
                        buildJsonObject {
                            put("quoteB64", TeeFixtures.QUOTE_B64)
                            put("boundPublicKey", Hex.encode(key))
                            put("collateral", TeeFixtures.COLLATERAL.toJson())
                        }
                    FakeNet.respond(req, 200, buildJsonObject { put("data", data) }.toString())
                }
                else -> null
            }
        }

    private fun dcap(net: FakeNet) = DcapRelayKeyVerifier(minReleaseVersion = "2.3.86", httpClient = net.client, now = { TeeFixtures.AT })

    private fun mockAttestation(
        nonce: ByteArray,
        bound: ByteArray = key,
    ) = RelayAttestation(base64(concat(mockHeader, nonce, attestKeyBinding(bound))), Hex.encode(key), JsonObject(emptyMap()))

    @Test
    fun `takes a mock quote only from a loopback relay`() {
        val verifier = DcapRelayKeyVerifier()
        val nonce = ByteArray(32) { 5 }
        assertEquals(Hex.encode(key), runBlocking { verifier.verify("http://127.0.0.1:2428", nonce, mockAttestation(nonce)) })
        assertEquals(Hex.encode(key), runBlocking { verifier.verify("http://10.0.2.2:2428", nonce, mockAttestation(nonce)) })
        assertRefuses("MOCK") { runBlocking { verifier.verify(relayUrl, nonce, mockAttestation(nonce)) } }
        assertEquals(Hex.encode(key), runBlocking { DcapRelayKeyVerifier(allowMock = true).verify(relayUrl, nonce, mockAttestation(nonce)) })
        assertRefuses("does not bind the key") {
            runBlocking { verifier.verify("http://localhost", nonce, mockAttestation(nonce, ByteArray(32))) }
        }
    }

    @Test
    fun `verifies a hosted relay's quote against the signed release it names, on the device`() {
        val net = hostedRelay()
        // dcap-qvl's sample quote is genuine (its chain, TCB and collateral all verify) but it is
        // not a mero-tee image, so it is refused at the measurements of release 2.3.87.
        val e = assertRefuses("are not an image this verifier trusts") { runBlocking { attestRelayNodeKey(relayUrl, verify = dcap(net).quoteVerifier(relayUrl), httpClient = net.client) } }
        assertTrue(e is TeeTrustException)
        assertEquals(
            listOf("$relayUrl/admin-api/tee/attest", "$relayUrl/admin-api/tee/info", "https://cloud.calimero.network/api/tee/node-releases/2.3.87"),
            net.requests,
        )
    }

    @Test
    fun `a relay below the minimum release is refused before its quote is read`() {
        val net = hostedRelay()
        val strict = DcapRelayKeyVerifier(minReleaseVersion = "2.3.99", httpClient = net.client, now = { TeeFixtures.AT })
        assertRefuses("older than the minimum trusted, 2.3.99") {
            runBlocking { attestRelayNodeKey(relayUrl, verify = strict.quoteVerifier(relayUrl), httpClient = net.client) }
        }
    }

    @Test
    fun `a mirror that does not answer is unavailable, not a refusal`() {
        val net = hostedRelay(mirrorUp = false)
        val e = assertRefuses("fetching the release the relay runs") { runBlocking { attestRelayNodeKey(relayUrl, verify = dcap(net).quoteVerifier(relayUrl), httpClient = net.client) } }
        assertTrue(e is RelayKeyTransportException)
    }

    @Test
    fun `CloudAccount reads a refused quote as refused, and a mirror outage as unavailable`() {
        val refusing = hostedRelay()
        val account = CloudAccount(MemorySecureStore(), relayKeyVerifier = dcap(refusing), httpClient = refusing.client)
        assertTrue(runBlocking { account.attemptRelayNodeKey(relayUrl) } is RelayNodeKeyAttempt.Refused)
        assertEquals(null, account.readPinnedRelayNodeKey(relayUrl))

        val down = hostedRelay(mirrorUp = false)
        val waiting = CloudAccount(MemorySecureStore(), relayKeyVerifier = dcap(down), httpClient = down.client)
        assertTrue(runBlocking { waiting.attemptRelayNodeKey(relayUrl) } is RelayNodeKeyAttempt.Unavailable)
    }

    @Test
    fun `is the default verifier of a CloudAccount and a RelayLogin`() {
        assertTrue(CloudAccount(MemorySecureStore()).relayKeyVerifier is DcapRelayKeyVerifier)
        assertEquals(MIN_RELAY_RELEASE, "2.3.99")
    }
}
