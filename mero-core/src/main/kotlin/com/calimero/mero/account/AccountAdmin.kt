package com.calimero.mero.account

import com.calimero.mero.admin.AccountDevices
import com.calimero.mero.admin.AddGroupMembersRequest
import com.calimero.mero.admin.AdminSurface
import com.calimero.mero.admin.CreateAliasResponseData
import com.calimero.mero.admin.CreateApplicationAliasRequest
import com.calimero.mero.admin.CreateContextAliasRequest
import com.calimero.mero.admin.CreateContextRequest
import com.calimero.mero.admin.CreateContextResponseData
import com.calimero.mero.admin.CreateDeviceAliasRequest
import com.calimero.mero.admin.CreateGroupInNamespaceRequest
import com.calimero.mero.admin.CreateGroupInNamespaceResponseData
import com.calimero.mero.admin.CreateGroupInvitationRequest
import com.calimero.mero.admin.CreateGroupInvitationResult
import com.calimero.mero.admin.CreateNamespaceInvitationRequest
import com.calimero.mero.admin.CreateNamespaceInvitationResponseData
import com.calimero.mero.admin.CreateNamespaceInvitationResult
import com.calimero.mero.admin.CreateNamespaceRequest
import com.calimero.mero.admin.CreateNamespaceResponseData
import com.calimero.mero.admin.DeleteAliasResponseData
import com.calimero.mero.admin.DeleteBlobResponseData
import com.calimero.mero.admin.DeleteContextRequest
import com.calimero.mero.admin.DeleteContextResponseData
import com.calimero.mero.admin.DeleteGroupRequest
import com.calimero.mero.admin.DeleteGroupResponseData
import com.calimero.mero.admin.DeleteNamespaceRequest
import com.calimero.mero.admin.DeleteNamespaceResponseData
import com.calimero.mero.admin.DetachContextFromGroupRequest
import com.calimero.mero.admin.GenerateContextIdentityResponseData
import com.calimero.mero.admin.GetBlobInfoResponseData
import com.calimero.mero.admin.GetContextIdentitiesResponseData
import com.calimero.mero.admin.GetTeeAdmissionPolicyResponseData
import com.calimero.mero.admin.GroupMember
import com.calimero.mero.admin.InstallApplicationRequest
import com.calimero.mero.admin.InstallApplicationResponseData
import com.calimero.mero.admin.InstallDevApplicationRequest
import com.calimero.mero.admin.JoinContextResponseData
import com.calimero.mero.admin.JoinGroupRequest
import com.calimero.mero.admin.JoinGroupResponseData
import com.calimero.mero.admin.JoinNamespaceRequest
import com.calimero.mero.admin.JoinNamespaceResponseData
import com.calimero.mero.admin.JoinSubgroupInheritanceResponseData
import com.calimero.mero.admin.ListAliasesResponseData
import com.calimero.mero.admin.ListBlobsResponseData
import com.calimero.mero.admin.ListNamespacesResponseData
import com.calimero.mero.admin.LookupAliasResponseData
import com.calimero.mero.admin.NodeIdentity
import com.calimero.mero.admin.RedeemOutcome
import com.calimero.mero.admin.RemoveGroupMembersRequest
import com.calimero.mero.admin.ReparentGroupRequest
import com.calimero.mero.admin.ReparentGroupResponseData
import com.calimero.mero.admin.RetryGroupUpgradeRequest
import com.calimero.mero.admin.RetryGroupUpgradeResponseData
import com.calimero.mero.admin.RevokeDeviceRequest
import com.calimero.mero.admin.RevokeDeviceResponseData
import com.calimero.mero.admin.SetContextMetadataRequest
import com.calimero.mero.admin.SetDefaultCapabilitiesRequest
import com.calimero.mero.admin.SetGroupMetadataRequest
import com.calimero.mero.admin.SetMemberCapabilitiesRequest
import com.calimero.mero.admin.SetMemberMetadataRequest
import com.calimero.mero.admin.SetOutboundApprovalRequest
import com.calimero.mero.admin.SetOutboundApprovalResponseData
import com.calimero.mero.admin.SetSubgroupVisibilityRequest
import com.calimero.mero.admin.SetTeeAdmissionPolicyRequest
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.admin.SyncGroupRequest
import com.calimero.mero.admin.SyncGroupResponseData
import com.calimero.mero.admin.UninstallApplicationResponseData
import com.calimero.mero.admin.UpdateMemberRoleRequest
import com.calimero.mero.admin.UpgradeGroupRequest
import com.calimero.mero.admin.UpgradeGroupResponseData
import com.calimero.mero.admin.UploadBlobRequest
import com.calimero.mero.admin.UploadBlobResponseData
import com.calimero.mero.admin.adminRedeemer
import com.calimero.mero.admin.redeemInvitation
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.MeroException
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.relay.ApplicationTarget
import com.calimero.mero.relay.GovernResult
import com.calimero.mero.relay.GovernanceMemberRole
import com.calimero.mero.relay.GovernanceOp
import com.calimero.mero.relay.GovernanceOps
import com.calimero.mero.relay.IntentRefusedException
import kotlinx.serialization.json.JsonElement

