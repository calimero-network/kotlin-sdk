package com.calimero.mero.cloud

import com.calimero.mero.http.HttpException
import com.calimero.mero.http.MeroException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The five refusals of [CloudClient.enableHaAsAccount] a person can act on — mero-js's
 * `AccountNotLinkedError` … `RelayNotDialableError` `CODE`s, Swift `HaRefusal`. Each has the
 * cloud's [code], the [status] it refuses with and what to tell a person ([message]).
 */
enum class HaRefusal(
    val code: String,
    val status: Int,
) {
    /** The account was never linked to a cloud user. Link it; retrying does not help. */
    ACCOUNT_NOT_LINKED("account_not_linked", 409),

    /** Several cloud users linked this account. Unlink all but one. */
    ACCOUNT_LINKED_TO_SEVERAL_USERS("account_linked_to_several_users", 409),

    /** Another namespace of this account awaits its first fleet node; the claim was not spent. */
    HA_REQUEST_PENDING("ha_request_pending", 409),

    /** The cloud runs no relay at the claim's `relay_url`. */
    UNKNOWN_RELAY("unknown_relay", 422),

    /** The relay has not reported its addresses yet; retrying later can succeed. */
    RELAY_NOT_DIALABLE("relay_not_dialable", 422),
    ;

    /** What to tell a person, in words they can act on (mero-js `HA_REFUSAL_MESSAGES`). */
    val message: String get() = AccountHaRefusedException.ADVICE.getValue(code)

    companion object {
        /** The refusal [code] names when the cloud answers it with [status], or null. */
        fun of(
            code: String?,
            status: Int,
        ): HaRefusal? = entries.firstOrNull { it.code == code && it.status == status }
    }
}

/**
 * The cloud refused [CloudClient.enableHaAsAccount] for a reason a person can act on.
 * [code] is the cloud's `error`, verbatim; the namespace stays founded either way.
 *
 * Extends [HttpException] (as mero-js's `AccountHaRefusedError` extends `HTTPError`), so
 * `catch (e: HttpException)` still catches it. Thrown as one of its five subclasses —
 * [AccountNotLinkedException], [AccountLinkedToSeveralUsersException],
 * [HaRequestPendingException], [UnknownRelayException], [RelayNotDialableException] — each
 * readable as a [HaRefusal] through [refusal].
 */
