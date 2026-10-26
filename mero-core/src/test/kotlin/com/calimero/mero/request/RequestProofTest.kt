package com.calimero.mero.request

import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.crypto.u64le
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conformance against core's pinned vectors, as mero-js `request-sig.test.ts`,
 * `caller-proof.test.ts` and `proof-signer.test.ts` assert them
 * (`crates/account/src/tests/request_wire_fixture.rs`, and `chain_for(1, key(7))` from
 * `crates/server/src/proof_auth.rs`). A disagreement is a proof that verifies nowhere.
 */
class RequestProofTest {
    private val signerSecret = "03".repeat(32)
    private val method = "GET"
    private val path = "/admin-api/namespaces?limit=5&q=a%2Fb"
    private val body = """{"k":"v"}"""
    private val issuedAt = 1_700_000_000L
    private val expiresAt = 1_700_000_300L

    private fun sig(
        method: String = this.method,
        path: String = this.path,
        body: String = this.body,
        issuedAt: Long = this.issuedAt,
        expiresAt: Long = this.expiresAt,
    ) = signRequest(RequestSigInput(method, path, issuedAt, expiresAt, body.encodeToByteArray(), signerSecret = signerSecret))

    @Test
    fun `commits to the body under its own domain, empty included`() {
        assertEquals("3b26137eb7b296bdf7d84b9193dd52d1947b2056731f38020f4a3b9b88d95121", Hex.encode(requestBodyHash(body)))
        val empty = Hex.encode(requestBodyHash())
        assertNotEquals("0".repeat(64), empty)
        assertNotEquals(Hex.encode(requestBodyHash(body)), empty)
    }

    @Test
    fun `reproduces core's recorded encoding exactly`() {
        assertEquals(
            "03000000" + "474554" +
                "25000000" + "2f61646d696e2d6170692f6e616d657370616365" + "733f6c696d69743d3526713d6125324662" +
                "3b26137eb7b296bdf7d84b9193dd52d1947b2056731f38020f4a3b9b88d95121" +
                "00f1536500000000" + "2cf2536500000000" +
                "fab437d01e5fe97a376e9165a55e0ec91e73325b08bb997c8755776df0939d2f" +
                "c3001455d4c08e306244afd2f050c30a01237aa06187e185fc0a671941c8ac09",
            sig(),
        )
    }

    @Test
    fun `signs differently when any signed field changes, and does not fold HEAD into GET`() {
        val original = sig()
        listOf(
            sig(method = "POST"),
            sig(path = "/admin-api/namespaces?q=a%2Fb&limit=5"),
            sig(path = "/admin-api/namespaces?limit=5&q=a/b"),
            sig(path = "/admin-api/namespaces"),
            sig(body = """{"k":"w"}"""),
            sig(issuedAt = issuedAt + 1),
            sig(expiresAt = expiresAt + 1),
        ).forEach { assertNotEquals(original, it) }
        assertNotEquals(sig(method = "HEAD", body = ""), sig(method = "GET", body = ""))
    }

    @Test
    fun `matches signRequestProof's request link`() {
        val proof =
            signRequestProof(
                RequestProofInput(
                    method,
                    path,
                    credential = "ab",
                    body = body.encodeToByteArray(),
                    deviceSecret = signerSecret,
                    validForSeconds = expiresAt - issuedAt,
                    now = issuedAt,
                ),
            )
        assertEquals("ab00${sig()}", proof)
    }

    @Test
    fun `signRequestProof refuses no method, a relative path and no key`() {
        assertThrows(IllegalArgumentException::class.java) { signRequestProof(RequestProofInput("", "/x", "ab", deviceSecret = signerSecret)) }
        assertThrows(IllegalArgumentException::class.java) { signRequestProof(RequestProofInput("GET", "x", "ab", deviceSecret = signerSecret)) }
        assertThrows(IllegalArgumentException::class.java) { signRequestProof(RequestProofInput("GET", "/x", "ab")) }
    }

