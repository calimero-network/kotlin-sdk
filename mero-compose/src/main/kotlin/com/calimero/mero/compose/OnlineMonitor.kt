package com.calimero.mero.compose

import com.calimero.mero.http.AuthRevokedException
import com.calimero.mero.http.HttpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** What a failed event stream means for the session — mero-react `MeroProvider`'s SSE error handler. */
enum class StreamVerdict {
    /** Offline, and nothing to do: the stream reconnects by itself (or a new token would not help). */
    OFFLINE,

    /** A 401: the access token may only have expired. Ask the node; log out only if it is really dead. */
    RECOVER,

    /** The session cannot come back: a revoked family, or a token that can never subscribe. */
    LOGOUT,
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403

/**
 * Classify a stream failure (mero-react 9.x's three cases):
 *
 * 1. The token family is gone ([AuthRevokedException]: 403 `token_revoked`, 401 `token_reuse`) —
 *    nothing will reopen the stream and there is no refresh token left: [StreamVerdict.LOGOUT].
 * 2. `403 permission_denied`: the token was minted without `context:subscribe`, and a refresh
 *    re-issues the same grants — only a fresh login fixes it: [StreamVerdict.LOGOUT].
 * 3. Any other 403 is core refusing this caller for a reason a new token would not change: offline.
 *
 * A 401 is not proof the session is over — the stream authenticates only at connect, so the
 * first reconnect after the access token ages out answers 401: [StreamVerdict.RECOVER].
 * A delegated stream is authenticated by a request proof per connect, so nothing here applies
 * to it: always [StreamVerdict.OFFLINE].
 */
fun streamFailureVerdict(
    error: Throwable,
    delegated: Boolean,
): StreamVerdict {
    if (delegated) return StreamVerdict.OFFLINE
    if (error is AuthRevokedException) return StreamVerdict.LOGOUT
    if (error is HttpException && error.status == HTTP_FORBIDDEN) {
        return if (error.authError == "permission_denied") StreamVerdict.LOGOUT else StreamVerdict.OFFLINE
    }
    val status = (error as? HttpException)?.status
    return if (status == HTTP_UNAUTHORIZED || error.message.orEmpty().contains("401")) StreamVerdict.RECOVER else StreamVerdict.OFFLINE
}

/**
 * Online/offline from the event stream — no polling: a connect frame is online, a failure is
 * offline, and a failure that ends the session ([streamFailureVerdict]) is acted on. One
 * recovery at a time, so a burst of reconnect failures cannot stack refreshes over a
 * single-use refresh token.
 */
internal class OnlineMonitor(
    private val events: MeroEvents,
    private val delegated: Boolean,
    private val setOnline: (Boolean) -> Unit,
    /** Whether the session is still good (mero-react `checkAuth`), renewing it if it can. */
    private val recover: suspend () -> Boolean,
    private val logout: () -> Unit,
) {
    private val recovering = AtomicBoolean(false)

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            // Listening before connecting, so the first connect frame is not missed. A terminal
            // refusal arrives on `errors` too (and also sits in `failure`).
            launch(start = CoroutineStart.UNDISPATCHED) { events.connects.collect { setOnline(true) } }
            launch(start = CoroutineStart.UNDISPATCHED) { events.errors.collect { onFailure(it) } }
            events.connect()
        }

    private suspend fun onFailure(error: Throwable) {
        setOnline(false)
        when (streamFailureVerdict(error, delegated)) {
            StreamVerdict.OFFLINE -> Unit
            StreamVerdict.LOGOUT -> logout()
            StreamVerdict.RECOVER -> {
                if (!recovering.compareAndSet(false, true)) return
                try {
                    if (recover()) events.connect() else logout()
                } finally {
                    recovering.set(false)
                }
            }
        }
    }
}