/*
 * An account's admin API — port of mero-js `src/account/account-admin.ts` and
 * `src/account/node-admin.ts`.
 *
 * The same method names and types as a node's [com.calimero.mero.admin.AdminApi]
 * ([AdminSurface]), so an app written against a node's admin needs no second code path.
 * Reads go to the relay's caller-scoped admin client; writes go through the relay as
 * governance ops, delegated context and namespace creation, or an invitation the account signs
 * itself. A call with no account form is refused by name ([NotForAccountException]); with no
 * relay yet, listings are empty and everything else names the missing relay ([NoRelayException]).
 */

/** [method] has no account form: it needs a node. mero-js `NotForAccountError`. */
class NotForAccountException(
    val method: String,
) : MeroException("$method is not available for an account: it needs a node")

/**
 * An account that has enrolled but joined nothing has no relay: nothing serves it yet. Its
 * listings are empty, which is true; anything else throws this. mero-js `NoRelayError`.
 */
class NoRelayException(
    val method: String,
) : MeroException("$method needs a relay, and this account has none yet: join from an invitation first")

/** The app an account admin founds namespaces for (mero-react's provider `app`). */
data class AccountApp(
    /** The registry package [AdminSurface.createNamespace] names; without one it is refused. */
    val packageName: String? = null,
    /** The version to found on; the registry's latest live version when null. */
    val packageVersion: String? = null,
    /** The registry to resolve the latest version from. */
    val registryUrl: String = ApplicationIds.DEFAULT_REGISTRY_URL,
)

/**
 * What an account admin does its work with — mero-js `AccountAdminDeps`, as an interface so
 * a test (or an app) can substitute any step. [CloudAccountAdminDeps] is the real one.
 */
interface AccountAdminDeps {
    /** A group op on [groupId] ([CloudAccount.governGroup]). */
    suspend fun governGroup(
        session: DelegatedSession,
        groupId: String,
        op: GovernanceOp,
    ): GovernResult

    /** A root op posted to [groupId]'s namespace ([CloudAccount.governRoot]). */
    suspend fun governRoot(
        session: DelegatedSession,
        groupId: String,
        op: GovernanceOp,
    ): GovernResult

    /** [CloudAccount.createDelegatedContext]. */
    suspend fun createContext(
        session: DelegatedSession,
        request: CreateDelegatedContextRequest,
    ): CreatedDelegatedContext

    /** [CloudAccount.foundDelegatedNamespace]. */
    suspend fun found(
        session: DelegatedSession,
        defaultCapabilities: Long?,
        application: ApplicationTarget,
    ): FoundedDelegatedNamespace

    /** [latestPublishedVersion]. */
    suspend fun latestVersion(
        registryUrl: String,
        packageName: String,
    ): String

    /** Sign an invitation as this device ([Invitations.signGroupInvitation]). */
    fun signInvitation(
        session: DelegatedSession,
        namespaceId: String,
        admitters: List<String>,
        members: List<GroupMember>,
        applicationId: String?,
        appKey: String?,
    ): SignedGroupOpenInvitation

    /** [CloudAccount.namespaceOfGroup]. */
    fun namespaceOfGroup(
        session: DelegatedSession,
        groupId: String,
    ): String?

    /** [CloudAccount.rememberGroupNamespace]. */
    fun rememberGroupNamespace(
        account: String,
        groupId: String,
        namespaceId: String,
    )

    /**
     * Redeem an invitation for this account and switch the session onto the relay it names,
     * returning the namespace joined. The session's owner supplies it; `null` refuses a join
     * by name. Throws the refusal (with its HTTP status).
     */
    val join: (suspend (namespaceId: String, invitation: SignedGroupOpenInvitation) -> String)? get() = null

    /**
     * The accounts of the nodes the cloud routes [namespaceId] to — the nodes a node-less
     * invitee can be admitted through. Given, an invitation is minted only if one of them is an
     * admitter it names; a lookup that throws does not block minting. `null` skips the check.
     */
    val routing: (suspend (namespaceId: String) -> List<String?>)? get() = null
}