    // ---- callerProof (bytes produced by core) ----------------------------------------------

    @Test
    fun `matches the bytes core produces for a three-link and a two-link chain`() {
        assertEquals(FULL_WITH_SESSION, callerProof(CallerProofInput(CREDENTIAL, REQUEST, SESSION)))
        assertEquals(FULL_WITHOUT_SESSION, callerProof(CallerProofInput(CREDENTIAL, REQUEST)))
    }

    @Test
    fun `does not treat an omitted session as an empty one, and refuses non-hex`() {
        val omitted = callerProof(CallerProofInput(CREDENTIAL, REQUEST))
        val empty = callerProof(CallerProofInput(CREDENTIAL, REQUEST, session = ""))
        assertNotEquals(omitted, empty)
        assertEquals("00", omitted.substring(CREDENTIAL.length, CREDENTIAL.length + 2))
        assertEquals("01", empty.substring(CREDENTIAL.length, CREDENTIAL.length + 2))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { callerProof(CallerProofInput("nothex", REQUEST)) }.message!!.contains("credential"))
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) { callerProof(CallerProofInput(CREDENTIAL, "abc")) }
                .message!!
                .contains("request signature"),
        )
    }

    // ---- createProofSigner (core's chain_for(1, key(7))) -----------------------------------

    @Test
    fun `reproduces core's CallerProof byte for byte`() =
        runBlocking {
            val sign = createProofSigner(ProofSignerOptions(CREDENTIAL, "65".repeat(32), session = SESSION, ttlSeconds = 300, now = { 1_700_000_000L }))
            assertEquals(FULL_WITH_SESSION, sign(ProofRequest("GET", "/admin-api/namespaces")))
            val twoLink = createProofSigner(ProofSignerOptions(CREDENTIAL, "65".repeat(32), ttlSeconds = 300, now = { 1_700_000_000L }))
            assertEquals(FULL_WITHOUT_SESSION, twoLink(ProofRequest("GET", "/admin-api/namespaces")))
        }

    @Test
    fun `defaults to a 120 s expiry`() =
        runBlocking {
            val implicit = createProofSigner(ProofSignerOptions(CREDENTIAL, "65".repeat(32), now = { 1_700_000_000L }))
            val explicit = createProofSigner(ProofSignerOptions(CREDENTIAL, "65".repeat(32), ttlSeconds = 120, now = { 1_700_000_000L }))
            assertEquals(explicit(ProofRequest("GET", "/x")), implicit(ProofRequest("GET", "/x")))
        }

    // ---- request window cap (core's MAX_REQUEST_LIFETIME_SECS) -------------------------------

    @Test
    fun `the request window cap is core's`() {
        assertEquals(300L, MAX_REQUEST_PROOF_TTL_SECONDS)
    }

    @Test
    fun `a proof signer refuses a window a node would refuse`() {
        for (ttl in listOf(0L, -1L, 301L, 86_400L, Long.MAX_VALUE)) {
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    createProofSigner(ProofSignerOptions(CREDENTIAL, "65".repeat(32), ttlSeconds = ttl))
                }
            assertTrue(error.message!!, error.message!!.contains("ttlSeconds"))
        }
    }

    @Test
    fun `a proof signer accepts the full window`() =
        runBlocking {
            val sign = createProofSigner(ProofSignerOptions(CREDENTIAL, "65".repeat(32), ttlSeconds = 300, now = { 1_700_000_000L }))
            val proof = sign(ProofRequest("GET", "/x"))!!
            // expires_at is the u64 just before the 64-byte signature.
            assertEquals(Hex.encode(u64le(1_700_000_300L)), proof.dropLast(128).takeLast(16))
        }

    @Test
    fun `signRequestProof refuses a window a node would refuse`() {
        for (validFor in listOf(0L, -1L, 301L, Long.MAX_VALUE)) {
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    signRequestProof(RequestProofInput("GET", "/x", "ab", deviceSecret = signerSecret, validForSeconds = validFor, now = issuedAt))
                }
            assertTrue(error.message!!, error.message!!.contains("validForSeconds"))
        }
    }

    @Test
    fun `an authorizer refuses a window a node would refuse up front`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                createProofAuthorizer(ProofCredential(CREDENTIAL, deviceSecret = signerSecret, validForSeconds = 301))
            }
        assertTrue(error.message!!, error.message!!.contains("validForSeconds"))
        createProofAuthorizer(ProofCredential(CREDENTIAL, deviceSecret = signerSecret, validForSeconds = 300))
    }

    // ---- createProofAuthorizer / signedPath ------------------------------------------------

    @Test
    fun `an authorizer validates its credential up front and returns the header`() =
        runBlocking {
            assertThrows(IllegalArgumentException::class.java) { createProofAuthorizer(ProofCredential("", deviceSecret = signerSecret)) }
            assertThrows(IllegalArgumentException::class.java) { createProofAuthorizer(ProofCredential("ab")) }
            val headers = createProofAuthorizer(ProofCredential("ab", signer = SeedSigner(signerSecret)))(ProofRequest("GET", "/x"))
            assertEquals(setOf(REQUEST_PROOF_HEADER), headers.keys)
            assertTrue(headers.getValue(REQUEST_PROOF_HEADER).startsWith("ab00"))
            assertTrue(carriesProof(mapOf("x-calimero-proof" to "1")))
        }

    @Test
    fun `signedPath is the request line's path and query, escapes kept`() {
        assertEquals("/admin-api/namespaces?limit=5&q=a%2Fb", signedPath("https://n.example/admin-api/namespaces?limit=5&q=a%2Fb"))
        assertEquals("/node1/admin-api/contexts", signedPath("http://h:2428/node1/admin-api/contexts"))
        assertEquals("/admin-api/contexts?", signedPath("http://h/admin-api/contexts?"))
        assertEquals("/x?a=1", signedPath("/x?a=1"))
    }

    private companion object {
        const val CREDENTIAL =
            "028a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c0000000004cfa21629a77f8cd8ddd3f821ed514009a9f572b2ce8e0a11f5cbb5e25340b0222222222222222222222222222222229aeef190d5865e90861a94ec2e0b28de17cb79fb2b4120f2b1ec65e4198d6e08b28e813feb01e4a400839b85e18080ce09090909090909090909090909090909090909090909090909090909090909090000000000000000bba802950e323563e8cef9867dfd562dfbd50e106ccd07c779acf3e4daba6a964e0d1354ee521747c38e0aa9839d9460dbc5a278822ac729e58f62ed8835120f"
        const val SESSION =
            "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c027777777777777777777777777777777777777777777777777777777777777777d62f016a1efd1e4fdf793eb42cd84471e1ba9f0cf04d1287b5cc71f616287cb817cb79fb2b4120f2b1ec65e4198d6e08b28e813feb01e4a400839b85e18080ce00f153650000000010ff53650000000056554af3cbf04380c22898aa2b15397f526d9dec50ab792b76fd45ca01668488be57b317015d9e4aada80a8dda0367f7390071051e2b3855c5e7eeaf0119520c"
        const val REQUEST =
            "03000000474554150000002f61646d696e2d6170692f6e616d65737061636573ea5bb92ca52d40a333e56e353225c6a569e2afbcb7a9152aa9727bed602872f900f15365000000002cf2536500000000603ab514b9df40ba389f9eae5d3c3040fa9ef473a4e00ac0000f8db1b08f50ada268bbcf7daf351e6afec3176e7aa2f6c5cab57a2ff930bd7962035358a8db0c"
        const val FULL_WITH_SESSION = CREDENTIAL + "01" + SESSION + REQUEST
        const val FULL_WITHOUT_SESSION = CREDENTIAL + "00" + REQUEST
    }
}
