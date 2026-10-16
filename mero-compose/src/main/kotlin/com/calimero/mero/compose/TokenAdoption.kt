package com.calimero.mero.compose

import com.calimero.mero.TokenData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

// Whether the token bundle an SSO callback carries may replace the one already stored — port of
// mero-react `auth/token-adoption.ts`.
//
// Since core 0.11.0 (core#3083) refresh tokens are SINGLE-USE: every refresh consumes the
// presented token. Re-presenting a consumed one is read as theft — 401 `token_reuse` — and the
// node revokes the whole family, hard-logging out every holder. So an unconditional
// `setTokenData(callbackBundle)` is dangerous: if the SDK already rotated the stored refresh
// token, writing the callback's copy over it resurrects a consumed one.
//
// The callback bundle is adopted only when it is safe: nothing is stored, the node changed, the
// callback's tokens are genuinely newer, or the stored bundle lacks a refresh token the callback
// can supply. Anything else — a stale callback, a replay of the token we hold — is ignored.

/** The ordering claims of a JWT: issued-at and expiry, in seconds. Either may be absent. */
data class JwtClaims(
    val iat: Long? = null,
    val exp: Long? = null,
)

private val claimsJson = Json { ignoreUnknownKeys = true }
private const val HOUR_MS = 3_600_000L
private const val MS_PER_SECOND = 1000L
private const val JWT_PARTS = 3

/**
 * A JWT's `iat` / `exp`, without verifying the signature — verification is the node's job; only
 * the ordering is needed here, and both bundles are node-trusted by then ([resolveTrustedNodeUrl]).
 * Null for anything that is not a decodable three-part JWT. Non-positive or non-numeric claims
 * read as absent.
 */
fun decodeJwtClaims(token: String): JwtClaims? {
    val parts = token.split('.')
    if (parts.size != JWT_PARTS) return null
    val payload =
        runCatching { claimsJson.parseToJsonElement(base64UrlToString(parts[1])) as? JsonObject }.getOrNull()
            ?: return null

    fun claim(name: String): Long? =
        (payload[name] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.doubleOrNull
            ?.takeIf { it > 0 }
            ?.toLong()
    return JwtClaims(iat = claim("iat"), exp = claim("exp"))
}

/** Why the callback bundle was (or was not) adopted — mero-react `TokenAdoptionReason`. */
enum class TokenAdoptionReason(
    val wire: String,
) {
    /** Nothing stored: a genuinely fresh login. Seeded from the callback. */
    FRESH_LOGIN("fresh-login"),

    /** The stored bundle belongs to another node. Re-seeded from the callback. */
    NODE_CHANGED("node-changed"),

    /** The callback's tokens are genuinely newer than the stored ones. */
    NEWER("newer"),

    /** Same session, but the stored bundle has no refresh token and the callback does. */
    FILL_REFRESH("fill-refresh"),

    /** The callback's tokens are stale, or replay the access token already held. */
    STALE("stale"),
}

/** What [resolveTokenAdoption] decided: write [tokens], or leave the store alone. */
data class TokenAdoptionDecision(
    val adopt: Boolean,
    val reason: TokenAdoptionReason,
    /** The bundle to store; null exactly when [adopt] is false. */
    val tokens: TokenData? = null,
)

/**
 * Decide what (if anything) to write to the token store for an SSO callback.
 *
 * Never returns a bundle with an empty refresh token while one for the same node is stored: an
 * access-only callback is MERGED over the stored bundle, so a fresher access token cannot strip
 * the session's ability to refresh.
 *
 * @param callbackRefreshToken empty when the callback carries none (hosts are moving to
 *   access-only bundles so they cannot hand back a rotated-away refresh token).
 * @param storedNodeUrl the node the stored bundle was issued by, i.e. the node before this callback.
 */
fun resolveTokenAdoption(
    callbackAccessToken: String,
    callbackRefreshToken: String,
    callbackNodeUrl: String,
    stored: TokenData?,
    storedNodeUrl: String?,
    now: () -> Long = System::currentTimeMillis,
): TokenAdoptionDecision {
    fun bundle(refresh: String) =
        TokenData(
            accessToken = callbackAccessToken,
            refreshToken = refresh,
            expiresAt = decodeJwtClaims(callbackAccessToken)?.exp?.times(MS_PER_SECOND) ?: (now() + HOUR_MS),
        )
    if (stored == null || stored.accessToken.isEmpty()) {
        return TokenAdoptionDecision(true, TokenAdoptionReason.FRESH_LOGIN, bundle(callbackRefreshToken))
    }
    // Another node's family: its refresh token is meaningless here, so it is not carried over.
    val sameNode = storedNodeUrl != null && sameOrigin(callbackNodeUrl, storedNodeUrl)
    if (!sameNode) return TokenAdoptionDecision(true, TokenAdoptionReason.NODE_CHANGED, bundle(callbackRefreshToken))

    val refresh = callbackRefreshToken.ifEmpty { stored.refreshToken }
    if (isCallbackNewer(callbackAccessToken, stored)) return TokenAdoptionDecision(true, TokenAdoptionReason.NEWER, bundle(refresh))
    // Not newer, but the stored bundle cannot refresh and the callback can repair that: keep the
    // stored access token (it is at least as new).
    if (stored.refreshToken.isEmpty() && callbackRefreshToken.isNotEmpty()) {
        return TokenAdoptionDecision(true, TokenAdoptionReason.FILL_REFRESH, stored.copy(refreshToken = callbackRefreshToken))
    }
    return TokenAdoptionDecision(false, TokenAdoptionReason.STALE)
}

/**
 * Is the callback's access token newer than the stored one? `iat` decides when both carry a
 * different one, then `exp`, then the stored bundle's recorded expiry. Undecidable is "not
 * newer": refusing costs a redundant login at worst; adopting a stale bundle burns the family.
 */
private fun isCallbackNewer(
    callbackAccessToken: String,
    stored: TokenData,
): Boolean {
    val incoming = decodeJwtClaims(callbackAccessToken) ?: return false
    val current = decodeJwtClaims(stored.accessToken)
    if (current != null) {
        if (incoming.iat != null && current.iat != null && incoming.iat != current.iat) return incoming.iat > current.iat
        if (incoming.exp != null && current.exp != null && incoming.exp != current.exp) return incoming.exp > current.exp
        return false
    }
    return incoming.exp != null && incoming.exp * MS_PER_SECOND > stored.expiresAt
}

/** Base64url (padding optional) to a UTF-8 string, in plain Kotlin so it runs on API 24 and the JVM. */
private fun base64UrlToString(input: String): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val out = java.io.ByteArrayOutputStream()
    var buffer = 0
    var bits = 0
    for (c in input) {
        if (c == '=') break
        val v =
            alphabet.indexOf(
                if (c == '+') {
                    '-'
                } else if (c == '/') {
                    '_'
                } else {
                    c
                },
            )
        require(v >= 0) { "not base64url" }
        buffer = (buffer shl 6) or v
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.write((buffer shr bits) and 0xFF)
        }
    }
    return out.toByteArray().decodeToString()
}
