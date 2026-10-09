package com.calimero.mero.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Base type for every error surfaced by the SDK. Abstract (not sealed) so subtypes can live in
 * sibling packages such as `com.calimero.mero.rpc`. */
abstract class MeroException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A transport-level failure with no HTTP response (DNS, connection reset, timeout, …). */
class NetworkException(
    message: String,
    cause: Throwable? = null,
) : MeroException(message, cause)

/** An invalid-state error (e.g. refresh requested with no refresh token, no credentials to log in). */
class MeroStateException(
    message: String,
) : MeroException(message)

/**
 * A non-2xx HTTP response.
 *
 * Port of `HTTPError` from mero-js `web-client.ts`.
 *
 * ## Typed refusals (core 0.11.0-rc.83)
 *
 * Core stopped answering most refusals with a blanket `500`: a bad proof is a
 * `400`, "not a member" a `403`, an unknown device a `404`, a stale owner-op
 * counter a `409`, an undecidable authority a `503`. The body kept its shape —
 * `{"error": "<message>"}` — and the method-error routes (`/contexts/{id}/intents`
 * and `/query`) add the same `type` / `data` pair JSON-RPC carries
 * (`{"error": …, "type": "FunctionCallError", "data": "<method message>"}`).
 *
 * [errorMessage], [errorType] and [errorData] read those keys out of [bodyText]
 * when present, and are `null` otherwise — a body that is not JSON (a proxy's
 * HTML page, an empty 502) is not an error of its own. Branch on [status] for the
 * class of refusal and on [errorType] for the method-error kind.
 */
open class HttpException(
    val status: Int,
    val bodyText: String,
    val headers: Map<String, String>,
    val url: String,
) : MeroException("HTTP $status ($url)") {
    /** True for authentication statuses (401/403). */
    val isAuthError: Boolean get() = status == 401 || status == 403

    private val errorBody: JsonObject? by lazy {
        if (bodyText.isBlank()) return@lazy null
        runCatching { LENIENT.parseToJsonElement(bodyText) as? JsonObject }.getOrNull()
    }

    /** The node's `error` message, when the body is core's `{"error": …}` shape. */
    val errorMessage: String? get() = (errorBody?.get("error") as? JsonPrimitive)?.contentOrNull

    /**
     * The refusal's kind, when the body names one — `FunctionCallError` from a
     * method that refused its call on `/intents` or `/query` (core rc.83).
     */
    val errorType: String? get() = (errorBody?.get("type") as? JsonPrimitive)?.contentOrNull

    /** The detail beside [errorType]: for a `FunctionCallError`, the method's own message. */
    val errorData: JsonElement? get() = errorBody?.get("data")

    /**
     * `x-auth-error` as the node sent it, e.g. `token_expired`, `permission_denied`,
     * `invalid_proof` (rc.83 request-proof auth), or `null` when absent.
     */
    val authError: String?
        get() = headers.entries.firstOrNull { it.key.equals("x-auth-error", ignoreCase = true) }?.value

    private companion object {
        val LENIENT = Json { isLenient = true }
    }
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
