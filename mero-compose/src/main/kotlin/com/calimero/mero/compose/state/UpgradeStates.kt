package com.calimero.mero.compose.state

import com.calimero.mero.admin.GroupUpgradeStatus
import com.calimero.mero.admin.MemberMigrationStatusEntry
import com.calimero.mero.admin.MigrationStatus
import com.calimero.mero.admin.MigrationStatusRollup
import com.calimero.mero.admin.compareSemver
import com.calimero.mero.compose.MeroEvents
import com.calimero.mero.compose.MeroSession
import com.calimero.mero.invite.InviteCodec
import com.calimero.mero.rpc.MigrateMyEntriesSummary
import com.calimero.mero.sse.ContextEvent
import com.calimero.mero.sse.GroupEvent
import com.calimero.mero.sse.GroupMigrationEvent
import com.calimero.mero.sse.asAppVersionChanged
import com.calimero.mero.sse.asMigrationEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** A holder's own child scope of [parent]'s, so [AutoCloseable.close] stops just it. */
internal fun childScope(parent: CoroutineScope): Pair<CoroutineScope, Job> {
    val job = SupervisorJob(parent.coroutineContext[Job])
    return CoroutineScope(parent.coroutineContext + job) to job
}

/** Collect [block] against the session's event stream, restarting on every new stream (and doing nothing without one). */
internal fun CoroutineScope.followEvents(
    sessions: StateFlow<MeroSession>,
    block: suspend CoroutineScope.(MeroEvents) -> Unit,
): Job =
    launch {
        sessions.map { it.events }.distinctUntilChanged().collectLatest { events ->
            if (events != null) coroutineScope { block(events) }
        }
    }

/**
 * A namespace's `GroupMigration` events — mero-react `useMigrationEvents`: [onEvent] per frame,
 * and [live] saying whether the stream is actually carrying frames (connected and subscribed),
 * which is what decides whether a caller still needs a fallback poll. A non-admin never sees
 * `CascadeProgress`; core delivers it to namespace admins only.
 */
class MigrationEventsState(
    sessions: StateFlow<MeroSession>,
    scope: CoroutineScope,
    private val namespaceId: String?,
    private val onEvent: (GroupMigrationEvent) -> Unit,
) : AutoCloseable {
    private val _live = MutableStateFlow(false)
    val live: StateFlow<Boolean> = _live.asStateFlow()
    private val owned = childScope(scope)

    init {
        owned.first.followEvents(sessions) { events -> watch(events) }
    }

    private suspend fun CoroutineScope.watch(events: MeroEvents) {
        val ns = namespaceId ?: return
        try {
            launch(start = CoroutineStart.UNDISPATCHED) {
                events.events
                    .filterIsInstance<GroupEvent>()
                    .mapNotNull { if (it.groupId == ns) it.asMigrationEvent() else null }
                    .collect { runCatching { onEvent(it) } }
            }
            launch(start = CoroutineStart.UNDISPATCHED) { events.connects.collect { _live.value = true } }
            launch(start = CoroutineStart.UNDISPATCHED) { events.errors.collect { _live.value = false } }
            events.connect()
            events.subscribe(groupIds = listOf(ns))
            _live.value = events.isConnected
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _live.value = false
        } finally {
            _live.value = false
        }
    }

    override fun close() = owned.second.cancel()
}

/**
 * A namespace's migration rollup — mero-react `useMigrationStatus`. Live: each `GroupMigration`
 * frame triggers a re-read (the rollup is recomputed from raw member rows, so the re-read is the
 * answer, not the frame). [pollIntervalMs] polls regardless; without one it polls every
 * [DEFAULT_MIGRATION_POLL_INTERVAL_MS] exactly while the stream is not live, so the panel never
 * sits frozen with neither transport. Derived counters are null-safe before the first load.
 */
