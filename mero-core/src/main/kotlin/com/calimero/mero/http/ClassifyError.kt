package com.calimero.mero.http

import com.calimero.mero.account.InvitationNotClaimableException
import com.calimero.mero.account.JoinException
import com.calimero.mero.account.NoRelayException
import com.calimero.mero.account.NotForAccountException
import com.calimero.mero.admin.WarrantNonceRouteUnavailableException
import com.calimero.mero.cloud.AccountHaRefusedException
import com.calimero.mero.cloud.CloudLinkDeclinedException
import com.calimero.mero.cloud.CloudNotReadyException
import com.calimero.mero.relay.IntentRefusedException
import com.calimero.mero.relay.NotAViewException
import com.calimero.mero.relay.RelayKeyTransportException
import com.calimero.mero.relay.WarrantNonceExhaustedException
import com.calimero.mero.rpc.ReadOnlyWriteRefusedException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

// ── What a failed request means, in terms a caller can act on ────────────────
//
// Core rc.56 onwards answers a refused admin request with the status that names the refusal:
// a malformed body is a 400, a caller the node will not serve a 403, a missing resource a 404,
// a state the request contradicts (an expired invitation, a removed member) a 409, an
// oversized upload a 413, and a request the node cannot serve *yet* (no member online to hand
// over a group key) a 503. Every caller asks the same two questions of such an error — what
// kind of failure is this, and would doing it again later help? — and [classifyError] answers
// both once. Port of mero-js `src/errors/classify.ts`.

/** The kind of failure, as far as it decides what the caller does next. mero-js `ErrorKind`. */
enum class ErrorKind(
    /** mero-js's spelling, e.g. `not-found`. */
    val wire: String,
    /**
     * Whether the same request could succeed later without anything changing on the caller's
     * side: false for a refusal the node chose, true for a failure about the moment.
     */
    val retryable: Boolean,
) {
    /** 400 (and any other 4xx without a kind of its own). The request is wrong; sending it again cannot help. */
    INVALID("invalid", false),

    /** 401. This client's session lapsed; signing in again can finish it. */
    UNAUTHORIZED("unauthorized", true),

    /** 403. The node will not do this for this caller. */
    FORBIDDEN("forbidden", false),

    /** 404. What the request names does not exist on this node (yet) — it may simply not have synced. */
    NOT_FOUND("not-found", true),

    /** 409. The request contradicts the current state: expired, spent, removed. */
    CONFLICT("conflict", false),

    /** 410. It existed and is gone for good. */
    GONE("gone", false),

    /** 413. The body is over the node's limit. */
    TOO_LARGE("too-large", false),

    /** 429. The node asked the caller to slow down. */
    RATE_LIMITED("rate-limited", true),

    /** 503 or 504. The node cannot serve this yet — no peer online, say. */
    UNAVAILABLE("unavailable", true),

    /** Any other 5xx. The node failed; the request may well have been fine. */
    SERVER("server", true),

    /** No response: offline, DNS, connection refused, a timeout. */
    UNREACHABLE("unreachable", true),

    /** Not an HTTP failure, or one with nothing more specific to go on. */
    UNKNOWN("unknown", true),
    ;

    companion object {
        /** The kind an HTTP status names. `0` (no response) is [UNREACHABLE]. */
        fun forStatus(status: Int): ErrorKind =
            when (status) {
                0 -> UNREACHABLE
                400 -> INVALID
                401 -> UNAUTHORIZED
                403 -> FORBIDDEN
                404 -> NOT_FOUND
                409 -> CONFLICT
                410 -> GONE
                413 -> TOO_LARGE
                429 -> RATE_LIMITED
                503, 504 -> UNAVAILABLE
                in 500..599 -> SERVER
                in 400..499 -> INVALID
                else -> UNKNOWN
            }
    }
}

/** What [classifyError] read out of an error. mero-js `ClassifiedError`. */
data class ClassifiedError(
    val kind: ErrorKind,
    /** The HTTP status, when the error carries one. `0` means no response arrived. */
    val status: Int?,
    /** See [ErrorKind.retryable]; a few SDK exceptions refine it (see [classifyError]). */
    val retryable: Boolean,
    /**
     * The node's own explanation, without an `HTTP <status>` prefix, falling back to the
     * error's message. For logs and for a UI that shows detail; a UI that wants friendly copy
     * should switch on [kind].
     */
    val message: String,
)

