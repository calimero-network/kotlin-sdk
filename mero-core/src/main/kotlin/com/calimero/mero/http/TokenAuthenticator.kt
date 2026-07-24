package com.calimero.mero.http

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * OkHttp [Authenticator] implementing mero-js's reactive-refresh-on-401.
 *
 * OkHttp invokes this automatically whenever a response comes back `401`. We refresh **only** when
 * the server tagged the response `x-auth-error: token_expired` — exactly mirroring mero-js, which
 * never refreshes on a bare 401 and treats `token_reuse`/`token_revoked` as terminal (those are
 * detected and thrown in [OkHttpTransport], never here).
 *
 * The refresh itself is funneled through a single-flight, cross-process-locked coordinator (passed
 * as [refresh]) so a double refresh can't replay a single-use refresh token and revoke the family.
 */
class TokenAuthenticator(
    /** Runs a serialized refresh given the access token that triggered the 401; returns the new
     * access token, or null if refresh is impossible/failed. */
    private val refresh: suspend (triggeringAccessToken: String?) -> String?,
) : Authenticator {
    override fun authenticate(
        route: Route?,
        response: Response,
    ): Request? {
        // Give up after one retry to avoid an infinite 401→refresh→401 loop.
        if (responseCount(response) >= MAX_ATTEMPTS) return null

        // Reactive-only: refresh strictly on token_expired. A bare 401 or a terminal reason is not
        // our job here.
        if (response.header("x-auth-error") != AUTH_ERROR_TOKEN_EXPIRED) return null

        val triggering =
            response.request
                .header("Authorization")
                ?.removePrefix("Bearer ")
                ?.trim()
                ?.ifBlank { null }

        val newToken =
            try {
                runBlocking { refresh(triggering) }
            } catch (_: Exception) {
                // A dead family surfaces as AuthRevokedException from the refresh call's own
                // transport check, which already cleared the store. Nothing to retry with.
                null
            }

        if (newToken.isNullOrBlank()) return null

        return response.request
            .newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var current: Response? = response
        var count = 1
        while (current?.priorResponse != null) {
            count++
            current = current.priorResponse
        }
        return count
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}
