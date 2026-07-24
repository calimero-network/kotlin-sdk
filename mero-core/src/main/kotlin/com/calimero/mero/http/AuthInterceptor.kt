package com.calimero.mero.http

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Application interceptor that attaches `Authorization: Bearer <token>` from the current token
 * store. Skips requests that already carry an `Authorization` header, so per-request overrides
 * (e.g. `AuthApi.validateToken`, which validates an explicit token) win.
 *
 * Analogous to mero-js's `getAuthToken` transport hook.
 */
class AuthInterceptor(
    private val tokenProvider: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) {
            return chain.proceed(request)
        }
        val token = tokenProvider()
        val authed =
            if (!token.isNullOrBlank()) {
                request.newBuilder().header("Authorization", "Bearer $token").build()
            } else {
                request
            }
        return chain.proceed(authed)
    }
}
