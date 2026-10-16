package com.calimero.mero.sse

import com.calimero.mero.request.RequestAuthorizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

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
) : NodeEvent {
    /**
     * For a presence (`Ephemeral`) event, the account a verified device
     * certificate names — set when an account's presence was carried by a relay
     * (core rc.83), `null` for a node's own presence and for every other kind.
     */
    val presenceAccount: String?
        get() =
            if (kind != KIND_EPHEMERAL) {
                null
            } else {
                ((payload as? JsonObject)?.get("data") as? JsonObject)?.get("account")?.jsonPrimitive?.contentOrNull
            }

    companion object {
        /** `result.type` of a presence update. */
        const val KIND_EPHEMERAL = "Ephemeral"
    }
}

/**
 * A group-keyed event — membership changes and migration progress — delivered to
 * a stream subscribed with `groupIds`. [kind] is `result.type` (e.g.
 * `MemberAdded`, `MemberRoleChanged`; roles may read `RelayTee` since core rc.83),
 * and [payload] the raw `result` JSON.
 */
data class GroupEvent(
    val groupId: String,
    val kind: String,
    val payload: JsonElement,
) : NodeEvent

/** Either kind of event an SSE stream delivers. */
sealed interface NodeEvent

/**
 * Server-Sent-Events subscription client — the Android analog of mero-js's SSE client (and the
 * Swift SDK's `SseClient`). Opens `GET {base}/sse` (Bearer header) and POSTs `{base}/sse/subscription` to
 * (re)subscribe, then streams events as a cold [Flow]. Each flow runs on its own
 * [SseConnection]; use [connection] (or `Mero.sse`) for one long-lived connection whose
 * subscriptions change while it is open.
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
 * failure alike and reconnect every three seconds forever, which turned both into
 * a silent loop delivering no events and reporting nothing. A `403` now fails the flow
 * with [com.calimero.mero.http.AuthRevokedException] (when `x-auth-error` names a dead
 * family) or a plain [com.calimero.mero.http.HttpException], so the collector sees it.
 * A `401` naming a dead family is terminal the same way (mero-js parity).
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
    /** Reconnect schedule for every connection; see [SseConnection]'s "Reconnection". */
    private val backoff: ReconnectBackoff = ReconnectBackoff.SSE,
    /**
     * Per-request authorization (a request proof — [com.calimero.mero.request.createProofAuthorizer]),
     * taking precedence over [token]; see [SseConnection]'s "Request proofs".
     */
    private val authorize: RequestAuthorizer? = null,
) {
    /**
     * A new long-lived [SseConnection] on this client's node, not yet connected. Its
     * subscriptions are changed on the live stream with [SseConnection.subscribe] /
     * [SseConnection.unsubscribe]; close it with [SseConnection.close].
     */
    fun connection(): SseConnection = SseConnection(baseUrl, token, client, json, backoff, authorize = authorize)

    /**
     * Cold stream of events for the given [contextIds]. Cancelling the collecting coroutine closes
     * the connection. Each connection attempt subscribes on the `connect` frame; a `close` frame or
     * any drop triggers a reconnect after [backoff] (3 s, doubling to 30 s while attempts fail).
     */
    fun events(contextIds: List<String>): Flow<ContextEvent> = nodeEvents(contextIds, emptyList()).filterIsInstance<ContextEvent>()

    /**
     * Cold stream of group-keyed events (membership, migration) for [groupIds].
     * The node drops ids the token may not observe, silently.
     */
    fun groupEvents(groupIds: List<String>): Flow<GroupEvent> = nodeEvents(emptyList(), groupIds).filterIsInstance<GroupEvent>()

    /** `AppVersionChanged` events for [contextIds], parsed (mero-js `onAppVersionChanged`). */
    fun appVersionChanges(contextIds: List<String>): Flow<AppVersionChangedEvent> = events(contextIds).mapNotNull { it.asAppVersionChanged() }

    /** Typed migration events for [groupIds] (subscribe with the namespace root). */
    fun migrationEvents(groupIds: List<String>): Flow<GroupMigrationEvent> = groupEvents(groupIds).mapNotNull { it.asMigrationEvent() }

    /** Typed membership events for [groupIds]. */
    fun membershipEvents(groupIds: List<String>): Flow<GroupMembershipEvent> = groupEvents(groupIds).mapNotNull { it.asMembershipEvent() }

    /**
     * One stream for both: context events for [contextIds] and group events for
     * [groupIds], subscribed together on every (re)connect. A terminal refusal fails the flow.
     */
    fun nodeEvents(
        contextIds: List<String>,
        groupIds: List<String>,
    ): Flow<NodeEvent> = connection().flowOf(contextIds, groupIds)
}

/**
 * This connection's events for [contextIds] and [groupIds] as a cold flow that owns the
 * connection: collecting subscribes and connects, cancelling closes it, and a terminal
 * failure fails the flow.
 */
internal fun SseConnection.flowOf(
    contextIds: List<String>,
    groupIds: List<String>,
): Flow<NodeEvent> =
    channelFlow {
        subscribe(contextIds, groupIds) // no session yet: remembered, sent with the `connect` frame
        launch { this@channelFlow.close(failure.filterNotNull().first()) }
        try {
            events.onSubscription { connect() }.collect { send(it) }
        } finally {
            this@flowOf.close()
        }
    }
