package com.calimero.mero.account

import com.calimero.mero.admin.GroupInvitationFromAdmin
import com.calimero.mero.admin.GroupMember
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-signed invitations against vectors core's own code produced
 * (`borsh::to_vec(&GroupInvitationFromAdmin)`, `Sha256`, `PrivateKey::sign`, `serde_json`),
 * copied from mero-js 24.5.0 `src/invitation/invitation.test.ts`. Ed25519 is deterministic,
 * so the signature is pinned too. Also the application-id derivation (`application-id.test.ts`).
 */
class InvitationsTest {
    private val signer = SeedSigner("6d".repeat(32))
    private val group = "42".repeat(32)
    private val now = 1_790_000_000L
    private val admitters = listOf("0a".repeat(32), "0b".repeat(32))
    private val inviterAccount = "cc".repeat(32)

    private val coreJson =
        """{"inviter_identity":[139,35,125,120,142,142,170,239,85,12,109,18,88,35,250,69,241,253,95,194,155,44,136,189,248,113,17,148,113,252,19,18],"group_id":[66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66,66],"expiration_timestamp":1790086400,"secret_salt":[7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7],"invited_role":1,"admitters":["0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a","0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"]}"""
    private val coreBorsh =
        "8b237d788e8eaaef550c6d125823fa45f1fd5fc29b2c88bdf871119471fc13124242424242424242424242424242424242424242424242424242424242424242008db26a00000000070707070707070707070707070707070707070707070707070707070707070701020000000a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"
    private val coreHash = "0739d4d206f6dc3359ea7510766139b908a5b58e4d53552c62da72cea5c4af72"
    private val coreSig =
        "a9945492839aa130892dc8056fd1a4c882792a0c1426a68f6bd90fac7218ebb2086b505e2f9cbdeb4b7dd1c60199cd8d589a1c14a0c8721bbb1331a20f0a7309"

    private fun sign(
        admitters: List<String> = this.admitters,
        members: List<GroupMember>? = null,
        validForSecs: Long = Invitations.MAX_INVITATION_VALIDITY_SECS,
        applicationId: String? = null,
        appKey: String? = null,
        admitterAddrs: List<String> = emptyList(),
    ) = Invitations.signGroupInvitation(
        groupId = group,
        inviterAccount = inviterAccount,
        signer = signer,
        admitters = admitters,
        members = members,
        validForSecs = validForSecs,
        now = now,
        nonce = ByteArray(32) { 7 },
        applicationId = applicationId,
        appKey = appKey,
        admitterAddrs = admitterAddrs,
    )

    @Test
    fun `encodes and hashes the body core does`() {
        val body = GroupInvitationFromAdmin(Json.parseToJsonElement(coreJson).jsonObject)
        assertEquals(coreBorsh, Hex.encode(NamespaceOp.encodeGroupInvitation(body)))
        assertEquals(coreHash, Hex.encode(Invitations.invitationHash(body)))
    }

    @Test
    fun `signs what core signs, in the JSON shape core emits`() {
        val signed = sign()
        assertEquals(Json.parseToJsonElement(coreJson), signed.raw["invitation"])
        assertEquals(coreSig, signed.inviterSignature)
        assertEquals(inviterAccount, signed.inviterAccount)
        assertEquals(setOf("invitation", "inviter_signature", "inviter_account"), signed.raw.keys)
        // It embeds in a join op through the same encoder.
        assertTrue(Hex.encode(NamespaceOp.encodeSignedInvitation(signed)).startsWith(coreBorsh))
    }

    @Test
    fun `defaults the admitters to the admins, sorted as core sorts them`() {
        val members =
            listOf(
                GroupMember("0B".repeat(32), "Admin"),
                GroupMember("0f".repeat(32), "Member"),
                GroupMember("0a".repeat(32), "Admin"),
                GroupMember("0b".repeat(32), "Admin"),
            )
        assertEquals(admitters, Invitations.defaultAdmitters(members))
        assertEquals(coreSig, sign(admitters = emptyList(), members = members).inviterSignature)
    }

    @Test
    fun `never mints an invitation claimable by broadcast`() {
        val e = assertThrows(IllegalArgumentException::class.java) { sign(admitters = emptyList()) }
        assertTrue(e.message!!.contains("broadcast"))
        val f = assertThrows(IllegalArgumentException::class.java) { sign(admitters = emptyList(), members = listOf(GroupMember("0f".repeat(32), "Member"))) }
        assertTrue(f.message!!.contains("no admin"))
    }

    @Test
    fun `clamps validity to what a node would issue`() {
        assertEquals(now + Invitations.MAX_INVITATION_VALIDITY_SECS, sign(validForSecs = 10 * Invitations.MAX_INVITATION_VALIDITY_SECS).invitation.expirationTimestamp)
        assertEquals(now + 60, sign(validForSecs = 60).invitation.expirationTimestamp)
    }

    @Test
    fun `carries the unsigned bootstrap fields outside the signature`() {
        val signed = sign(applicationId = "33".repeat(32), appKey = "44".repeat(32), admitterAddrs = listOf("https://relay.example"))
        assertEquals(coreSig, signed.inviterSignature)
        assertEquals(List(32) { 0x33 }, signed.applicationId)
        assertEquals(List(32) { 0x44 }, signed.appKey)
        assertEquals(listOf("https://relay.example"), signed.admitterAddrs)
        assertEquals(JsonPrimitive(51), signed.raw["application_id"]!!.jsonArray[0])
        assertTrue(signed.raw["invitation"] is JsonObject)
    }

    @Test
    fun `derives application ids as merod does`() {
        // The id merod computed installing kv-store 0.0.41 and 0.0.54 on prod.
        assertEquals(
            "e810e86f443e8c1feb98bb83a266246478a34c75397a66a78bd5a790c6d72d0d",
            ApplicationIds.applicationIdForBundle("com.calimero.kv-store", "did:key:z6MkoWkrrFjwC4FXQfyGwwcgTPvRoJZenMEVm9Z332bdkz6B"),
        )
        // UTF-8 byte lengths, not character counts.
        assertEquals(
            "cf9b180c7952ef0aa41fe73e9cb7ac2b4b19917099341e295534aa4a50861bd5",
            ApplicationIds.applicationIdForBundle("com.calimero.café", "did:key:z6MkExample"),
        )
        val picked =
            ApplicationIds.selectLatestBundle(
                listOf(RegistryBundle(appVersion = "0.0.54", yanked = true), RegistryBundle(appVersion = "0.0.41"), RegistryBundle(appVersion = "0.0.9")),
            )
        assertEquals("0.0.41", picked?.appVersion)
    }
}
