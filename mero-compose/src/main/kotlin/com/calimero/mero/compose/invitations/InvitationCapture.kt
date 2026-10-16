package com.calimero.mero.compose.invitations

import android.content.Context
import android.content.Intent
import com.calimero.mero.compose.KeyValueStore
import com.calimero.mero.compose.MemoryKeyValueStore
import com.calimero.mero.compose.SharedPreferencesStore
import com.calimero.mero.invite.InviteLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.net.URLEncoder

// Capturing an invitation, however it reached the app — port of mero-react
// `invitations/capture.ts` and the `@calimero-network/mero-platform` deep-link controller under it.
//
// Sources, on Android: the Intent that started the Activity (a cold open — `onCreate`) and each
// one delivered to it while it runs (a warm link — `onNewIntent`). Both are handed over with
// [InvitationCapture.capture]. The SDK dedups them by a content nonce, persists them (so an
// intent survives process death and the sign-in), replays to a handler that registers late, and
// drops an intent only when the app acks it ([CapturedInvitation.resolve]).
//
// So the landing screen does not matter — a handler mounted anywhere sees the intent — and
// arriving before the session is ready is fine: the intent is buffered until something asks.
//
// To receive the links, declare an intent filter on the Activity for this app's deep-link slug
// (its package id), and optionally for an https App Link host you own:
//
//     <intent-filter>
//         <action android:name="android.intent.action.VIEW" />
//         <category android:name="android.intent.category.DEFAULT" />
//         <category android:name="android.intent.category.BROWSABLE" />
//         <data android:scheme="calimero" android:host="com.example.app" android:pathPrefix="/join" />
//     </intent-filter>
//
// with `android:launchMode="singleTop"` (or `singleTask`) so a warm link arrives in `onNewIntent`.

/** The action an invite link carries: `…/<slug>/join?invitation=…`. */
const val JOIN_ACTION = "join"

/** The query parameter every app's invite builder emits. */
const val INVITATION_PARAM = "invitation"

/**
 * How many loads may auto-join one invitation before it needs a person. An unacked intent is
 * replayed on every load so a transient failure is retried; without a ceiling a permanent one
 * would be retried forever.
 */
const val MAX_AUTO_JOIN_ATTEMPTS = 3

/** One captured invitation, with the ack the app owes the store. */
class CapturedInvitation(
    /** The raw invitation token, ready to parse. */
    val token: String,
    /** Every query parameter the link carried, the token included. */
    val params: Map<String, String>,
    /**
     * Whether to attempt the join without being asked. False once this invitation has spent its
     * [MAX_AUTO_JOIN_ATTEMPTS]: the token is still handed over (a field can be prefilled, one tap
     * retries), but a link that can never join stops re-firing on every launch.
     */
    val autoJoin: Boolean,
    private val ack: () -> Unit,
) {
    /** Ack it so the store stops replaying it. Call once handled OR declined. */
    fun resolve() = ack()

    override fun toString(): String = "CapturedInvitation(token=$token, autoJoin=$autoJoin)"
}

/**
 * The link an inviter shares — mero-react `buildInviteLink`:
 * `https://links.calimero.network/<slug>/join?invitation=…` (see [InviteLink.DEFAULT_HOST]).
 * [slug] is the app's deep-link handler, for a Calimero app its package id; [extra] rides along
 * beside the token (empty values are dropped). Pass [host] `"calimero://"` for the device-local
 * `calimero://<slug>/join?invitation=…` form.
 */
fun buildInviteLink(
    slug: String,
    token: String,
    extra: Map<String, String?> = emptyMap(),
    host: String = InviteLink.DEFAULT_HOST,
): String {
    val params = LinkedHashMap<String, String>()
    params[INVITATION_PARAM] = token
    for ((key, value) in extra) if (!value.isNullOrEmpty()) params[key] = value
    val query = params.entries.joinToString("&") { (k, v) -> "${formEncode(k)}=${formEncode(v)}" }
    // A bare scheme (`calimero://`, `calimero:/`, `calimero:`) roots the link as `calimero://`;
    // any other host loses its trailing slashes.
    val root = BARE_SCHEME.matchEntire(host.trim())?.let { "${it.groupValues[1]}://" } ?: (host.trimEnd('/') + "/")
    return "$root${pathEncode(slug)}/${pathEncode(JOIN_ACTION)}?$query"
}

