package com.calimero.mero.sse

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
 * A node event pushed over SSE. [payload] is the raw event JSON — for a contract emission
 * (`kind == "ExecutionEvent"`) the events live under `data.events[].data` (a byte array carrying
 * the encoded contract event).
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
    fun events(contextIds: List<String>): Flow<ContextEvent> = channelFlow {
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
    private suspend fun ProducerScope<ContextEvent>.runConnection(contextIds: List<String>, accessToken: String) {
        val base = baseUrl.trimEnd('/')
        val request = Request.Builder()
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
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return

            val frameType = obj["type"]?.jsonPrimitive?.contentOrNull
            if (frameType != null) {
                when (frameType) {
                    "connect" -> obj["session_id"]?.jsonPrimitive?.contentOrNull
                        ?.let { subscribe(base, contextIds, it, accessToken) }
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

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
            if (!done.isCompleted) done.complete(Unit)
        }
    }

    /**
     * POST the subscription request (never dropped, unlike a WS message sent before the socket is
     * open — the reason mero-chat moved to SSE). Fire-and-forget; failures just mean the next
     * `connect` re-subscribes.
     */
    private fun subscribe(base: String, contextIds: List<String>, sessionId: String, accessToken: String) {
        val payload = buildJsonObject {
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
        val request = Request.Builder()
            .url("$base/sse/subscription")
            .header("Authorization", "Bearer $accessToken")
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()
        runCatching { client.newCall(request).execute().use { } }
    }

    private companion object {
        const val RECONNECT_DELAY_MS = 3_000L
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