/**
 * The real [AccountAdminDeps]: governance, creation and founding through [account]'s relays,
 * invitations signed by its device, and the cloud's routing.
 *
 * @param join defaults to [CloudAccount.joinAsAccount] (the session-moving form), persisting
 *   the moved session; pass your own to also switch an in-memory session, or `null` to refuse joins.
 */
class CloudAccountAdminDeps(
    private val account: CloudAccount,
    override val join: (suspend (namespaceId: String, invitation: SignedGroupOpenInvitation) -> String)? = { ns, invitation ->
        account.joinAsAccount(account.requireSession(), ns, invitation) { moved -> account.saveSession(moved) }
    },
    override val routing: (suspend (namespaceId: String) -> List<String?>)? = { ns ->
        account
            .cloud(account.requireSession())
            .getNamespaceRouting(ns)
            .nodes
            .map { it.account }
    },
) : AccountAdminDeps {
    override suspend fun governGroup(
        session: DelegatedSession,
        groupId: String,
        op: GovernanceOp,
    ): GovernResult = account.governGroup(session, groupId, op)

    override suspend fun governRoot(
        session: DelegatedSession,
        groupId: String,
        op: GovernanceOp,
    ): GovernResult = account.governRoot(session, groupId, op)

    override suspend fun createContext(
        session: DelegatedSession,
        request: CreateDelegatedContextRequest,
    ): CreatedDelegatedContext = account.createDelegatedContext(session, request)

    override suspend fun found(
        session: DelegatedSession,
        defaultCapabilities: Long?,
        application: ApplicationTarget,
    ): FoundedDelegatedNamespace = account.foundDelegatedNamespace(session, defaultCapabilities, application)

    override suspend fun latestVersion(
        registryUrl: String,
        packageName: String,
    ): String = latestPublishedVersion(registryUrl, packageName, account.httpClient)

    override fun signInvitation(
        session: DelegatedSession,
        namespaceId: String,
        admitters: List<String>,
        members: List<GroupMember>,
        applicationId: String?,
        appKey: String?,
    ): SignedGroupOpenInvitation =
        Invitations.signGroupInvitation(
            groupId = namespaceId,
            inviterAccount = session.account,
            signer = account.deviceKeys.loadOrCreate().signer(),
            admitters = admitters,
            members = members,
            applicationId =
                applicationId?.lowercase()?.takeIf {
                    com.calimero.mero.crypto.Hex
                        .isHex32(it)
                },
            appKey =
                appKey?.lowercase()?.takeIf {
                    com.calimero.mero.crypto.Hex
                        .isHex32(it)
                },
        )

    override fun namespaceOfGroup(
        session: DelegatedSession,
        groupId: String,
    ): String? = account.namespaceOfGroup(session, groupId)

    override fun rememberGroupNamespace(
        account: String,
        groupId: String,
        namespaceId: String,
    ) = this.account.rememberGroupNamespace(account, groupId, namespaceId)
}

/**
 * An account's admin API over [read] — the relay's caller-scoped admin client (a
 * [com.calimero.mero.transport.MeroTransportClient.admin] from [CloudAccount.buildDelegatedClient]),
 * or `null` for an account with no relay yet — mero-js `createAccountAdmin`.
 *
 * Overridden: `listNamespacesForApplication` (filtered from the scoped list), `getNodeIdentity`
 * (the account itself), `createNamespace` (founded through the relay with [app]'s package),
 * subgroup create / delete / reparent and joins (root ops), member, role, capability, metadata,
 * visibility, leave and detach (group ops), `createContext` (a creation warrant), context
 * identities and `joinContext` (the account is its own identity), `createNamespaceInvitation`
 * (signed by this device, naming the namespace's relays as admitters), `joinNamespace` /
 * `redeemInvitation` ([AccountAdminDeps.join]) and the no-op syncs. Upgrades, outbound approvals
 * (`setOutboundApproval`, and `createNamespace` with `approveOutbound`), installs, aliases,
 * context identities and deletes of a node's own copy, TEE policy, blob deletion and account
 * devices are refused by name. Everything else is a read, answered by the relay.
 */
fun createAccountAdmin(
    session: DelegatedSession,
    read: AdminSurface?,
    app: AccountApp = AccountApp(),
    deps: AccountAdminDeps,
): AdminSurface = AccountAdmin(session, read, app, deps)

/** [createAccountAdmin] with this account's real deps ([CloudAccountAdminDeps]). */
fun CloudAccount.accountAdmin(
    session: DelegatedSession,
    read: AdminSurface?,
    app: AccountApp = AccountApp(),
): AdminSurface = createAccountAdmin(session, read, app, CloudAccountAdminDeps(this))

