package com.calimero.mero.auth

import com.calimero.mero.http.HttpException
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.NetworkException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Why a username/password login ([com.calimero.mero.Mero.authenticate]) failed, typed, so an
 * app can answer each case differently: ask again for a wrong password, count down a lockout,
 * point at the URL for a missing node.
 *
 * Build one from whatever the login threw with [of]. Port of Swift `LoginFailureReason`.
 */
sealed class LoginFailureReason {
    /** The node refused the username or password (a `4xx` about the credentials). */
    data object InvalidCredentials : LoginFailureReason()

    /**
     * Too many failed attempts: the node locked logins out for a while (`429`).
     * [retryAfterSeconds] is the node's `Retry-After`, when it sent one.
     */
    data class RateLimited(
        val retryAfterSeconds: Double?,
    ) : LoginFailureReason()

    /** Nothing answered: no network, DNS, a refused connection, a timeout. */
    data object Unreachable : LoginFailureReason()

    /** Something answered, but has no login there (`404`): usually a wrong URL. */
    data object NotFound : LoginFailureReason()

    /** The node failed (`5xx`). */
    data class ServerError(
        val status: Int,
    ) : LoginFailureReason()

    /** Anything else (an undecodable answer, say). */
    data object Other : LoginFailureReason()

    companion object {
        /** Classify an error thrown by a login. */
        fun of(error: Throwable): LoginFailureReason =
            when (error) {
                is MeroStateException -> InvalidCredentials
                is NetworkException -> Unreachable
                is HttpException -> classify(error)
                is IOException, is TimeoutCancellationException -> Unreachable
                else -> Other
            }

        private fun classify(http: HttpException): LoginFailureReason =
            when (http.status) {
                HTTP_TOO_MANY_REQUESTS -> {
                    val header =
                        http.headers.entries
                            .firstOrNull { it.key.equals("retry-after", ignoreCase = true) }
                            ?.value
                    RateLimited(header?.let(::retryAfter))
                }
                HTTP_NOT_FOUND -> NotFound
                in HTTP_SERVER_ERRORS -> ServerError(http.status)
                in HTTP_CLIENT_ERRORS -> InvalidCredentials
                else -> Other
            }

        /** `Retry-After` as seconds (core sends seconds; an HTTP date is read too). */
        internal fun retryAfter(
            value: String,
            nowMs: Long = System.currentTimeMillis(),
        ): Double? {
            val trimmed = value.trim()
            trimmed.toDoubleOrNull()?.let { return it.takeIf { s -> s.isFinite() && s >= 0 } }
            val format =
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                    isLenient = false
                }
            val date = runCatching { format.parse(trimmed) }.getOrNull() ?: return null
            return maxOf(0.0, (date.time - nowMs) / MS_PER_SECOND)
        }

        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_NOT_FOUND = 404
        private val HTTP_CLIENT_ERRORS = 400..499
        private val HTTP_SERVER_ERRORS = 500..Int.MAX_VALUE
        private const val MS_PER_SECOND = 1000.0
    }
}
