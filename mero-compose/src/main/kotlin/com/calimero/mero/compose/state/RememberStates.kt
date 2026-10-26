package com.calimero.mero.compose.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.calimero.mero.admin.GetBlobInfoResponseData
import com.calimero.mero.admin.GroupContextEntry
import com.calimero.mero.admin.GroupInfo
import com.calimero.mero.admin.GroupMember
import com.calimero.mero.admin.GroupUpgradeStatus
import com.calimero.mero.admin.MetadataRecord
import com.calimero.mero.admin.Namespace
import com.calimero.mero.admin.NodeIdentity
import com.calimero.mero.admin.OutboundApproval
import com.calimero.mero.admin.SubgroupEntry
import com.calimero.mero.compose.MeroSession
import com.calimero.mero.compose.useMero
import com.calimero.mero.ephemeral.Codec
import com.calimero.mero.sse.GroupMigrationEvent
import com.calimero.mero.sse.NodeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

// The Compose face of the state holders — mero-react's hooks as `remember*` functions. Each
// remembers its holder per ambient MeroClient and inputs (a new input is a new holder, which
// starts from the initial value, as a hook resets on new deps), runs it in the composition's
// scope and closes it when it leaves. Collect a holder's `state` with
// `collectAsStateWithLifecycle()`; call its suspend functions from `rememberCoroutineScope()`.

/** Remember any closeable holder built from the ambient client's session. */
@Composable
fun <T : AutoCloseable> rememberMeroHolder(
    vararg keys: Any?,
    factory: (sessions: StateFlow<MeroSession>, scope: CoroutineScope) -> T,
): T {
    val client = useMero()
    val scope = rememberCoroutineScope()
    val holder = remember(client, *keys) { factory(client.session, scope) }
    DisposableEffect(holder) { onDispose { holder.close() } }
    return holder
}

/** Remember a read built with [MeroReads]. */
@Composable
fun <T> rememberMeroResource(
    vararg keys: Any?,
    read: MeroReads.() -> AsyncResource<T>,
): AsyncResource<T> = rememberMeroHolder(*keys) { sessions, scope -> MeroReads(sessions, scope).read() }

/** Remember a write built from the ambient client's session. */
@Composable
fun <T> rememberMeroMutation(
    vararg keys: Any?,
    factory: (StateFlow<MeroSession>) -> T,
): T {
    val client = useMero()
    return remember(client, *keys) { factory(client.session) }
}

// ---- Reads ---------------------------------------------------------------------------------

@Composable
fun rememberContexts(applicationId: String? = null): AsyncResource<List<ApplicationContextRecord>> =
    rememberMeroResource(applicationId) { contexts(applicationId) }

@Composable
fun rememberApplicationContexts(applicationId: String?): AsyncResource<List<ApplicationContextRecord>> =
    rememberMeroResource(applicationId) { applicationContexts(applicationId) }

@Composable
fun rememberContextGroup(contextId: String?): AsyncResource<String?> = rememberMeroResource(contextId) { contextGroup(contextId) }

@Composable
fun rememberGroupInfo(groupId: String?): AsyncResource<GroupInfo?> = rememberMeroResource(groupId) { groupInfo(groupId) }

@Composable
fun rememberGroupMembers(groupId: String?): AsyncResource<List<GroupMember>> = rememberMeroResource(groupId) { groupMembers(groupId) }

@Composable
fun rememberGroupContexts(groupId: String?): AsyncResource<List<GroupContextEntry>> = rememberMeroResource(groupId) { groupContexts(groupId) }

@Composable
fun rememberSubgroups(groupId: String?): AsyncResource<List<SubgroupEntry>> = rememberMeroResource(groupId) { subgroups(groupId) }

@Composable
fun rememberDefaultCapabilities(groupId: String?): AsyncResource<Int?> = rememberMeroResource(groupId) { defaultCapabilities(groupId) }

@Composable
fun rememberSubgroupVisibility(groupId: String?): AsyncResource<String?> = rememberMeroResource(groupId) { subgroupVisibility(groupId) }

@Composable
fun rememberGroupUpgradeStatus(groupId: String?): AsyncResource<GroupUpgradeStatus?> =
    rememberMeroResource(groupId) { groupUpgradeStatus(groupId) }

@Composable
fun rememberGroupMetadata(groupId: String?): AsyncResource<MetadataRecord?> = rememberMeroResource(groupId) { groupMetadata(groupId) }

@Composable
fun rememberMemberMetadata(
    groupId: String?,
    identity: String?,
): AsyncResource<MetadataRecord?> = rememberMeroResource(groupId, identity) { memberMetadata(groupId, identity) }

@Composable
fun rememberNamespaces(): AsyncResource<List<Namespace>> = rememberMeroResource { namespaces() }