open class AccountHaRefusedException(
    val code: String,
    status: Int,
    bodyText: String,
    headers: Map<String, String> = emptyMap(),
    url: String = "",
) : HttpException(status, bodyText, headers, url) {
    override val message: String get() = "the cloud refused to host the namespace (HTTP $status): $code"

    /** The typed refusal, or null for a code/status pair the SDK does not know. */
    val refusal: HaRefusal? get() = HaRefusal.of(code, status)

    /** What to tell a person, in words they can act on. */
    val advice: String get() = ADVICE[code] ?: code

    companion object {
        const val ACCOUNT_NOT_LINKED = "account_not_linked"
        const val ACCOUNT_LINKED_TO_SEVERAL_USERS = "account_linked_to_several_users"
        const val HA_REQUEST_PENDING = "ha_request_pending"
        const val UNKNOWN_RELAY = "unknown_relay"
        const val RELAY_NOT_DIALABLE = "relay_not_dialable"

        /** mero-js `HA_REFUSAL_MESSAGES` — the same map as [com.calimero.mero.account.HA_REFUSAL_MESSAGES]. */
        val ADVICE: Map<String, String> get() = com.calimero.mero.account.HA_REFUSAL_MESSAGES

        private val LENIENT = Json { isLenient = true }

        /**
         * The typed refusal [e] is — by its status and the body's `error` code (or FastAPI's
         * `detail` / `detail.error`) — or null when it is none of the five.
         */
        fun from(e: HttpException): AccountHaRefusedException? {
            val refusal = HaRefusal.of(refusalCode(e.bodyText), e.status) ?: return null
            return when (refusal) {
                HaRefusal.ACCOUNT_NOT_LINKED -> AccountNotLinkedException(e.status, e.bodyText, e.headers, e.url)
                HaRefusal.ACCOUNT_LINKED_TO_SEVERAL_USERS -> AccountLinkedToSeveralUsersException(e.status, e.bodyText, e.headers, e.url)
                HaRefusal.HA_REQUEST_PENDING -> HaRequestPendingException(e.status, e.bodyText, e.headers, e.url)
                HaRefusal.UNKNOWN_RELAY -> UnknownRelayException(e.status, e.bodyText, e.headers, e.url)
                HaRefusal.RELAY_NOT_DIALABLE -> RelayNotDialableException(e.status, e.bodyText, e.headers, e.url)
            }
        }

        private fun refusalCode(body: String): String? =
            runCatching {
                val o = LENIENT.parseToJsonElement(body) as? JsonObject ?: return null
                text(o["error"]) ?: text(o["detail"]) ?: text((o["detail"] as? JsonObject)?.get("error"))
            }.getOrNull()

        private fun text(element: Any?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}

/** `account_not_linked` (409): link the account to a cloud user in the wallet; retrying does not help. */
class AccountNotLinkedException(
    status: Int = 409,
    bodyText: String = "",
    headers: Map<String, String> = emptyMap(),
    url: String = "",
) : AccountHaRefusedException(CODE, status, bodyText, headers, url) {
    companion object {
        const val CODE = ACCOUNT_NOT_LINKED
    }
}

/** `account_linked_to_several_users` (409): unlink the account from all but one cloud user. */
class AccountLinkedToSeveralUsersException(
    status: Int = 409,
    bodyText: String = "",
    headers: Map<String, String> = emptyMap(),
    url: String = "",
) : AccountHaRefusedException(CODE, status, bodyText, headers, url) {
    companion object {
        const val CODE = ACCOUNT_LINKED_TO_SEVERAL_USERS
    }
}

/** `ha_request_pending` (409): another namespace of this account is waiting for its first fleet node. */
class HaRequestPendingException(
    status: Int = 409,
    bodyText: String = "",
    headers: Map<String, String> = emptyMap(),
    url: String = "",
) : AccountHaRefusedException(CODE, status, bodyText, headers, url) {
    companion object {
        const val CODE = HA_REQUEST_PENDING
    }
}

/** `unknown_relay` (422): the cloud runs no relay at the claim's `relay_url`. */
class UnknownRelayException(
    status: Int = 422,
    bodyText: String = "",
    headers: Map<String, String> = emptyMap(),
    url: String = "",
) : AccountHaRefusedException(CODE, status, bodyText, headers, url) {
    companion object {
        const val CODE = UNKNOWN_RELAY
    }
}

/** `relay_not_dialable` (422): the relay has not reported its addresses yet; retry shortly. */
class RelayNotDialableException(
    status: Int = 422,
    bodyText: String = "",
    headers: Map<String, String> = emptyMap(),
    url: String = "",
) : AccountHaRefusedException(CODE, status, bodyText, headers, url) {
    companion object {
        const val CODE = RELAY_NOT_DIALABLE
    }
}

/**
 * The cloud cannot give [connectCloud] a connection yet, and the message says what the
 * person must go and do: claim a namespace, pick one of several, enable HA, wait for a
 * relay, or have an admin admit relays or grant authorship (mero-js's `connectCloud`
 * messages). Swift `AccountError.cloudNotReady`; [com.calimero.mero.http.classifyError]
 * reads it as `UNAVAILABLE`.
 */
class CloudNotReadyException(
    message: String,
) : MeroException(message)

/**
 * Base of the account-link callback refusals: [CloudLinkDeclinedException] (the person or
 * the cloud said no) and [UnexpectedCloudLinkException] (a grant this app never asked for).
 */
open class CloudLinkException(
    message: String,
) : MeroException(message)

/**
 * The person declined the account link at the cloud (`#error=denied`), or the cloud refused
 * it ([reason], verbatim). Swift `AccountError.linkDeclined`; classified `FORBIDDEN`.
 */
class CloudLinkDeclinedException(
    message: String,
    /** The cloud's `error` value. */
    val reason: String,
) : CloudLinkException(message) {
    /** True when the person pressed Cancel, rather than the cloud refusing. */
    val declined: Boolean get() = reason == "denied"
}

/**
 * A link grant that answers no link this app began, for the account it named — the
 * redirect URL can be forwarded. Start the link again from here.
 */
class UnexpectedCloudLinkException : CloudLinkException("this account link answers a request this app did not make - start the link again from here")
