package com.calimero.mero.compose.state

import com.calimero.mero.admin.AdminSurface
import com.calimero.mero.compose.MeroSession
import com.calimero.mero.http.ClassifiedError
import com.calimero.mero.http.classifyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** What a read shows: its [data], whether a request is in flight, and the last failure. */
data class ResourceState<out T>(
    val data: T,
    val loading: Boolean = false,
    val error: Throwable? = null,
)

/**
 * One read with a latest-request-wins guard — mero-react's `useAsyncResource`, the shape every
 * read state holder is built on.
 *
 * - A null fetcher is a disabled read (a missing input): [data] goes back to the initial value
 *   and nothing is fetched.
 * - [setFetcher] (a new input, or a new session through [bind]) drops the previous input's data
 *   and error at once and invalidates any request in flight, so a slow earlier answer can never
 *   overwrite a newer one.
 *
 * Close it when its owner goes away ([close] stops the session binding).
 */
open class AsyncResource<T>(
    protected val scope: CoroutineScope,
    private val initial: T,
) : AutoCloseable {
    private val _state = MutableStateFlow(ResourceState(initial))

    /** The read, as a flow a composable collects. */
    val state: StateFlow<ResourceState<T>> = _state.asStateFlow()

    val data: T get() = _state.value.data
    val loading: Boolean get() = _state.value.loading
    val error: Throwable? get() = _state.value.error

    private val sequence = AtomicLong()

    @Volatile private var fetcher: (suspend () -> T)? = null
    private var binding: Job? = null

    /** Whether a fetcher is set (the read is enabled). */
    val isEnabled: Boolean get() = fetcher != null

    /** Replace what is read: reset to the initial value, invalidate any request in flight, and fetch again. */
    fun setFetcher(fetcher: (suspend () -> T)?) {
        this.fetcher = fetcher
        sequence.incrementAndGet()
        _state.value = ResourceState(initial)
        start()
    }

    /** Fetch again in the background. */
    fun start(): Job = scope.launch { refetch() }

    /** Fetch again, and return once the answer (or the failure) is published. Superseded, it publishes nothing. */
    open suspend fun refetch() {
        val seq = sequence.incrementAndGet()
        val fn = fetcher
        if (fn == null) {
            if (seq == sequence.get()) _state.value = ResourceState(initial)
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        try {
            val value = fn()
            if (seq == sequence.get()) _state.update { it.copy(data = value) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (seq == sequence.get()) _state.update { it.copy(error = e) }
        } finally {
            if (seq == sequence.get()) _state.update { it.copy(loading = false) }
        }
    }

    /** Set [data] directly (a write that knows the new value), invalidating any read in flight. */
    fun override(value: T) {
        sequence.incrementAndGet()
        _state.value = ResourceState(value)
    }

    /** Invalidate any read in flight without changing what is shown. */
    protected fun invalidate() {
        sequence.incrementAndGet()
    }

    /**
     * Re-derive the fetcher from [sessions] now and on every session change — mero-react's
     * dependency on `admin`: a sign-in, a sign-out or a new relay re-reads.
     */
    fun bind(
        sessions: StateFlow<MeroSession>,
        derive: (MeroSession) -> (suspend () -> T)?,
    ): AsyncResource<T> {
        binding?.cancel()
        binding = scope.launch(start = CoroutineStart.UNDISPATCHED) { sessions.collect { setFetcher(derive(it)) } }
        return this
    }

    override fun close() {
        binding?.cancel()
        binding = null
    }
}

/** What a write shows: in flight, the last failure, and what kind of refusal it was. */
data class MutationState(
    val loading: Boolean = false,
    val error: Throwable? = null,
    /** What kind of refusal [error] was, and whether trying again later could work. */
    val failure: ClassifiedError? = null,
)

/**
 * One write — mero-react's `useAsyncMutation`, which every write state holder is built on.
 * [run] resolves `null` on failure (after recording it) unless [throwOnError] is set, in which
 * case it rethrows the original error (mero-react `MutationOptions.throwOnError`).
 */
open class AsyncMutation(
    val throwOnError: Boolean = false,
) {
    private val _state = MutableStateFlow(MutationState())
    val state: StateFlow<MutationState> = _state.asStateFlow()

    val loading: Boolean get() = _state.value.loading
    val error: Throwable? get() = _state.value.error
    val failure: ClassifiedError? get() = _state.value.failure

    /** Run [action], recording its failure. */
    suspend fun <T> run(action: suspend () -> T): T? {
        _state.value = MutationState(loading = true)
        try {
            return action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = MutationState(loading = true, error = e, failure = classifyError(e))
            if (throwOnError) throw e
            return null
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    /** Record a refusal that never reached the network ("not connected"). */
    fun fail(error: Throwable) {
        _state.value = MutationState(error = error, failure = classifyError(error))
    }
}

/**
 * A write against the session's admin API: resolves `null` with nothing to send it through,
 * as mero-react's write hooks do when `admin` is null.
 */
open class AdminMutation(
    protected val sessions: StateFlow<MeroSession>,
    throwOnError: Boolean = false,
) : AsyncMutation(throwOnError) {
    /** Run [block] against the current session's admin, or return null when there is none. */
    protected suspend fun <T> withAdmin(block: suspend (AdminSurface) -> T): T? {
        val admin = sessions.value.admin ?: return null
        return run { block(admin) }
    }
}

/** The error a state holder reports with no client to send through. */
class NotConnectedException(
    message: String = "Not connected",
) : IllegalStateException(message) {
    companion object {
        /** The account is signed in but no relay serves it yet: an invitation is the cure, not a new login. */
        val NO_RELAY =
            NotConnectedException(
                "No relay for this account yet, so there is nowhere to send this write. Redeeming an invitation admits the " +
                    "account to a namespace and gives it a relay; reads and events resume with it.",
            )
    }
}
