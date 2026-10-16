package com.calimero.mero.ws

import com.calimero.mero.ExperimentalMeroApi
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.NetworkException
import com.calimero.mero.sse.AppVersionChangedEvent
import com.calimero.mero.sse.ContextEvent
import com.calimero.mero.sse.GroupEvent
import com.calimero.mero.sse.GroupMembershipEvent
import com.calimero.mero.sse.GroupMigrationEvent
import com.calimero.mero.sse.NodeEvent
import com.calimero.mero.sse.asAppVersionChanged
import com.calimero.mero.sse.asMembershipEvent
import com.calimero.mero.sse.asMigrationEvent
import com.calimero.mero.sse.terminalFailureOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * WebSocket event client — port of mero-js `WsClient` (`src/events/ws.ts`), also
 * **experimental** there. SSE ([com.calimero.mero.sse.SseConnection], `Mero.sse`) is the
 * recommended transport: a WebSocket message sent before the socket opens is dropped,
 * while an SSE subscription is a POST that either lands or fails visibly.
 *
 * Core serves `GET /ws` (crates/server/src/ws.rs). Messages are core's
 * `Request { id, method, params }` with `method` `subscribe` / `unsubscribe` and `params`
 * `{ contextIds?, groupIds? }` (`server/primitives/src/ws.rs`; each list only when
 * non-empty). Pushed events are `{ "id": null, "result": { contextId | groupId, type, data } }`,
 * the same `result` an SSE frame carries, delivered here as [ContextEvent] / [GroupEvent].
 *
 * The token goes in an `Authorization: Bearer` header. Core's auth layer reads that first
 * and falls back to `?token=` (crates/server/src/auth.rs), which mero-js uses only because
 * a browser `WebSocket` cannot set headers; a token in a URL ends up in logs.
 *
 * Reconnection backs off exponentially, `1 s · 2ⁿ` capped at 30 s, resetting on a
 * successful open — exactly mero-js's `scheduleReconnect`. Every remembered id is
 * re-subscribed on each open, after [connects] fires. Unlike a browser, OkHttp sees the
 * upgrade's HTTP status, so a `403` (or a `401` naming a dead token family) stops the
 * client instead of retrying a refused credential: [failure] is set and [connect] must be
 * called again, as with SSE.
 */
