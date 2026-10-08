package com.calimero.mero.account

import com.calimero.mero.admin.GroupInvitationFromAdmin
import com.calimero.mero.admin.GroupMember
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.checkedSignature
import com.calimero.mero.crypto.randomBytes
import com.calimero.mero.crypto.sha256
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Minting a group (or namespace) invitation without a node.
 *
 * Port of mero-js `src/invitation/invitation.ts` (`signGroupInvitation`). An invitation is
 * not a governance op: nothing is published when one is made. It is a bearer credential,
 * `GroupInvitationFromAdmin` borsh-encoded, hashed with SHA-256 and signed by the inviter's
 * key. A node signs with its namespace key; an account with no node signs here with its
 * **device** key, which peers resolve to the account through the namespace's device
 * bindings (an account that founded or joined the namespace has one).
 *
 * Derived from core's `GroupInvitationFromAdmin` / `SignedGroupOpenInvitation`
 * (`crates/context/config/src/types.rs`) and `create_group_invitation.rs`:
 * `sign(sha256(borsh(invitation)))`. Pinned by core-produced vectors in `InvitationsTest`.
 */
object Invitations {
    /** core's `MAX_INVITATION_VALIDITY_SECS`: one day, also the default. */
    const val MAX_INVITATION_VALIDITY_SECS = 24L * 60 * 60

    /** `invited_role` values, as core numbers them. */
    const val ROLE_ADMIN = 0
    const val ROLE_MEMBER = 1
    const val ROLE_READ_ONLY = 2

    /** `sha256(borsh(invitation))`: the 32 bytes the inviter's key signs. */
    fun invitationHash(body: GroupInvitationFromAdmin): ByteArray = sha256(NamespaceOp.encodeGroupInvitation(body))

    /**
     * The admitters a node names when its caller names none: the group's admins, sorted by
     * account bytes and deduplicated, as core's `BTreeSet` leaves them.
     */
    fun defaultAdmitters(members: List<GroupMember>): List<String> =
        members
            .filter { it.role == "Admin" }
            .map { it.identity.lowercase() }
            .distinct()
            .sorted()

    /**
     * Sign an invitation to [groupId] with the device [signer], in the JSON shape a node's
     * `createGroupInvitation` returns, so it goes to `joinAsAccount` / `signMemberJoinOp`
     * unchanged.
     *
     * @param admitters accounts permitted to admit a claim (signed). Empty defaults to the
     *   admins in [members]; an invitation with no admitters (claimable by broadcast) is
     *   never produced.
     * @param validForSecs clamped to [MAX_INVITATION_VALIDITY_SECS].
     * @param applicationId unsigned bootstrap hint, 64 hex.
     * @param appKey unsigned bootstrap hint (core's `bytecode_id`), 64 hex.
     * @param admitterAddrs unsigned bootstrap hint: where the admitters are reachable.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun signGroupInvitation(
        groupId: String,
        inviterAccount: String,
        signer: DeviceSigner,
        admitters: List<String> = emptyList(),
        members: List<GroupMember>? = null,
        invitedRole: Int = ROLE_MEMBER,
        validForSecs: Long = MAX_INVITATION_VALIDITY_SECS,
        now: Long = System.currentTimeMillis() / 1000,
        nonce: ByteArray = randomBytes(32),
        applicationId: String? = null,
        appKey: String? = null,
        admitterAddrs: List<String> = emptyList(),
    ): SignedGroupOpenInvitation {
        val inviterKey = Hex.decode(signer.publicKey, "signer.publicKey", 32)
        val group = Hex.decode(groupId, "groupId", 32)
        val account = Hex.encode(Hex.decode(inviterAccount, "inviterAccount", 32))

        var named = admitters.map { it.trim().lowercase() }
        if (named.isEmpty()) {
            requireNotNull(members) {
                "name the admitters, or pass the group members to default them to its admins: " +
                    "an invitation with no admitters is claimable by broadcast"
            }
            named = defaultAdmitters(members)
            require(named.isNotEmpty()) { "the member list names no admin, so there is nobody to default the admitters to" }
        }
        named.forEachIndexed { i, a -> Hex.decode(a, "admitters[$i]", 32) }
        require(invitedRole in ROLE_ADMIN..ROLE_READ_ONLY) {
            "invitedRole must be 0 (Admin), 1 (Member) or 2 (ReadOnly), got $invitedRole"
        }
        val validFor = minOf(validForSecs, MAX_INVITATION_VALIDITY_SECS)
        require(validFor > 0) { "validForSecs must be positive, got $validForSecs" }
        require(nonce.size == 32) { "nonce must be 32 bytes, got ${nonce.size}" }

        val body =
            GroupInvitationFromAdmin(
                buildJsonObject {
                    put("inviter_identity", byteArray(inviterKey))
                    put("group_id", byteArray(group))
                    put("expiration_timestamp", now + validFor)
                    put("secret_salt", byteArray(nonce))
                    put("invited_role", invitedRole)
                    put("admitters", buildJsonArray { named.forEach { add(JsonPrimitive(it)) } })
                },
            )
        val signature = checkedSignature(signer.sign(invitationHash(body)))
        return SignedGroupOpenInvitation(
            buildJsonObject {
                put("invitation", body.raw)
                put("inviter_signature", Hex.encode(signature))
                put("inviter_account", account)
                if (admitterAddrs.isNotEmpty()) put("admitter_addrs", buildJsonArray { admitterAddrs.forEach { add(JsonPrimitive(it)) } })
                applicationId?.let { put("application_id", byteArray(Hex.decode(it, "applicationId", 32))) }
                appKey?.let { put("app_key", byteArray(Hex.decode(it, "appKey", 32))) }
            },
        )
    }

    /** A `[u8; 32]` as core's JSON carries it: 32 byte values. */
    private fun byteArray(bytes: ByteArray): JsonArray = buildJsonArray { bytes.forEach { add(JsonPrimitive(it.toInt() and 0xFF)) } }
}
