package com.calimero.mero.compose

import com.calimero.mero.Mero
import com.calimero.mero.account.ApplicationIds
import com.calimero.mero.account.NoRelayException
import com.calimero.mero.account.ResolvedApplication
import com.calimero.mero.admin.AdminSurface
import com.calimero.mero.admin.CreateContextRequest
import com.calimero.mero.admin.CreateGroupInNamespaceRequest
import com.calimero.mero.admin.MetadataRecord
import com.calimero.mero.admin.RemoveGroupMembersRequest
import com.calimero.mero.admin.SetMetadataRequest
import com.calimero.mero.admin.SetSubgroupVisibilityRequest
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.admin.UpdateMemberRoleRequest
import com.calimero.mero.admin.UploadBlobRequest
import com.calimero.mero.app.AppBlob
import com.calimero.mero.app.AppContextEntry
import com.calimero.mero.app.AppContextEvent
import com.calimero.mero.app.AppFoundedNamespace
import com.calimero.mero.app.AppJoinedNamespace
import com.calimero.mero.app.AppMember
import com.calimero.mero.app.AppMetadata
import com.calimero.mero.app.AppNamespace
import com.calimero.mero.app.AppSubgroup
import com.calimero.mero.app.ContextContract
import com.calimero.mero.app.MeroAppBackend
import com.calimero.mero.app.NoRelayContextContract
import com.calimero.mero.app.RelayContextContract
import com.calimero.mero.relay.ApplicationTarget
import com.calimero.mero.relay.GovernanceMemberRole
import com.calimero.mero.relay.RelayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement

