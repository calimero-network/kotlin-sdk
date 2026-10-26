package com.calimero.mero.sse

import com.calimero.mero.http.AuthRevokedException
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.NetworkException
import com.calimero.mero.http.ProofRedirectRefusedException
import com.calimero.mero.http.TERMINAL_AUTH_ERRORS
import com.calimero.mero.request.ProofRequest
import com.calimero.mero.request.RequestAuthorizer
import com.calimero.mero.request.carriesProof
import com.calimero.mero.request.signedPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One long-lived SSE connection to a node: the Android port of mero-js `SseClient`
 * (`src/events/sse.ts`). Unlike the per-call flows on [SseClient], it keeps a set of
 * subscribed context and group ids that you change **on the live connection**:
 *
 * ```kotlin
 * val sse = mero.sse
 * scope.launch { sse.events.collect { handle(it) } }
 * sse.connect()
 * sse.subscribe(contextIds = listOf(contextId))          // POST /sse/subscription
 * …
 * sse.unsubscribe(contextIds = listOf(contextId))
 * sse.close()
 * ```
 *
 * ## Lifecycle
 *
 * [connect] opens `GET {base}/sse` (Bearer header). The node answers with a `connect`
 * frame naming a session; a new session starts subscribed to nothing, so every id this
 * connection remembers is re-subscribed in one `POST /sse/subscription`, and **then**
 * [connects] emits the session id — so a collector of [connects] knows the subscriptions
 * are live again. A failed re-subscribe still announces the session, then reports the
 * failure on [errors], exactly as mero-js does.
 *
 * ## Reconnection
 *
 * After a drop (the stream ending, a `close` frame, a network failure or a non-terminal
 * HTTP status) the connection waits as [backoff] says and opens a new stream. The default,
 * [ReconnectBackoff.SSE], waits 3 s (mero-js's `reconnectDelayMs`), then doubles up to 30 s
 * while attempts keep failing, ±20% jitter; a stream that opens resets it to 3 s. mero-js
 * waits a flat 3 s forever — pass [ReconnectBackoff.fixed]`(3_000)` for exactly that.
 *
 * ## Terminal failures
 *
 * Some refusals are a decision, not a hiccup, and reconnecting would only re-send a dead
 * credential forever:
 * - any `403` on `/sse` or `/sse/subscription` — a token without `context:subscribe`
 *   (core rc.41) or a revoked token family (`x-auth-error`, empty body);
 * - a `401` whose `x-auth-error` names a dead family (`token_reuse`, `token_revoked`).
 *
 * Those stop the connection: [failure] is set, the error goes to [errors]
 * ([AuthRevokedException] for a dead family, [HttpException] otherwise) and nothing
 * reconnects until [connect] is called again (after a fresh login). A plain `401`
 * (`token_expired`) is not terminal: the next attempt asks [token] again.
 *
 * ## Presence seed
 *
 * The node replays a context's presence (`Ephemeral` events carrying `ageMs`) once, when
 * the context is first subscribed on a connection. This connection keeps the latest entry
 * per author of each subscribed context, so a presence listener that arrives later can be
 * seeded from [presenceSeed] — see [com.calimero.mero.ephemeral.subscribePresence].
 *
 * [subscribe] and [unsubscribe] never throw for an HTTP failure: like mero-js they report
 * it on [errors] (and a terminal one ends the connection as above).
 *
 * ## Request proofs
 *
 * Pass [authorize] to authenticate each request with headers of its own — a device cert
 * signing a request proof ([com.calimero.mero.request.createProofAuthorizer]) — instead of
 * the Bearer [token] (mero-js `SseClient({ authorize })`). It is called for the `GET /sse`
 * and every subscription POST with that request's method, path and body. A request that
 * carries `X-Calimero-Proof` never follows a redirect: the 3xx is reported on [errors] as a
 * [com.calimero.mero.http.ProofRedirectRefusedException] and the stream reconnects.
 */
class SseConnection(
    baseUrl: String,
    private val token: suspend () -> String?,
    client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** How long to wait between a drop and the next attempt; see "Reconnection". */
    val backoff: ReconnectBackoff = ReconnectBackoff.SSE,
    private val now: () -> Long = System::currentTimeMillis,
    /** Per-request authorization; takes precedence over [token] when set. */
    private val authorize: RequestAuthorizer? = null,
) {
    private val base = baseUrl.trimEnd('/')

    // Every stream is held to MAX_LINE_BYTES; a proof is signed for one path, so a client that
    // may carry one never follows a redirect.
    private val client: OkHttpClient =
        client
            .newBuilder()
            .addInterceptor(SseLineLimit(MAX_LINE_BYTES))
            .apply { if (authorize != null) followRedirects(false).followSslRedirects(false) }
            .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val contextIds = LinkedHashSet<String>()
    private val groupIds = LinkedHashSet<String>()

    /** contextId → author → (latest `Ephemeral` data, when it arrived). Guarded by [lock]. */
    private val presence = HashMap<String, LinkedHashMap<String, Pair<JsonObject, Long>>>()
    private var loop: Job? = null

    @Volatile private var currentSession: String? = null

    /** The jitter draw for [backoff], in `[0, 1)`; a test pins it. */
    internal var random: () -> Double = { kotlin.random.Random.nextDouble() }

    /** The reconnect wait; a test records it instead of sleeping. */
    internal var sleep: suspend (Long) -> Unit = { delay(it) }

    private val _events = MutableSharedFlow<NodeEvent>(extraBufferCapacity = EVENT_BUFFER)
    private val _connects = MutableSharedFlow<String>(extraBufferCapacity = SIGNAL_BUFFER)
    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = SIGNAL_BUFFER)
    private val _failure = MutableStateFlow<Throwable?>(null)

    /** Every context and group event this connection receives, hot. Collect before [connect]. */
    val events: SharedFlow<NodeEvent> = _events.asSharedFlow()

    /** The session id of each (re)connection, emitted once its re-subscription has landed. */
    val connects: SharedFlow<String> = _connects.asSharedFlow()

    /** Non-fatal errors (a drop, a failed subscription POST) and the terminal one, as they happen. */
    val errors: SharedFlow<Throwable> = _errors.asSharedFlow()

    /** The terminal failure that stopped this connection, or `null` while it runs or reconnects. */
    val failure: StateFlow<Throwable?> = _failure.asStateFlow()

    /** The node's id for the current stream, or `null` between streams. */
    val sessionId: String? get() = currentSession

    /** Context events only. */
    val contextEvents: Flow<ContextEvent> get() = events.filterIsInstance<ContextEvent>()

    /** Group-keyed events only. */
    val groupEvents: Flow<GroupEvent> get() = events.filterIsInstance<GroupEvent>()

    /** `AppVersionChanged` context events, already parsed (mero-js `onAppVersionChanged`). */
    val appVersionChanges: Flow<AppVersionChangedEvent> get() = contextEvents.mapNotNull { it.asAppVersionChanged() }

    /**
     * Group-migration events, typed (mero-js `onMigrationEvent`). A non-admin subscriber
     * never sees [GroupMigrationEvent.CascadeProgress].
     */
    val migrationEvents: Flow<GroupMigrationEvent> get() = groupEvents.mapNotNull { it.asMigrationEvent() }

    /** Group-membership events, typed. */
    val membershipEvents: Flow<GroupMembershipEvent> get() = groupEvents.mapNotNull { it.asMembershipEvent() }

    /**
     * Calls [handler] for each `AppVersionChanged` event until the returned function is
     * called — the callback form of [appVersionChanges], as mero-js has it.
     */
    fun onAppVersionChanged(handler: (AppVersionChangedEvent) -> Unit): () -> Unit = listen(appVersionChanges, handler)

    /** Callback form of [migrationEvents]; call the returned function to stop. */
    fun onMigrationEvent(handler: (GroupMigrationEvent) -> Unit): () -> Unit = listen(migrationEvents, handler)

    private fun <T> listen(
        flow: Flow<T>,
        handler: (T) -> Unit,
    ): () -> Unit {
        val job =
            scope.launch {
                flow.collect { runCatching { handler(it) } }
            }
        return { job.cancel() }
    }

    /** Open the stream (a no-op while it is already running). Returns at once. */
    fun connect() {
        synchronized(lock) {
            if (loop?.isActive == true) return
            _failure.value = null
            loop = scope.launch { runLoop() }
        }
    }

    /**
     * Add ids to this connection. On a live session only the ids not already held are
     * POSTed (`method: "subscribe"`, 10 s timeout); before a session exists they are
     * remembered and go out with the next `connect` frame.
     */
    suspend fun subscribe(
        contextIds: Collection<String> = emptyList(),
        groupIds: Collection<String> = emptyList(),
    ) {
        val (newContexts, newGroups, session) =
            synchronized(lock) {
                val c = contextIds.filter { this.contextIds.add(it) }
                val g = groupIds.filter { this.groupIds.add(it) }
                Triple(c, g, currentSession)
            }
        if ((newContexts.isNotEmpty() || newGroups.isNotEmpty()) && session != null) {
            postSubscription(METHOD_SUBSCRIBE, newContexts, newGroups, session)?.let { report(it) }
        }
    }

    /** Drop ids from this connection (and their presence). Only ids it held are POSTed. */
    suspend fun unsubscribe(
        contextIds: Collection<String> = emptyList(),
        groupIds: Collection<String> = emptyList(),
    ) {
        val (hadContexts, hadGroups, session) =
            synchronized(lock) {
                val c = contextIds.filter { this.contextIds.remove(it) }
                contextIds.forEach { presence.remove(it) }
                val g = groupIds.filter { this.groupIds.remove(it) }
                Triple(c, g, currentSession)
            }
        if ((hadContexts.isNotEmpty() || hadGroups.isNotEmpty()) && session != null) {
            postSubscription(METHOD_UNSUBSCRIBE, hadContexts, hadGroups, session)?.let { report(it) }
        }
    }

    /** Whether this connection holds [contextId]'s events. */
    fun isSubscribed(contextId: String): Boolean = synchronized(lock) { contextId in contextIds }

    /** Whether this connection holds [groupId]'s events. */
    fun isSubscribedToGroup(groupId: String): Boolean = synchronized(lock) { groupId in groupIds }

    /** The context ids this connection holds, in subscription order. */
    val subscribedContextIds: List<String> get() = synchronized(lock) { contextIds.toList() }

    /** The group ids this connection holds, in subscription order. */
    val subscribedGroupIds: List<String> get() = synchronized(lock) { groupIds.toList() }

    /**
     * The presence currently known for [contextId], as `Ephemeral` event `data` objects,
     * each one's `ageMs` advanced by how long ago it arrived. Removals are dropped. Empty
     * for a context this connection does not hold, and after a reconnect until the node's
     * fresh replay arrives.
     */
    fun presenceSeed(contextId: String): List<JsonObject> {
        val t = now()
        return synchronized(lock) { presence[contextId]?.values?.toList().orEmpty() }.map { (data, at) ->
            val age = (data["ageMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            JsonObject(data + ("ageMs" to JsonPrimitive(age + (t - at))))
        }
    }

    /**
     * Stop the stream and forget every subscription and the presence kept for them, as
     * mero-js `close()` does. [connect] may be called again afterwards.
     */
    fun close() {
        synchronized(lock) {
            loop?.cancel()
            loop = null
            currentSession = null
            contextIds.clear()
            groupIds.clear()
            presence.clear()
        }
    }

    // ---- The connection loop ----------------------------------------------------------

    private suspend fun CoroutineScope.runLoop() {
        var attempt = 0
        while (isActive) {
            val opened = AtomicBoolean(false)
            val headers =
                try {
                    streamHeaders()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errors.tryEmit(e)
                    null
                }
            if (headers != null) {
                try {
                    runStream(headers, opened)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TerminalSseFailure) {
                    fail(e.error)
                    return
                } catch (e: Exception) {
                    _errors.tryEmit(e)
                }
            }
            currentSession = null
            // A stream that opened was a success: the next failure starts the schedule over.
            if (opened.get()) attempt = 0
            val wait = backoff.delayMs(attempt, random())
            attempt++
            sleep(wait)
        }
    }

    /** The `GET /sse` headers, or `null` when there is no credential yet (wait and retry). */
    private suspend fun streamHeaders(): Map<String, String>? {
        val authorize = authorize ?: return token()?.let { mapOf("Authorization" to "Bearer $it") }
        return authorize(ProofRequest("GET", signedPath("$base/sse")))
    }

    /** One stream: open it, handle its frames in order, return when it ends. */
    private suspend fun runStream(
        headers: Map<String, String>,
        opened: AtomicBoolean,
    ) {
        val builder = Request.Builder().url("$base/sse")
        // The bearer goes in the header, like every other call. Core still accepts
        // `?token=` (a browser EventSource needs it), but a token in a URL ends up in logs.
        headers.forEach { (name, value) -> builder.header(name, value) }
        val request = builder.header("Accept", "text/event-stream").build()
        val proofAttached = carriesProof(headers)
        val frames = Channel<String>(Channel.UNLIMITED)
        val done = CompletableDeferred<Unit>()
        val source = EventSources.createFactory(client).newEventSource(request, listener(frames, done, proofAttached, opened))
        try {
            for (frame in frames) {
                // The node said `close`: this stream is over, reconnect like after a drop.
                if (handleFrame(frame)) return
            }
            done.await()
        } finally {
            source.cancel()
        }
    }

    private fun listener(
        frames: Channel<String>,
        done: CompletableDeferred<Unit>,
        proofAttached: Boolean,
        opened: AtomicBoolean,
    ) = object : EventSourceListener() {
        override fun onOpen(
            eventSource: EventSource,
            response: Response,
        ) {
            opened.set(true)
        }

        override fun onEvent(
            eventSource: EventSource,
            id: String?,
            type: String?,
            data: String,
        ) {
            frames.trySend(data)
        }

        override fun onClosed(eventSource: EventSource) {
            done.complete(Unit)
            frames.close()
        }

        override fun onFailure(
            eventSource: EventSource,
            t: Throwable?,
            response: Response?,
        ) {
            val terminal = response?.let { terminalFailureOf(it) }
            val error: Throwable =
                when {
                    proofAttached && response != null && response.code in REDIRECTS -> proofRedirect(response)
                    terminal != null -> TerminalSseFailure(terminal)
                    response != null && !response.isSuccessful -> httpExceptionOf(response)
                    // A drop mid-stream, or a 200 that is not an event stream.
                    else -> NetworkException(t?.message ?: "SSE stream failed", t)
                }
            done.completeExceptionally(error)
            frames.close()
        }
    }

    /** Handles one `data:` frame; true when the node said `close`. */
    private suspend fun handleFrame(data: String): Boolean {
        val obj = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return false
        val frameType = obj.str("type")
        if (frameType != null) {
            if (frameType == "connect") obj.str("session_id")?.let { onSession(it) }
            return frameType == "close"
        }
        val result = obj["result"] as? JsonObject ?: return false
        val kind = result.str("type") ?: "event"
        val contextId = result.str("contextId")
        val groupId = result.str("groupId")
        when {
            contextId != null -> {
                if (kind == ContextEvent.KIND_EPHEMERAL) notePresence(contextId, result["data"] as? JsonObject)
                _events.emit(ContextEvent(contextId = contextId, kind = kind, payload = result))
            }
            groupId != null -> _events.emit(GroupEvent(groupId = groupId, kind = kind, payload = result))
        }
        return false
    }

    private fun onSession(session: String) {
        val (contexts, groups) =
            synchronized(lock) {
                currentSession = session
                // A new connection gets a new replay when it re-subscribes; what the old one
                // said may name authors who have since gone.
                presence.clear()
                contextIds.toList() to groupIds.toList()
            }
        if (contexts.isEmpty() && groups.isEmpty()) {
            _connects.tryEmit(session)
            return
        }
        scope.launch {
            val failure = postSubscription(METHOD_SUBSCRIBE, contexts, groups, session)
            if (currentSession != session) return@launch
            _connects.emit(session)
            failure?.let { report(it) }
        }
    }

    private fun notePresence(
        contextId: String,
        data: JsonObject?,
    ) {
        val author = (data?.get("author") as? JsonPrimitive)?.contentOrNull ?: return
        val removed = (data["removed"] as? JsonPrimitive)?.contentOrNull == "true"
        synchronized(lock) {
            if (contextId !in contextIds) return
            val byAuthor = presence.getOrPut(contextId) { LinkedHashMap() }
            if (removed) byAuthor.remove(author) else byAuthor[author] = data to now()
        }
    }

    /** A failure from a subscription POST: terminal ones end the connection. */
    private fun report(error: Throwable) {
        if (error is TerminalSseFailure) fail(error.error) else _errors.tryEmit(error)
    }

    private fun fail(error: Throwable) {
        synchronized(lock) {
            _failure.value = error
            currentSession = null
            loop?.cancel()
            loop = null
        }
        _errors.tryEmit(error)
    }

    /** Sends one subscription change and returns why it failed, or `null`. */
    private suspend fun postSubscription(
        method: String,
        contexts: List<String>,
        groups: List<String>,
        session: String,
    ): Throwable? =
        withContext(Dispatchers.IO) {
            try {
                val payload =
                    buildJsonObject {
                        put("id", session)
                        put("method", method)
                        put(
                            "params",
                            buildJsonObject {
                                // Only non-empty lists: core skips an empty one, and a node
                                // predating group subscriptions denies the unknown key.
                                if (contexts.isNotEmpty()) put("contextIds", buildJsonArray { contexts.forEach { add(it) } })
                                if (groups.isNotEmpty()) put("groupIds", buildJsonArray { groups.forEach { add(it) } })
                            },
                        )
                    }
                val url = "$base/sse/subscription"
                val body = json.encodeToString(JsonObject.serializer(), payload).encodeToByteArray()
                val builder = Request.Builder().url(url).post(body.toRequestBody(JSON_MEDIA))
                val headers =
                    authorize?.invoke(ProofRequest("POST", signedPath(url), body))
                        ?: token()?.let { mapOf("Authorization" to "Bearer $it") }
                        ?: emptyMap()
                headers.forEach { (name, value) -> builder.header(name, value) }
                val call = client.newCall(builder.build())
                call.timeout().timeout(SUBSCRIPTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                call.execute().use { resp ->
                    when {
                        carriesProof(headers) && resp.code in REDIRECTS -> proofRedirect(resp)
                        resp.isSuccessful -> null
                        else -> terminalFailureOf(resp)?.let { TerminalSseFailure(it) } ?: httpExceptionOf(resp)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                NetworkException("SSE $method failed: ${e.message}", e)
            } catch (e: Exception) {
                e
            }
        }

    /** Marks a refusal that must stop the connection; [error] is what the caller sees. */
    private class TerminalSseFailure(
        val error: HttpException,
    ) : Exception(error.message, error)

    companion object {
        /** How long a subscription POST may take before it counts as failed (mero-js `SUBSCRIPTION_TIMEOUT_MS`). */
        const val SUBSCRIPTION_TIMEOUT_MS = 10_000L

        /**
         * The longest line (and the largest event) the stream may send, in bytes. Past it the
         * connection is dropped and reconnected, reported on [errors] as a
         * [com.calimero.mero.http.NetworkException]. A node event is a single JSON line, far
         * smaller than this.
         */
        const val MAX_LINE_BYTES = 1024L * 1024

        private const val METHOD_SUBSCRIBE = "subscribe"
        private const val METHOD_UNSUBSCRIBE = "unsubscribe"
        private const val EVENT_BUFFER = 256
        private const val SIGNAL_BUFFER = 16
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}

private const val HTTP_UNAUTHORIZED = 401

/** Every 3xx: none is followed for a request carrying a proof. */
internal val REDIRECTS = 300..399
private const val HTTP_FORBIDDEN = 403

/**
 * The exception a refusal that must not be retried deserves, or `null` when retrying may
 * help. Any `403` (a missing `context:subscribe`, or a dead family) and a `401` naming a
 * dead family in `x-auth-error` are terminal; a dead family gets [AuthRevokedException].
 */
internal fun terminalFailureOf(response: Response): HttpException? {
    val deadFamily = response.header("x-auth-error")?.takeIf { it in TERMINAL_AUTH_ERRORS }
    if (response.code != HTTP_FORBIDDEN && !(response.code == HTTP_UNAUTHORIZED && deadFamily != null)) return null
    val ex = httpExceptionOf(response)
    return if (deadFamily != null) AuthRevokedException(deadFamily, ex.status, ex.bodyText, ex.headers, ex.url) else ex
}

/** The refusal for a 3xx that answered a request carrying a proof (mero-js `refuseProofRedirect`). */
internal fun proofRedirect(response: Response): ProofRedirectRefusedException =
    ProofRedirectRefusedException(response.request.url.toString(), response.code, response.header("Location"))

internal fun httpExceptionOf(response: Response): HttpException {
    val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
    val headers = response.headers.names().associateWith { response.headers[it].orEmpty() }
    return HttpException(response.code, body, headers, response.request.url.toString())
}

private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
