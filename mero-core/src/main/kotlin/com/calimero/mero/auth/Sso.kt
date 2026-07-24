package com.calimero.mero.auth

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Result of parsing an SSO callback URL's hash fragment. Port of mero-js `AuthCallbackResult`.
 */
data class AuthCallbackResult(
    val accessToken: String,
    val refreshToken: String,
    val applicationId: String,
    val contextId: String,
    val contextIdentity: String,
    val nodeUrl: String,
)

/** Options for [buildAuthLoginUrl]. Port of mero-js `AuthLoginOptions`. */
data class AuthLoginOptions(
    /** Deep-link the node redirects back to (custom scheme or App Link), e.g. `myapp://auth-callback`. */
    val callbackUrl: String,
    val mode: String,
    val permissions: List<String>? = null,
    val packageName: String? = null,
    val packageVersion: String? = null,
    val registryUrl: String? = null,
)

/**
 * Parse an SSO callback URL. Tokens arrive in the URL **hash fragment**
 * (`#access_token=…&refresh_token=…&application_id=…&…`). Returns null if `access_token` is absent.
 *
 * Pure port of mero-js `parseAuthCallback`, kept dependency-free (no `android.net.Uri`) so it is
 * unit-testable on the JVM.
 */
fun parseAuthCallback(url: String): AuthCallbackResult? {
    return try {
        val hashIndex = url.indexOf('#')
        if (hashIndex == -1) return null
        val params = parseQuery(url.substring(hashIndex + 1))
        val accessToken = params["access_token"] ?: return null
        AuthCallbackResult(
            accessToken = accessToken,
            refreshToken = params["refresh_token"].orEmpty(),
            applicationId = params["application_id"].orEmpty(),
            contextId = params["context_id"].orEmpty(),
            contextIdentity = params["context_identity"].orEmpty(),
            nodeUrl = params["node_url"].orEmpty(),
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * Build the node's auth-login URL to open in a browser / Custom Tab. Port of
 * mero-js `buildAuthLoginUrl`.
 */
fun buildAuthLoginUrl(
    nodeUrl: String,
    opts: AuthLoginOptions,
): String {
    val params = LinkedHashMap<String, String>()
    params["callback-url"] = opts.callbackUrl
    opts.permissions?.takeIf { it.isNotEmpty() }?.let { params["permissions"] = it.joinToString(",") }
    params["mode"] = opts.mode
    if (opts.packageName != null) {
        params["package-name"] = opts.packageName
        opts.packageVersion?.let { params["package-version"] = it }
        opts.registryUrl?.let { params["registry-url"] = it }
    }
    val query = params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
    val base = nodeUrl.trimEnd('/')
    return "$base/auth/login?$query"
}

private fun parseQuery(query: String): Map<String, String> =
    query
        .split("&")
        .filter { it.isNotEmpty() }
        .associate { pair ->
            val idx = pair.indexOf('=')
            if (idx == -1) {
                decode(pair) to ""
            } else {
                decode(pair.substring(0, idx)) to decode(pair.substring(idx + 1))
            }
        }

private fun decode(s: String): String = URLDecoder.decode(s, "UTF-8")

private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
