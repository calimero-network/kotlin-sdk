package com.calimero.mero.compose.invitations

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Base window for the gate: a governance op plus the namespace's own state typically land in
 * under a second on a healthy mesh; past this the gate gives up ONLY if nothing is in flight.
 */
const val JOIN_SYNC_WATCHDOG_MS = 30_000L

/** Hard ceiling: a genuinely stuck sync can never pin the UI forever, even while it reports activity. */
const val JOIN_SYNC_MAX_MS = 120_000L

/** How often the watchdog re-checks activity past the base window. */
const val JOIN_SYNC_POLL_MS = 2_000L

/**
 * Gate the window between "you joined" and "your data is here" — mero-react `useJoinSync`.
 * Without it a just-joined namespace renders its ordinary empty state, and a person cannot tell an
 * empty workspace from one still arriving.
 *
 * Feed it with [update] whenever its inputs change (the `rememberJoinSync` composable does):
 * - `namespaceId` — the namespace on screen, or null;
 * - `settled` — the app's own "has this namespace's data arrived?", INCLUDING an empty answer
 *   ("no documents yet" is settled);
 * - `active` — optional: a sync is making progress, which holds the gate open past
 *   [JOIN_SYNC_WATCHDOG_MS], up to [JOIN_SYNC_MAX_MS]. A stuck sync must report false.
 */
class JoinSync(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private data class Inputs(
        val namespaceId: String?,
        val settled: Boolean,
    )

    private val inputs = MutableStateFlow(Inputs(null, false))

    @Volatile private var active: Boolean? = null
    private val firstSeen = HashMap<String, Long>()
    private val _isSyncing = MutableStateFlow(false)

    /** Show the "syncing from peers" affordance: a namespace just joined, its data not here, within the watchdog. */
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private var watchdog: Job? = null
    private var watched: Triple<String?, Boolean, Boolean>? = null

    private val job: Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(inputs, JustJoinedNamespaces.namespaces) { i, _ -> i }.collect { recompute(it) }
        }

    /** The current inputs (see the class docs). */
    fun update(
        namespaceId: String?,
        settled: Boolean,
        active: Boolean? = null,
    ) {
        // Read through a field inside the watchdog, so a chatty sync cannot postpone the deadline.
        this.active = active
        inputs.value = Inputs(namespaceId, settled)
    }

    /** Drop the gate now — for a "show me anyway" control. */
    fun dismiss() {
        inputs.value.namespaceId?.let(JustJoinedNamespaces::clear)
    }

    private fun recompute(i: Inputs) {
        val ns = i.namespaceId
        val flagged = JustJoinedNamespaces.isJustJoined(ns)
        if (ns != null) {
            synchronized(firstSeen) { if (flagged) firstSeen.getOrPut(ns) { clock() } else firstSeen.remove(ns) }
        }
        // Settled is the happy path: stop gating and forget the namespace.
        if (ns != null && i.settled && flagged) {
            JustJoinedNamespaces.clear(ns)
            return
        }
        _isSyncing.value = flagged && !i.settled
        val key = Triple(ns, flagged, i.settled)
        if (key == watched) return
        watched = key
        watchdog?.cancel()
        watchdog = if (ns != null && flagged && !i.settled) scope.launch { watch(ns) } else null
    }

    private suspend fun watch(ns: String) {
        val since = synchronized(firstSeen) { firstSeen[ns] } ?: clock()
        delay(JOIN_SYNC_WATCHDOG_MS)
        while (true) {
            val elapsed = clock() - since
            if (elapsed >= JOIN_SYNC_MAX_MS || (elapsed >= JOIN_SYNC_WATCHDOG_MS && active != true)) {
                JustJoinedNamespaces.clear(ns)
                return
            }
            delay(JOIN_SYNC_POLL_MS)
        }
    }

    override fun close() {
        job.cancel()
        watchdog?.cancel()
    }
}
