package com.calimero.mero.cloud

import com.calimero.mero.account.Enrolment
import com.calimero.mero.account.EnrolmentException
import com.calimero.mero.account.NamespaceOp
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.crypto.readU32le
import com.calimero.mero.relay.RelayWarrantNonceState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parsing / encoding edges of the account layer. */
class AccountParsingTest {
    private val pk = "aa".repeat(32)
    private val kem = "bb".repeat(32)

    @Test
    fun `accepts both an https App Link and an app scheme as the callback`() {
        Enrolment.deviceEnrolmentUrl(pk, kem, "https://app.example/calimero/enrol", "s")
        Enrolment.deviceEnrolmentUrl(pk, kem, "mero-sample://enrol", "s")
        for (bad in listOf("enrol", "://x", "mero-sample://enrol#frag")) {
            try {
                Enrolment.deviceEnrolmentUrl(pk, kem, bad, "s")
                throw AssertionError("accepted $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `refuses keys that are not 64 lowercase hex`() {
        try {
            Enrolment.deviceEnrolmentUrl("AA".repeat(32), kem, "mero-sample://enrol")
            throw AssertionError("accepted uppercase")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `reads the fragment, ignores an ordinary link, and reports a refusal`() {
        assertNull(Enrolment.readEnrolmentCallback("mero-sample://enrol"))
        assertNull(Enrolment.readEnrolmentCallback("mero-sample://enrol#credential=ab"))
        val cb = Enrolment.readEnrolmentCallback("mero-sample://enrol#credential=c1&account=a1&device=d1&state=s%201")!!
        assertEquals("c1", cb.credential)
        assertEquals("a1", cb.account)
        assertEquals("d1", cb.device)
        assertEquals("s 1", cb.state)
        try {
            Enrolment.readEnrolmentCallback("mero-sample://enrol#error=server_error")
            throw AssertionError("expected EnrolmentException")
        } catch (e: EnrolmentException) {
            assertEquals("server_error", e.reason)
            assertTrue(!e.cancelled)
        }
    }

    @Test
    fun `parses a warrant nonce past 2^53 without rounding, and exhaustion`() {
        val open =
            RelayWarrantNonceState.parse(
                """{"data":{"contextId":"c","authorDeviceKey":"k","seen":true,"highWaterNonce":18446744073709551614,"nextNonce":18446744073709551615,"windowWidth":64}}""",
            ) as RelayWarrantNonceState.Open
        assertEquals(ULong.MAX_VALUE, open.nextNonce)
        assertEquals(ULong.MAX_VALUE - 1u, open.highWaterNonce)
        assertTrue(open.seen)
        val exhausted = RelayWarrantNonceState.parse("""{"data":{"contextId":"c","authorDeviceKey":"k","seen":true,"windowWidth":64}}""")
        assertTrue(exhausted is RelayWarrantNonceState.Exhausted)
    }

    @Test
    fun `chooseRelay prefers a fresh relay, then any with an address, then none`() {
        val fresh = CloudAccountRelay("p1", "https://a", true, "11".repeat(32), false)
        val stale = CloudAccountRelay("p2", "https://b", false, null, false)
        val dark = CloudAccountRelay("p3", null, true, null, false)
        assertEquals(RelayChoice("https://a", "11".repeat(32), null), CloudClient.chooseRelay(listOf(stale, fresh)))
        assertEquals("https://b", CloudClient.chooseRelay(listOf(dark, stale)).relayUrl)
        assertNull(CloudClient.chooseRelay(listOf(dark)).relayUrl)
        assertNull(CloudClient.chooseRelay(emptyList()).relayUrl)
    }

    @Test
    fun `writes admitter_addrs as bare borsh strings, in order`() {
        // namespace-op.test.ts: checked differentially, so the assertion stays about this field.
        val addr = "/ip4/10.0.0.1/tcp/2528/p2p/12D3KooWExample"
        val without = NamespaceOp.encodeSignedInvitation(invitation(emptyList()))
        val withOne = NamespaceOp.encodeSignedInvitation(invitation(listOf(addr)))
        assertEquals(4 + addr.encodeToByteArray().size, withOne.size - without.size)

        val two = NamespaceOp.encodeSignedInvitation(invitation(listOf("/a", "/b")))
        var at = 0
        while (at < without.size && without[at] == two[at]) at++
        assertEquals(2L, readU32le(two, at))
    }

    @Test
    fun `encodes the invitation body as core lays it out`() {
        val body = NamespaceOp.encodeGroupInvitation(invitation(emptyList()).invitation)
        // inviter 32 ‖ group 32 ‖ expiry u64 ‖ salt 32 ‖ role u8 ‖ admitters (u32 count, none)
        assertEquals(32 + 32 + 8 + 32 + 1 + 4, body.size)
        assertEquals(1, body[0].toInt())
        assertEquals(2, body[32].toInt())
        assertEquals(1, body[32 + 32 + 8 + 32].toInt())
    }

    private fun invitation(addrs: List<String>): SignedGroupOpenInvitation {
        fun bytes(v: Int) = buildJsonArray { repeat(32) { add(JsonPrimitive(v)) } }
        return SignedGroupOpenInvitation(
            buildJsonObject {
                put(
                    "invitation",
                    buildJsonObject {
                        put("inviter_identity", bytes(1))
                        put("group_id", bytes(2))
                        put("expiration_timestamp", 1_900_000_000)
                        put("secret_salt", bytes(3))
                        put("invited_role", 1)
                        put("admitters", buildJsonArray { })
                    },
                )
                put("inviter_signature", "deadbeef")
                put("admitter_addrs", buildJsonArray { addrs.forEach { add(JsonPrimitive(it)) } })
            },
        )
    }
}
