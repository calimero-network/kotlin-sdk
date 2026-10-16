package com.calimero.mero.compose.state

import com.calimero.mero.admin.AdminSurface
import com.calimero.mero.compose.MeroSession
import com.calimero.mero.ephemeral.Codec
import com.calimero.mero.ephemeral.EphemeralEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Observe and publish ephemeral presence (cursors, typing, online) for one context — mero-react
 * `useEphemeral`.
 *
 * One read path: the subscription. Subscribing makes the node replay the context's current
 * presence as ordinary entries (each carrying `ageMs`) before the live deltas start, so seeding
 * and updating share one shape and a reconnect re-seeds itself. A replay is authoritative: once a
 * replay burst ends ([REPLAY_RECONCILE_MS] after its last entry) [peers] is reconciled down to
 * what it contained, so a peer swept while the stream was down does not survive the reconnect.
 * Freshness ([ageOf]) is local clock deltas only. There is no client-side TTL: the node sweeps
 * at 7 s and re-publishes your slot every 2.5 s.
 *
 * Your own echo is filtered out by this node's context identity (`identities-owned`, resolved
 * through the connection's own admin) unless [includeSelf].
 *
 * One holder per context: a new context is a new holder, so nothing — peers, a queued publish,
 * a latched error — crosses from one to the next.
 */