/** core's `MemberRoleSet` default mask for a namespace an account founds (mero-js uses 231). */
private const val FOUNDED_DEFAULT_CAPABILITIES = 231L
private const val U32 = 0xFFFF_FFFFL
private const val ROLE_RELAY_TEE = "RelayTee"
private val ALREADY_DIRECT_MEMBER = Regex("signer [0-9a-f]+ is a direct member", RegexOption.IGNORE_CASE)

@Suppress("TooManyFunctions", "LargeClass")
private class AccountAdmin(
    private val s: DelegatedSession,
    private val read: AdminSurface?,
    private val app: AccountApp,
    private val deps: AccountAdminDeps,
) : AdminSurface by (read ?: NoRelayAdmin) {
    private fun relay(method: String): AdminSurface = read ?: throw NoRelayException(method)

    private fun refuse(method: String): Nothing = if (read == null) throw NoRelayException(method) else throw NotForAccountException(method)

    /**
     * A group or root op is posted to the group's namespace. A subgroup this account neither
     * created nor joined is one the relay can name: ask it once, and remember.
     */
    private suspend fun knowNamespaceOf(
        method: String,
        groupId: String,
    ) {
        if (deps.namespaceOfGroup(s, groupId) != null) return
        val info = relay(method).getGroupInfo(groupId)
        deps.rememberGroupNamespace(s.account, groupId, info.namespaceId ?: groupId)
    }

    private suspend fun group(
        method: String,
        groupId: String,
        op: GovernanceOp,
    ): GovernResult {
        knowNamespaceOf(method, groupId)
        return deps.governGroup(s, groupId, op)
    }

    private suspend fun root(
        method: String,
        groupId: String,
        op: GovernanceOp,
    ): GovernResult {
        knowNamespaceOf(method, groupId)
        return deps.governRoot(s, groupId, op)
    }

    /**
     * `MemberJoinedOpen` on [groupId], as a node's join sends it: an inherited member becomes
     * a direct one, and a direct member's join is a no-op — core refuses it with "signer … is a
     * direct member", which is the no-op. Returns whether this join made the account a member.
     */
    private suspend fun joinOpen(
        method: String,
        groupId: String,
    ): Boolean =
        try {
            root(method, groupId, GovernanceOps.memberJoinedOpenOp(s.account, groupId, s.credential))
            true
        } catch (e: Exception) {
            if (isAlreadyDirectMember(e)) false else throw e
        }

    private fun isAlreadyDirectMember(e: Exception): Boolean {
        val status =
            when (e) {
                is HttpException -> e.status
                is IntentRefusedException -> e.status
                else -> null
            }
        val text = listOfNotNull(e.message, (e as? HttpException)?.bodyText, (e as? IntentRefusedException)?.reason).joinToString(" ")
        return (status == null || status == 409) && ALREADY_DIRECT_MEMBER.containsMatchIn(text)
    }

    private suspend fun contextGroupOf(
        method: String,
        contextId: String,
    ): String = relay(method).getContextGroup(contextId) ?: throw MeroStateException("the relay names no group for context $contextId")

    private fun roleOf(role: String?): GovernanceMemberRole =
        GovernanceMemberRole.entries.firstOrNull { it.name == (role ?: "Member") }
            ?: throw IllegalArgumentException("unknown member role $role: one of ${GovernanceMemberRole.entries.joinToString()}")

    // ---- Reads an account answers itself -----------------------------------------------

    // The node-wide `for-application` listing is not caller-scoped, so a relay refuses it to
    // an account; its own scoped list, filtered, is the answer.
    override suspend fun listNamespacesForApplication(applicationId: String): ListNamespacesResponseData =
        read?.listNamespaces()?.filter { it.targetApplicationId == applicationId } ?: emptyList()

    override suspend fun getNodeIdentity(): NodeIdentity =
        NodeIdentity(accountId = s.account, deviceId = null, publicKey = "", accountRootPublicKey = "")

    // An account runs as itself in every context it can reach, so its identity there is the
    // account — owned once its account is a member of the context's group.
    override suspend fun getContextIdentitiesOwned(contextId: String): GetContextIdentitiesResponseData {
        val groupId = contextGroupOf("getContextIdentitiesOwned", contextId)
        val members = relay("getContextIdentitiesOwned").listGroupMembers(groupId).members
        val owned = members.any { it.identity.equals(s.account, ignoreCase = true) }
        return GetContextIdentitiesResponseData(if (owned) listOf(s.account) else emptyList())
    }

    // ---- Namespaces and groups ---------------------------------------------------------

    override suspend fun createNamespace(request: CreateNamespaceRequest): CreateNamespaceResponseData {
        relay("createNamespace")
        val pkg = app.packageName ?: throw NotForAccountException("createNamespace without a packageName")
        // Founding through a relay carries no outbound approval; dropping the ask silently would
        // leave the app's requests refused with nothing to say why.
        if (request.approveOutbound != null) throw NotForAccountException("createNamespace with approveOutbound")
        val version = app.packageVersion ?: deps.latestVersion(app.registryUrl, pkg)
        val founded = deps.found(s, FOUNDED_DEFAULT_CAPABILITIES, ApplicationTarget(request.applicationId, pkg, version))
        request.name?.takeIf { it.isNotEmpty() }?.let {
            group("createNamespace", founded.namespaceId, GovernanceOps.groupMetadataSetOp(name = it))
        }
        // A node's result is `{ namespaceId }`; HA's outcome rides along as extra fields.
        return CreateNamespaceResponseData(founded.namespaceId, haEnabled = founded.haEnabled, haError = founded.haError)
    }

    override suspend fun createGroupInNamespace(
        namespaceId: String,
        request: CreateGroupInNamespaceRequest?,
    ): CreateGroupInNamespaceResponseData {
        relay("createGroupInNamespace")
        // Absent ⇒ Open, as on a node: a subgroup created Restricted and flipped Open strands
        // every namespace member outside it.
        val restricted = request?.visibility == CreateGroupInNamespaceRequest.VISIBILITY_RESTRICTED
        val creation = GovernanceOps.subgroupCreation(namespaceId, restricted = restricted, admin = s.account)
        root("createGroupInNamespace", namespaceId, creation.op)
        // The id is derived from the salt, and core refuses any other: the derived one is the answer.
        val groupId = creation.groupId
        deps.rememberGroupNamespace(s.account, groupId, namespaceId)
        request?.groupName?.takeIf { it.isNotEmpty() }?.let {
            group("createGroupInNamespace", groupId, GovernanceOps.groupMetadataSetOp(name = it))
        }
        return CreateGroupInNamespaceResponseData(groupId)
    }

    override suspend fun addGroupMembers(
        groupId: String,
        request: AddGroupMembersRequest,
    ) {
        relay("addGroupMembers")
        for (m in request.members) group("addGroupMembers", groupId, GovernanceOps.memberAddedOp(m.identity, roleOf(m.role)))
    }

    override suspend fun removeGroupMembers(
        groupId: String,
        request: RemoveGroupMembersRequest,
    ) {
        relay("removeGroupMembers")
        for (m in request.members) group("removeGroupMembers", groupId, GovernanceOps.memberRemovedOp(m))
    }

    override suspend fun setGroupMetadata(
        groupId: String,
        request: SetGroupMetadataRequest,
    ) {
        relay("setGroupMetadata")
        group("setGroupMetadata", groupId, GovernanceOps.groupMetadataSetOp(request.name, request.data.orEmpty()))
    }

    override suspend fun setMemberMetadata(
        groupId: String,
        identity: String,
        request: SetMemberMetadataRequest,
    ) {
        relay("setMemberMetadata")
        group("setMemberMetadata", groupId, GovernanceOps.memberMetadataSetOp(identity, request.name, request.data.orEmpty()))
    }

    override suspend fun setContextMetadata(
        groupId: String,
        contextId: String,
        request: SetContextMetadataRequest,
    ) {
        relay("setContextMetadata")
        group("setContextMetadata", groupId, GovernanceOps.contextMetadataSetOp(contextId, request.name, request.data.orEmpty()))
    }

    override suspend fun setDefaultCapabilities(
        groupId: String,
        request: SetDefaultCapabilitiesRequest,
    ) {
        relay("setDefaultCapabilities")
        group("setDefaultCapabilities", groupId, GovernanceOps.defaultCapabilitiesSetOp(request.defaultCapabilities.toLong() and U32))
    }

    override suspend fun setSubgroupVisibility(
        groupId: String,
        request: SetSubgroupVisibilityRequest,
    ) {
        relay("setSubgroupVisibility")
        group("setSubgroupVisibility", groupId, GovernanceOps.subgroupVisibilitySetOp(restricted = request.subgroupVisibility != "open"))
    }

    override suspend fun setMemberCapabilities(
        groupId: String,
        identity: String,
        request: SetMemberCapabilitiesRequest,
    ) {
        relay("setMemberCapabilities")
        group("setMemberCapabilities", groupId, GovernanceOps.memberCapabilitySetOp(identity, request.capabilities.toLong() and U32))
    }

    override suspend fun updateMemberRole(
        groupId: String,
        identity: String,
        request: UpdateMemberRoleRequest,
    ) {
        relay("updateMemberRole")
        group("updateMemberRole", groupId, GovernanceOps.memberRoleSetOp(identity, roleOf(request.role)))
    }

    override suspend fun deleteGroup(
        groupId: String,
        request: DeleteGroupRequest?,
    ): DeleteGroupResponseData {
        relay("deleteGroup")
        root("deleteGroup", groupId, GovernanceOps.groupDeletedOp(groupId))
        return DeleteGroupResponseData(isDeleted = true)
    }

    override suspend fun reparentGroup(
        childGroupId: String,
        request: ReparentGroupRequest,
    ): ReparentGroupResponseData {
        relay("reparentGroup")
        root("reparentGroup", childGroupId, GovernanceOps.groupReparentedOp(childGroupId, request.newParentId))
        return ReparentGroupResponseData(reparented = true)
    }

    override suspend fun leaveGroup(
        groupId: String,
        request: Map<String, JsonElement>?,
    ) {
        relay("leaveGroup")
        group("leaveGroup", groupId, GovernanceOps.memberLeftOp(s.account))
    }

    override suspend fun leaveNamespace(
        namespaceId: String,
        request: Map<String, JsonElement>?,
    ) {
        relay("leaveNamespace")
        group("leaveNamespace", namespaceId, GovernanceOps.memberLeftOp(s.account))
    }

    // An open subgroup is joined the way joinContext joins one: MemberJoinedOpen on it.
    override suspend fun joinSubgroupInheritance(groupId: String): JoinSubgroupInheritanceResponseData {
        relay("joinSubgroupInheritance")
        return JoinSubgroupInheritanceResponseData(groupId, s.account, wasInherited = joinOpen("joinSubgroupInheritance", groupId))
    }

    override suspend fun detachContextFromGroup(
        groupId: String,
        contextId: String,
        request: DetachContextFromGroupRequest?,
    ) {
        relay("detachContextFromGroup")
        group("detachContextFromGroup", groupId, GovernanceOps.contextDetachedOp(contextId))
    }

    // The relay syncs its own view; an account has nothing to pull. The group's state is what the relay holds.
    override suspend fun syncGroup(
        groupId: String,
        request: SyncGroupRequest?,
    ): SyncGroupResponseData {
        val info = relay("syncGroup").getGroupInfo(groupId)
        return SyncGroupResponseData(info.groupId, info.appKey, info.targetApplicationId, info.memberCount, info.contextCount)
    }

    override suspend fun syncContext(contextId: String?) {
        relay("syncContext")
    }

    // ---- Contexts ----------------------------------------------------------------------

    override suspend fun joinContext(contextId: String): JoinContextResponseData {
        joinOpen("joinContext", contextGroupOf("joinContext", contextId))
        return JoinContextResponseData(contextId, s.account)
    }

    override suspend fun createContext(request: CreateContextRequest): CreateContextResponseData {
        val info = relay("createContext").getGroupInfo(request.groupId)
        // Every field with a place in the creation warrant; `identitySecret` has none for an account.
        val created =
            deps.createContext(
                s,
                CreateDelegatedContextRequest(
                    namespaceId = info.namespaceId ?: request.groupId,
                    applicationId = request.applicationId,
                    groupId = request.groupId,
                    serviceName = request.serviceName,
                    contextSeed = request.contextSeed,
                    initializationParams = request.initializationParams,
                    name = request.name,
                ),
            )
        // The relay executes as the account, so the account is the context's member key.
        return CreateContextResponseData(created.contextId, memberPublicKey = s.account, groupId = request.groupId)
    }

    // ---- Invitations and joins ---------------------------------------------------------

    override suspend fun createNamespaceInvitation(
        namespaceId: String,
        request: CreateNamespaceInvitationRequest?,
    ): CreateNamespaceInvitationResult {
        val admin = relay("createNamespaceInvitation")
        val members = admin.listGroupMembers(namespaceId).members
        val info = admin.getGroupInfo(namespaceId)
        // The relays admit: whoever claims an account's invitation may have no node, and only a
        // relay can admit a joiner with none. With no relay in it, the admins do.
        val relays = members.filter { it.role == ROLE_RELAY_TEE }.map { it.identity.lowercase() }
        deps.routing?.let { routing ->
            val named = relays.ifEmpty { members.filter { it.role == "Admin" }.map { it.identity.lowercase() } }.toSet()
            val nodes =
                try {
                    routing(namespaceId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null // nothing learned: the claimant's own lookup will say what is wrong
                }
            if (nodes != null) {
                if (nodes.isEmpty()) throw InvitationNotClaimableException(namespaceId, InvitationNotClaimableReason.NOT_HOSTED)
                if (nodes.none { it != null && it.lowercase() in named }) {
                    throw InvitationNotClaimableException(namespaceId, InvitationNotClaimableReason.NO_NAMED_NODE)
                }
            }
        }
        val invitation = deps.signInvitation(s, namespaceId, relays, members, info.targetApplicationId, info.appKey)
        return CreateNamespaceInvitationResult.Single(CreateNamespaceInvitationResponseData(invitation))
    }

    // Reachable with or without a relay: the join is how an account gets one.
    override suspend fun joinNamespace(
        namespaceId: String,
        request: JoinNamespaceRequest,
    ): JoinNamespaceResponseData {
        val join = deps.join ?: throw NotForAccountException("joinNamespace without a join handler")
        val joined = join(namespaceId, request.invitation)
        return JoinNamespaceResponseData(namespaceIdField = joined, memberIdentity = s.account, memberAccount = s.account)
    }

    override suspend fun redeemInvitation(
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
        groupName: String?,
        teamName: String?,
    ): RedeemOutcome = redeemInvitation(namespaceId, invitation, adminRedeemer(this, groupName), teamName)

    // ---- Refused by name: a node's to do, or no delegated form in core -------------------

    override suspend fun installApplication(
        request: InstallApplicationRequest,
    ): InstallApplicationResponseData = refuse("installApplication")

    override suspend fun installFromRegistry(
        packageName: String,
        version: String,
    ): InstallApplicationResponseData = refuse("installFromRegistry")

    override suspend fun installDevApplication(
        request: InstallDevApplicationRequest,
    ): InstallApplicationResponseData = refuse("installDevApplication")

    override suspend fun uninstallApplication(
        appId: String,
    ): UninstallApplicationResponseData = refuse("uninstallApplication")

    override suspend fun deleteContext(
        contextId: String,
        request: DeleteContextRequest?,
    ): DeleteContextResponseData = refuse("deleteContext")

    override suspend fun generateContextIdentity(): GenerateContextIdentityResponseData = refuse("generateContextIdentity")

    override suspend fun deleteBlob(
        blobId: String,
    ): DeleteBlobResponseData = refuse("deleteBlob")

    // An account session holds `blob:add-own` and `blob:get-own` on its relay: it may store
    // a blob into, and read one out of, a context it is a member of, and the relay checks that
    // membership (and, for a read, that it holds the blob against that context) on every call.
    // So each of these names the context; without one the relay could only refuse.

    /**
     * Store [UploadBlobRequest.data] on the relay, against [UploadBlobRequest.contextId]
     * (required: an account uploads into a context it is a member of). The relay announces it
     * to the context, so the other members' nodes can fetch it.
     */
    override suspend fun uploadBlob(
        request: UploadBlobRequest,
    ): UploadBlobResponseData = withContext("uploadBlob", request.contextId).uploadBlob(request)

    /** A blob's bytes, read through [contextId] (required): the relay serves it from its own store or a peer of that context. */
    override suspend fun getBlob(
        blobId: String,
        contextId: String?,
    ): ByteArray = withContext("getBlob", contextId).getBlob(blobId, contextId)

    /** A blob's metadata, read through [contextId] (required). */
    override suspend fun getBlobInfo(
        blobId: String,
        contextId: String?,
    ): GetBlobInfoResponseData = withContext("getBlobInfo", contextId).getBlobInfo(blobId, contextId)

    /** The blobs the relay scopes to this account (those of its contexts); empty with no relay. */
    override suspend fun listBlobs(): ListBlobsResponseData = read?.listBlobs() ?: ListBlobsResponseData(emptyList())

    private fun withContext(
        method: String,
        contextId: String?,
    ): AdminSurface {
        val relay = relay(method)
        require(!contextId.isNullOrEmpty()) {
            "$method needs a contextId: an account reaches blobs only through a context it is a member of"
        }
        return relay
    }

    override suspend fun createContextAlias(
        request: CreateContextAliasRequest,
    ): CreateAliasResponseData = refuse("createContextAlias")

    override suspend fun createApplicationAlias(
        request: CreateApplicationAliasRequest,
    ): CreateAliasResponseData = refuse("createApplicationAlias")

    override suspend fun lookupContextAlias(
        name: String,
    ): LookupAliasResponseData = refuse("lookupContextAlias")

    override suspend fun lookupApplicationAlias(
        name: String,
    ): LookupAliasResponseData = refuse("lookupApplicationAlias")

    override suspend fun deleteContextAlias(
        name: String,
    ): DeleteAliasResponseData = refuse("deleteContextAlias")

    override suspend fun deleteApplicationAlias(
        name: String,
    ): DeleteAliasResponseData = refuse("deleteApplicationAlias")

    override suspend fun listContextAliases(): ListAliasesResponseData = refuse("listContextAliases")

    override suspend fun listApplicationAliases(): ListAliasesResponseData = refuse("listApplicationAliases")

    override suspend fun listDeviceAliases(): ListAliasesResponseData = refuse("listDeviceAliases")

    override suspend fun createDeviceAlias(
        request: CreateDeviceAliasRequest,
    ): CreateAliasResponseData = refuse("createDeviceAlias")

    override suspend fun lookupDeviceAlias(
        name: String,
    ): LookupAliasResponseData = refuse("lookupDeviceAlias")

    override suspend fun deleteDeviceAlias(
        name: String,
    ): DeleteAliasResponseData = refuse("deleteDeviceAlias")

    override suspend fun deleteNamespace(
        namespaceId: String,
        request: DeleteNamespaceRequest?,
    ): DeleteNamespaceResponseData = refuse("deleteNamespace")

    override suspend fun setTeeAdmissionPolicy(
        groupId: String,
        request: SetTeeAdmissionPolicyRequest,
    ): Unit = refuse("setTeeAdmissionPolicy")

    override suspend fun setTeeAdmissionPolicy(
        groupId: String,
        request: SetTeeAdmissionPolicyRequest,
        root: DeviceSigner,
        keyEpoch: Long,
        chain: List<String>,
    ): Unit = refuse("setTeeAdmissionPolicy")

    override suspend fun getTeeAdmissionPolicy(
        groupId: String,
    ): GetTeeAdmissionPolicyResponseData = refuse("getTeeAdmissionPolicy")

    override suspend fun upgradeGroup(
        groupId: String,
        request: UpgradeGroupRequest,
    ): UpgradeGroupResponseData = refuse("upgradeGroup")

    override suspend fun setOutboundApproval(
        namespaceId: String,
        request: SetOutboundApprovalRequest,
    ): SetOutboundApprovalResponseData = refuse("setOutboundApproval")

    override suspend fun retryGroupUpgrade(
        groupId: String,
        request: RetryGroupUpgradeRequest?,
    ): RetryGroupUpgradeResponseData = refuse("retryGroupUpgrade")

    override suspend fun createGroupInvitation(
        groupId: String,
        request: CreateGroupInvitationRequest?,
    ): CreateGroupInvitationResult = refuse("createGroupInvitation")

    override suspend fun joinGroup(
        request: JoinGroupRequest,
    ): JoinGroupResponseData = refuse("joinGroup")

    override suspend fun leaveContext(
        contextId: String,
        request: Map<String, JsonElement>?,
    ): Unit = refuse("leaveContext")

    override suspend fun abortMigration(
        namespaceId: String,
        request: Map<String, JsonElement>?,
    ): JsonElement = refuse("abortMigration")

    override suspend fun listAccountDevices(): AccountDevices = refuse("listAccountDevices")

    override suspend fun revokeAccountDevice(
        namespaceId: String,
        request: RevokeDeviceRequest,
    ): RevokeDeviceResponseData = refuse("revokeAccountDevice")
}

/**
 * A node's admin API as an app writes against it, with `createNamespace` made whole — mero-js
 * `createNodeAdmin`. On a node a namespace can only be created for an application the node
 * already holds, so this installs [app]'s package from the registry first when it is missing
 * (the step an account never sees: founding through a relay names the package and the relay
 * fetches it), and refuses when the installed bundle is a different application. Same call,
 * same result, on both. Every other call passes through to [admin] unchanged.
 */
fun createNodeAdmin(
    admin: AdminSurface,
    app: AccountApp = AccountApp(),
    latestVersion: suspend (registryUrl: String, packageName: String) -> String = { url, pkg -> latestPublishedVersion(url, pkg) },
): AdminSurface = NodeAdmin(admin, app, latestVersion)

private class NodeAdmin(
    private val admin: AdminSurface,
    private val app: AccountApp,
    private val latestVersion: suspend (String, String) -> String,
) : AdminSurface by admin {
    override suspend fun createNamespace(request: CreateNamespaceRequest): CreateNamespaceResponseData {
        ensureInstalled(request.applicationId)
        return admin.createNamespace(request)
    }

    private suspend fun ensureInstalled(applicationId: String) {
        if (admin.listApplications().apps.any { it.id == applicationId }) return
        val pkg =
            app.packageName
                ?: throw MeroStateException("application $applicationId is not installed on this node, and no packageName is set to install it")
        val version = app.packageVersion ?: latestVersion(app.registryUrl, pkg)
        val installed = admin.installApplication(InstallApplicationRequest(pkg, version)).applicationId
        // A registry bundle signed by a different key is a different application: creating
        // against it would found a workspace for some other app.
        if (installed != applicationId) throw MeroStateException("installed $pkg@$version as $installed, not $applicationId")
    }
}
