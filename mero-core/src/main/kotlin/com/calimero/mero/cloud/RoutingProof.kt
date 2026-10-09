package com.calimero.mero.cloud

import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.crypto.base64
import com.calimero.mero.crypto.checkedSignature
import com.calimero.mero.crypto.concat

/**
 * Proving an account to the cloud manager for a routing read, with the device key.
 *
 * Port of mero-js `src/cloud/routing-proof.ts`. The cloud mints a sealed nonce; the
 * device signs `"calimero.mdma.routing-read.v1\0" ‖ utf8(nonce)` — a flat prefix, not a
 * `domainHash`, because MDMA's verifier checks the raw message — and the read carries
 * the certificate, the nonce and the base64 signature as headers.
 */
object RoutingProof {
    private val DOMAIN = "calimero.mdma.routing-read.v1\u0000".encodeToByteArray()

    const val HEADER_CREDENTIAL = "X-Calimero-Credential"
    const val HEADER_NONCE = "X-Calimero-Nonce"
    const val HEADER_SIGNATURE = "X-Calimero-Signature"

    /** base64 Ed25519 signature over the domain and the nonce. */
    fun signRoutingChallenge(
        nonce: String,
        signer: DeviceSigner,
    ): String = base64(checkedSignature(signer.sign(concat(DOMAIN, nonce.encodeToByteArray()))))

    /** The three headers one proven read carries. */
    fun headers(
        nonce: String,
        credential: RoutingCredential,
    ): Map<String, String> =
        mapOf(
            HEADER_CREDENTIAL to credential.credential,
            HEADER_NONCE to nonce,
            HEADER_SIGNATURE to signRoutingChallenge(nonce, credential.signer),
        )
}

/** A device certificate and the key to sign challenges with. The credential is public; the key never leaves. */
class RoutingCredential(
    /** The `AccountProof<DeviceCert>`, hex. */
    val credential: String,
    val signer: DeviceSigner,
) {
    constructor(credential: String, deviceSecret: String) : this(credential, SeedSigner(deviceSecret))
}
