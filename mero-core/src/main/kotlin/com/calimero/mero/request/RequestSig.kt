package com.calimero.mero.request

import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.checkedSignature
import com.calimero.mero.crypto.concat
import com.calimero.mero.crypto.domainHash
import com.calimero.mero.crypto.resolveSigner
import com.calimero.mero.crypto.u32le
import com.calimero.mero.crypto.u64le

/*
 * Signing one HTTP request, so a caller's identity travels with it — port of mero-js
 * `src/request/{request-sig,caller-proof,proof-signer}.ts`.
 *
 * ```text
 * account root ──signs──▶ DeviceCert       cold, once, offline
 * device key   ──signs──▶ LoginStatement   once per session, hours   (optional link)
 * session key  ──signs──▶ RequestSig       per call   ◀── signRequest
 * ```
 *
 * **What it binds.** Method, path *and query* exactly as on the request line (no
 * re-encoding, no reordering), and a hash of the body. The method is not normalised: `HEAD`
 * and `GET` are the same permission but different requests. Replay inside the window
 * performs the identical request and is bounded rather than prevented — keep `expiresAt`
 * short.
 *
 * The byte contract is pinned in core at `crates/account/src/tests/request_wire_fixture.rs`
 * and verified by `crates/server/src/proof_auth.rs` (`CallerProof::verify` over the
 * request target as it reached the listener); `RequestProofTest` asserts the same vectors.
 */

/** core's request signing domain (`calimero.auth.request.v2`). */
internal const val SIGN_DOMAIN = "calimero.auth.request.v2"

/** Its own domain, distinct from the signing one: the commitment is not a signed preimage. */
private const val BODY_DOMAIN = "calimero.auth.request.body.v1"

private fun borshString(value: String): ByteArray {
    val bytes = value.encodeToByteArray()
    return concat(u32le(bytes.size), bytes)
}

/**
 * Commit to a request body. Always call it, including for a body-less request: an empty
 * body hashes to a value like any other, and there is no "absent body" encoding.
 */
fun requestBodyHash(body: ByteArray = ByteArray(0)): ByteArray = domainHash(BODY_DOMAIN, listOf(body))

/** [requestBodyHash] over a string's UTF-8. */
fun requestBodyHash(body: String): ByteArray = requestBodyHash(body.encodeToByteArray())

/** core's `RequestSig` signing preimage: domain-hash of method, path, body hash and the window. */
internal fun requestSigPayload(
    method: String,
    path: String,
    bodyHash: ByteArray,
    issuedAt: Long,
    expiresAt: Long,
): ByteArray =
    domainHash(
        SIGN_DOMAIN,
        listOf(method.encodeToByteArray(), path.encodeToByteArray(), bodyHash, u64le(issuedAt), u64le(expiresAt)),
    )

/** core's `RequestSig` borsh: method, path, body hash, issued/expires (u64 LE), signature. */
internal fun requestSigBytes(
    method: String,
    path: String,
    bodyHash: ByteArray,
    issuedAt: Long,
    expiresAt: Long,
    signature: ByteArray,
): ByteArray = concat(borshString(method), borshString(path), bodyHash, u64le(issuedAt), u64le(expiresAt), signature)

/** What a caller is about to send — mero-js `RequestSigInput`. */
class RequestSigInput(
    /** The HTTP method exactly as it will be sent (`GET`, `POST`, `HEAD`). Not folded. */
    val method: String,
    /** The path and query exactly as on the request line. Use [signedPath] to take it from a URL. */
    val path: String,
    /** Unix seconds at signing. */
    val issuedAt: Long,
    /** Unix seconds after which the node must refuse it. Keep it short: it bounds replay. */
    val expiresAt: Long,
    /** The bytes that will be sent (empty for none). Re-serialising after signing breaks the signature. */
    val body: ByteArray = ByteArray(0),
    /** The signing secret, 64 hex: the session key on the three-link chain, the device key on the two-link one. */
    val signerSecret: String? = null,
    /** The same key as a signer, instead of [signerSecret]. */
    val signer: DeviceSigner? = null,
)

/**
 * Sign one request and return core's `RequestSig`, hex — mero-js `signRequest`. The
 * encoding, not an object, for the reason `signWarrant` returns one: the signature covers
 * exactly these bytes.
 */
