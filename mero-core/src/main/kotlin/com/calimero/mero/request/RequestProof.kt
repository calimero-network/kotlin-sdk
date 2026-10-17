package com.calimero.mero.request

import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.checkedSignature
import com.calimero.mero.crypto.concat
import com.calimero.mero.crypto.signerFromSecret
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/*
 * A device cert as something every transport can authenticate a request with — port of
 * mero-js `src/request-proof/{request-proof,authorizer}.ts`.
 *
 * The device key signs the request itself and the certificate its account signed says whose
 * key that is: core verifies both with no store read (`crates/server/src/proof_auth.rs`),
 * which is what lets a hosted relay serve a caller it has never seen. Core consults the
 * proof only when the request carries **no** token (no `Authorization`, no `?token=`), and
 * a node serves proofs from other accounts only with `--delegated-access` (else `403`); a
 * proof that does not verify is a `401` with `x-auth-error: invalid_proof`.
 */

/** The header a signed request carries. Core reads it case-insensitively (`x-calimero-proof`). */
const val REQUEST_PROOF_HEADER = "X-Calimero-Proof"

/** [signRequestProof]'s default validity, seconds. */
private const val DEFAULT_VALID_FOR = 300L

/** One request to sign with a device cert — mero-js `RequestProofInput`. */
@Suppress("LongParameterList")
class RequestProofInput(
    /** The HTTP method, exactly as it will be sent. Not folded. */
    val method: String,
    /** The path and query exactly as on the request line ([signedPath]). */
    val path: String,
    /** The `AccountProof<DeviceCert>` from `DeviceCert.sign`, hex. */
    val credential: String,
    /** The body exactly as it will be sent; `null` commits to the hash of no bytes. */
    val body: ByteArray? = null,
    /** The certified device's ed25519 secret, hex. Or pass [signer]. */
    val deviceSecret: String? = null,
    /** A signer, for a key that is not held as hex. Used when both are given, as in mero-js. */
    val signer: DeviceSigner? = null,
    /** Seconds the signature stays valid (default 300). */
    val validForSeconds: Long = DEFAULT_VALID_FOR,
    /** Override the clock, for tests. Unix seconds. */
    val now: Long? = null,
)

/**
 * Sign one request with the device key and return the `X-Calimero-Proof` value, hex —
 * mero-js `signRequestProof`. The two-link chain:
 *
 * ```
 * CallerProof = <account_proof bytes> ‖ 0x00 (no session) ‖ RequestSig
 * ```
 *
 * The credential *is* the account-proof prefix, so it is concatenated rather than
 * re-encoded.
 *
 * @throws IllegalArgumentException without a method, a path that does not start with `/`,
 *   malformed hex, or no key.
 */
fun signRequestProof(input: RequestProofInput): String {
    require(input.method.isNotEmpty()) { "signRequestProof needs the method the request will use" }
    require(input.path.startsWith("/")) { "signRequestProof needs a path, got \"${input.path}\"" }
    val bodyHash = requestBodyHash(input.body ?: ByteArray(0))
    val issuedAt = input.now ?: (System.currentTimeMillis() / 1000)
    val expiresAt = issuedAt + input.validForSeconds
    val signer =
        input.signer
            ?: input.deviceSecret?.let { signerFromSecret(it, "deviceSecret") }
            ?: throw IllegalArgumentException("signRequestProof needs either `deviceSecret` or `signer`")
    val signature = checkedSignature(signer.sign(requestSigPayload(input.method, input.path, bodyHash, issuedAt, expiresAt)))
    val accountProof = Hex.decodeUnsized(input.credential, "credential")
    return Hex.encode(
        concat(accountProof, byteArrayOf(0), requestSigBytes(input.method, input.path, bodyHash, issuedAt, expiresAt, signature)),
    )
}

/** What a keyholder holds: a certificate, and the key it certifies — mero-js `ProofCredential`. */
class ProofCredential(
    /** The `AccountProof<DeviceCert>` from `DeviceCert.sign`, hex. */
    val credential: String,
    /** The certified device's ed25519 secret, hex. Or pass [signer]. */
    val deviceSecret: String? = null,
    /** A signer, for a key that is not held as hex. */
    val signer: DeviceSigner? = null,
    /** Seconds each signature stays valid; `null` for [signRequestProof]'s 300. */
    val validForSeconds: Long? = null,
) {
    override fun toString(): String = "ProofCredential(signer=${signer?.publicKey})"
}

/**
 * A per-request authorizer: the headers to merge into one request — mero-js
 * `RequestAuthorizer`. `SseClient`, `SseConnection` and `WsClient` take one (`authorize`),
 * so a keyholder is authenticated the same way on every transport.
 */
typealias RequestAuthorizer = suspend (RequestToAuthorize) -> Map<String, String>

private fun ProofCredential.validated(): DeviceSigner {
    require(credential.isNotEmpty()) { "a proof authorizer needs the `credential` from signDeviceCert" }
    return signer
        ?: deviceSecret?.takeIf { it.isNotEmpty() }?.let { signerFromSecret(it, "deviceSecret") }
        ?: throw IllegalArgumentException("a proof authorizer needs either `deviceSecret` or `signer`")
}

private fun ProofCredential.sign(
    signer: DeviceSigner,
    request: ProofRequest,
): String =
    signRequestProof(
        RequestProofInput(
            method = request.method,
            path = request.path,
            credential = credential,
            body = request.body,
            signer = signer,
            validForSeconds = validForSeconds ?: DEFAULT_VALID_FOR,
        ),
    )

/**
 * Turn a device cert into a [RequestAuthorizer] — mero-js `createProofAuthorizer`. The
 * credential is validated here, once, rather than surfacing as a `401` on whichever request
 * happened to run first.
 */
fun createProofAuthorizer(credential: ProofCredential): RequestAuthorizer {
    val signer = credential.validated()
    return { request -> mapOf(REQUEST_PROOF_HEADER to credential.sign(signer, request)) }
}

/**
 * A [ProofSigner] (for [com.calimero.mero.MeroConfig.requestProof]) over a device cert —
 * the two-link chain, the device key signing each request itself.
 */
fun createProofSigner(credential: ProofCredential): ProofSigner {
    val signer = credential.validated()
    return { request -> credential.sign(signer, request) }
}

/** A [RequestAuthorizer] that sends what [signer] produces, or nothing when it returns `null`. */
fun proofAuthorizerOf(signer: ProofSigner): RequestAuthorizer = { request -> signer(request)?.let { mapOf(REQUEST_PROOF_HEADER to it) } ?: emptyMap() }

/**
 * The path and query a proof signs, read from [url] exactly as OkHttp writes them on the
 * request line (`encodedPath`, then `?encodedQuery` when there is a query — an empty one
 * included). A relative URL resolves against `http://localhost`, as mero-js resolves one
 * against the page.
 */
fun signedPath(url: String): String = signedPath(if (url.startsWith("/")) "http://localhost$url".toHttpUrl() else url.toHttpUrl())

/** [signedPath] for a parsed URL. */
fun signedPath(url: HttpUrl): String = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")

/** Whether [headers] carry a request proof — which must never follow a redirect. */
fun carriesProof(headers: Map<String, String>): Boolean = headers.keys.any { it.equals(REQUEST_PROOF_HEADER, ignoreCase = true) }
