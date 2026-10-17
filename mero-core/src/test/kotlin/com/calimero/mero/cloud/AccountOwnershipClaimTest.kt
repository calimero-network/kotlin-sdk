package com.calimero.mero.cloud

import com.calimero.mero.crypto.Ed25519
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.crypto.fromBase64
import com.calimero.mero.http.ErrorKind
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.classifyError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * mero-js `account-ownership.test.ts` (the session claim) and the typed HA refusals of
 * `enable-ha-as-account.test.ts`.
 */
class AccountOwnershipClaimTest {
    private val secret = "07".repeat(32)
    private val signer = SeedSigner(secret)
    private val ns = "aa".repeat(32)
    private val acc = "bb".repeat(32)
    private val salt = "cc".repeat(32)
    private val credential = "dd".repeat(40)
    private val now = 1_700_000_000_000L
    private val domain = "calimero.mdma.account-ownership-claim.v1\u0000".encodeToByteArray()

    private fun payloadOf(proof: AccountOwnershipProof): JsonObject = Json.parseToJsonElement(fromBase64(proof.signedPayload).decodeToString()).jsonObject

    private fun verifies(proof: AccountOwnershipProof): Boolean =
        Ed25519.verify(Hex.decode(signer.publicKey, "pk", 32), fromBase64(proof.signature), domain + fromBase64(proof.signedPayload))

    @Test
    fun `builds mdma's session claim, keyed in order, and signs domain plus payload`() {
        val proof = signAccountOwnershipClaim(ns, acc, salt, "founder@example.com", credential, signer, now = now)
        assertEquals(credential, proof.credential)
        assertEquals("account", proof.toJson()["kind"]?.jsonPrimitive?.content)
        val payload = payloadOf(proof)
        assertEquals(
            listOf("v", "audience", "group_id", "account_id", "salt", "subject", "nonce", "issued_at_ms", "expires_at_ms"),
            payload.keys.toList(),
        )
        assertEquals(JsonPrimitive(ACCOUNT_OWNERSHIP_AUDIENCE), payload["audience"])
        assertEquals("mdma:enable-ha-namespace", ACCOUNT_OWNERSHIP_AUDIENCE)
        assertEquals(JsonPrimitive("founder@example.com"), payload["subject"])
        assertEquals(now + 60_000, payload["expires_at_ms"]!!.jsonPrimitive.long)
        assertTrue(Regex("^[0-9a-f]{32}$").matches(payload["nonce"]!!.jsonPrimitive.content))
        assertTrue(verifies(proof))
        assertFalse(Ed25519.verify(Hex.decode(signer.publicKey, "pk", 32), fromBase64(proof.signature), fromBase64(proof.signedPayload)))
    }

    @Test
    fun `refuses a lifetime the cloud would refuse, a missing subject and malformed ids`() {
        val bad =
            listOf(
                { signAccountOwnershipClaim(ns, acc, salt, "s@x", credential, signer, ttlMs = 5 * 60_000 + 1) },
                { signAccountOwnershipClaim(ns, acc, salt, "", credential, signer) },
                { signAccountOwnershipClaim(ns, acc, "zz", "s@x", credential, signer) },
            )
        for ((i, call) in bad.withIndex()) {
            try {
                call()
                fail("case $i accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `the anonymous claim cannot stand in for the session claim`() {
        val ha = payloadOf(AccountOwnership.signAccountHaClaim(ns, acc, salt, credential, signer, now = now))
        assertFalse("subject" in ha)
        assertEquals(JsonPrimitive(ACCOUNT_HA_AUDIENCE), ha["audience"])
        assertTrue(ACCOUNT_HA_AUDIENCE != ACCOUNT_OWNERSHIP_AUDIENCE)
    }

    // ---- typed refusals ------------------------------------------------------------------

    private fun refusedWith(
        status: Int,
        body: String,
    ): Throwable =
        runBlocking {
            val server = MockWebServer().apply { start() }
            server.enqueue(MockResponse().setResponseCode(status).setBody(body))
            try {
                CloudClient(server.url("/").toString()).enableHaAsAccount(ns, salt, acc, credential, signer)
                fail("expected a refusal")
                error("unreachable")
            } catch (e: HttpException) {
                e
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `maps each refusal to its own exception, by status and code`() {
        val cases =
            listOf(
                Triple(409, "account_not_linked", AccountNotLinkedException::class),
                Triple(409, "account_linked_to_several_users", AccountLinkedToSeveralUsersException::class),
                Triple(409, "ha_request_pending", HaRequestPendingException::class),
                Triple(422, "unknown_relay", UnknownRelayException::class),
                Triple(422, "relay_not_dialable", RelayNotDialableException::class),
            )
        for ((status, code, type) in cases) {
            val e = refusedWith(status, """{"error":"$code"}""")
            assertEquals(type, e::class)
            e as AccountHaRefusedException
            assertEquals(code, e.code)
            assertEquals(status, e.status)
            assertEquals(HaRefusal.entries.first { it.code == code }, e.refusal)
            assertEquals(e.refusal!!.message, e.advice)
        }
        // FastAPI's `detail` spellings read the same.
        assertTrue(refusedWith(409, """{"detail":"account_not_linked"}""") is AccountNotLinkedException)
        assertTrue(refusedWith(409, """{"detail":{"error":"ha_request_pending","namespace_id":"x"}}""") is HaRequestPendingException)
    }

    @Test
    fun `leaves a known code on the wrong status, or an unknown code, a plain HttpException`() {
        assertFalse(refusedWith(422, """{"error":"account_not_linked"}""") is AccountHaRefusedException)
        assertFalse(refusedWith(409, """{"error":"something_else"}""") is AccountHaRefusedException)
        assertFalse(refusedWith(403, "not json") is AccountHaRefusedException)
    }

    @Test
    fun `classifies the refusals with their advice, and the cloud errors by kind`() {
        val e = refusedWith(409, """{"error":"account_not_linked"}""")
        val c = classifyError(e)
        assertEquals(ErrorKind.CONFLICT, c.kind)
        assertEquals(HaRefusal.ACCOUNT_NOT_LINKED.message, c.message)
        assertEquals(ErrorKind.UNAVAILABLE, classifyError(CloudNotReadyException("no relay")).kind)
        assertTrue(classifyError(CloudNotReadyException("no relay")).retryable)
        assertEquals(ErrorKind.FORBIDDEN, classifyError(CloudLinkDeclinedException("declined", "denied")).kind)
        assertNull(HaRefusal.of("unknown_relay", 409))
    }
}
