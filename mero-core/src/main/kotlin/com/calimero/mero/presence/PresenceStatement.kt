package com.calimero.mero.presence

import com.calimero.mero.admin.PresenceIntentRequest
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.checkedSignature
import com.calimero.mero.crypto.concat
import com.calimero.mero.crypto.sha256
import com.calimero.mero.crypto.u32le
import com.calimero.mero.crypto.u64le

/**
 * The bytes a device signs for one presence update: the borsh of core's
 * `PresenceStatement` (`calimero_node_primitives::presence`), signed raw. Port of mero-js
 * `src/presence/statement.ts`, pinned to core's `statement_vector_is_stable` vector in
 * `PresenceStatementTest`, so the encoders cannot drift.
 */
object PresenceStatement {
    /** core's `PRESENCE_DOMAIN` (`calimero/presence/1`, 19 bytes, no length prefix): part of the signed bytes. */
    val PRESENCE_DOMAIN: ByteArray = "calimero/presence/1".encodeToByteArray()

    /** `sha256(borsh(Option<Vec<u8>>))`: `None` is `[0]`, `Some(v)` is `[1] ‖ u32le(len) ‖ v` — so a retract is signed too. */
    fun stateHash(state: ByteArray?): ByteArray =
        sha256(
            if (state == null) byteArrayOf(0) else concat(byteArrayOf(1), u32le(state.size), state),
        )

    /** What the device key signs: `domain ‖ context ‖ author ‖ u64le(seq) ‖ u64le(sentAtMs) ‖ stateHash`. */
    fun presenceStatementBytes(
        contextId: String,
        author: String,
        seq: Long,
        sentAtMs: Long,
        state: ByteArray?,
    ): ByteArray =
        concat(
            PRESENCE_DOMAIN,
            Hex.decode(contextId, "contextId", ID_BYTES),
            Hex.decode(author, "author", ID_BYTES),
            u64le(seq),
            u64le(sentAtMs),
            stateHash(state),
        )

    /** [signer]'s signature over the statement for this update, hex (64 bytes). */
    fun sign(
        signer: DeviceSigner,
        contextId: String,
        seq: Long,
        sentAtMs: Long,
        state: ByteArray?,
    ): String = Hex.encode(checkedSignature(signer.sign(presenceStatementBytes(contextId, signer.publicKey, seq, sentAtMs, state))))

    /**
     * A ready-to-send `presence-intents` body for `AdminApi.presenceIntent`: [state] as hex
     * (or `null` to retract), signed by [signer], carrying [authorProof] (the device's
     * `AccountProof<DeviceCert>`, hex).
     */
    @Suppress("LongParameterList")
    fun signedRequest(
        signer: DeviceSigner,
        authorProof: String,
        contextId: String,
        state: ByteArray?,
        seq: Long,
        sentAtMs: Long = System.currentTimeMillis(),
    ): PresenceIntentRequest =
        PresenceIntentRequest(
            state = state?.let { Hex.encode(it) },
            seq = seq,
            sentAtMs = sentAtMs,
            signature = sign(signer, contextId, seq, sentAtMs, state),
            authorProof = authorProof,
        )

    private const val ID_BYTES = 32
}
