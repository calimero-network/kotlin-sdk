package com.calimero.mero.sse

import com.calimero.mero.http.AuthRevokedException
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.TERMINAL_AUTH_ERRORS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * A node event pushed over SSE. [kind] is the frame's `result.type`: a live
 * `merod 0.11.0-rc.32` sends **`StateMutation`** (the context state moved) and
 * **`SyncStatus`** (`syncing` / `waitingForPeers` / …). It does NOT send
 * `ExecutionEvent`, which this doc used to name — a `when` branching on it never fired.
 *
 * [payload] is the raw event JSON; for a `StateMutation` the contract's own events live
 * under `data.events[]`, each with a `kind` and a `data` byte array carrying the encoding.
 */
data class ContextEvent(
    val contextId: String,
    val kind: String,
    val payload: JsonElement,
)

/**
 * Server-Sent-Events subscription client — the Android analog of mero-js's SSE client (and the
 * Swift SDK's `SseClient`). Opens `GET {base}/sse?token=…` and POSTs `{base}/sse/subscription` to
 * (re)subscribe to context ids, then streams [ContextEvent]s as a cold [Flow]. Reconnects
 * automatically after a drop (the node persists session subscriptions), so a chat view can react to
 * new messages without polling.
 *
 * Usage:
 * ```kotlin
 * val job = scope.launch {
 *     mero.events(listOf(contextId)).collect { reload() }  // e.g. re-fetch messages
 * }
 * // job.cancel() closes the stream.
 * ```
 *
 * ## ⚠️ A `403` is terminal, and used not to be
 *
 * Core 0.11.0-rc.41 (core#3942) mapped `/sse`, `/sse/subscription` and
 * `/sse/session/{id}` to the **`context:subscribe`** permission. Before it, the event
 * transports required *no* permission at all — any valid token could open a stream. A
 * token minted with a narrow permission list that does not name `context:subscribe` is
 * now refused with a `403`, and so is one whose refresh family was revoked.
 *
 * Neither is fixed by waiting, so neither is retried: this client used to treat every
 * failure alike and reconnect every [RECONNECT_DELAY_MS] forever, which turned both into
 * a silent loop delivering no events and reporting nothing. A `403` now fails the flow
 * with [AuthRevokedException] (when `x-auth-error` names a dead family) or a plain
 * [HttpException], so the collector sees it.
 *
 * `context:subscribe` is **not** implied by `context` — core's `ContextPermission::All`
 * arm matches only another `All`. `admin` does cover it. A scoped token must name it:
 * `permissions = listOf("context:subscribe", …)`.
 */
