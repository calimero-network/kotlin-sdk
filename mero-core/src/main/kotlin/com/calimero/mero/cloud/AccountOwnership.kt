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
 * Port of mero-js `src/cloud/account-ownership.ts` (`signAccountOwnershipClaim`,
 * `signAccountHaClaim`). A namespace id
 * is `domain_hash("calimero.namespace.id.v1", [founder, salt])`, so only the founding
 * account, with the salt its founding returned, reproduces it. The device key signs the
 * claim and the certificate says whose device that is.
 *
 * Signature input: `"calimero.mdma.account-ownership-claim.v1\0" ‖ payload`, where
 * `payload` is the UTF-8 JSON sent (base64) as `signed_payload`.
 */
object AccountOwnership {
    /** The audience of the session route's claim (`/api/cloud/me/namespaces/{ns}/enable-ha`). */
    const val ACCOUNT_OWNERSHIP_AUDIENCE = "mdma:enable-ha-namespace"

    /** The audience of the anonymous account route (`/api/cloud/accounts/{a}/namespaces/{ns}/enable-ha`). */
    const val ACCOUNT_HA_AUDIENCE = "mdma:enable-ha-namespace-as-account"

    /** The default claim lifetime; the cloud caps it at five minutes. */
    const val DEFAULT_TTL_MS = 60_000L
    const val MAX_TTL_MS = 5 * 60_000L
    private const val MAX_RELAY_URL = 1024
    private val DOMAIN = "calimero.mdma.account-ownership-claim.v1\u0000".encodeToByteArray()

    /**
     * The founder's claim for the **session** route ([CloudClient.enableFoundedNamespaceHa]),
     * bound to one cloud login: [subject] is that login's email. Port of mero-js
     * `signAccountOwnershipClaim`.
     *
     * Payload keys, in order: `v`, `audience` ([ACCOUNT_OWNERSHIP_AUDIENCE]), `group_id`,
     * `account_id`, `salt`, `subject`, `nonce`, `issued_at_ms`, `expires_at_ms`.
     *
     * @throws IllegalArgumentException for an empty [subject], a malformed id or salt, or a
     *   lifetime outside `(0, 5 min]`.
     */
    @Suppress("LongParameterList")
    fun signAccountOwnershipClaim(
        namespaceId: String,
        accountId: String,
        salt: String,
        subject: String,
        credential: String,
        signer: DeviceSigner,
        ttlMs: Long = DEFAULT_TTL_MS,
        now: Long = System.currentTimeMillis(),
    ): AccountOwnershipProof {
        require(subject.isNotEmpty()) { "subject must be the cloud login email" }
        return signClaim(namespaceId, accountId, salt, credential, signer, ACCOUNT_OWNERSHIP_AUDIENCE, subject, null, ttlMs, now)
    }

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
    ): AccountOwnershipProof = signClaim(namespaceId, accountId, salt, credential, signer, ACCOUNT_HA_AUDIENCE, null, relayUrl, ttlMs, now)

    // `subject` is spliced in after `salt` only when given, and `relay_url` goes last, so
    // each claim keeps mero-js's exact key order.
    @Suppress("LongParameterList")
    private fun signClaim(
        namespaceId: String,
        accountId: String,
        salt: String,
        credential: String,
        signer: DeviceSigner,
        audience: String,
        subject: String?,
        relayUrl: String?,
        ttlMs: Long,
        now: Long,
    ): AccountOwnershipProof {
        require(relayUrl == null || relayUrl.length in 1..MAX_RELAY_URL) {
            "relayUrl must be a non-empty string of at most $MAX_RELAY_URL characters"
        }
        require(ttlMs in 1..MAX_TTL_MS) { "ttlMs must be in (0, $MAX_TTL_MS]" }
        val payload =
            buildJsonObject {
                put("v", 1)
                put("audience", audience)
                put("group_id", hex32(namespaceId, "namespaceId"))
                put("account_id", hex32(accountId, "accountId"))
                put("salt", hex32(salt, "salt"))
                if (subject != null) put("subject", subject)
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

/** mero-js `ACCOUNT_OWNERSHIP_AUDIENCE`: the session route's claim audience. */
const val ACCOUNT_OWNERSHIP_AUDIENCE: String = AccountOwnership.ACCOUNT_OWNERSHIP_AUDIENCE

/** mero-js `ACCOUNT_HA_AUDIENCE`: the anonymous account route's claim audience. */
const val ACCOUNT_HA_AUDIENCE: String = AccountOwnership.ACCOUNT_HA_AUDIENCE

/** mero-js-named form of [AccountOwnership.signAccountOwnershipClaim]. */
@Suppress("LongParameterList")
fun signAccountOwnershipClaim(
    namespaceId: String,
    accountId: String,
    salt: String,
    subject: String,
    credential: String,
    signer: DeviceSigner,
    ttlMs: Long = AccountOwnership.DEFAULT_TTL_MS,
    now: Long = System.currentTimeMillis(),
): AccountOwnershipProof = AccountOwnership.signAccountOwnershipClaim(namespaceId, accountId, salt, subject, credential, signer, ttlMs, now)

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
