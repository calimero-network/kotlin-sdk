package com.calimero.mero.http

import com.calimero.mero.request.ProofRequest
import com.calimero.mero.request.ProofSigner
import com.calimero.mero.request.REQUEST_PROOF_HEADER
import com.calimero.mero.request.signedPath
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * Application interceptor that signs each request with a [ProofSigner] and attaches the
 * result as `X-Calimero-Proof` — the OkHttp counterpart of mero-js's `getProof` transport
 * hook (`MeroJsConfig.requestProof`).
 *
 * Signed here because only here are the method, the final path and query (as OkHttp will
 * write them on the request line — [signedPath]) and the exact body bytes all known. A
 * one-shot or duplex body cannot be read without consuming it, so it is refused rather than
 * signed as a guess.
 *
 * A proof is signed for one path, so the client this is installed on must not follow
 * redirects (`followRedirects(false)`, `followSslRedirects(false)` — [com.calimero.mero.Mero]
 * sets both when a proof is configured): a 3xx answering a proof-carrying request fails the
 * call with [ProofRedirectRefusedException], as mero-js `refuseProofRedirect` does.
 *
 * Failures travel as [IOException]s (OkHttp's contract for interceptors);
 * [OkHttpTransport] unwraps them to the [ProofRedirectRefusedException] or signing error
 * they carry.
 */
class RequestProofInterceptor(
    private val signer: ProofSigner,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body
        if (body != null && (body.isOneShot() || body.isDuplex())) {
            throw RequestProofFailure(
                IllegalArgumentException(
                    "a request-carried proof cannot commit to a one-shot or duplex body: send bytes or a string, or use a session",
                ),
            )
        }
        val bytes = body?.let { Buffer().also(it::writeTo).readByteArray() }
        val proof = sign(ProofRequest(request.method, signedPath(request.url), bytes)) ?: return chain.proceed(request)

        val response = chain.proceed(request.newBuilder().header(REQUEST_PROOF_HEADER, proof).build())
        if (response.code in REDIRECT_CODES) {
            val location = response.header("Location")
            response.close()
            throw RequestProofFailure(ProofRedirectRefusedException(request.url.toString(), response.code, location))
        }
        return response
    }

    private fun sign(request: ProofRequest): String? =
        try {
            runBlocking { signer(request) }
        } catch (e: Exception) {
            throw RequestProofFailure(e)
        }

    private companion object {
        val REDIRECT_CODES = 300..399
    }
}

/** Carries a proof failure out of OkHttp's interceptor chain; [OkHttpTransport] rethrows [cause]. */
internal class RequestProofFailure(
    override val cause: Exception,
) : IOException(cause.message, cause)