class MigrationStatusState(
    sessions: StateFlow<MeroSession>,
    scope: CoroutineScope,
    private val namespaceId: String?,
    private val pollIntervalMs: Long? = null,
) : AutoCloseable {
    private val owned = childScope(scope)
    private val resource: AsyncResource<MigrationStatus?> =
        AsyncResource<MigrationStatus?>(owned.first, null).bind(sessions) { s ->
            val admin = s.admin
            if (admin == null || namespaceId == null) null else ({ admin.getMigrationStatus(namespaceId) })
        }
    private val events = MigrationEventsState(sessions, owned.first, namespaceId) { owned.first.launch { resource.refetch() } }

    val state: StateFlow<ResourceState<MigrationStatus?>> = resource.state

    /** Whether the event stream is live (and so the fallback poll is off). */
    val live: StateFlow<Boolean> = events.live

    val status: MigrationStatus? get() = resource.data
    val rollup: MigrationStatusRollup? get() = status?.rollup
    val members: List<MemberMigrationStatusEntry> get() = status?.members.orEmpty()
    val allMigrated: Boolean get() = rollup?.allMigrated ?: false
    val membersPendingSignature: Int get() = rollup?.membersPendingSignature ?: 0

    /** Members whose migrate aborted (migration-check failed or apply errored). */
    val failed: Int get() = rollup?.failed ?: 0

    init {
        owned.first.launch {
            combine(events.live, sessions.map { it.admin != null }) { isLive, connected -> isLive to connected }
                .distinctUntilChanged()
                .collectLatest { (isLive, connected) ->
                    val interval = pollIntervalMs ?: if (isLive) null else DEFAULT_MIGRATION_POLL_INTERVAL_MS
                    if (interval == null || interval <= 0 || !connected || namespaceId == null) return@collectLatest
                    while (true) {
                        delay(interval)
                        resource.refetch()
                    }
                }
        }
    }

    suspend fun refetch() = resource.refetch()

    override fun close() {
        resource.close()
        events.close()
        owned.second.cancel()
    }

    companion object {
        /** Fallback poll cadence while the stream is down — the heartbeat cadence migration facts arrive on. */
        const val DEFAULT_MIGRATION_POLL_INTERVAL_MS = 5_000L
    }
}

/**
 * Bundle-version skew — mero-react `useAppVersion`: the context's installed `applicationVersion`
 * against the app's [expected] build constant. Any mismatch is [isStale] ("reload to update").
 * An `AppVersionChanged` event on the session's stream updates it live, winning over a read in
 * flight (the hook listens; it does not subscribe the context itself).
 */
class AppVersionState(
    sessions: StateFlow<MeroSession>,
    scope: CoroutineScope,
    private val contextId: String?,
    val expected: String? = null,
) : AutoCloseable {
    private val owned = childScope(scope)
    private val resource =
        AsyncResource<String?>(owned.first, null).bind(sessions) { s ->
            val admin = s.admin
            if (admin == null || contextId == null) null else ({ admin.getContext(contextId).applicationVersion })
        }

    val state: StateFlow<ResourceState<String?>> = resource.state
    val appVersion: String? get() = resource.data
    val isStale: Boolean get() = appVersion != null && expected != null && appVersion != expected

    init {
        owned.first.followEvents(sessions) { events ->
            val ctx = contextId ?: return@followEvents
            events.events
                .filterIsInstance<ContextEvent>()
                .mapNotNull { it.asAppVersionChanged() }
                .collect { change -> if (change.contextId == ctx) change.toVersion?.let { resource.override(it) } }
        }
    }

    suspend fun refetch() = resource.refetch()

    override fun close() {
        resource.close()
        owned.second.cancel()
    }
}

/**
 * "Is a newer version available?" — mero-react `useLatestVersion`: a package's published versions
 * from the registry (newest first), and [updateAvailable] only when the newest is strictly
 * greater than [currentVersion]. Reads nothing until both [registryUrl] and [packageName] are set.
 */
class LatestVersionState(
    sessions: StateFlow<MeroSession>,
    scope: CoroutineScope,
    registryUrl: String?,
    packageName: String?,
    val currentVersion: String? = null,
) : AutoCloseable {
    private val resource =
        AsyncResource<List<String>>(scope, emptyList()).bind(sessions) { s ->
            val admin = s.admin
            if (admin == null || registryUrl == null || packageName == null) null else ({ admin.getRegistryVersions(registryUrl, packageName) })
        }

    val state: StateFlow<ResourceState<List<String>>> = resource.state
    val versions: List<String> get() = resource.data
    val latestVersion: String? get() = versions.firstOrNull()
    val updateAvailable: Boolean
        get() {
            val latest = latestVersion ?: return false
            val current = currentVersion ?: return false
            return compareSemver(latest, current) > 0
        }

    suspend fun refetch() = resource.refetch()

    override fun close() = resource.close()
}

/** What [GroupAppVersionState] reads. */
data class GroupAppVersion(
    /** Semver of the application the group targets; null until loaded. */
    val version: String? = null,
    /** The group's `targetApplicationId`. */
    val applicationId: String? = null,
    /** The installed bundle differs from the one the group targets: downloaded, not yet applied. */
    val pendingApply: Boolean = false,
    /** Only populated on the group-info path (a subgroup); null for a namespace. */
    val activeUpgrade: GroupUpgradeStatus? = null,
)

/** An appKey carries no "pending" signal when it is absent or all zeros. */
internal fun isZeroAppKey(appKey: String?): Boolean = appKey.isNullOrEmpty() || appKey.all { it == '0' }