@Composable
fun rememberNamespace(namespaceId: String?): AsyncResource<Namespace?> = rememberMeroResource(namespaceId) { namespace(namespaceId) }

@Composable
fun rememberOutboundApprovals(namespaceId: String?): AsyncResource<List<OutboundApproval>> =
    rememberMeroResource(namespaceId) { outboundApprovals(namespaceId) }

@Composable
fun rememberNodeIdentity(): AsyncResource<NodeIdentity?> = rememberMeroResource { nodeIdentity() }

@Composable
fun rememberNamespacesForApplication(applicationId: String?): AsyncResource<List<Namespace>> =
    rememberMeroResource(applicationId) { namespacesForApplication(applicationId) }

@Composable
fun rememberNamespaceGroups(namespaceId: String?): AsyncResource<List<SubgroupEntry>> =
    rememberMeroResource(namespaceId) { namespaceGroups(namespaceId) }

@Composable
fun rememberBlobInfo(
    blobId: String?,
    contextId: String? = null,
): AsyncResource<GetBlobInfoResponseData?> = rememberMeroResource(blobId, contextId) { blobInfo(blobId, contextId) }

/** A blob as a file in the app's cache directory; deleted when it leaves the composition. */
@Composable
fun rememberBlobFile(
    blobId: String?,
    contextId: String? = null,
    extension: String? = null,
): BlobFileState {
    val cacheDir = LocalContext.current.cacheDir
    return rememberMeroHolder(blobId, contextId, extension) { sessions, scope ->
        BlobFileState(sessions, scope, blobId, java.io.File(cacheDir, "mero-blobs"), contextId, extension)
    }
}

@Composable
fun rememberGroupCapabilities(
    groupId: String?,
    memberId: String?,
): GroupCapabilitiesState = rememberMeroHolder(groupId, memberId) { sessions, scope -> GroupCapabilitiesState(sessions, scope, groupId, memberId) }

// ---- Live -----------------------------------------------------------------------------------

@Composable
fun rememberExecute(contextId: String?): ExecuteState = rememberMeroMutation(contextId) { ExecuteState(it, contextId) }

/** Events of [contextIds] / [groupIds] to [onEvent] (the latest lambda), while in the composition. */
@Composable
fun rememberSubscription(
    contextIds: List<String> = emptyList(),
    groupIds: List<String> = emptyList(),
    onEvent: (NodeEvent) -> Unit,
): SubscriptionState {
    val latest by rememberUpdatedState(onEvent)
    return rememberMeroHolder(contextIds, groupIds) { sessions, scope ->
        SubscriptionState(sessions, scope, contextIds, groupIds) { latest(it) }
    }
}

@Composable
fun <T> rememberEphemeral(
    contextId: String?,
    codec: Codec<T>,
    initial: T? = null,
    throttleMs: Long = EphemeralState.DEFAULT_THROTTLE_MS,
    includeSelf: Boolean = false,
): EphemeralState<T> =
    rememberMeroHolder(contextId, includeSelf, throttleMs) { sessions, scope ->
        EphemeralState(sessions, scope, contextId, codec, initial, throttleMs, includeSelf)
    }

@Composable
fun rememberContextDiscovery(options: ContextDiscoveryOptions): ContextDiscoveryState =
    rememberMeroMutation(options) { ContextDiscoveryState(it, options) }

// ---- Upgrades and migration ------------------------------------------------------------------

@Composable
fun rememberMigrationEvents(
    namespaceId: String?,
    onEvent: (GroupMigrationEvent) -> Unit,
): MigrationEventsState {
    val latest by rememberUpdatedState(onEvent)
    return rememberMeroHolder(namespaceId) { sessions, scope -> MigrationEventsState(sessions, scope, namespaceId) { latest(it) } }
}

@Composable
fun rememberMigrationStatus(
    namespaceId: String?,
    pollIntervalMs: Long? = null,
): MigrationStatusState = rememberMeroHolder(namespaceId, pollIntervalMs) { sessions, scope -> MigrationStatusState(sessions, scope, namespaceId, pollIntervalMs) }

@Composable
fun rememberAppVersion(
    contextId: String?,
    expected: String? = null,
): AppVersionState = rememberMeroHolder(contextId, expected) { sessions, scope -> AppVersionState(sessions, scope, contextId, expected) }

@Composable
fun rememberLatestVersion(
    registryUrl: String?,
    packageName: String?,
    currentVersion: String? = null,
): LatestVersionState =
    rememberMeroHolder(registryUrl, packageName, currentVersion) { sessions, scope ->
        LatestVersionState(sessions, scope, registryUrl, packageName, currentVersion)
    }