/**
 * What a failed request means: its kind, whether a later attempt could work, and the node's
 * own words for it. Accepts anything a `catch` hands over.
 *
 * How the SDK's exceptions map:
 * - [HttpException] (and [AuthRevokedException]): by [HttpException.status]; the message is
 *   the body's `error` (or a proxy's nested `error.message`, or the cloud's `detail`).
 * - [NetworkException]: [ErrorKind.UNREACHABLE], status `0` (mero-js's status-0 `HTTPError`).
 *   A bare [IOException] or a coroutine timeout is unreachable too, with no status.
 * - Exceptions that carry a status ([IntentRefusedException], [JoinException],
 *   [AccountHaRefusedException], [WarrantNonceRouteUnavailableException] = 404,
 *   [NotAViewException] = 409): by that status. An intent refused for its nonce stays
 *   retryable (re-sign with a fresh nonce); a Cloud HA refusal reports its advice.
 * - [ReadOnlyWriteRefusedException]: [ErrorKind.FORBIDDEN], not retryable — this node's role
 *   cannot write; [WarrantNonceExhaustedException]: [ErrorKind.CONFLICT].
 * - [CloudLinkDeclinedException] (the account link was declined): [ErrorKind.FORBIDDEN];
 *   [CloudNotReadyException] (no namespace or relay to connect to yet): [ErrorKind.UNAVAILABLE],
 *   retryable — Swift `linkDeclined` / `cloudNotReady`.
 * - [NotForAccountException] (a node-only call on an account's admin): [ErrorKind.FORBIDDEN];
 *   [NoRelayException] (an account with no relay yet) and [InvitationNotClaimableException]:
 *   [ErrorKind.UNAVAILABLE], retryable — Swift `notForAccount` / `noRelay` / `invitationNotClaimable`.
 * - [RelayKeyTransportException] (a step that only moves bytes failed while learning a relay's
 *   key): [ErrorKind.UNAVAILABLE], retryable — Swift `RelayKeyTransportException`.
 * - Anything else (an RPC method error, a state error): [ErrorKind.UNKNOWN], retryable.
 */
fun classifyError(error: Throwable?): ClassifiedError {
    val status = statusOf(error)
    val kind =
        when {
            error is ReadOnlyWriteRefusedException -> ErrorKind.FORBIDDEN
            error is CloudLinkDeclinedException -> ErrorKind.FORBIDDEN
            error is CloudNotReadyException -> ErrorKind.UNAVAILABLE
            error is NotForAccountException -> ErrorKind.FORBIDDEN
            error is NoRelayException -> ErrorKind.UNAVAILABLE
            error is InvitationNotClaimableException -> ErrorKind.UNAVAILABLE
            error is RelayKeyTransportException -> ErrorKind.UNAVAILABLE
            error is WarrantNonceExhaustedException -> ErrorKind.CONFLICT
            status != null -> ErrorKind.forStatus(status)
            error is IOException || error is TimeoutCancellationException -> ErrorKind.UNREACHABLE
            else -> ErrorKind.UNKNOWN
        }
    val retryable = kind.retryable || (error is IntentRefusedException && error.retryable)
    return ClassifiedError(kind, status, retryable, messageOf(error))
}

/** The HTTP status [error] carries, or null when there is none. `0` for a [NetworkException]. */
fun statusOf(error: Throwable?): Int? =
    when (error) {
        is AccountHaRefusedException -> error.status
        is HttpException -> error.status
        is NetworkException -> 0
        is IntentRefusedException -> error.status
        is JoinException -> error.status
        is WarrantNonceRouteUnavailableException -> HTTP_NOT_FOUND
        is NotAViewException -> HTTP_CONFLICT
        else -> null
    }

/**
 * The most specific human-readable explanation [error] carries, or [fallback]. Prefers the
 * node's words over a message that repeats the status line.
 */
fun messageOf(
    error: Throwable?,
    fallback: String = "Request failed",
): String {
    val candidates =
        when (error) {
            // Before HttpException, which it extends: its advice beats the cloud's bare code.
            is AccountHaRefusedException -> listOf(error.advice, error.message)
            is HttpException -> listOf(explanationOf(error.bodyText), error.message)
            is IntentRefusedException -> listOf(error.reason, error.message)
            else -> listOf(error?.message)
        }
    return candidates.firstOrNull { !it.isNullOrBlank() } ?: fallback
}

private val LENIENT = Json { isLenient = true }

// mero-js `extractErrorMessage`: `{"error": "…"}` (core), `{"error": {"message": "…"}}`
// (proxies in front of a node), `{"detail": "…"}` (the cloud's FastAPI routes).
private fun explanationOf(bodyText: String): String? {
    if (bodyText.isBlank()) return null
    val body = runCatching { LENIENT.parseToJsonElement(bodyText) as? JsonObject }.getOrNull() ?: return null
    val error = body["error"]
    return text(error) ?: text((error as? JsonObject)?.get("message")) ?: text(body["detail"])
}

/** A JSON string that is not blank, or null. */
private fun text(element: JsonElement?): String? {
    val primitive = element as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.contentOrNull?.takeIf { it.isNotBlank() } else null
}

private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