@ExperimentalMeroApi
class WsClient(
    baseUrl: String,
    private val token: suspend () -> String?,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** First reconnect delay; doubled per failed attempt (mero-js: 1000 ms). */
    val initialBackoffMs: Long = INITIAL_BACKOFF_MS,
    /** Ceiling of the reconnect delay (mero-js `MAX_BACKOFF_MS`, 30 000 ms). */
    val maxBackoffMs: Long = MAX_BACKOFF_MS,
) {
    private val base = baseUrl.trimEnd('/')
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val contextIds = LinkedHashSet<String>()
    private val groupIds = LinkedHashSet<String>()
    private var socket: WebSocket? = null
    private var connecting = false
    private var open = false
    private var closed = false
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private val frames = Channel<String>(Channel.UNLIMITED)

    private val _events = MutableSharedFlow<NodeEvent>(extraBufferCapacity = 256)
    private val _connects = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 16)
    private val _failure = MutableStateFlow<Throwable?>(null)

    /** Every context and group event received, hot. Collect before [connect]. */
    val events: SharedFlow<NodeEvent> = _events.asSharedFlow()

    /** Fires on each successful open, just before the re-subscription is sent. */
    val connects: SharedFlow<Unit> = _connects.asSharedFlow()

    /** Socket errors and failed attempts, as they happen. */
    val errors: SharedFlow<Throwable> = _errors.asSharedFlow()

    /** The refusal that stopped this client, or `null` while it runs or reconnects. */
    val failure: StateFlow<Throwable?> = _failure.asStateFlow()

    /** Whether the socket is open now. */
    val isOpen: Boolean get() = synchronized(lock) { open }

    /** Context events only. */
    val contextEvents: Flow<ContextEvent> get() = events.filterIsInstance<ContextEvent>()

    /** Group-keyed events only. */
    val groupEvents: Flow<GroupEvent> get() = events.filterIsInstance<GroupEvent>()

    /** Parsed `AppVersionChanged` events. */
    val appVersionChanges: Flow<AppVersionChangedEvent> get() = contextEvents.mapNotNull { it.asAppVersionChanged() }

    /** Typed group-migration events. */
    val migrationEvents: Flow<GroupMigrationEvent> get() = groupEvents.mapNotNull { it.asMigrationEvent() }

    /** Typed group-membership events. */
    val membershipEvents: Flow<GroupMembershipEvent> get() = groupEvents.mapNotNull { it.asMembershipEvent() }

    init {
        // One consumer, so events keep their order and a slow collector back-pressures
        // this coroutine rather than OkHttp's reader thread.
        scope.launch { for (frame in frames) handleMessage(frame) }
    }

    /** Open the socket; a no-op while one is connecting or open. Returns at once. */
    fun connect() {
        synchronized(lock) {
            if (socket != null || connecting) return
            connecting = true
            closed = false
            _failure.value = null
        }
        scope.launch { openSocket() }
    }

    private suspend fun openSocket() {
        val accessToken =
            try {
                token()
            } catch (e: Exception) {
                synchronized(lock) { connecting = false }
                _errors.tryEmit(e)
                scheduleReconnect()
                return
            }
        if (accessToken == null) {
            synchronized(lock) { connecting = false }
            _errors.tryEmit(MeroStateException("No authentication token available for WebSocket connection"))
            scheduleReconnect()
            return
        }
        val request =
            Request
                .Builder()
                .url("$base/ws")
                .header("Authorization", "Bearer $accessToken")
                .build()
        synchronized(lock) {
            connecting = false
            if (closed) return
            socket = client.newWebSocket(request, listener)
        }
    }

    private val listener =
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                synchronized(lock) {
                    if (webSocket !== socket) return
                    open = true
                    reconnectAttempt = 0
                }
                _connects.tryEmit(Unit)
                resubscribeAfterReconnect()
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                frames.trySend(text)
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                dropped(webSocket)
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) {
                val terminal = response?.let { terminalFailureOf(it) }
                if (terminal != null) {
                    synchronized(lock) {
                        if (webSocket !== socket) return
                        socket = null
                        open = false
                        closed = true
                        _failure.value = terminal
                    }
                    _errors.tryEmit(terminal)
                    return
                }
                if (synchronized(lock) { webSocket === socket && !closed }) {
                    _errors.tryEmit(NetworkException(t.message ?: "WebSocket error", t))
                }
                dropped(webSocket)
            }
        }

    private fun dropped(webSocket: WebSocket) {
        synchronized(lock) {
            if (webSocket !== socket) return
            socket = null
            open = false
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        synchronized(lock) {
            if (closed) return
            reconnectJob?.cancel()
            val wait = backoffMs(reconnectAttempt, initialBackoffMs, maxBackoffMs)
            reconnectAttempt++
            reconnectJob =
                scope.launch {
                    delay(wait)
                    connect()
                }
        }
    }

    private suspend fun handleMessage(raw: String) {
        val msg = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
        val result = msg["result"] as? JsonObject ?: return
        val kind = result.str("type") ?: "event"
        val contextId = result.str("contextId")
        val groupId = result.str("groupId")
        when {
            contextId != null -> _events.emit(ContextEvent(contextId = contextId, kind = kind, payload = result))
            groupId != null -> _events.emit(GroupEvent(groupId = groupId, kind = kind, payload = result))
        }
    }

    /**
     * Remember [contextIds] / [groupIds] and, when the socket is open, send a `subscribe`
     * naming exactly these. Before it opens they go out with the next open.
     */
    fun subscribe(
        contextIds: Collection<String> = emptyList(),
        groupIds: Collection<String> = emptyList(),
    ) {
        val isOpen =
            synchronized(lock) {
                this.contextIds.addAll(contextIds)
                this.groupIds.addAll(groupIds)
                open
            }
        if (isOpen) send(METHOD_SUBSCRIBE, contextIds, groupIds)
    }

    /** Forget [contextIds] / [groupIds] and, when open, send an `unsubscribe` naming them. */
    fun unsubscribe(
        contextIds: Collection<String> = emptyList(),
        groupIds: Collection<String> = emptyList(),
    ) {
        val isOpen =
            synchronized(lock) {
                this.contextIds.removeAll(contextIds.toSet())
                this.groupIds.removeAll(groupIds.toSet())
                open
            }
        if (isOpen) send(METHOD_UNSUBSCRIBE, contextIds, groupIds)
    }

    /** Whether [contextId] is among the ids this client keeps subscribed. */
    fun isSubscribed(contextId: String): Boolean = synchronized(lock) { contextId in contextIds }

    private fun resubscribeAfterReconnect() {
        val (contexts, groups) = synchronized(lock) { contextIds.toList() to groupIds.toList() }
        if (contexts.isEmpty() && groups.isEmpty()) return
        send(METHOD_SUBSCRIBE, contexts, groups)
    }

    private fun send(
        method: String,
        contexts: Collection<String>,
        groups: Collection<String>,
    ) {
        val ws = synchronized(lock) { socket.takeIf { open } } ?: return
        ws.send(json.encodeToString(JsonObject.serializer(), subscriptionMessage(method, contexts, groups)))
    }

    /** Stop for good: cancel any pending reconnect, close the socket, forget every id. */
    fun close() {
        val ws =
            synchronized(lock) {
                closed = true
                reconnectJob?.cancel()
                reconnectJob = null
                contextIds.clear()
                groupIds.clear()
                open = false
                socket.also { socket = null }
            }
        ws?.close(NORMAL_CLOSURE, null)
    }

    companion object {
        /** mero-js: the first retry waits one second. */
        const val INITIAL_BACKOFF_MS = 1_000L

        /** mero-js `WsClient.MAX_BACKOFF_MS`. */
        const val MAX_BACKOFF_MS = 30_000L

        private const val NORMAL_CLOSURE = 1000
        private const val METHOD_SUBSCRIBE = "subscribe"
        private const val METHOD_UNSUBSCRIBE = "unsubscribe"
        private const val MAX_SHIFT = 30

        /** `min(initial · 2^attempt, max)` — the delay before reconnect number `attempt + 1`. */
        fun backoffMs(
            attempt: Int,
            initial: Long = INITIAL_BACKOFF_MS,
            max: Long = MAX_BACKOFF_MS,
        ): Long {
            if (attempt >= MAX_SHIFT) return max
            return minOf(initial shl attempt, max)
        }

        /**
         * The message core's `/ws` takes: `{"id":null,"method":…,"params":{…}}`, each id list
         * only when non-empty (core `SubscribeRequest` is `deny_unknown_fields` with
         * `skip_serializing_if = Vec::is_empty`).
         */
        internal fun subscriptionMessage(
            method: String,
            contexts: Collection<String>,
            groups: Collection<String>,
        ): JsonObject =
            buildJsonObject {
                put("id", JsonNull)
                put("method", method)
                put(
                    "params",
                    buildJsonObject {
                        if (contexts.isNotEmpty()) put("contextIds", buildJsonArray { contexts.forEach { add(it) } })
                        if (groups.isNotEmpty()) put("groupIds", buildJsonArray { groups.forEach { add(it) } })
                    },
                )
            }
    }
}

private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
