package com.calimero.mero.cloud

import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.account.SecureStore
import com.calimero.mero.account.resolveRoot
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.randomBytes
import com.calimero.mero.http.PlainJsonHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URI
import java.net.URLDecoder

/**
 * Links this app began, so a link callback can be matched to one — the native counterpart
 * of the `localStorage` store mero-js keeps under `calimero.cloud.link.pending`
 * (`src/cloud/link-state.ts`), with the same storage shape:
 * `{ "<state>": { "account": "<64 hex>", "expiresAt": <ms> } }`.
 *
 * Each state is **single use**, names the account it was begun for, and **expires** after
 * [LIFETIME_MS] (a grant lives 5 minutes, plus time to consent). At most [MAX_PENDING] are
 * kept, oldest dropped first. Unreadable storage reads as empty and a failed write fails
 * closed: the callback is refused.
 *
 * [shared] is in memory: a link begun and answered in one process. Back it with the
 * Keystore-encrypted store so a link survives the process dying while the Custom Tab is open:
 *
 * ```kotlin
 * PendingCloudLinks.shared = PendingCloudLinks(EncryptedPrefsSecureStore(context, "mero_cloud_link"))
 * ```
 */
class PendingCloudLinks(
    private val store: SecureStore = MemorySecureStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val key: String = DEFAULT_KEY,
) {
    private val lock = Any()

    /** Remember a link for [account] under a fresh state (16 random bytes, hex) and return the state. */
    fun begin(account: String): String =
        synchronized(lock) {
            val now = clock()
            val pending = read(now)
            val state = Hex.encode(randomBytes(STATE_BYTES))
            pending[state] = account to now + LIFETIME_MS
            write(
                pending.entries
                    .toList()
                    .takeLast(MAX_PENDING)
                    .associate { it.key to it.value },
            )
            state
        }

    /**
     * Spend [state]: the account it was begun for, or null when this app did not begin it,
     * it lapsed, or it was spent before. Always null for a null or empty state.
     */
    fun take(state: String?): String? =
        synchronized(lock) {
            if (state.isNullOrEmpty()) return null
            val pending = read(clock())
            val entry = pending.remove(state) ?: return null
            write(pending)
            entry.first
        }

    /** How many links are pending (not lapsed). */
    fun pendingCount(): Int = synchronized(lock) { read(clock()).size }

    /** Forget every pending link. */
    fun clear() {
        synchronized(lock) { runCatching { store.remove(key) } }
    }

    private fun read(now: Long): LinkedHashMap<String, Pair<String, Long>> {
        val out = LinkedHashMap<String, Pair<String, Long>>()
        val raw = runCatching { store.get(key) }.getOrNull() ?: return out
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return out
        for ((state, entry) in obj) {
            val e = entry as? JsonObject
            val expiresAt = (e?.get("expiresAt") as? JsonPrimitive)?.longOrNull
            val account = (e?.get("account") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (account != null && expiresAt != null && expiresAt > now) out[state] = account to expiresAt
        }
        return out
    }

    private fun write(pending: Map<String, Pair<String, Long>>) {
        runCatching {
            val body =
                buildJsonObject {
                    pending.forEach { (state, entry) ->
                        put(
                            state,
                            buildJsonObject {
                                put("account", entry.first)
                                put("expiresAt", entry.second)
                            },
                        )
                    }
                }
            store.put(key, body.toString())
        }
    }

    companion object {
        /** The storage key, the one mero-js uses. */
        const val DEFAULT_KEY = "calimero.cloud.link.pending"

        /** How long a begun link can be answered: 10 minutes. */
        const val LIFETIME_MS = 10 * 60 * 1000L

        /** At most this many links are remembered at once. */
        const val MAX_PENDING = 20

        private const val STATE_BYTES = 16

        /** The store the link helpers use by default: in memory. */
        @Volatile
        @JvmStatic
        var shared: PendingCloudLinks = PendingCloudLinks()
    }
}

/** A grant the cloud handed back — mero-js `CloudLinkCallback`. */
data class CloudLinkCallback(
    val grant: String,
    /** The account the cloud sealed into the grant. */
    val account: String,
)

/**
 * Linking an account to a cloud login from an app that holds the account root and no cloud
 * session — port of mero-js `src/cloud/link-redirect.ts`.
 *
 * The link needs a cloud session, which only Google mints, and a root signature, which only
 * the holder can make. So the person goes to the cloud ([cloudLinkUrl], opened in a Custom
 * Tab by `CloudWebLauncher.beginCloudLink`), where they are signed in, and consents to
 * linking one named account; the cloud redirects back with a **grant** in the URL fragment —
 * that login's sealed consent, spendable only by whoever signs with the account's root.
 * [readCloudLinkCallback] reads it (refusing one this app did not ask for) and
 * [completeCloudLink] spends it, anonymously.
 *
 * Show the person the account id before sending them: the consent screen is the only check
 * that they meant this account.
 */
object CloudLink {
    /** Where the consent screen lives: the web app, not the manager API. */
    const val DEFAULT_CLOUD_WEB_URL = CloudClient.DEFAULT_PORTAL_URL

    /** The query parameter a link's state rides in on the return URL. */
    const val STATE_PARAM = "link_state"

    /**
     * The consent URL to open: `{cloudWebUrl}?link-account=<id>&callback-url=<returnTo with
     * link_state>`. Remembers the link in [pending] so [readCloudLinkCallback] can match it.
     * mero-js `cloudLinkUrl`.
     *
     * @param accountId 64 lowercase hex — derive it and show it; never let a person type it.
     * @param returnTo where the cloud sends the person back: an https App Link or an app
     *   scheme this app receives. The grant comes back in its fragment.
     * @throws IllegalArgumentException for an id the consent page would refuse, or a
     *   [returnTo] that is not an absolute URL without a fragment (nothing is remembered then).
     */
    fun cloudLinkUrl(
        accountId: String,
        returnTo: String,
        cloudWebUrl: String = DEFAULT_CLOUD_WEB_URL,
        pending: PendingCloudLinks = PendingCloudLinks.shared,
    ): String {
        val web = cloudWebUrl.toHttpUrl()
        val callback = beginLinkState(accountId, returnTo, pending)
        return web
            .newBuilder()
            .setQueryParameter("link-account", accountId)
            .setQueryParameter("callback-url", callback)
            .build()
            .toString()
    }

    /**
     * Read a grant out of a callback URL's fragment. Null on a URL that carries none, so it
     * can be run on every incoming deep link. mero-js `readCloudLinkCallback`.
     *
     * @throws CloudLinkDeclinedException when the person declined (or the cloud refused);
     *   the pending link is forgotten.
     * @throws UnexpectedCloudLinkException when the grant answers no link this app began for
     *   that account. The state is spent either way, so a callback is accepted at most once.
     */
    fun readCloudLinkCallback(
        url: String,
        pending: PendingCloudLinks = PendingCloudLinks.shared,
    ): CloudLinkCallback? {
        val params = UrlParts.formDecode(UrlParts.fragment(url))
        val state = UrlParts.formDecode(UrlParts.query(url))[STATE_PARAM]
        params["error"]?.takeIf { it.isNotEmpty() }?.let { error ->
            pending.take(state)
            throw CloudLinkDeclinedException(
                if (error == "denied") "the account link was declined at the cloud" else "the cloud refused this link: $error",
                error,
            )
        }
        val grant = params["grant"]?.ifEmpty { null } ?: return null
        val account = params["account"]?.ifEmpty { null } ?: return null
        if (pending.take(state) != account) throw UnexpectedCloudLinkException()
        return CloudLinkCallback(grant, account)
    }

    /**
     * Spend the grant: sign it with the root ([rootSecret], 64 hex) and hand it to the
     * cloud, anonymously ([CloudClient.linkAccountWithGrant]). mero-js `completeCloudLink`.
     *
     * @throws IllegalArgumentException before anything is sent when the grant names another
     *   account than the root derives.
     */
    suspend fun completeCloudLink(
        callback: CloudLinkCallback,
        rootSecret: String,
        cloudBaseUrl: String = CloudClient.DEFAULT_BASE_URL,
        httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
    ): CloudAccountLink = completeCloudLink(callback, resolveRoot(rootSecret).signer, cloudBaseUrl, httpClient)

    /** [completeCloudLink] with a root held as a signer. */
    suspend fun completeCloudLink(
        callback: CloudLinkCallback,
        root: DeviceSigner,
        cloudBaseUrl: String = CloudClient.DEFAULT_BASE_URL,
        httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
    ): CloudAccountLink {
        val resolved = resolveRoot(root)
        require(resolved.accountId == callback.account) {
            "this grant is for account ${callback.account}, but the root here derives ${resolved.accountId} — " +
                "it was minted for a different account"
        }
        return CloudClient(cloudBaseUrl, httpClient = httpClient).linkAccountWithGrant(callback.grant, signer = resolved.signer)
    }

    /**
     * [url] without its fragment and `link_state` — what to keep once a callback was read, so
     * a re-delivered intent does not replay a spent grant. mero-js `withoutLinkState`.
     */
    fun withoutLinkState(url: String): String {
        val noFragment = url.substringBefore('#')
        val base = noFragment.substringBefore('?')
        val query = noFragment.substringAfter('?', missingDelimiterValue = "")
        val kept = query.split('&').filter { it.isNotEmpty() && it != STATE_PARAM && !it.startsWith("$STATE_PARAM=") }
        return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
    }

    /**
     * Remember a link for [account] and return [returnTo] carrying its state (replacing any
     * `link_state` already there). Validates both first, so a bad return URL begins nothing.
     */
    internal fun beginLinkState(
        account: String,
        returnTo: String,
        pending: PendingCloudLinks,
    ): String {
        require(Hex.isHex32(account)) { "accountId must be 64 lowercase hex characters" }
        require(UrlParts.isAbsolute(returnTo) && '#' !in returnTo) {
            "returnTo must be an absolute URL without a fragment, got $returnTo"
        }
        val state = pending.begin(account)
        val stripped = withoutLinkState(returnTo)
        return stripped + (if ('?' in stripped) "&" else "?") + "$STATE_PARAM=$state"
    }
}

/**
 * What the cloud's `/enable-ha` or `/disable-ha` page sent back to [HaPageOptions.redirectUrl]:
 * every query and fragment parameter, verbatim, with an `error` pulled out. The page defines
 * no fields of its own today, so arriving here means only "the person is back"; check the
 * namespace's state with [CloudClient.getMyNamespaces].
 */
data class HaPageCallback(
    val params: Map<String, String>,
    /** The page's `error`, when it named one. */
    val error: String? = null,
)

/**
 * Read an `/enable-ha` / `/disable-ha` return: null unless [url] is [redirectUrl] (same URL
 * up to its query and fragment), so it can run on every deep link. The counterpart of
 * `CloudWebLauncher.enableHA` / `disableHA`, which open the pages in a Custom Tab.
 */
fun readHaPageCallback(
    url: String,
    redirectUrl: String,
): HaPageCallback? {
    val bare = { u: String -> u.substringBefore('#').substringBefore('?').trimEnd('/') }
    if (bare(url) != bare(redirectUrl)) return null
    val params = UrlParts.formDecode(UrlParts.query(url)) + UrlParts.formDecode(UrlParts.fragment(url))
    return HaPageCallback(params, params["error"]?.ifEmpty { null })
}

/**
 * Is a credential or grant going back somewhere other than the page that asked? Compares web
 * origins (scheme, host, non-default port); false when either side is unknown or [returnTo]
 * is not a URL. A smell, not a verdict — a sibling callback host is legitimate — so a wallet
 * can show it to the person approving. A custom app scheme has an opaque (`null`) origin, as
 * in a browser. mero-js `callbackIsForeign`.
 */
fun callbackIsForeign(
    returnTo: String,
    pageOrigin: String?,
): Boolean {
    if (pageOrigin.isNullOrEmpty()) return false
    val there = UrlParts.webOrigin(returnTo) ?: return false
    return there != (UrlParts.webOrigin(pageOrigin) ?: pageOrigin)
}

/** URL pieces for callback URLs of any scheme (OkHttp's `HttpUrl` takes only http/https). */
internal object UrlParts {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.+")
    private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443, "ws" to 80, "wss" to 443, "ftp" to 21)

    fun isAbsolute(url: String): Boolean = SCHEME.matches(url) && url.none { it.isWhitespace() }

    fun fragment(url: String): String = url.substringAfter('#', missingDelimiterValue = "")

    fun query(url: String): String = url.substringBefore('#').substringAfter('?', missingDelimiterValue = "")

    /** `URLSearchParams` semantics: form-decoded, the first value of a repeated key wins. */
    fun formDecode(params: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in params.removePrefix("?").removePrefix("#").split('&')) {
            if (pair.isEmpty()) continue
            val k = decode(pair.substringBefore('='))
            if (k !in out) out[k] = decode(pair.substringAfter('=', missingDelimiterValue = ""))
        }
        return out
    }

    /** `URL.origin` as a browser serialises it, or null when [value] is not an absolute URL. */
    fun webOrigin(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val defaultPort = DEFAULT_PORTS[scheme] ?: return "null"
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = uri.port
        return if (port == -1 || port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
