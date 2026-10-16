package com.calimero.mero.compose

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Which node an SSO callback may authenticate against — port of mero-react `auth/node-trust.ts`.
//
// The login flow sends the browser to a node's auth page and back to the app with
// `access_token` / `refresh_token` / `node_url` in the callback URL. That URL is
// attacker-influenceable, so a malicious `node_url` would otherwise make the SDK send the
// freshly minted tokens to an attacker's node (exfiltration). Defense: only authenticate against
// a node we can independently trust — the node login was *started* with (saved before the
// Custom Tab opened), or an app-configured allowlist.

/** True iff both URLs parse as http(s) and share an origin (scheme + host + port). */
fun sameOrigin(
    a: String,
    b: String,
): Boolean {
    val x = a.trim().toHttpUrlOrNull() ?: return false
    val y = b.trim().toHttpUrlOrNull() ?: return false
    return x.scheme == y.scheme && x.host == y.host && x.port == y.port
}

/** What [resolveTrustedNodeUrl] decided. */
data class TrustedNodeResult(
    /** The node URL that is safe to authenticate against, or null. */
    val url: String?,
    /** A candidate `node_url` was present but could not be trusted (likely an attack). */
    val rejected: Boolean,
)

/**
 * Decide which node URL, if any, is safe to authenticate against.
 *
 * - No [candidate] → the [initiated] node.
 * - Candidate and an initiated node → it must share the initiated node's origin, else rejected.
 * - Candidate, no initiated node, an allowlist → it must be in [allowedNodeUrls], else rejected.
 * - Candidate and no trust anchor at all → rejected: there is nothing to check the callback
 *   against. The normal flow ([MeroClient.connectToNode]) always records the initiated node.
 */
fun resolveTrustedNodeUrl(
    candidate: String?,
    initiated: String?,
    allowedNodeUrls: List<String> = emptyList(),
): TrustedNodeResult {
    if (candidate.isNullOrEmpty()) return TrustedNodeResult(initiated, rejected = false)
    if (initiated != null) {
        return if (sameOrigin(candidate, initiated)) TrustedNodeResult(candidate, false) else TrustedNodeResult(null, true)
    }
    if (allowedNodeUrls.isNotEmpty()) {
        return if (allowedNodeUrls.any { sameOrigin(candidate, it) }) TrustedNodeResult(candidate, false) else TrustedNodeResult(null, true)
    }
    return TrustedNodeResult(null, rejected = true)
}