/** The token in a raw deep-link payload (a bare token, or a URL / query carrying one), or null. */
fun invitationFromRaw(raw: String): String? {
    val direct = raw.trim()
    if (direct.isEmpty()) return null
    val query = if (direct.contains('?')) direct.substring(direct.indexOf('?') + 1) else direct
    parseQuery(query.substringBefore('#'))[INVITATION_PARAM]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (direct.contains('=') || direct.contains('?')) return null
    if (direct.startsWith("/") || SCHEME.containsMatchIn(direct)) return null
    return direct
}

/** [href] with the invitation parameter removed (unchanged when it has none). */
fun urlWithoutInvitation(href: String): String {
    val q = href.indexOf('?')
    if (q < 0) return href
    val hash = href.indexOf('#', q).let { if (it < 0) href.length else it }
    val pairs = href.substring(q + 1, hash).split('&').filter { it.isNotEmpty() }
    if (pairs.none { decode(it.substringBefore('=')) == INVITATION_PARAM }) return href
    val kept = pairs.filter { decode(it.substringBefore('=')) != INVITATION_PARAM }
    val base = href.substring(0, q)
    return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + href.substring(hash)
}

/** A raw deep link split into `slug`, `action` and query params — mero-platform `parseIntent`. */
internal data class ParsedIntent(
    val slug: String?,
    val action: String?,
    val params: Map<String, String>,
)

/**
 * `calimero://<slug>/<action>?…` is split by hand: host parsing on a non-special scheme mangles a
 * dotted slug like `com.calimero.curb`. Anything else is read as an absolute or relative URL whose
 * first two path segments are the slug and action.
 */
internal fun parseIntent(raw: String): ParsedIntent {
    val (pathPart, queryPart) =
        if (raw.startsWith("calimero://", ignoreCase = true)) {
            val rest = raw.substring("calimero://".length)
            rest.substringBefore('?') to rest.substringAfter('?', "")
        } else {
            val noScheme = SCHEME_AND_AUTHORITY.find(raw)?.let { raw.substring(it.range.last + 1) } ?: raw
            noScheme.substringBefore('?').substringBefore('#') to noScheme.substringAfter('?', "")
        }
    val segs = pathPart.split('/').filter { it.isNotEmpty() }
    return ParsedIntent(segs.getOrNull(0)?.let(::decode), segs.getOrNull(1)?.let(::decode), parseQuery(queryPart.substringBefore('#')))
}

/** Does [href] carry a deep-link signal (a query, or a non-root path) worth capturing? */
internal fun hasIntentSignal(href: String): Boolean {
    val parsed = parseIntent(href)
    val query = href.substringAfter('?', "").substringBefore('#')
    return query.isNotEmpty() || parsed.slug != null
}

/** FNV-1a 32-bit over UTF-16 code units, as hex — mero-platform's dedup nonce. */
internal fun hashNonce(raw: String): String {
    var h = FNV_OFFSET
    for (c in raw) {
        h = h xor c.code
        h *= FNV_PRIME
    }
    return Integer.toUnsignedString(h, HEX).padStart(NONCE_LENGTH, '0')
}

@Serializable
internal data class StoredIntent(
    val nonce: String,
    val raw: String,
    val capturedAt: Long,
)

/**
 * The durable pending-intent store — mero-platform `PendingIntentStore`: one key, a JSON list of
 * intents, deduped by content nonce, dropped only on [ack].
 */
internal class PendingIntentStore(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val serializer = ListSerializer(StoredIntent.serializer())

    fun all(): List<StoredIntent> =
        runCatching { store.get(STORAGE_KEY)?.let { json.decodeFromString(serializer, it) } }.getOrNull().orEmpty()

    private fun write(list: List<StoredIntent>) {
        runCatching { store.put(STORAGE_KEY, json.encodeToString(serializer, list)) }
    }

    /** Capture [raw]; idempotent by content nonce. */
    fun capture(raw: String): StoredIntent {
        val nonce = hashNonce(raw)
        val list = all()
        list.firstOrNull { it.nonce == nonce }?.let { return it }
        return StoredIntent(nonce, raw, now()).also { write(list + it) }
    }

    fun ack(nonce: String) = write(all().filter { it.nonce != nonce })

    companion object {
        const val STORAGE_KEY = "calimero.platform.pendingIntents"
    }
}