fun signRequest(input: RequestSigInput): String {
    val signer = resolveSigner(input.signerSecret, input.signer, "signerSecret")
    val bodyHash = requestBodyHash(input.body)
    val payload = requestSigPayload(input.method, input.path, bodyHash, input.issuedAt, input.expiresAt)
    val signature = checkedSignature(signer.sign(payload))
    return Hex.encode(requestSigBytes(input.method, input.path, bodyHash, input.issuedAt, input.expiresAt, signature))
}

/** The three links of a `CallerProof`, each hex as the function that signed it returned it. */
data class CallerProofInput(
    /** `DeviceCert.sign(...)` — the root's certificate over the device key. */
    val credential: String,
    /** The request signature, [signRequest]. */
    val request: String,
    /**
     * A `LoginStatement` (hex), when a session key signs the request. `null` on the
     * two-link chain — which is **not** the same as an empty string: borsh writes `Option`
     * as a tag byte.
     */
    val session: String? = null,
)

/**
 * Concatenate the links into the `X-Calimero-Proof` value — mero-js `callerProof`. Core's
 * `CallerProof` is a plain struct of the three, and borsh writes a struct as its fields back
 * to back: `credential ‖ Option<session> ‖ request`.
 */
fun callerProof(input: CallerProofInput): String {
    val credential = Hex.decodeUnsized(input.credential, "credential")
    val request = Hex.decodeUnsized(input.request, "request signature")
    val session =
        input.session?.let { concat(byteArrayOf(1), Hex.decodeUnsized(it, "session statement")) } ?: byteArrayOf(0)
    return Hex.encode(concat(credential, session, request))
}

/**
 * What the transport knows about the call it is about to make — mero-js `ProofRequest` /
 * `RequestToAuthorize`.
 */
class ProofRequest(
    /** `GET`, `POST`, … exactly as it will be sent. */
    val method: String,
    /** The path and query the node will see ([signedPath]). */
    val path: String,
    /** The exact bytes that will be sent, or `null` for none. */
    val body: ByteArray? = null,
) {
    override fun toString(): String = "ProofRequest($method $path, ${body?.size ?: 0} body bytes)"
}

/** mero-js `RequestToAuthorize`: the same three facts. */
typealias RequestToAuthorize = ProofRequest

/**
 * Produces the `X-Calimero-Proof` value for one request, or `null` to send none — mero-js's
 * `getProof` / `MeroJsConfig.requestProof` callback. Pass one as
 * [com.calimero.mero.MeroConfig.requestProof]. Build one with [createProofSigner], or write
 * your own when the key must not reach the SDK at all (hardware, a remote signer).
 */
typealias ProofSigner = suspend (ProofRequest) -> String?

/** The keys a [createProofSigner] mints each request's proof with — mero-js `ProofSignerOptions`. */
class ProofSignerOptions(
    /** `DeviceCert.sign(...)`, hex. */
    val credential: String,
    /** The ed25519 secret that signs each request, hex: the session key when [session] is set, else the device key. */
    val signerSecret: String,
    /** A `LoginStatement`, hex — the three-link chain. `null` for the two-link chain. */
    val session: String? = null,
    /** How long each signature stays valid, seconds (default 120). It bounds replay. */
    val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    /** Unix seconds. Injectable so a test does not depend on the wall clock. */
    val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    override fun toString(): String = "ProofSignerOptions(session=${session != null}, ttlSeconds=$ttlSeconds)"

    companion object {
        /** mero-js `createProofSigner`'s default validity. */
        const val DEFAULT_TTL_SECONDS = 120L
    }
}

/**
 * A [ProofSigner] over a credential and the key that signs requests — mero-js
 * `createProofSigner`. Each call signs that request's method, path and body at the moment
 * of the call.
 */
fun createProofSigner(options: ProofSignerOptions): ProofSigner {
    val signer = resolveSigner(options.signerSecret, null, "signerSecret")
    return { request ->
        val issuedAt = options.now()
        callerProof(
            CallerProofInput(
                credential = options.credential,
                session = options.session,
                request =
                    signRequest(
                        RequestSigInput(
                            method = request.method,
                            path = request.path,
                            body = request.body ?: ByteArray(0),
                            issuedAt = issuedAt,
                            expiresAt = issuedAt + options.ttlSeconds,
                            signer = signer,
                        ),
                    ),
            ),
        )
    }
}