@Suppress("LongParameterList")
class EphemeralState<T>(
    private val sessions: StateFlow<MeroSession>,
    scope: CoroutineScope,
    private val contextId: String?,
    private val codec: Codec<T>,
    /** The base value [setPresence]'s first update starts from. */
    initial: T? = null,
    /** Minimum ms between publishes. Trailing edge: the latest value wins. `0` publishes at once. */
    private val throttleMs: Long = DEFAULT_THROTTLE_MS,
    /** Include your own echoed presence in [peers]. */
    private val includeSelf: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private enum class Source { IDENTITY, PUBLISH }

    private val own = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + own)
    private val lock = Any()

    private val _peers = MutableStateFlow<Map<String, T>>(emptyMap())

    /** Everyone present, by author (the node's context member key), without you unless [includeSelf]. */
    val peers: StateFlow<Map<String, T>> = _peers.asStateFlow()

    private val latched = MutableStateFlow<Pair<Source, Throwable>?>(null)

    /**
     * Derived, never accumulated: "this connection carries no presence" is a standing condition,
     * recomputed from the session; a failed publish or identity lookup is latched. An unresolved
     * identity matters only for self-filtering, so with [includeSelf] it is not reported.
     */
    val error: StateFlow<Throwable?> =
        combine(sessions, latched) { session, latch ->
            surfaceError(session) ?: latch?.takeUnless { it.first == Source.IDENTITY && includeSelf }?.second
        }.stateIn(ownScope, SharingStarted.Eagerly, null)

    private val receivedAt = ConcurrentHashMap<String, Long>()

    @Volatile private var self: String? = null
    private var lastLocal: T? = initial
    private var pending: T? = null
    private var timer: Job? = null

    // Seeded to now, so a call right after creation is throttled like the rest of a burst.
    private var lastSentAt = clock()
    private var replaySeen: MutableSet<String>? = null
    private var replayTimer: Job? = null

    private val reader: Job = ownScope.launch { sessions.collectLatest { follow(it) } }

    /** How long ago [author]'s current entry was current, in ms, or null when not present. */
    fun ageOf(author: String): Long? = receivedAt[author]?.let { clock() - it }

    /**
     * Publish your own presence: [update] gets the last value set here (starting from `initial`)
     * and returns the next one — `setPresence { it.copy(cursor = c) }` — since presence is one slot
     * per author and a bare replace would drop what you did not mention. Throttled on the
     * trailing edge: rapid calls collapse into one publish carrying the newest value.
     */
    fun setPresence(update: (T?) -> T) {
        val next: T
        synchronized(lock) {
            next = update(lastLocal)
            lastLocal = next
            if (throttleMs > 0) {
                pending = next
                if (timer?.isActive == true) return
                val wait = (throttleMs - (clock() - lastSentAt)).coerceAtLeast(0)
                timer =
                    ownScope.launch {
                        delay(wait)
                        val queued = synchronized(lock) { pending.also { pending = null } }
                        if (queued != null) publish(queued)
                    }
                return
            }
        }
        publish(next)
    }

    /** [setPresence] with a whole value. */
    fun setPresence(value: T) = setPresence { value }

    private fun publish(value: T) {
        val presence = sessions.value.presence ?: return
        val ctx = contextId ?: return
        synchronized(lock) { lastSentAt = clock() }
        ownScope.launch {
            try {
                presence.set(ctx, value, codec)
                clear(Source.PUBLISH)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                latched.value = Source.PUBLISH to e
            }
        }
    }

    private suspend fun follow(session: MeroSession) =
        coroutineScope {
            self = null
            val ctx = contextId ?: return@coroutineScope
            session.rawAdmin?.let { admin -> launch { resolveSelf(admin, ctx) } }
            val presence = session.presence ?: return@coroutineScope
            try {
                presence.subscribe(ctx, codec).collect { onEntry(it) }
            } finally {
                synchronized(lock) {
                    replayTimer?.cancel()
                    replaySeen = null
                }
            }
        }

    private suspend fun resolveSelf(
        admin: AdminSurface,
        ctx: String,
    ) {
        try {
            val identity = admin.getContextIdentitiesOwned(ctx).identities.firstOrNull()
            self = identity
            if (identity == null) {
                latched.value = Source.IDENTITY to IllegalStateException(COULD_NOT_RESOLVE_IDENTITY)
                return
            }
            clear(Source.IDENTITY)
            // The replay may have seeded our own entry before we knew who we are.
            if (!includeSelf) {
                receivedAt.remove(identity)
                _peers.update { it - identity }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            latched.value = Source.IDENTITY to e
        }
    }

    private fun onEntry(entry: EphemeralEntry<T>) {
        val author = entry.author
        if (entry.ageMs != null) {
            // A replayed entry: (re)open the reconcile window and debounce its close.
            synchronized(lock) {
                if (replaySeen == null) replaySeen = mutableSetOf()
                replayTimer?.cancel()
                replayTimer =
                    ownScope.launch {
                        delay(REPLAY_RECONCILE_MS)
                        closeReplayWindow()
                    }
            }
        }
        val state = entry.state
        if (entry.removed || state == null) {
            synchronized(lock) { replaySeen?.remove(author) }
            receivedAt.remove(author)
            _peers.update { it - author }
            return
        }
        if (!includeSelf && author == self) return
        synchronized(lock) { replaySeen?.add(author) }
        // `ageMs` absent: current as of now; present: as of now - ageMs (0 is a real value).
        receivedAt[author] = clock() - (entry.ageMs ?: 0)
        _peers.update { it + (author to state) }
    }

    private fun closeReplayWindow() {
        val seen =
            synchronized(lock) {
                replayTimer = null
                replaySeen.also { replaySeen = null }
            } ?: return
        _peers.update { current ->
            val gone = current.keys - seen
            gone.forEach { receivedAt.remove(it) }
            if (gone.isEmpty()) current else current - gone
        }
    }

    private fun clear(source: Source) {
        latched.update { if (it?.first == source) null else it }
    }

    private fun surfaceError(session: MeroSession): Throwable? =
        if (session.admin != null && session.presence == null) IllegalStateException(NO_PRESENCE_SURFACE) else null

    override fun close() {
        own.cancel()
        reader.cancel()
    }

    companion object {
        /** mero-react's default `throttleMs`. */
        const val DEFAULT_THROTTLE_MS = 30L

        /** How long after the last replayed entry the replay window stays open. */
        const val REPLAY_RECONCILE_MS = 50L

        const val NO_PRESENCE_SURFACE =
            "This connection cannot carry presence: an account's relay has to be attested (its node key learned) first."

        const val COULD_NOT_RESOLVE_IDENTITY =
            "Could not resolve a local context identity, so your own presence cannot be filtered out. " +
                "Pass includeSelf = true to accept this."
    }
}