/**
 * The process-wide invitation capture — mero-react's `primeInvitationCapture`, `onInvitation`,
 * `peekInvitation` and `subscribeInvitation`. Use [shared] (primed with [primeInvitationCapture])
 * or construct one for a test.
 *
 * @param appKey namespaces the auto-join attempt ledger, so two apps sharing a store cannot
 *   spend each other's budget.
 * @param slugs this app's deep-link slugs (its package id). Given them, a `join` link addressed
 *   to a different app is acked and dropped instead of offered here.
 */
class InvitationCapture(
    private val store: KeyValueStore = MemoryKeyValueStore(),
    appKey: String? = null,
    slugs: Collection<String> = emptyList(),
) {
    private val lock = Any()
    private val attemptsKey = "${appKey ?: "calimero"}:invitation-attempts"
    private val ownSlugs = slugs.toSet()
    private val intents = PendingIntentStore(store)
    private val delivered = HashSet<String>()
    private val listeners = LinkedHashSet<(CapturedInvitation) -> Unit>()
    private val watchers = LinkedHashSet<() -> Unit>()
    private var buffered: CapturedInvitation? = null
    private val _current = MutableStateFlow<CapturedInvitation?>(null)

    /** The captured invitation right now (null when none, or once acked) — for UI that only needs to know. */
    val current: StateFlow<CapturedInvitation?> = _current.asStateFlow()

    init {
        // Replay what earlier launches captured and never acked.
        intents.all().forEach(::offer)
    }

    /**
     * Hand over a link the app received — a cold open's `intent.data` or a warm one's. A link
     * with no query and no path is ignored.
     */
    fun capture(raw: String?) {
        if (raw.isNullOrBlank() || !hasIntentSignal(raw)) return
        offer(intents.capture(raw))
    }

    /** [capture] the data of [intent] (a VIEW intent's URI). */
    fun capture(intent: Intent?) = capture(intent?.dataString)

    /** Subscribe to invitations. Replays one already captured, so a late subscriber still sees it. */
    fun onInvitation(listener: (CapturedInvitation) -> Unit): () -> Unit {
        val pending =
            synchronized(lock) {
                listeners += listener
                buffered.also { buffered = null }
            }
        pending?.let(listener)
        return { synchronized(lock) { listeners -= listener } }
    }

    /** The captured invitation, without consuming it or subscribing. */
    fun peekInvitation(): CapturedInvitation? = _current.value

    /** Be told when [peekInvitation] would answer differently: one arrives, or it is acked. */
    fun subscribeInvitation(watcher: () -> Unit): () -> Unit {
        synchronized(lock) { watchers += watcher }
        return { synchronized(lock) { watchers -= watcher } }
    }

    private fun offer(stored: StoredIntent) {
        synchronized(lock) { if (!delivered.add(stored.nonce)) return }
        handle(stored)
    }

    private fun handle(stored: StoredIntent) {
        val intent = parseIntent(stored.raw)
        val ack = { intents.ack(stored.nonce) }
        // `join`, or no action at all (the app's own link with the parameter appended). An app
        // page carrying an invitation (`/teams/t?invitation=…`) is still ours: the parameter decides.
        if (intent.action != null && intent.action != JOIN_ACTION && intent.params[INVITATION_PARAM] == null) return
        // A join addressed to another app: acked so it does not replay, never offered.
        if (intent.action == JOIN_ACTION && intent.slug != null && ownSlugs.isNotEmpty() && intent.slug !in ownSlugs) {
            ack()
            return
        }
        val token =
            invitationFromRaw(stored.raw) ?: run {
                ack()
                return
            }
        lateinit var captured: CapturedInvitation
        captured =
            CapturedInvitation(token, intent.params + (INVITATION_PARAM to token), spendAttempt(token)) {
                forgetAttempts(token)
                synchronized(lock) { if (buffered === captured) buffered = null }
                ack()
                if (_current.value === captured) {
                    _current.value = null
                    notifyWatchers()
                }
            }
        deliver(captured)
    }

    private fun deliver(invitation: CapturedInvitation) {
        _current.value = invitation
        notifyWatchers()
        val targets =
            synchronized(lock) {
                if (listeners.isEmpty()) {
                    buffered = invitation
                    return@synchronized emptyList()
                }
                listeners.toList()
            }
        targets.forEach { it(invitation) }
    }

    private fun notifyWatchers() = synchronized(lock) { watchers.toList() }.forEach { runCatching { it() } }

    private fun readAttempts(): MutableMap<String, Int> =
        runCatching { store.get(attemptsKey)?.let { json.decodeFromString(attemptsSerializer, it) } }.getOrNull().orEmpty().toMutableMap()

    private fun writeAttempts(attempts: Map<String, Int>) {
        runCatching { store.put(attemptsKey, json.encodeToString(attemptsSerializer, attempts)) }
    }

    /** Count this launch's attempt; whether it is still within the cap. */
    private fun spendAttempt(token: String): Boolean {
        val attempts = readAttempts()
        val spent = attempts[token] ?: 0
        if (spent >= MAX_AUTO_JOIN_ATTEMPTS) return false
        attempts[token] = spent + 1
        writeAttempts(attempts)
        return true
    }

    private fun forgetAttempts(token: String) {
        val attempts = readAttempts()
        if (attempts.remove(token) != null) writeAttempts(attempts)
    }

    companion object {
        @Volatile private var instance: InvitationCapture? = null

        /** The process-wide capture; an in-memory one until [primeInvitationCapture] runs. */
        val shared: InvitationCapture
            get() = instance ?: synchronized(this) { instance ?: InvitationCapture().also { instance = it } }

        /** Install [capture] as [shared] (what [primeInvitationCapture] does). */
        fun install(capture: InvitationCapture) {
            instance = capture
        }

        /** Test seam: forget the shared capture. */
        fun resetForTests() {
            instance = null
        }
    }
}

