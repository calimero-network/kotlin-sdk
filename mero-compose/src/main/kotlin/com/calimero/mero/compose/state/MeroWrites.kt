// Each holder's method keeps its mero-react hook's name (`useCreateContext().createContext`), so the
// member deliberately shares the class's name.
@file:Suppress("MemberNameEqualsClassName")

package com.calimero.mero.compose.state

import com.calimero.mero.admin.AddGroupMembersRequest
import com.calimero.mero.admin.CreateContextRequest
import com.calimero.mero.admin.CreateContextResponseData
import com.calimero.mero.admin.CreateGroupInNamespaceRequest
import com.calimero.mero.admin.CreateGroupInNamespaceResponseData
import com.calimero.mero.admin.CreateGroupInvitationRequest
import com.calimero.mero.admin.CreateGroupInvitationResult
import com.calimero.mero.admin.CreateNamespaceInvitationRequest
import com.calimero.mero.admin.CreateNamespaceInvitationResult
import com.calimero.mero.admin.CreateNamespaceRequest
import com.calimero.mero.admin.CreateNamespaceResponseData
import com.calimero.mero.admin.DeleteContextResponseData
import com.calimero.mero.admin.DeleteGroupResponseData
import com.calimero.mero.admin.DeleteNamespaceResponseData
import com.calimero.mero.admin.GroupMemberInput
import com.calimero.mero.admin.InstallApplicationRequest
import com.calimero.mero.admin.InstallApplicationResponseData
import com.calimero.mero.admin.JoinContextResponseData
import com.calimero.mero.admin.JoinGroupRequest
import com.calimero.mero.admin.JoinGroupResponseData
import com.calimero.mero.admin.JoinNamespaceRequest
import com.calimero.mero.admin.JoinNamespaceResponseData
import com.calimero.mero.admin.JoinSubgroupInheritanceResponseData
import com.calimero.mero.admin.RedeemOutcome
import com.calimero.mero.admin.RemoveGroupMembersRequest
import com.calimero.mero.admin.ReparentGroupRequest
import com.calimero.mero.admin.ReparentGroupResponseData
import com.calimero.mero.admin.ResyncContextRequest
import com.calimero.mero.admin.ResyncContextResponseData
import com.calimero.mero.admin.RetryGroupUpgradeResponseData
import com.calimero.mero.admin.SetContextMetadataRequest
import com.calimero.mero.admin.SetDefaultCapabilitiesRequest
import com.calimero.mero.admin.SetGroupMetadataRequest
import com.calimero.mero.admin.SetMemberMetadataRequest
import com.calimero.mero.admin.SetOutboundApprovalRequest
import com.calimero.mero.admin.SetOutboundApprovalResponseData
import com.calimero.mero.admin.SetSubgroupVisibilityRequest
import com.calimero.mero.admin.SetTeeAdmissionPolicyRequest
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.admin.SyncGroupResponseData
import com.calimero.mero.admin.UpdateMemberRoleRequest
import com.calimero.mero.admin.UpgradeGroupRequest
import com.calimero.mero.admin.UpgradeGroupResponseData
import com.calimero.mero.admin.UploadBlobRequest
import com.calimero.mero.admin.UploadBlobResponseData
import com.calimero.mero.compose.CreatePrivateContextRequest
import com.calimero.mero.compose.MeroSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// The write state holders mero-react's write hooks are, one per hook: each tracks its own
// `loading` / `error` / `failure` (an [AdminMutation]) and resolves `null` with no session to
// send through, or on failure unless `throwOnError`. The session's admin decides how a write
// travels: a node's own call, or — on an account — a warrant or a governance op through the
// relay (the account admin), so one call works on both.

// ---- Contexts --------------------------------------------------------------------------------

/** mero-react `useCreateContext`. An account creates through its relay under a creation warrant. */
class CreateContext(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun createContext(request: CreateContextRequest): CreateContextResponseData? = withAdmin { it.createContext(request) }
}

/**
 * mero-react `useCreatePrivateContext`: a context only you and the request's members are in
 * (a DM, a small room) — its own restricted group in the namespace, members added directly.
 * A node makes the group, adds the members and creates the context with its own admin calls; an
 * account has the relay do the same three steps, each under its own warrant. Returns the context id.
 */
class CreatePrivateContext(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun createPrivateContext(request: CreatePrivateContextRequest): String? {
        val session = sessions.value
        val delegated = session.delegated
        if (session.isDelegated) return delegated?.let { ops -> run { ops.createPrivateContext(request) } }
        return withAdmin { admin ->
            val groupId =
                admin
                    .createGroupInNamespace(
                        request.namespaceId,
                        CreateGroupInNamespaceRequest(visibility = CreateGroupInNamespaceRequest.VISIBILITY_RESTRICTED),
                    ).groupId
            // The creator is the new group's admin already; the others join it here.
            admin.addGroupMembers(groupId, AddGroupMembersRequest(request.members.map { GroupMemberInput(it, "Member") }))
            admin.createContext(CreateContextRequest(request.applicationId, groupId, initializationParams = request.initializationParams)).contextId
        }
    }
}

