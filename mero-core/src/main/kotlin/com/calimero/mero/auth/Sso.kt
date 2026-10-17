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
 * (`#access_token=…&refresh_token=…&application_id=…&…`).
 *
 * Returns null if `access_token` is absent, **or if the callback does not answer a login this
 * app began** with [buildAuthLoginUrl]: the callback URL must carry an `auth_state` query
 * parameter that [pending] recorded, unexpired and unused. The state is spent, so the same
 * callback is adopted once. This stops a crafted deep link from signing the person in to an
 * attacker's node with the attacker's tokens.
 *
 * @param allowUnsolicited accept tokens this app did not ask for — only for a host that
 *   injects them itself (mero-js's Calimero Desktop case). Anyone who can open the app with a
 *   link can then choose the session, so leave it false on a phone.
 * @param pending where [buildAuthLoginUrl] recorded the login; [PendingAuthStates.shared] by default.
 *
 * Port of mero-js `parseAuthCallback`, kept dependency-free (no `android.net.Uri`) so it is
 * unit-testable on the JVM.
 */
fun parseAuthCallback(
    url: String,
    allowUnsolicited: Boolean = false,
    pending: PendingAuthStates = PendingAuthStates.shared,
): AuthCallbackResult? {
    return try {
        val hashIndex = url.indexOf('#')
        if (hashIndex == -1) return null
        val params = parseQuery(url.substring(hashIndex + 1))
        val accessToken = params["access_token"]?.takeIf { it.isNotEmpty() } ?: return null
        if (!allowUnsolicited && !pending.take(stateOf(url, hashIndex))) return null
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

/** The `auth_state` in the callback's query (between `?` and `#`), or null. */
private fun stateOf(
    url: String,
    hashIndex: Int,
): String? {
    val queryIndex = url.indexOf('?')
    if (queryIndex == -1 || queryIndex > hashIndex) return null
    return parseQuery(url.substring(queryIndex + 1, hashIndex))[AUTH_STATE_PARAM]
}

/**
 * Build the node's auth-login URL to open in a browser / Custom Tab. Port of
 * mero-js `buildAuthLoginUrl`.
 *
 * Records a fresh single-use state in [pending] and adds it to the callback URL as
 * `auth_state=<hex>` (the callback's own query and fragment are kept), so
 * [parseAuthCallback] can tell this login's answer from a link someone else crafted. Call it
 * once per login attempt, right before opening the URL.
 */
fun buildAuthLoginUrl(
    nodeUrl: String,
    opts: AuthLoginOptions,
    pending: PendingAuthStates = PendingAuthStates.shared,
): String {
    val params = LinkedHashMap<String, String>()
    params["callback-url"] = withQueryParam(opts.callbackUrl, AUTH_STATE_PARAM, pending.begin())
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

/** The query parameter [buildAuthLoginUrl] adds to the callback URL (mero-js `STATE_PARAM`). */
const val AUTH_STATE_PARAM = "auth_state"

/** [url] with [name] set to [value] in its query (replacing any), keeping the rest and the fragment. */
private fun withQueryParam(
    url: String,
    name: String,
    value: String,
): String {
    val hashAt = url.indexOf('#')
    val beforeHash = if (hashAt == -1) url else url.substring(0, hashAt)
    val fragment = if (hashAt == -1) "" else url.substring(hashAt)
    val queryAt = beforeHash.indexOf('?')
    val base = if (queryAt == -1) beforeHash else beforeHash.substring(0, queryAt)
    val kept =
        if (queryAt == -1) {
            emptyList()
        } else {
            beforeHash.substring(queryAt + 1).split("&").filter { it.isNotEmpty() && decode(it.substringBefore('=')) != name }
        }
    val query = (kept + "${encode(name)}=${encode(value)}").joinToString("&")
    return "$base?$query$fragment"
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