/**
 * Start capturing invitation links: call once, early (`Application.onCreate` or the launcher
 * Activity's `onCreate`, before setting content), then hand over the launch Intent with
 * `InvitationCapture.shared.capture(intent)` — and every later one from `onNewIntent`.
 * Pending intents and the attempt ledger persist in the `mero_invitations` SharedPreferences.
 */
fun primeInvitationCapture(
    context: Context,
    appKey: String? = null,
    slugs: Collection<String> = emptyList(),
): InvitationCapture =
    InvitationCapture(SharedPreferencesStore(context, "mero_invitations"), appKey, slugs).also(InvitationCapture::install)

/** [InvitationCapture.onInvitation] on the shared capture. */
fun onInvitation(listener: (CapturedInvitation) -> Unit): () -> Unit = InvitationCapture.shared.onInvitation(listener)

/** [InvitationCapture.peekInvitation] on the shared capture. */
fun peekInvitation(): CapturedInvitation? = InvitationCapture.shared.peekInvitation()

/** [InvitationCapture.subscribeInvitation] on the shared capture. */
fun subscribeInvitation(watcher: () -> Unit): () -> Unit = InvitationCapture.shared.subscribeInvitation(watcher)

private val json = Json { ignoreUnknownKeys = true }
private val attemptsSerializer = MapSerializer(String.serializer(), Int.serializer())
private val BARE_SCHEME = Regex("^([a-z][a-z0-9+.-]*):/*$", RegexOption.IGNORE_CASE)
private val SCHEME = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)
private val SCHEME_AND_AUTHORITY = Regex("^[a-z][a-z0-9+.-]*://[^/?#]*", RegexOption.IGNORE_CASE)
private const val FNV_OFFSET = 0x811c9dc5.toInt()
private const val FNV_PRIME = 0x01000193
private const val HEX = 16
private const val NONCE_LENGTH = 8

private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

/** `application/x-www-form-urlencoded`, as URLSearchParams writes it (space is `+`). */
private fun formEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

/** A path segment, as encodeURIComponent writes it. */
private fun pathEncode(s: String): String =
    URLEncoder
        .encode(s, "UTF-8")
        .replace("+", "%20")
        .replace("%21", "!")
        .replace("%27", "'")
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%7E", "~")

private fun parseQuery(query: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        val key = decode(pair.substringBefore('='))
        if (key !in out) out[key] = decode(pair.substringAfter('=', ""))
    }
    return out
}
