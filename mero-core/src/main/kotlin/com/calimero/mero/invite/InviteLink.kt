package com.calimero.mero.invite

/**
 * Shareable invitation links, matching `@calimero-network/mero-platform`.
 *
 * A native app is a client of a node that runs on a computer, so the *link* is
 * not something the phone opens — it is something the phone hands to a person,
 * who opens it on the machine with the desktop app. There the launcher resolves
 * the slug, installs the app if it is missing, and joins the namespace. What the
 * phone needs is only two things: build the link, and accept one somebody pastes
 * back in.
 *
 * That is why there is no intent-filter or App Links setup here. Nothing about
 * this requires Android to route a link to the app.
 */
object InviteLink {
    /**
     * Where shareable links point. Always HTTPS: an HTTPS link opens the web
     * build directly and hands off to the desktop launcher on a machine that has
     * it, whereas `calimero://` is a device-local transport that does not survive
     * being pasted into a chat window.
     */
    const val DEFAULT_HOST = "https://links.calimero.network"

    /** The intent verb an invitation carries. */
    const val JOIN_ACTION = "join"

    /** The query parameter the payload travels in. */
    const val INVITATION_PARAM = "invitation"

    /**
     * Build a canonical shareable link.
     *
     * [slug] is the app's package id (e.g. `com.calimero.mero-tag`). The desktop
     * launcher matches it against `Application.package`, so it must equal the id
     * the app is published under — not a display name.
     */
    fun create(
        slug: String,
        action: String = JOIN_ACTION,
        params: Map<String, String> = emptyMap(),
        host: String = DEFAULT_HOST,
    ): String {
        val base = host.trimEnd('/')
        val path = "${encode(slug)}/${encode(action)}"
        if (params.isEmpty()) return "$base/$path"
        // Sorted so a given invitation always produces the same string.
        val query =
            params.toSortedMap().entries.joinToString("&") { (k, v) ->
                "${encode(k)}=${encode(v)}"
            }
        return "$base/$path?$query"
    }

    /** The shareable link for an invitation token. */
    fun invitation(token: String, slug: String, host: String = DEFAULT_HOST): String =
        create(slug = slug, params = mapOf(INVITATION_PARAM to token), host = host)

    /**
     * Pull the invitation token out of whatever a person pasted: a shareable
     * HTTPS link, a `calimero://` link, or the bare token.
     *
     * The query is split by hand rather than with `android.net.Uri` or
     * `java.net.URI`, for two reasons: this stays unit-testable on the JVM with
     * no device, and host parsing on a non-special scheme mangles a dotted slug
     * like `com.calimero.mero-tag`.
     *
     * Returns null only for blank input — an unrecognized string is handed back
     * unchanged, so a bare token still works and the codec gets the final say.
     */
    fun tokenFromPasted(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val lower = trimmed.lowercase()
        val isLink = lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("calimero://")
        if (!isLink) return trimmed

        val queryStart = trimmed.indexOf('?')
        if (queryStart < 0) return trimmed
        for (pair in trimmed.substring(queryStart + 1).split("&")) {
            val separator = pair.indexOf('=')
            if (separator < 0) continue
            if (pair.substring(0, separator) != INVITATION_PARAM) continue
            return decodePercent(pair.substring(separator + 1))
        }
        return trimmed
    }

    /** Percent-encode everything a token or slug may contain. */
    private fun encode(value: String): String {
        val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = byte.toInt().toChar()
            if (safe.indexOf(ch) >= 0) out.append(ch)
            else out.append('%').append("%02X".format(byte.toInt() and 0xff))
        }
        return out.toString()
    }

    private fun decodePercent(value: String): String {
        if (!value.contains('%') && !value.contains('+')) return value
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when {
                ch == '%' && i + 2 < value.length -> {
                    val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex == null) {
                        bytes.write(ch.code); i++
                    } else {
                        bytes.write(hex); i += 3
                    }
                }
                else -> {
                    for (b in ch.toString().toByteArray(Charsets.UTF_8)) bytes.write(b.toInt())
                    i++
                }
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }
}