/** A Base58 id as lowercase hex (leading `1`s are leading zero bytes) — mero-react `base58ToHex`. */
fun base58ToHex(input: String): String {
    val bytes = InviteCodec.base58Decode(input) ?: throw IllegalArgumentException("invalid base58: $input")
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * What app version a group runs, and whether a downloaded update is not yet applied —
 * mero-react `useGroupAppVersion`. [GroupAppVersion.pendingApply] is true when the group's
 * `appKey` (the blob it targets) is present, non-zero and differs from the blob installed for
 * its `targetApplicationId`. Namespace first (`listNamespaces`), else `getGroupInfo`; re-reads on
 * any `AppVersionChanged`.
 */
class GroupAppVersionState(
    sessions: StateFlow<MeroSession>,
    scope: CoroutineScope,
    private val groupId: String?,
) : AutoCloseable {
    private val owned = childScope(scope)
    private val resource =
        AsyncResource(owned.first, GroupAppVersion()).bind(sessions) { s ->
            val admin = s.admin
            if (admin == null || groupId == null) null else ({ read(admin, groupId) })
        }

    val state: StateFlow<ResourceState<GroupAppVersion>> = resource.state
    val value: GroupAppVersion get() = resource.data

    init {
        owned.first.followEvents(sessions) { events ->
            if (groupId == null) return@followEvents
            events.events
                .filterIsInstance<ContextEvent>()
                .mapNotNull { it.asAppVersionChanged() }
                .collect { launch { resource.refetch() } }
        }
    }

    private suspend fun read(
        admin: com.calimero.mero.admin.AdminSurface,
        groupId: String,
    ): GroupAppVersion {
        val namespace = admin.listNamespaces().firstOrNull { it.namespaceId == groupId }
        val appKey: String?
        val applicationId: String?
        var activeUpgrade: GroupUpgradeStatus? = null
        if (namespace != null) {
            appKey = namespace.appKey
            applicationId = namespace.targetApplicationId
        } else {
            val info = admin.getGroupInfo(groupId)
            appKey = info.appKey
            applicationId = info.targetApplicationId
            activeUpgrade = info.activeUpgrade
        }
        if (applicationId.isNullOrEmpty()) return GroupAppVersion(null, applicationId, false, activeUpgrade)
        val application = admin.getApplication(applicationId).application
        val pending =
            application != null &&
                !isZeroAppKey(appKey) &&
                appKey!!.lowercase() != base58ToHex(application.blob.bytecode).lowercase()
        return GroupAppVersion(application?.version, applicationId, pending, activeUpgrade)
    }

    suspend fun refetch() = resource.refetch()

    override fun close() {
        resource.close()
        owned.second.cancel()
    }
}

/**
 * The caller's own identity-gated entries pending re-signature — mero-react
 * `useMyAuthoredMigration`. [pending] is `pendingCount > 0` (`count_my_pending`); [authorize]
 * runs the one-tap `migrate_my_entries` convert and folds its `remaining` back into the count.
 */
class MyAuthoredMigrationState(
    private val sessions: StateFlow<MeroSession>,
    scope: CoroutineScope,
    private val contextId: String?,
) : AutoCloseable {
    private val owned = childScope(scope)
    private val mutation = AsyncMutation()
    private val sequence = AtomicLong()
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()
    private val _summary = MutableStateFlow<MigrateMyEntriesSummary?>(null)
    val summary: StateFlow<MigrateMyEntriesSummary?> = _summary.asStateFlow()

    /** The convert's in-flight / failure state. */
    val state: StateFlow<MutationState> = mutation.state
    val pending: Boolean get() = _pendingCount.value > 0

    init {
        owned.first.launch {
            sessions.map { it.rpc }.distinctUntilChanged().collect {
                sequence.incrementAndGet()
                _pendingCount.value = 0
                _summary.value = null
                launch { refresh() }
            }
        }
    }

    /** Re-read the count (best-effort: a transient failure leaves the previous value). */
    suspend fun refresh() {
        val rpc = sessions.value.rpc
        if (rpc == null || contextId == null) {
            _pendingCount.value = 0
            return
        }
        val seq = sequence.incrementAndGet()
        try {
            val count = rpc.countMyPending(contextId)
            if (seq == sequence.get()) _pendingCount.value = count
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // best-effort
        }
    }

    /** Run the convert; its summary, or null (see [state]). */
    suspend fun authorize(): MigrateMyEntriesSummary? {
        val rpc = sessions.value.rpc ?: return null
        val ctx = contextId ?: return null
        val result = mutation.run { rpc.migrateMyEntries(ctx) } ?: return null
        sequence.incrementAndGet()
        _summary.value = result
        _pendingCount.value = result.remaining
        return result
    }

    override fun close() = owned.second.cancel()
}