@Composable
fun rememberGroupAppVersion(groupId: String?): GroupAppVersionState = rememberMeroHolder(groupId) { sessions, scope -> GroupAppVersionState(sessions, scope, groupId) }

@Composable
fun rememberMyAuthoredMigration(contextId: String?): MyAuthoredMigrationState =
    rememberMeroHolder(contextId) { sessions, scope -> MyAuthoredMigrationState(sessions, scope, contextId) }

// ---- Writes ----------------------------------------------------------------------------------

@Composable fun rememberCreateContext(): CreateContext = rememberMeroMutation { CreateContext(it) }

@Composable fun rememberCreatePrivateContext(): CreatePrivateContext = rememberMeroMutation { CreatePrivateContext(it) }

@Composable fun rememberDeleteContext(): DeleteContext = rememberMeroMutation { DeleteContext(it) }

@Composable fun rememberJoinContext(throwOnError: Boolean = false): JoinContext = rememberMeroMutation(throwOnError) { JoinContext(it, throwOnError) }

@Composable fun rememberResyncContext(): ResyncContext = rememberMeroMutation { ResyncContext(it) }

@Composable fun rememberDeleteGroup(): DeleteGroup = rememberMeroMutation { DeleteGroup(it) }

@Composable fun rememberSyncGroup(): SyncGroup = rememberMeroMutation { SyncGroup(it) }

@Composable fun rememberAddGroupMembers(): AddGroupMembers = rememberMeroMutation { AddGroupMembers(it) }

@Composable fun rememberRemoveGroupMembers(): RemoveGroupMembers = rememberMeroMutation { RemoveGroupMembers(it) }

@Composable fun rememberUpdateMemberRole(): UpdateMemberRole = rememberMeroMutation { UpdateMemberRole(it) }

@Composable fun rememberSetDefaultCapabilities(): SetDefaultCapabilities = rememberMeroMutation { SetDefaultCapabilities(it) }

@Composable fun rememberSetSubgroupVisibility(): SetSubgroupVisibility = rememberMeroMutation { SetSubgroupVisibility(it) }

@Composable fun rememberSetTeeAdmissionPolicy(): SetTeeAdmissionPolicy = rememberMeroMutation { SetTeeAdmissionPolicy(it) }

@Composable fun rememberReparentGroup(): ReparentGroup = rememberMeroMutation { ReparentGroup(it) }

@Composable fun rememberDetachContextFromGroup(): DetachContextFromGroup = rememberMeroMutation { DetachContextFromGroup(it) }

@Composable fun rememberJoinSubgroupInheritance(): JoinSubgroupInheritance = rememberMeroMutation { JoinSubgroupInheritance(it) }

@Composable fun rememberGroupInvitations(): GroupInvitations = rememberMeroMutation { GroupInvitations(it) }

@Composable fun rememberJoinGroup(): JoinGroup = rememberMeroMutation { JoinGroup(it) }

@Composable fun rememberSetGroupMetadata(): SetGroupMetadata = rememberMeroMutation { SetGroupMetadata(it) }

@Composable fun rememberSetMemberMetadata(): SetMemberMetadata = rememberMeroMutation { SetMemberMetadata(it) }

@Composable fun rememberSetContextMetadata(): SetContextMetadata = rememberMeroMutation { SetContextMetadata(it) }

@Composable fun rememberCreateNamespace(): CreateNamespace = rememberMeroMutation { CreateNamespace(it) }

@Composable fun rememberDeleteNamespace(): DeleteNamespace = rememberMeroMutation { DeleteNamespace(it) }

@Composable fun rememberSetOutboundApproval(): SetOutboundApproval = rememberMeroMutation { SetOutboundApproval(it) }

@Composable fun rememberCreateNamespaceInvitation(): CreateNamespaceInvitation = rememberMeroMutation { CreateNamespaceInvitation(it) }

@Composable fun rememberJoinNamespace(throwOnError: Boolean = false): JoinNamespace = rememberMeroMutation(throwOnError) { JoinNamespace(it, throwOnError) }

@Composable fun rememberRedeemInvitation(): RedeemInvitation = rememberMeroMutation { RedeemInvitation(it) }

@Composable fun rememberCreateGroupInNamespace(): CreateGroupInNamespace = rememberMeroMutation { CreateGroupInNamespace(it) }

@Composable fun rememberUpgradeGroup(): UpgradeGroup = rememberMeroMutation { UpgradeGroup(it) }

@Composable fun rememberRetryGroupUpgrade(): RetryGroupUpgrade = rememberMeroMutation { RetryGroupUpgrade(it) }

@Composable fun rememberInstallFromRegistry(): InstallFromRegistry = rememberMeroMutation { InstallFromRegistry(it) }

@Composable fun rememberUploadBlob(): UploadBlob = rememberMeroMutation { UploadBlob(it) }