class SseClient(
    private val baseUrl: String,
    private val token: suspend () -> String?,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Cold stream of events for the given [contextIds]. Cancelling the collecting coroutine closes
     * the connection. Each connection attempt subscribes on the `connect` frame; a `close` frame or
     * any drop triggers a reconnect after [RECONNECT_DELAY_MS].
     */
    fun events(contextIds: List<String>): Flow<ContextEvent> =
        channelFlow {
            while (isActive) {
                val accessToken = token()
                if (accessToken != null) {
                    runConnection(contextIds, accessToken)
                }
                // Wait before reconnecting (or before retrying when no token was available yet). The
                // cancellable delay exits the loop when the collector is cancelled.
                delay(RECONNECT_DELAY_MS)
            }
            awaitClose { }
        }

    /**
     * One connection attempt: open the SSE stream, subscribe on `connect`, forward events into
     * [scope] until the stream ends (server `close`, a drop, or failure). Suspends until then.
     */
    private suspend fun ProducerScope<ContextEvent>.runConnection(
        contextIds: List<String>,
        accessToken: String,
    ) {
        val base = baseUrl.trimEnd('/')
        val request =
            Request
                .Builder()
                .url("$base/sse?token=$accessToken")
                .header("Accept", "text/event-stream")
                .build()

        // Bridges the EventSource callback into structured concurrency: completes when the stream
        // ends (server `close`, drop, or failure) so the caller can reconnect.
        val done = CompletableDeferred<Unit>()
        val listener = streamListener(base, contextIds, accessToken, done)

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        try {
            done.await()
        } finally {
            eventSource.cancel()
        }
    }

    private fun ProducerScope<ContextEvent>.streamListener(
        base: String,
        contextIds: List<String>,
        accessToken: String,
        done: CompletableDeferred<Unit>,
    ) = object : EventSourceListener() {
        override fun onEvent(
            eventSource: EventSource,
            id: String?,
            type: String?,
            data: String,
        ) {
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return

            val frameType = obj["type"]?.jsonPrimitive?.contentOrNull
            if (frameType != null) {
                when (frameType) {
                    "connect" ->
                        obj["session_id"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.let { subscribe(base, contextIds, it, accessToken, done) }
                    "close" -> {
                        eventSource.cancel()
                        if (!done.isCompleted) done.complete(Unit) // reconnect
                    }
                }
                return
            }

            val result = obj["result"] as? JsonObject ?: return
            val contextId = result["contextId"]?.jsonPrimitive?.contentOrNull ?: return
            val kind = result["type"]?.jsonPrimitive?.contentOrNull ?: "event"
            trySend(ContextEvent(contextId = contextId, kind = kind, payload = result))
        }

        override fun onClosed(eventSource: EventSource) {
            if (!done.isCompleted) done.complete(Unit)
        }

        override fun onFailure(
            eventSource: EventSource,
            t: Throwable?,
            response: okhttp3.Response?,
        ) {
            if (done.isCompleted) return
            // A 403 is a decision, not a hiccup: either this token never carried
            // `context:subscribe` (rc.41) or its family is gone. Retrying re-asks a
            // question already answered, so fail the flow instead of looping.
            val terminal = response?.takeIf { it.code == HTTP_FORBIDDEN }?.let { terminalFor(it) }
            if (terminal != null) done.completeExceptionally(terminal) else done.complete(Unit)
        }
    }

    /**
     * POST the subscription request (never dropped, unlike a WS message sent before the socket is
     * open — the reason mero-chat moved to SSE). Fire-and-forget for every failure but one: a
     * `403` fails [done], because the next `connect` would only be refused again.
     */
    private fun subscribe(
        base: String,
        contextIds: List<String>,
        sessionId: String,
        accessToken: String,
        done: CompletableDeferred<Unit>,
    ) {
        val payload =
            buildJsonObject {
                put("id", sessionId)
                put("method", "subscribe")
                put(
                    "params",
                    buildJsonObject {
                        put(
                            "contextIds",
                            buildJsonArray { contextIds.forEach { add(it) } },
                        )
                    },
                )
            }
        val request =
            Request
                .Builder()
                .url("$base/sse/subscription")
                .header("Authorization", "Bearer $accessToken")
                .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
                .build()
        // A failed POST is normally harmless — the next `connect` re-subscribes. A 403 is
        // not: the stream stays open and silent, which used to read as "this context is
        // quiet" rather than "you were refused". Core rc.41 put `context:subscribe` on
        // this route too, so the open can succeed and the subscribe still be refused.
        runCatching {
            client.newCall(request).execute().use { resp ->
                if (resp.code == HTTP_FORBIDDEN && !done.isCompleted) {
                    done.completeExceptionally(terminalFor(resp))
                }
            }
        }
    }

    /**
     * The exception a `403` deserves. A dead token family is named by `x-auth-error`
     * and already has a type apps catch; anything else at this status is a permission
     * the token does not hold — since rc.41 that is `context:subscribe` — and gets a
     * plain [HttpException] carrying the body and headers to say which.
     */
    private fun terminalFor(response: okhttp3.Response): HttpException {
        val authError = response.header("x-auth-error")
        val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
        val headers = response.headers.names().associateWith { response.headers[it].orEmpty() }
        val url = response.request.url.toString()
        return if (authError != null && authError in TERMINAL_AUTH_ERRORS) {
            AuthRevokedException(authError, response.code, body, headers, url)
        } else {
            HttpException(response.code, body, headers, url)
        }
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 3_000L
        const val HTTP_FORBIDDEN = 403
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