/**
 * [MeroAppBackend] for a Calimero Cloud account, delegated end to end: a snapshot of [client]'s
 * connection when it was made, which [MeroAppSession] rebuilds when the session changes.
 * (Swift `MeroKitUI.DelegatedAppBackend`.)
 *
 * - **Contract calls**: [RelayContextContract] over the connection's [RelayClient] (relay query /
 *   warrant intents); [NoRelayContextContract] while no relay serves the account.
 * - **Listings**: the account admin ([MeroClient.admin]), whose reads are the relay's
 *   caller-scoped admin API.
 * - **Subgroups and contexts**: the account admin turns `createGroupInNamespace`,
 *   `setSubgroupVisibility`, `createContext` and `joinContext` into governance and context
 *   intents posted to the namespace's relay.
 * - **Namespaces**: [MeroClient.foundNamespace] founds through the relay;
 *   [MeroClient.createNamespaceInvitation] signs invitations with the device key;
 *   [MeroClient.joinWithInvitation] is direct admission through the relay the cloud routes to.
 * - **Names, members, leaving and deleting**: the account admin reads them from the relay's
 *   caller-scoped admin (`/groups/{id}/contexts`, `/members`, `/metadata`) and turns each write
 *   into its governance op (`GroupMetadataSet`, `ContextMetadataSet`, `MemberMetadataSet`,
 *   `MemberRoleSet`, `MemberRemoved`, `MemberLeft`, `GroupDeleted`, `ContextDetached`) on the
 *   namespace's relay.
 * - **Blobs**: the relay's blob routes, through a context the account is a member of.
 * - **Live updates**: the relay's event stream over the Bearer session.
 *
 * @throws IllegalStateException from the constructor when [client] holds no account session.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class DelegatedAppBackend(
    private val client: MeroClient,
    /** The app's registry package: what a namespace is founded with. */
    val packageName: String,
    /** The registry [packageName] is resolved in. */
    val registryUrl: String = ApplicationIds.DEFAULT_REGISTRY_URL,
    /** The app's application id, when known: [listNamespaces] keeps only the namespaces running it. Null lists every namespace. */
    val applicationId: String? = null,
    /**
     * What a new namespace is founded with. Null resolves [packageName] from the registry; a test
     * names the bundle installed on its relay, which the registry does not list.
     */
    val foundingApplication: ApplicationTarget? = null,
    /** Where to send a join. Null asks the Cloud manager for the admitting relay; a test with no cloud names its relay. */
    val joinNodeUrl: String? = null,
    private val resolveApplication: suspend (packageName: String, registryUrl: String) -> ResolvedApplication = { pkg, registry ->
        ApplicationIds.resolveFromRegistry(pkg, registry)
    },
    /** The admin to read and write through; null takes [MeroClient.admin]. A test names its own. */
    adminSurface: AdminSurface? = null,
) : MeroAppBackend {
    override val account: String = checkNotNull(client.state.value.account) { "DelegatedAppBackend needs a signed-in account" }

    /** The account admin this snapshot reads and writes through. */
    val admin: AdminSurface = adminSurface ?: checkNotNull(client.admin) { "DelegatedAppBackend needs a signed-in account" }

    /** The account's relay, or null when none serves it yet. */
    val relay: RelayClient? = client.relay

    /** The relay's Bearer session, for live events; null until it is minted. */
    private val live: Mero? = client.mero.takeIf { relay != null && client.state.value.sessionReady }

    override val contract: ContextContract = relay?.let { RelayContextContract(it) } ?: NoRelayContextContract

    override val hasRelay: Boolean get() = relay != null

    override val canSubscribe: Boolean get() = live != null

    override suspend fun listNamespaces(): List<AppNamespace> {
        val all = admin.listNamespaces()
        val mine = applicationId?.let { app -> all.filter { it.targetApplicationId.equals(app, ignoreCase = true) } } ?: all
        return mine.map { AppNamespace(it.namespaceId, it.name, it.targetApplicationId, it.memberCount) }
    }

    override suspend fun listSubgroups(namespaceId: String): List<AppSubgroup> {
        val subgroups = admin.listSubgroups(namespaceId)
        return coroutineScope {
            subgroups
                .map { subgroup ->
                    async {
                        // A subgroup whose contexts cannot be read still lists, empty.
                        val contexts =
                            try {
                                admin.listGroupContexts(subgroup.groupId).map { it.contextId }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                emptyList()
                            }
                        AppSubgroup(subgroup.groupId, subgroup.name, contexts)
                    }
                }.awaitAll()
        }
    }

    override suspend fun listContexts(groupId: String): List<String> = admin.listGroupContexts(groupId).map { it.contextId }

    override suspend fun isMember(contextId: String): Boolean = admin.getContextIdentitiesOwned(contextId).identities.isNotEmpty()

    override suspend fun joinContext(contextId: String) {
        admin.joinContext(contextId)
    }

    override suspend fun foundNamespace(name: String): AppFoundedNamespace {
        if (relay == null) throw NoRelayException("foundNamespace")
        val app =
            foundingApplication
                ?: resolveApplication(packageName, registryUrl).let { ApplicationTarget(it.applicationId, it.packageName, it.version) }
        val founded = client.foundNamespace(application = app, name = name)
        return AppFoundedNamespace(founded.namespaceId, founded.haEnabled, founded.haError, founded.nameError)
    }

    override suspend fun createSubgroup(
        namespaceId: String,
        name: String,
        isPublic: Boolean,
    ): String {
        val visibility =
            if (isPublic) CreateGroupInNamespaceRequest.VISIBILITY_OPEN else CreateGroupInNamespaceRequest.VISIBILITY_RESTRICTED
        // Born with its visibility (a subgroup created restricted and flipped open strands every
        // member outside it), then set explicitly as well, as the web apps do. A failure of the
        // second is not fatal.
        val groupId = admin.createGroupInNamespace(namespaceId, CreateGroupInNamespaceRequest(groupName = name, visibility = visibility)).groupId
        try {
            admin.setSubgroupVisibility(groupId, SetSubgroupVisibilityRequest(subgroupVisibility = visibility))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The subgroup already carries the visibility it was born with.
        }
        return groupId
    }

    override suspend fun createContext(
        groupId: String,
        applicationId: String,
        name: String,
        initArgs: JsonElement,
    ): String =
        admin
            .createContext(
                CreateContextRequest(
                    applicationId = applicationId,
                    groupId = groupId,
                    initializationParams = initArgs.toString().toByteArray(Charsets.UTF_8).map { it.toInt() and BYTE_MASK },
                    name = name,
                ),
            ).contextId

    override suspend fun createInvitation(namespaceId: String): SignedGroupOpenInvitation = client.createNamespaceInvitation(namespaceId)

    override suspend fun joinNamespace(
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
    ): AppJoinedNamespace {
        val joined =
            if (joinNodeUrl == null) {
                client.joinWithInvitation(namespaceId, invitation)
            } else {
                client.account.joinAsAccount(namespaceId, invitation, joinNodeUrl).also { client.restore() }
            }
        return AppJoinedNamespace(namespaceId, joined.published, joined.relayUrl)
    }

    // ---- names ---------------------------------------------------------------------------

    override suspend fun listContextEntries(groupId: String): List<AppContextEntry> =
        admin.listGroupContexts(groupId).map { AppContextEntry(it.contextId, it.name) }

    override suspend fun groupMetadata(groupId: String): AppMetadata? = admin.getGroupMetadata(groupId)?.toApp()

    override suspend fun contextMetadata(
        groupId: String,
        contextId: String,
    ): AppMetadata? = admin.getContextMetadata(groupId, contextId)?.toApp()

    /** Setting metadata replaces the whole record, so the current `data` is read first and sent back with the new name. */
    override suspend fun setGroupName(
        groupId: String,
        name: String,
    ) {
        val data = admin.getGroupMetadata(groupId)?.data
        admin.setGroupMetadata(groupId, SetMetadataRequest(name = name, data = data))
    }

    override suspend fun setContextName(
        groupId: String,
        contextId: String,
        name: String,
    ) {
        val data = admin.getContextMetadata(groupId, contextId)?.data
        admin.setContextMetadata(groupId, contextId, SetMetadataRequest(name = name, data = data))
    }

    // ---- blobs ---------------------------------------------------------------------------

    override suspend fun uploadBlob(
        contextId: String,
        data: ByteArray,
    ): AppBlob = admin.uploadBlob(UploadBlobRequest(data = data, contextId = contextId)).let { AppBlob(it.blobId, it.size) }

    override suspend fun getBlob(
        blobId: String,
        contextId: String,
    ): ByteArray = admin.getBlob(blobId, contextId)

    // ---- leaving and deleting ------------------------------------------------------------

    override suspend fun leaveNamespace(namespaceId: String) {
        admin.leaveNamespace(namespaceId)
    }

    override suspend fun leaveSubgroup(groupId: String) {
        admin.leaveGroup(groupId)
    }

    override suspend fun deleteSubgroup(groupId: String) {
        admin.deleteGroup(groupId)
    }

    override suspend fun removeContext(
        groupId: String,
        contextId: String,
    ) {
        admin.detachContextFromGroup(groupId, contextId)
    }

    // ---- members -------------------------------------------------------------------------

    override suspend fun listMembers(groupId: String): List<AppMember> =
        admin.listGroupMembers(groupId).members.map { AppMember(it.identity, it.role, it.name) }

    override suspend fun setMemberRole(
        groupId: String,
        identity: String,
        role: GovernanceMemberRole,
    ) {
        admin.updateMemberRole(groupId, identity, UpdateMemberRoleRequest(role.name))
    }

    override suspend fun removeMember(
        groupId: String,
        identity: String,
    ) {
        admin.removeGroupMembers(groupId, RemoveGroupMembersRequest(listOf(identity)))
    }

    override suspend fun setMemberName(
        groupId: String,
        name: String,
    ) {
        val data = admin.getMemberMetadata(groupId, account)?.data
        admin.setMemberMetadata(groupId, account, SetMetadataRequest(name = name, data = data))
    }

    // ---- events --------------------------------------------------------------------------

    override fun events(contextId: String): Flow<AppContextEvent> {
        val mero = live ?: return emptyFlow()
        return mero
            .events(listOf(contextId))
            .filter { it.contextId == contextId }
            .map { AppContextEvent(it.contextId, it.kind) }
    }

    private companion object {
        const val BYTE_MASK = 0xff

        fun MetadataRecord.toApp() = AppMetadata(name, data)
    }
}
