package com.calimero.mero.compose.state

import com.calimero.mero.admin.SetMemberCapabilitiesRequest
import com.calimero.mero.compose.MeroSession
import com.calimero.mero.sse.ContextEvent
import com.calimero.mero.sse.GroupEvent
import com.calimero.mero.sse.NodeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Contract calls against one context — mero-react `useExecute`. Through whichever transport the
 * session has: a node's JSON-RPC, or a warrant through the account's relay. With nothing to send
 * through it reports [NotConnectedException] — for an account with no relay yet, the one that
 * says an invitation is the cure ([NotConnectedException.NO_RELAY]).
 */
class ExecuteState(
    private val sessions: StateFlow<MeroSession>,
    private val contextId: String?,
) {
    private val mutation = AsyncMutation()

    /** In flight, the last failure. */
    val state: StateFlow<MutationState> = mutation.state

    /** Run [method] with [args]; the method's return value, or null on failure (see [state]). */
    suspend fun execute(
        method: String,
        args: JsonObject = JsonObject(emptyMap()),
    ): JsonElement? {
        val session = sessions.value
        val rpc = session.rpc
        if (rpc == null || contextId == null) {
            mutation.fail(if (rpc == null && session.isDelegated) NotConnectedException.NO_RELAY else NotConnectedException())
            return null
        }
        return mutation.run { rpc.execute(contextId, method, args) }
    }
}

/**
 * Live events for some contexts and/or groups — mero-react `useSubscription`. Opens the
 * session's stream, subscribes the ids, and hands [onEvent] each event of those ids; follows the
 * session (a new client re-subscribes). Stop with [close].
 */
class SubscriptionState(
    sessions: StateFlow<MeroSession>,
    scope: CoroutineScope,
    private val contextIds: List<String> = emptyList(),
    private val groupIds: List<String> = emptyList(),
    private val onEvent: (NodeEvent) -> Unit,
) : AutoCloseable {
    private val job: Job =
        scope.launch {
            sessions.map { it.events }.distinctUntilChanged().collectLatest { events ->
                if (events == null || (contextIds.isEmpty() && groupIds.isEmpty())) return@collectLatest
                coroutineScope {
                    launch(start = CoroutineStart.UNDISPATCHED) { events.events.filter(::matches).collect { onEvent(it) } }
                    events.connect()
                    runCatching { events.subscribe(contextIds, groupIds) }
                    awaitCancellation()
                }
            }
        }

    private fun matches(event: NodeEvent): Boolean =
        when (event) {
            is ContextEvent -> event.contextId in contextIds
            is GroupEvent -> event.groupId in groupIds
        }

    override fun close() = job.cancel()
}

/** mero-react `ContextDiscoveryOptions`. */
data class ContextDiscoveryOptions(
    val applicationId: String,
    /** Contexts already known: discovery looks for one that is not among them. */
    val knownContextIds: Set<String> = emptySet(),
    /** An alias to look up first, each round. */
    val targetAlias: String? = null,
    val pollIntervalMs: Long = 1_000,
    val timeoutMs: Long = 30_000,
)

/**
 * Wait for a new context of an application to appear — mero-react `useContextDiscovery`: each
 * round tries [ContextDiscoveryOptions.targetAlias], then the application's contexts, until one
 * that is not already known turns up or the timeout passes.
 */
class ContextDiscoveryState(
    private val sessions: StateFlow<MeroSession>,
    private val options: ContextDiscoveryOptions,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _context = MutableStateFlow<ApplicationContextRecord?>(null)
    val context: StateFlow<ApplicationContextRecord?> = _context.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    /** Look until found or timed out; the context found, or null (see [error]). */
    suspend fun discover(): ApplicationContextRecord? {
        val admin =
            sessions.value.admin ?: run {
                _error.value = NotConnectedException()
                return null
            }
        val deadline = clock() + options.timeoutMs
        _loading.value = true
        _error.value = null
        try {
            while (clock() <= deadline) {
                val alias = options.targetAlias?.let { admin.lookupContextAlias(it).value }
                val found =
                    alias?.takeIf { it !in options.knownContextIds }?.let { ApplicationContextRecord(it, options.applicationId) }
                        ?: admin
                            .getContextsForApplication(options.applicationId)
                            .contexts
                            .firstOrNull { it.id !in options.knownContextIds }
                            ?.let { ApplicationContextRecord(it.id, it.applicationId) }
                if (found != null) return found.also { _context.value = it }
                if (clock() + options.pollIntervalMs > deadline) break
                delay(options.pollIntervalMs)
            }
            _context.value = null
            _error.value = IllegalStateException("Timed out discovering a context for application ${options.applicationId}")
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _context.value = null
            _error.value = e
            return null
        } finally {
            _loading.value = false
        }
    }

    fun reset() {
        _context.value = null
        _error.value = null
        _loading.value = false
    }
}

/**
 * A member's capability mask, readable and settable — mero-react `useGroupCapabilities`. Takes
 * the member's ACCOUNT (64 hex), as [MeroReads.groupMembers] rows are keyed, not a signing key.
 * A read+write hybrid: [setCapabilities] shares the read's state, and a write's value wins over
 * any read still in flight.
 */
class GroupCapabilitiesState(
    private val sessions: StateFlow<MeroSession>,
    scope: CoroutineScope,
    private val groupId: String?,
    private val memberId: String?,
) : AutoCloseable {
    private val _state = MutableStateFlow(ResourceState<Int?>(null))
    val state: StateFlow<ResourceState<Int?>> = _state.asStateFlow()
    val capabilities: Int? get() = _state.value.data
    private val sequence = AtomicLong()

    private val binding: Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.map { it.admin }.distinctUntilChanged().collect {
                sequence.incrementAndGet()
                _state.value = ResourceState(null)
                launch { refetch() }
            }
        }

    /** Read the mask again; the value read, or null. */
    suspend fun refetch(): Int? {
        val seq = sequence.incrementAndGet()
        val admin = sessions.value.admin
        if (admin == null || groupId == null || memberId == null) {
            if (seq == sequence.get()) _state.value = ResourceState(null)
            return null
        }
        _state.value = _state.value.copy(loading = true, error = null)
        return try {
            admin.getMemberCapabilities(groupId, memberId).capabilities.also { caps ->
                if (seq == sequence.get()) _state.value = ResourceState(caps)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (seq == sequence.get()) _state.value = _state.value.copy(loading = false, error = e)
            null
        }
    }

    /** Set the mask; the value set, or null on failure. */
    suspend fun setCapabilities(capabilities: Int): Int? {
        val admin = sessions.value.admin ?: return null
        if (groupId == null || memberId == null) return null
        _state.value = _state.value.copy(loading = true, error = null)
        return try {
            admin.setMemberCapabilities(groupId, memberId, SetMemberCapabilitiesRequest(capabilities))
            sequence.incrementAndGet()
            _state.value = ResourceState(capabilities)
            capabilities
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(loading = false, error = e)
            null
        }
    }

    override fun close() = binding.cancel()
}
