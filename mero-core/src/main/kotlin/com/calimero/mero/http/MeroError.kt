package com.calimero.mero.http

/** Base type for every error surfaced by the SDK. Abstract (not sealed) so subtypes can live in
 * sibling packages such as `com.calimero.mero.rpc`. */
abstract class MeroException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A transport-level failure with no HTTP response (DNS, connection reset, timeout, …). */
class NetworkException(message: String, cause: Throwable? = null) : MeroException(message, cause)

/** An invalid-state error (e.g. refresh requested with no refresh token, no credentials to log in). */
class MeroStateException(message: String) : MeroException(message)

/**
 * A non-2xx HTTP response.
 *
 * Port of `HTTPError` from mero-js `web-client.ts`.
 */
open class HttpException(
    val status: Int,
    val bodyText: String,
    val headers: Map<String, String>,
    val url: String,
) : MeroException("HTTP $status ($url)") {
    /** True for authentication statuses (401/403). */
    val isAuthError: Boolean get() = status == 401 || status == 403
}

/**
 * Terminal authentication error: the refresh-token family was revoked (a single-use refresh token
 * was replayed — core#3083 — or the token was explicitly revoked). Never refreshed and never
 * retried; the only recovery is a fresh login. Apps should catch this and force re-authentication.
 *
 * Extends [HttpException] so existing `catch (e: HttpException)` handling keeps working.
 *
 * Port of `AuthRevokedError` from mero-js.
 */
class AuthRevokedException(
    /** Value of the `x-auth-error` header, e.g. `token_reuse` or `token_revoked`. */
    val reason: String,
    status: Int,
    bodyText: String,
    headers: Map<String, String>,
    url: String,
) : HttpException(status, bodyText, headers, url)

/** `x-auth-error` reasons that mean the whole token family is gone. */
internal val TERMINAL_AUTH_ERRORS = setOf("token_reuse", "token_revoked")

/** `x-auth-error` reason that means "refresh me" — the reactive-refresh trigger. */
internal const val AUTH_ERROR_TOKEN_EXPIRED = "token_expired"
