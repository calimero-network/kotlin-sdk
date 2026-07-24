package com.calimero.mero

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val jwtJson = Json { ignoreUnknownKeys = true }

/**
 * Decode a base64url segment to bytes. Pure Kotlin so it works on every API level (unlike
 * `java.util.Base64`, which is API 26+) and is unit-testable on the JVM (unlike `android.util.Base64`).
 */
internal fun base64UrlDecode(input: String): ByteArray {
    var s = input.replace('-', '+').replace('_', '/')
    while (s.length % 4 != 0) s += '='
    val table = IntArray(128) { -1 }
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    for (i in alphabet.indices) table[alphabet[i].code] = i

    val out = ArrayList<Byte>(s.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (c in s) {
        if (c == '=') break
        val v = if (c.code < 128) table[c.code] else -1
        if (v >= 0) {
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
    }
    return out.toByteArray()
}

/**
 * Extract `exp` (seconds) from a JWT access token and return it as an epoch-millisecond timestamp.
 * Returns [fallbackMs] when the token is not a parseable JWT or has no numeric `exp`.
 *
 * Port of `expiresAtFromJwt` in mero-js.
 */
fun expiresAtFromJwt(
    token: String,
    fallbackMs: Long,
): Long =
    try {
        val parts = token.split(".")
        if (parts.size == 3) {
            val payloadJson = base64UrlDecode(parts[1]).decodeToString()
            val exp =
                jwtJson
                    .parseToJsonElement(payloadJson)
                    .jsonObject["exp"]
                    ?.jsonPrimitive
                    ?.longOrNull
            if (exp != null) exp * 1000 else fallbackMs
        } else {
            fallbackMs
        }
    } catch (_: Exception) {
        fallbackMs
    }