class DeleteContext(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun deleteContext(contextId: String): DeleteContextResponseData? = withAdmin { it.deleteContext(contextId) }
}

/** mero-react `useJoinContext`: [failure] says what kind of refusal a failed join was. */
class JoinContext(
    sessions: StateFlow<MeroSession>,
    throwOnError: Boolean = false,
) : AdminMutation(sessions, throwOnError) {
    suspend fun joinContext(contextId: String): JoinContextResponseData? = withAdmin { it.joinContext(contextId) }
}

/** mero-react `useResyncContext`: operator recovery for a context stranded mid-sync. */
class ResyncContext(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun resyncContext(
        contextId: String,
        request: ResyncContextRequest = ResyncContextRequest(),
    ): ResyncContextResponseData? = withAdmin { it.resyncContext(contextId, request) }
}

// ---- Groups ----------------------------------------------------------------------------------

class DeleteGroup(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun deleteGroup(groupId: String): DeleteGroupResponseData? = withAdmin { it.deleteGroup(groupId) }
}

class SyncGroup(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun syncGroup(groupId: String): SyncGroupResponseData? = withAdmin { it.syncGroup(groupId) }
}

/** Takes the invitee's signing KEY — the one member call that does. mero-react `useAddGroupMembers`. */
class AddGroupMembers(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun addGroupMembers(
        groupId: String,
        request: AddGroupMembersRequest,
    ): Unit? = withAdmin { it.addGroupMembers(groupId, request) }
}

/** Takes the member's ACCOUNT (64 hex), as [MeroReads.groupMembers] rows are keyed. */
class RemoveGroupMembers(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun removeGroupMembers(
        groupId: String,
        request: RemoveGroupMembersRequest,
    ): Unit? = withAdmin { it.removeGroupMembers(groupId, request) }
}

/** Takes the member's ACCOUNT. mero-react `useUpdateMemberRole`. */
class UpdateMemberRole(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun updateMemberRole(
        groupId: String,
        identity: String,
        request: UpdateMemberRoleRequest,
    ): Unit? = withAdmin { it.updateMemberRole(groupId, identity, request) }
}

class SetDefaultCapabilities(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun setDefaultCapabilities(
        groupId: String,
        request: SetDefaultCapabilitiesRequest,
    ): Unit? = withAdmin { it.setDefaultCapabilities(groupId, request) }
}

class SetSubgroupVisibility(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun setSubgroupVisibility(
        groupId: String,
        request: SetSubgroupVisibilityRequest,
    ): Unit? = withAdmin { it.setSubgroupVisibility(groupId, request) }
}

/** mero-react `useSetTeeAdmissionPolicy`. */
class SetTeeAdmissionPolicy(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun setTeeAdmissionPolicy(
        groupId: String,
        request: SetTeeAdmissionPolicyRequest,
    ): Unit? = withAdmin { it.setTeeAdmissionPolicy(groupId, request) }
}

/** Move a group under a new parent. mero-react `useReparentGroup`. */
class ReparentGroup(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun reparentGroup(
        childGroupId: String,
        request: ReparentGroupRequest,
    ): ReparentGroupResponseData? = withAdmin { it.reparentGroup(childGroupId, request) }
}

class DetachContextFromGroup(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun detachContextFromGroup(
        groupId: String,
        contextId: String,
    ): Unit? = withAdmin { it.detachContextFromGroup(groupId, contextId) }
}

/** Join an open subgroup yourself. On an account a root op carrying its own credential. */
class JoinSubgroupInheritance(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun joinSubgroupInheritance(groupId: String): JoinSubgroupInheritanceResponseData? = withAdmin { it.joinSubgroupInheritance(groupId) }
}

/** The legacy group invitation. Prefer namespace invitations. mero-react `useGroupInvitations`. */
class GroupInvitations(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun createInvitation(
        groupId: String,
        request: CreateGroupInvitationRequest? = null,
    ): CreateGroupInvitationResult? = withAdmin { it.createGroupInvitation(groupId, request) }
}

/** The legacy group join. mero-react `useJoinGroup`. */
class JoinGroup(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun joinGroup(request: JoinGroupRequest): JoinGroupResponseData? = withAdmin { it.joinGroup(request) }
}

// ---- Metadata --------------------------------------------------------------------------------

/** Replaces the whole record. mero-react `useSetGroupMetadata`. */
class SetGroupMetadata(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun setGroupMetadata(
        groupId: String,
        request: SetGroupMetadataRequest,
    ): Unit? = withAdmin { it.setGroupMetadata(groupId, request) }
}

/** Takes the member's ACCOUNT; replaces the whole record. */
class SetMemberMetadata(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun setMemberMetadata(
        groupId: String,
        identity: String,
        request: SetMemberMetadataRequest,
    ): Unit? = withAdmin { it.setMemberMetadata(groupId, identity, request) }
}

