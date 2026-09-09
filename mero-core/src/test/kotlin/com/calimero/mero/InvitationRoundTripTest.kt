package com.calimero.mero

import com.calimero.mero.admin.JoinNamespaceRequest
import com.calimero.mero.admin.SignedGroupOpenInvitation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An invitation must survive a round trip through the SDK **byte for byte**.
 *
 * The node verifies an invitation by deserializing the JSON into its own struct,
 * re-encoding as borsh, and checking `inviter_signature` over those bytes. So any
 * key the model drops — or adds — changes the borsh and invalidates the signature.
 * Measured against a live `merod 0.11.0-rc.32`, on one namespace, two freshly
 * minted invitations, same joining node:
 *
 * ```
 * model-shaped (2 of 6 envelope keys, 5 of 6 signed) → 500
 *     node log: invalid invitation signature: Verification equation was not satisfied
 * verbatim                                          → 200, joined
 * ```
 *
 * The fixture is a REAL captured body (`fixtures/invitation-rc32.json`). A
 * hand-written one would only prove the model agrees with itself, which is
 * exactly what let this ship.
 */
class InvitationRoundTripTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/invitation-rc32.json")) {
            "fixtures/invitation-rc32.json missing"
        }.bufferedReader().readText()

    @Test
    fun `a real rc32 invitation re-encodes with every key it arrived with`() {
        val original = json.parseToJsonElement(fixture()).jsonObject
        val decoded = json.decodeFromString<SignedGroupOpenInvitation>(fixture())
        val reencoded = json.encodeToJsonElement(SignedGroupOpenInvitation.serializer(), decoded).jsonObject

        assertEquals("envelope must round-trip unchanged", original, reencoded)
        assertEquals(
            "signed body must round-trip unchanged",
            original["invitation"]!!.jsonObject,
            reencoded["invitation"]!!.jsonObject,
        )
    }

    /**
     * The specific field that makes rc.29+ refuse the join. It is inside the
     * SIGNED body, and core#3714 made it non-empty on every invitation — before
     * that it was usually absent, so dropping it was invisible.
     */
    @Test
    fun `the signed admitters list survives and is readable`() {
        val invitation = json.decodeFromString<SignedGroupOpenInvitation>(fixture())
        val admitters = invitation.invitation.admitters

        assertTrue("rc.29+ mints a non-empty admitters list", admitters.isNotEmpty())
        assertTrue("admitters are 64-hex account ids", admitters.all { it.length == 64 })
        assertTrue(
            "admitters must still be on the wire after a re-encode",
            json.encodeToString(SignedGroupOpenInvitation.serializer(), invitation).contains("\"admitters\""),
        )
    }

    /** The unsigned hints a joiner needs: without them it records zeros and diverges. */
    @Test
    fun `the unsigned bootstrap hints survive too`() {
        val invitation = json.decodeFromString<SignedGroupOpenInvitation>(fixture())

        assertEquals(64, invitation.inviterAccount?.length)
        assertEquals(32, invitation.applicationId?.size)
        assertEquals(32, invitation.appKey?.size)
        assertTrue("rc.32 renamed admitter_hints to admitter_addrs", invitation.admitterAddrs.isNotEmpty())
        assertTrue("each addr carries its /p2p/ peer id", invitation.admitterAddrs.all { it.contains("/p2p/") })
    }

    /**
     * Adding a key is as fatal as dropping one: core mirrors `skip_serializing_if`,
     * so an invitation that arrived WITHOUT `admitters` must not come back with
     * `"admitters":[]`. This is why the model is not a data class with defaults.
     */
    @Test
    fun `an absent optional key is not resurrected as an empty default`() {
        val stripped =
            """
            {"invitation":{"inviter_identity":[1],"group_id":[2],"expiration_timestamp":9,"secret_salt":[3]},
             "inviter_signature":"ab"}
            """.trimIndent()
        val out =
            json.encodeToString(
                SignedGroupOpenInvitation.serializer(),
                json.decodeFromString<SignedGroupOpenInvitation>(stripped),
            )

        assertTrue("must not invent admitters", !out.contains("admitters"))
        assertTrue("must not invent invited_role", !out.contains("invited_role"))
        assertTrue("must not invent app_key", !out.contains("app_key"))
        assertEquals(json.parseToJsonElement(stripped), json.parseToJsonElement(out))
    }

    /** A u64 expiry must not round-trip through a Double and lose its low bits. */
    @Test
    fun `a large expiration timestamp keeps every digit`() {
        val big = "18446744073709551615" // u64::MAX
        val body = """{"invitation":{"expiration_timestamp":$big},"inviter_signature":""}"""
        val out =
            json.encodeToString(
                SignedGroupOpenInvitation.serializer(),
                json.decodeFromString<SignedGroupOpenInvitation>(body),
            )

        assertTrue("the literal must survive verbatim", out.contains(big))
    }

    /** The shape `joinNamespace` actually puts on the wire. */
    @Test
    fun `the join request carries the invitation verbatim`() {
        val original = json.parseToJsonElement(fixture()).jsonObject
        val request = JoinNamespaceRequest(invitation = json.decodeFromString(fixture()))
        val body = json.encodeToJsonElement(JoinNamespaceRequest.serializer(), request).jsonObject

        assertEquals(original, body["invitation"] as JsonObject)
    }
}
