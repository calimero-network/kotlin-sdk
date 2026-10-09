package com.calimero.mero.cloud

import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.base64
import com.calimero.mero.crypto.checkedSignature
import com.calimero.mero.crypto.concat
import com.calimero.mero.crypto.randomBytes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The ownership claim an account with no node presents to have the cloud host (HA) a
 * namespace it founded through a relay.
 *
 * Port of mero-js `src/cloud/account-ownership.ts` (`signAccountHaClaim`). A namespace id
 * is `domain_hash("calimero.namespace.id.v1", [founder, salt])`, so only the founding
 * account, with the salt its founding returned, reproduces it. The device key signs the
 * claim and the certificate says whose device that is.
 *
 * Signature input: `"calimero.mdma.account-ownership-claim.v1\0" ‖ payload`, where
 * `payload` is the UTF-8 JSON sent (base64) as `signed_payload`.
 */
object AccountOwnership {
    /** The audience of the anonymous account route (`/api/cloud/accounts/{a}/namespaces/{ns}/enable-ha`). */
    const val ACCOUNT_HA_AUDIENCE = "mdma:enable-ha-namespace-as-account"

    /** The default claim lifetime; the cloud caps it at five minutes. */
    const val DEFAULT_TTL_MS = 60_000L
    const val MAX_TTL_MS = 5 * 60_000L
    private const val MAX_RELAY_URL = 1024
    private val DOMAIN = "calimero.mdma.account-ownership-claim.v1\u0000".encodeToByteArray()

    /**
     * The founder's claim for the anonymous account route: no `subject` key at all (the
     * cloud tells the session and account claims apart by it). [relayUrl], when given, is
     * signed in so nobody forwarding the claim can swap the admitter the cloud hands its
     * fleet node.
     */
    @Suppress("LongParameterList")
    fun signAccountHaClaim(
        namespaceId: String,
        accountId: String,
        salt: String,
        credential: String,
        signer: DeviceSigner,
        relayUrl: String? = null,
        ttlMs: Long = DEFAULT_TTL_MS,
        now: Long = System.currentTimeMillis(),
    ): AccountOwnershipProof {
        require(relayUrl == null || relayUrl.length in 1..MAX_RELAY_URL) {
            "relayUrl must be a non-empty string of at most $MAX_RELAY_URL characters"
        }
        require(ttlMs in 1..MAX_TTL_MS) { "ttlMs must be in (0, $MAX_TTL_MS]" }
        val payload =
            buildJsonObject {
                put("v", 1)
                put("audience", ACCOUNT_HA_AUDIENCE)
                put("group_id", hex32(namespaceId, "namespaceId"))
                put("account_id", hex32(accountId, "accountId"))
                put("salt", hex32(salt, "salt"))
                put("nonce", Hex.encode(randomBytes(16)))
                put("issued_at_ms", now)
                put("expires_at_ms", now + ttlMs)
                if (relayUrl != null) put("relay_url", relayUrl)
            }.toString().encodeToByteArray()
        val signature = checkedSignature(signer.sign(concat(DOMAIN, payload)))
        return AccountOwnershipProof(credential = credential, signedPayload = base64(payload), signature = base64(signature))
    }

    private fun hex32(
        value: String,
        label: String,
    ): String = Hex.encode(Hex.decode(value, label, 32))
}

/** The `ownership_proof` body: `{kind: "account", credential, signed_payload, signature}`. */
data class AccountOwnershipProof(
    /** hex `AccountProof<DeviceCert>`. */
    val credential: String,
    /** base64 UTF-8 JSON, the claim. */
    val signedPayload: String,
    /** base64 Ed25519 by the certified device key. */
    val signature: String,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("kind", "account")
            put("credential", credential)
            put("signed_payload", signedPayload)
            put("signature", signature)
        }
}