class SetContextMetadata(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun setContextMetadata(
        groupId: String,
        contextId: String,
        request: SetContextMetadataRequest,
    ): Unit? = withAdmin { it.setContextMetadata(groupId, contextId, request) }
}

// ---- Namespaces ------------------------------------------------------------------------------

/** On a node this installs the app's package first when needed; an account founds through its relay. */
class CreateNamespace(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun createNamespace(request: CreateNamespaceRequest): CreateNamespaceResponseData? = withAdmin { it.createNamespace(request) }
}

class DeleteNamespace(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun deleteNamespace(namespaceId: String): DeleteNamespaceResponseData? = withAdmin { it.deleteNamespace(namespaceId) }
}

/**
 * Set what a namespace approves for an app publisher's outbound requests, exactly: an empty
 * [com.calimero.mero.admin.OutboundSelection.Prefixes] revokes everything. Approving at creation
 * or upgrade is `approveOutbound` on [CreateNamespace] / [UpgradeGroup]. An account is refused.
 */
class SetOutboundApproval(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun setOutboundApproval(
        namespaceId: String,
        request: SetOutboundApprovalRequest,
    ): SetOutboundApprovalResponseData? = withAdmin { it.setOutboundApproval(namespaceId, request) }
}

/** On an account the invitation is signed by this device. mero-react `useCreateNamespaceInvitation`. */
class CreateNamespaceInvitation(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun createNamespaceInvitation(
        namespaceId: String,
        request: CreateNamespaceInvitationRequest? = null,
    ): CreateNamespaceInvitationResult? = withAdmin { it.createNamespaceInvitation(namespaceId, request) }
}

/**
 * mero-react `useJoinNamespace`: [failure] says what kind of refusal a failed join was. To follow
 * an invitation link prefer [RedeemInvitation], which also recognises a join that landed although
 * its request failed.
 */
class JoinNamespace(
    sessions: StateFlow<MeroSession>,
    throwOnError: Boolean = false,
) : AdminMutation(sessions, throwOnError) {
    suspend fun joinNamespace(
        namespaceId: String,
        request: JoinNamespaceRequest,
    ): JoinNamespaceResponseData? = withAdmin { it.joinNamespace(namespaceId, request) }
}

class CreateGroupInNamespace(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun createGroupInNamespace(
        namespaceId: String,
        request: CreateGroupInNamespaceRequest? = null,
    ): CreateGroupInNamespaceResponseData? = withAdmin { it.createGroupInNamespace(namespaceId, request) }
}

/**
 * Follow an invitation: join its namespace once, then check membership, and report what
 * happened as a [RedeemOutcome] — mero-react `useRedeemInvitation`. A request that failed but
 * whose join landed anyway is `AlreadyMember`, not a failure. [redeem] resolves null only with no
 * session to send it through.
 */
class RedeemInvitation(
    private val sessions: StateFlow<MeroSession>,
) {
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _outcome = MutableStateFlow<RedeemOutcome?>(null)
    val outcome: StateFlow<RedeemOutcome?> = _outcome.asStateFlow()

    suspend fun redeem(
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
        groupName: String? = null,
        teamName: String? = null,
    ): RedeemOutcome? {
        val admin = sessions.value.admin ?: return null
        _loading.value = true
        _outcome.value = null
        try {
            return admin.redeemInvitation(namespaceId, invitation, groupName, teamName).also { _outcome.value = it }
        } finally {
            _loading.value = false
        }
    }
}

// ---- Upgrades --------------------------------------------------------------------------------

class UpgradeGroup(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun upgradeGroup(
        groupId: String,
        request: UpgradeGroupRequest,
    ): UpgradeGroupResponseData? = withAdmin { it.upgradeGroup(groupId, request) }
}

class RetryGroupUpgrade(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun retryGroupUpgrade(groupId: String): RetryGroupUpgradeResponseData? = withAdmin { it.retryGroupUpgrade(groupId) }
}

/**
 * Install a registry package version onto the node — the "Download" step an Updates flow runs
 * before [UpgradeGroup]. The node resolves it against the registry it is configured with.
 * mero-react `useInstallFromRegistry`.
 */
class InstallFromRegistry(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun installFromRegistry(
        packageName: String,
        version: String,
    ): InstallApplicationResponseData? = withAdmin { it.installApplication(InstallApplicationRequest(packageName, version)) }
}

// ---- Blobs -----------------------------------------------------------------------------------

/**
 * Upload bytes to the blob store — mero-react `useUploadBlob`. Name a context in the request to
 * announce the blob to it, which is what lets its other members find it later.
 */
class UploadBlob(
    sessions: StateFlow<MeroSession>,
) : AdminMutation(sessions) {
    suspend fun uploadBlob(request: UploadBlobRequest): UploadBlobResponseData? = withAdmin { it.uploadBlob(request) }
}
