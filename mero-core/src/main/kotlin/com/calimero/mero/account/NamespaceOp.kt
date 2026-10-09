package com.calimero.mero.account

import com.calimero.mero.admin.GroupInvitationFromAdmin
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.crypto.BorshWriter
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.checkedSignature
import com.calimero.mero.crypto.concat

/**
 * The signed namespace op a joiner's own device signs to claim an invitation.
 *
 * Port of mero-js `src/namespace-op/namespace-op.ts` (schema 24) and the invitation
 * borsh encoding of `src/invitation/invitation.ts`. The invitation is re-encoded from the
 * exact JSON core sent (see [SignedGroupOpenInvitation]), because it sits inside the
 * signed op: a byte out of place fails as "invalid invitation signature".
 */
object NamespaceOp {
    /** core's `SIGNED_NAMESPACE_OP_SCHEMA_VERSION` at rc.83. */
    const val SCHEMA_VERSION = 24
    private val SIGN_DOMAIN = "calimero.namespace.v1".encodeToByteArray()

    private const val NAMESPACE_OP_ROOT = 0
    private const val ROOT_OP_MEMBER_JOINED = 5
    private const val ROOT_OP_MEMBER_JOINED_AT = 8

    /** Borsh `GroupInvitationFromAdmin`. */
    fun encodeGroupInvitation(body: GroupInvitationFromAdmin): ByteArray {
        val w =
            BorshWriter()
                .raw(bytes32(body.inviterIdentity, "inviter_identity"))
                .raw(bytes32(body.groupId, "group_id"))
                .u64(body.expirationTimestamp)
                .raw(bytes32(body.secretSalt, "secret_salt"))
                .u8(body.invitedRole ?: 0)
                .u32(body.admitters.size)
        body.admitters.forEachIndexed { i, a -> w.raw(Hex.decode(a, "admitters[$i]", 32)) }
        return w.toByteArray()
    }

    /** Borsh `SignedGroupOpenInvitation`: the body, then the unsigned bootstrap fields in core's order. */
    fun encodeSignedInvitation(signed: SignedGroupOpenInvitation): ByteArray {
        val w = BorshWriter().raw(encodeGroupInvitation(signed.invitation)).string(signed.inviterSignature)
        w.option(signed.inviterAccount) { raw(Hex.decode(it, "inviter_account", 32)) }
        w.u32(signed.admitterAddrs.size)
        signed.admitterAddrs.forEach { w.string(it) }
        w.option(signed.applicationId) { raw(bytes32(it, "application_id")) }
        w.option(signed.appKey) { raw(bytes32(it, "app_key")) }
        return w.toByteArray()
    }

    /**
     * Sign the op that admits [member] (an account) to [namespaceId] on [invitation],
     * carrying the device [credential]. Returns hex of `signable ‖ signature ‖ 0x00`.
     *
     * An expiring invitation uses `MemberJoinedAt` with [joinedAt] (Unix seconds, now by
     * default); a non-expiring one uses `MemberJoined`.
     */
    @Suppress("LongParameterList")
    fun signMemberJoinOp(
        namespaceId: String,
        member: String,
        invitation: SignedGroupOpenInvitation,
        credential: String,
        signer: DeviceSigner,
        nonce: ULong,
        parentOpHashes: List<String> = emptyList(),
        joinedAt: Long = System.currentTimeMillis() / 1000,
    ): String {
        val credentialBytes = Hex.decodeUnsized(credential, "credential")
        val expires = invitation.invitation.expirationTimestamp != 0L
        val rootOp =
            BorshWriter()
                .u8(if (expires) ROOT_OP_MEMBER_JOINED_AT else ROOT_OP_MEMBER_JOINED)
                .raw(Hex.decode(member, "member", 32))
                .raw(encodeSignedInvitation(invitation))
                .also { if (expires) it.u64(joinedAt) }
                .raw(credentialBytes)
                .toByteArray()
        val op = concat(byteArrayOf(NAMESPACE_OP_ROOT.toByte()), rootOp)
        val signable =
            BorshWriter()
                .u8(SCHEMA_VERSION)
                .raw(Hex.decode(namespaceId, "namespaceId", 32))
                .u32(parentOpHashes.size)
                .also { w -> parentOpHashes.forEachIndexed { i, p -> w.raw(Hex.decode(p, "parentOpHashes[$i]", 32)) } }
                .raw(Hex.decode(signer.publicKey, "signer.publicKey", 32))
                .u64(nonce)
                .raw(op)
                .toByteArray()
        val signature = checkedSignature(signer.sign(concat(SIGN_DOMAIN, signable)))
        // Trailing 0x00: no endorsement. The admitting node adds its own.
        return Hex.encode(concat(signable, signature, byteArrayOf(0)))
    }

    private fun bytes32(
        value: List<Int>,
        label: String,
    ): ByteArray {
        require(value.size == 32) { "$label must be 32 bytes, got ${value.size}" }
        return ByteArray(32) { value[it].toByte() }
    }
}
