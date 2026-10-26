package com.calimero.mero.account

import com.calimero.mero.admin.AccountApplications
import com.calimero.mero.admin.AccountDevices
import com.calimero.mero.admin.AccountSignWithRootRequest
import com.calimero.mero.admin.AccountSignWithRootResponseData
import com.calimero.mero.admin.AddGroupMembersRequest
import com.calimero.mero.admin.AdminAuthStatus
import com.calimero.mero.admin.AdminSurface
import com.calimero.mero.admin.AdmitJoinRequest
import com.calimero.mero.admin.AdmitJoinResponseData
import com.calimero.mero.admin.ApplicationVersionEntry
import com.calimero.mero.admin.CapabilityChange
import com.calimero.mero.admin.CascadeStatusEntry
import com.calimero.mero.admin.ChangeNamespaceAdminRequest
import com.calimero.mero.admin.Context
import com.calimero.mero.admin.ContextGroupResponseData
import com.calimero.mero.admin.ContextIntentRelayInfo
import com.calimero.mero.admin.ContextStorageResponseData
import com.calimero.mero.admin.ContextsWithExecutorsResponseData
import com.calimero.mero.admin.CreateAliasResponseData
import com.calimero.mero.admin.CreateApplicationAliasRequest
import com.calimero.mero.admin.CreateContextAliasRequest
import com.calimero.mero.admin.CreateContextIntentRequest
import com.calimero.mero.admin.CreateContextIntentResponseData
import com.calimero.mero.admin.CreateContextRequest
import com.calimero.mero.admin.CreateContextResponseData
import com.calimero.mero.admin.CreateDeviceAliasRequest
import com.calimero.mero.admin.CreateGroupInNamespaceRequest
import com.calimero.mero.admin.CreateGroupInNamespaceResponseData
import com.calimero.mero.admin.CreateGroupInvitationRequest
import com.calimero.mero.admin.CreateGroupInvitationResult
import com.calimero.mero.admin.CreateGroupRequest
import com.calimero.mero.admin.CreateGroupResponseData
import com.calimero.mero.admin.CreateNamespaceInvitationRequest
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
import com.calimero.mero.admin.DisableTeeAuthoringPolicyRequest
import com.calimero.mero.admin.GenerateContextIdentityResponseData
import com.calimero.mero.admin.GetApplicationResponseData
import com.calimero.mero.admin.GetBlobInfoResponseData
import com.calimero.mero.admin.GetContextIdentitiesResponseData
import com.calimero.mero.admin.GetContextsResponseData
import com.calimero.mero.admin.GetLatestVersionResponseData
import com.calimero.mero.admin.GetTeeAdmissionPolicyResponseData
import com.calimero.mero.admin.GovernanceIntentRelayInfo
import com.calimero.mero.admin.GovernanceIntentRequest
import com.calimero.mero.admin.GovernanceIntentResponseData
import com.calimero.mero.admin.GroupInfoResponseData
import com.calimero.mero.admin.GroupUpgradeStatusResponseData
import com.calimero.mero.admin.HealthStatus
import com.calimero.mero.admin.InstallApplicationRequest
import com.calimero.mero.admin.InstallApplicationResponseData
import com.calimero.mero.admin.InstallDevApplicationRequest
import com.calimero.mero.admin.IntentRelayInfo
import com.calimero.mero.admin.IssueNamespaceOwnershipProofRequest
import com.calimero.mero.admin.IssueOwnershipProofRequest
import com.calimero.mero.admin.IssueOwnershipProofResponseData
import com.calimero.mero.admin.JoinContextResponseData
import com.calimero.mero.admin.JoinGroupRequest
import com.calimero.mero.admin.JoinGroupResponseData
import com.calimero.mero.admin.JoinNamespaceRequest
import com.calimero.mero.admin.JoinNamespaceResponseData
import com.calimero.mero.admin.JoinSubgroupInheritanceResponseData
import com.calimero.mero.admin.LabelDeviceRequest
import com.calimero.mero.admin.LabelDeviceResponseData
import com.calimero.mero.admin.LinkAccountDeviceRequest
import com.calimero.mero.admin.LinkAccountDeviceResponseData
import com.calimero.mero.admin.ListAliasesResponseData
import com.calimero.mero.admin.ListApplicationsResponseData
import com.calimero.mero.admin.ListBlobsResponseData
import com.calimero.mero.admin.ListGroupContextsResponseData
import com.calimero.mero.admin.ListGroupMembersResponseData
import com.calimero.mero.admin.ListNamespacesResponseData
import com.calimero.mero.admin.ListPackagesResponseData
import com.calimero.mero.admin.ListVersionsResponseData
import com.calimero.mero.admin.LookupAliasResponseData
import com.calimero.mero.admin.MemberCapabilities
import com.calimero.mero.admin.MemberDeviceGroup
import com.calimero.mero.admin.MemberDevices
import com.calimero.mero.admin.MetadataRecord
import com.calimero.mero.admin.MigrationStatus
import com.calimero.mero.admin.Namespace
import com.calimero.mero.admin.NamespaceRevokedDevices
import com.calimero.mero.admin.NodeIdentity
import com.calimero.mero.admin.OutboundApproval
import com.calimero.mero.admin.OwnerDeleteGroupRequest
import com.calimero.mero.admin.PairCompleteRequest
import com.calimero.mero.admin.PairCompleteResponseData
import com.calimero.mero.admin.PairInitRequest
import com.calimero.mero.admin.PairInitResponseData
import com.calimero.mero.admin.PeersCountResponseData
import com.calimero.mero.admin.PerformIntentRequest
import com.calimero.mero.admin.PerformIntentResponseData
import com.calimero.mero.admin.PresenceIntentRequest
import com.calimero.mero.admin.QueryContextRequest
import com.calimero.mero.admin.QueryContextResponseData
import com.calimero.mero.admin.RedeemOutcome
import com.calimero.mero.admin.RelinkDeviceRequest
import com.calimero.mero.admin.RelinkDeviceResponseData
import com.calimero.mero.admin.RemoveGroupMembersRequest
import com.calimero.mero.admin.ReparentGroupRequest
import com.calimero.mero.admin.ReparentGroupResponseData
import com.calimero.mero.admin.RescopeDeviceRequest
import com.calimero.mero.admin.RescopeDeviceResponseData
import com.calimero.mero.admin.ResyncContextRequest
import com.calimero.mero.admin.ResyncContextResponseData
import com.calimero.mero.admin.RetryGroupUpgradeRequest
import com.calimero.mero.admin.RetryGroupUpgradeResponseData
import com.calimero.mero.admin.RevokeDeviceRequest
import com.calimero.mero.admin.RevokeDeviceResponseData
import com.calimero.mero.admin.SealToAccountRequest
import com.calimero.mero.admin.SealedEnvelope
import com.calimero.mero.admin.SetContextMetadataRequest
import com.calimero.mero.admin.SetDefaultCapabilitiesRequest
import com.calimero.mero.admin.SetGroupMetadataRequest
import com.calimero.mero.admin.SetMemberCapabilitiesRequest
import com.calimero.mero.admin.SetMemberMetadataRequest
import com.calimero.mero.admin.SetOutboundApprovalRequest
import com.calimero.mero.admin.SetOutboundApprovalResponseData
import com.calimero.mero.admin.SetSubgroupVisibilityRequest
import com.calimero.mero.admin.SetTeeAdmissionPolicyRequest
import com.calimero.mero.admin.SetTeeAuthoringPolicyRequest
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.admin.SubgroupEntry
import com.calimero.mero.admin.SyncGroupRequest
import com.calimero.mero.admin.SyncGroupResponseData
import com.calimero.mero.admin.TeeAttestRequest
import com.calimero.mero.admin.TeeAttestResponseData
import com.calimero.mero.admin.TeeInfoResponseData
import com.calimero.mero.admin.TeeRegistrationAttestRequest
import com.calimero.mero.admin.TransferOwnershipRequest
import com.calimero.mero.admin.UninstallApplicationResponseData
import com.calimero.mero.admin.UpdateContextApplicationRequest
import com.calimero.mero.admin.UpdateMemberRoleRequest
import com.calimero.mero.admin.UpgradeGroupRequest
import com.calimero.mero.admin.UpgradeGroupResponseData
import com.calimero.mero.admin.UploadBlobRequest
import com.calimero.mero.admin.UploadBlobResponseData
import com.calimero.mero.crypto.DeviceSigner
import kotlinx.serialization.json.JsonElement

/**
 * The admin surface of an account with no relay yet: it is a member of nothing, so its
 * listings are empty, and every other call names the missing relay ([NoRelayException]).
 * [createAccountAdmin] layers the calls an account answers itself (who it is, joining) on top.
 */
@Suppress("TooManyFunctions", "LargeClass")
internal object NoRelayAdmin : AdminSurface {
    override suspend fun healthCheck(): HealthStatus = throw NoRelayException("healthCheck")

    override suspend fun isAuthed(): AdminAuthStatus = throw NoRelayException("isAuthed")

    override suspend fun isReady(): HealthStatus = throw NoRelayException("isReady")

    override suspend fun getNodeIdentity(): NodeIdentity = throw NoRelayException("getNodeIdentity")

    override suspend fun installApplication(
        request: InstallApplicationRequest,
    ): InstallApplicationResponseData = throw NoRelayException("installApplication")

    override suspend fun installFromRegistry(
        packageName: String,
        version: String,
    ): InstallApplicationResponseData = throw NoRelayException("installFromRegistry")

    override suspend fun getRegistryVersions(
        registryUrl: String,
        packageName: String,
    ): List<String> = throw NoRelayException("getRegistryVersions")

    override suspend fun installDevApplication(
        request: InstallDevApplicationRequest,
    ): InstallApplicationResponseData = throw NoRelayException("installDevApplication")

    override suspend fun uninstallApplication(
        appId: String,
    ): UninstallApplicationResponseData = throw NoRelayException("uninstallApplication")

    override suspend fun listApplications(): ListApplicationsResponseData = throw NoRelayException("listApplications")

    override suspend fun getApplication(
        appId: String,
    ): GetApplicationResponseData = throw NoRelayException("getApplication")

    override suspend fun listApplicationVersions(
        applicationId: String,
    ): List<ApplicationVersionEntry> = throw NoRelayException("listApplicationVersions")

    override suspend fun listPackages(): ListPackagesResponseData = throw NoRelayException("listPackages")

    override suspend fun listPackageVersions(
        packageName: String,
    ): ListVersionsResponseData = throw NoRelayException("listPackageVersions")

    override suspend fun getLatestPackageVersion(
        packageName: String,
    ): GetLatestVersionResponseData = throw NoRelayException("getLatestPackageVersion")

    override suspend fun createContext(
        request: CreateContextRequest,
    ): CreateContextResponseData = throw NoRelayException("createContext")

    override suspend fun deleteContext(
        contextId: String,
        request: DeleteContextRequest?,
    ): DeleteContextResponseData = throw NoRelayException("deleteContext")

    override suspend fun getContexts(): GetContextsResponseData = GetContextsResponseData(emptyList())

    override suspend fun getContext(
        contextId: String,
    ): Context = throw NoRelayException("getContext")

    override suspend fun getContextsForApplication(
        applicationId: String,
    ): GetContextsResponseData = throw NoRelayException("getContextsForApplication")

    override suspend fun generateContextIdentity(): GenerateContextIdentityResponseData = throw NoRelayException("generateContextIdentity")

    override suspend fun getContextIdentities(
        contextId: String,
    ): GetContextIdentitiesResponseData = throw NoRelayException("getContextIdentities")

    override suspend fun getContextIdentitiesOwned(
        contextId: String,
    ): GetContextIdentitiesResponseData = throw NoRelayException("getContextIdentitiesOwned")

    override suspend fun joinContext(
        contextId: String,
    ): JoinContextResponseData = throw NoRelayException("joinContext")

    override suspend fun getContextGroup(
        contextId: String,
    ): ContextGroupResponseData = throw NoRelayException("getContextGroup")

    override suspend fun getContextStorage(
        contextId: String,
    ): ContextStorageResponseData = throw NoRelayException("getContextStorage")

    override suspend fun syncContext(
        contextId: String?,
    ): Unit = throw NoRelayException("syncContext")

    override suspend fun resyncContext(
        contextId: String,
        request: ResyncContextRequest,
    ): ResyncContextResponseData = throw NoRelayException("resyncContext")

    override suspend fun updateContextApplication(
        contextId: String,
        request: UpdateContextApplicationRequest,
    ): Unit = throw NoRelayException("updateContextApplication")

    override suspend fun getContextsWithExecutorsForApplication(
        applicationId: String,
    ): ContextsWithExecutorsResponseData = throw NoRelayException("getContextsWithExecutorsForApplication")

    override suspend fun uploadBlob(
        request: UploadBlobRequest,
    ): UploadBlobResponseData = throw NoRelayException("uploadBlob")

    override suspend fun deleteBlob(
        blobId: String,
    ): DeleteBlobResponseData = throw NoRelayException("deleteBlob")

    override suspend fun listBlobs(): ListBlobsResponseData = throw NoRelayException("listBlobs")

    override suspend fun getBlob(
        blobId: String,
        contextId: String?,
    ): ByteArray = throw NoRelayException("getBlob")

    override suspend fun getBlobInfo(
        blobId: String,
        contextId: String?,
    ): GetBlobInfoResponseData = throw NoRelayException("getBlobInfo")

    override suspend fun createContextAlias(
        request: CreateContextAliasRequest,
    ): CreateAliasResponseData = throw NoRelayException("createContextAlias")

    override suspend fun createApplicationAlias(
        request: CreateApplicationAliasRequest,
    ): CreateAliasResponseData = throw NoRelayException("createApplicationAlias")

    override suspend fun lookupContextAlias(
        name: String,
    ): LookupAliasResponseData = throw NoRelayException("lookupContextAlias")

    override suspend fun lookupApplicationAlias(
        name: String,
    ): LookupAliasResponseData = throw NoRelayException("lookupApplicationAlias")

    override suspend fun deleteContextAlias(
        name: String,
    ): DeleteAliasResponseData = throw NoRelayException("deleteContextAlias")

    override suspend fun deleteApplicationAlias(
        name: String,
    ): DeleteAliasResponseData = throw NoRelayException("deleteApplicationAlias")

    override suspend fun listContextAliases(): ListAliasesResponseData = throw NoRelayException("listContextAliases")

    override suspend fun listApplicationAliases(): ListAliasesResponseData = throw NoRelayException("listApplicationAliases")

    override suspend fun listDeviceAliases(): ListAliasesResponseData = throw NoRelayException("listDeviceAliases")

    override suspend fun createDeviceAlias(
        request: CreateDeviceAliasRequest,
    ): CreateAliasResponseData = throw NoRelayException("createDeviceAlias")

    override suspend fun lookupDeviceAlias(
        name: String,
    ): LookupAliasResponseData = throw NoRelayException("lookupDeviceAlias")

    override suspend fun deleteDeviceAlias(
        name: String,
    ): DeleteAliasResponseData = throw NoRelayException("deleteDeviceAlias")

    override suspend fun listNamespaces(): ListNamespacesResponseData = emptyList()

    override suspend fun getNamespace(
        namespaceId: String,
    ): Namespace = throw NoRelayException("getNamespace")

    override suspend fun listNamespacesForApplication(
        applicationId: String,
    ): ListNamespacesResponseData = emptyList()

    override suspend fun createNamespace(
        request: CreateNamespaceRequest,
    ): CreateNamespaceResponseData = throw NoRelayException("createNamespace")

    override suspend fun deleteNamespace(
        namespaceId: String,
        request: DeleteNamespaceRequest?,
    ): DeleteNamespaceResponseData = throw NoRelayException("deleteNamespace")

    override suspend fun listOutboundApprovals(
        namespaceId: String,
    ): List<OutboundApproval> = throw NoRelayException("listOutboundApprovals")

    override suspend fun setOutboundApproval(
        namespaceId: String,
        request: SetOutboundApprovalRequest,
    ): SetOutboundApprovalResponseData = throw NoRelayException("setOutboundApproval")

    override suspend fun createNamespaceInvitation(
        namespaceId: String,
        request: CreateNamespaceInvitationRequest?,
    ): CreateNamespaceInvitationResult = throw NoRelayException("createNamespaceInvitation")

    override suspend fun joinNamespace(
        namespaceId: String,
        request: JoinNamespaceRequest,
    ): JoinNamespaceResponseData = throw NoRelayException("joinNamespace")

    override suspend fun createGroupInNamespace(
        namespaceId: String,
        request: CreateGroupInNamespaceRequest?,
    ): CreateGroupInNamespaceResponseData = throw NoRelayException("createGroupInNamespace")

    override suspend fun listNamespaceGroups(
        namespaceId: String,
    ): List<SubgroupEntry> = throw NoRelayException("listNamespaceGroups")

    override suspend fun getGroupInfo(
        groupId: String,
    ): GroupInfoResponseData = throw NoRelayException("getGroupInfo")

    override suspend fun getDefaultCapabilities(
        groupId: String,
    ): Int = throw NoRelayException("getDefaultCapabilities")

    override suspend fun getSubgroupVisibility(
        groupId: String,
    ): String = throw NoRelayException("getSubgroupVisibility")

    override suspend fun deleteGroup(
        groupId: String,
        request: DeleteGroupRequest?,
    ): DeleteGroupResponseData = throw NoRelayException("deleteGroup")

    override suspend fun listGroupMembers(
        groupId: String,
    ): ListGroupMembersResponseData = throw NoRelayException("listGroupMembers")

    override suspend fun listGroupContexts(
        groupId: String,
    ): ListGroupContextsResponseData = throw NoRelayException("listGroupContexts")

    override suspend fun addGroupMembers(
        groupId: String,
        request: AddGroupMembersRequest,
    ): Unit = throw NoRelayException("addGroupMembers")

    override suspend fun removeGroupMembers(
        groupId: String,
        request: RemoveGroupMembersRequest,
    ): Unit = throw NoRelayException("removeGroupMembers")

    override suspend fun updateMemberRole(
        groupId: String,
        identity: String,
        request: UpdateMemberRoleRequest,
    ): Unit = throw NoRelayException("updateMemberRole")

    override suspend fun getMemberCapabilities(
        groupId: String,
        identity: String,
    ): MemberCapabilities = throw NoRelayException("getMemberCapabilities")

    override suspend fun setMemberCapabilities(
        groupId: String,
        identity: String,
        request: SetMemberCapabilitiesRequest,
    ): Unit = throw NoRelayException("setMemberCapabilities")

    override suspend fun setDefaultCapabilities(
        groupId: String,
        request: SetDefaultCapabilitiesRequest,
    ): Unit = throw NoRelayException("setDefaultCapabilities")

    override suspend fun setSubgroupVisibility(
        groupId: String,
        request: SetSubgroupVisibilityRequest,
    ): Unit = throw NoRelayException("setSubgroupVisibility")

    override suspend fun setTeeAdmissionPolicy(
        groupId: String,
        request: SetTeeAdmissionPolicyRequest,
    ): Unit = throw NoRelayException("setTeeAdmissionPolicy")

    override suspend fun setTeeAdmissionPolicy(
        groupId: String,
        request: SetTeeAdmissionPolicyRequest,
        root: DeviceSigner,
        keyEpoch: Long,
        chain: List<String>,
    ): Unit = throw NoRelayException("setTeeAdmissionPolicy")

    override suspend fun getTeeAdmissionPolicy(
        groupId: String,
    ): GetTeeAdmissionPolicyResponseData = throw NoRelayException("getTeeAdmissionPolicy")

    override suspend fun setGroupMetadata(
        groupId: String,
        request: SetGroupMetadataRequest,
    ): Unit = throw NoRelayException("setGroupMetadata")

    override suspend fun getGroupMetadata(
        groupId: String,
    ): MetadataRecord? = throw NoRelayException("getGroupMetadata")

    override suspend fun setMemberMetadata(
        groupId: String,
        identity: String,
        request: SetMemberMetadataRequest,
    ): Unit = throw NoRelayException("setMemberMetadata")

    override suspend fun getMemberMetadata(
        groupId: String,
        identity: String,
    ): MetadataRecord? = throw NoRelayException("getMemberMetadata")

    override suspend fun setContextMetadata(
        groupId: String,
        contextId: String,
        request: SetContextMetadataRequest,
    ): Unit = throw NoRelayException("setContextMetadata")

    override suspend fun getContextMetadata(
        groupId: String,
        contextId: String,
    ): MetadataRecord? = throw NoRelayException("getContextMetadata")

    override suspend fun syncGroup(
        groupId: String,
        request: SyncGroupRequest?,
    ): SyncGroupResponseData = throw NoRelayException("syncGroup")

    override suspend fun syncGroupContexts(
        groupId: String,
    ): ListGroupContextsResponseData = throw NoRelayException("syncGroupContexts")

    override suspend fun upgradeGroup(
        groupId: String,
        request: UpgradeGroupRequest,
    ): UpgradeGroupResponseData = throw NoRelayException("upgradeGroup")

    override suspend fun getGroupUpgradeStatus(
        groupId: String,
    ): GroupUpgradeStatusResponseData = throw NoRelayException("getGroupUpgradeStatus")

    override suspend fun getMigrationStatus(
        namespaceId: String,
    ): MigrationStatus = throw NoRelayException("getMigrationStatus")

    override suspend fun getCascadeStatus(
        namespaceId: String,
    ): List<CascadeStatusEntry> = throw NoRelayException("getCascadeStatus")

    override suspend fun retryGroupUpgrade(
        groupId: String,
        request: RetryGroupUpgradeRequest?,
    ): RetryGroupUpgradeResponseData = throw NoRelayException("retryGroupUpgrade")

    override suspend fun reparentGroup(
        childGroupId: String,
        request: ReparentGroupRequest,
    ): ReparentGroupResponseData = throw NoRelayException("reparentGroup")

    override suspend fun listSubgroups(
        groupId: String,
    ): List<SubgroupEntry> = throw NoRelayException("listSubgroups")

    override suspend fun detachContextFromGroup(
        groupId: String,
        contextId: String,
        request: DetachContextFromGroupRequest?,
    ): Unit = throw NoRelayException("detachContextFromGroup")

    override suspend fun createGroupInvitation(
        groupId: String,
        request: CreateGroupInvitationRequest?,
    ): CreateGroupInvitationResult = throw NoRelayException("createGroupInvitation")

    override suspend fun joinGroup(
        request: JoinGroupRequest,
    ): JoinGroupResponseData = throw NoRelayException("joinGroup")

    override suspend fun joinSubgroupInheritance(
        groupId: String,
    ): JoinSubgroupInheritanceResponseData = throw NoRelayException("joinSubgroupInheritance")

    override suspend fun getTeeInfo(): TeeInfoResponseData = throw NoRelayException("getTeeInfo")

    override suspend fun teeAttest(
        request: TeeAttestRequest,
    ): TeeAttestResponseData = throw NoRelayException("teeAttest")

    override suspend fun teeRegistrationAttest(
        request: TeeRegistrationAttestRequest,
    ): TeeAttestResponseData = throw NoRelayException("teeRegistrationAttest")

    override suspend fun getPeersCount(): PeersCountResponseData = throw NoRelayException("getPeersCount")

    override suspend fun getNetworkStatus(): JsonElement = throw NoRelayException("getNetworkStatus")

    override suspend fun getUsage(): JsonElement = throw NoRelayException("getUsage")

    override suspend fun createGroup(
        request: Map<String, JsonElement>,
    ): CreateGroupResponseData = throw NoRelayException("createGroup")

    override suspend fun createGroup(
        request: CreateGroupRequest,
    ): CreateGroupResponseData = throw NoRelayException("createGroup")

    override suspend fun leaveGroup(
        groupId: String,
        request: Map<String, JsonElement>?,
    ): Unit = throw NoRelayException("leaveGroup")

    override suspend fun leaveContext(
        contextId: String,
        request: Map<String, JsonElement>?,
    ): Unit = throw NoRelayException("leaveContext")

    override suspend fun leaveNamespace(
        namespaceId: String,
        request: Map<String, JsonElement>?,
    ): Unit = throw NoRelayException("leaveNamespace")

    override suspend fun issueOwnershipProof(
        groupId: String,
        request: Map<String, JsonElement>?,
    ): JsonElement = throw NoRelayException("issueOwnershipProof")

    override suspend fun issueOwnershipProof(
        groupId: String,
        request: IssueOwnershipProofRequest,
    ): IssueOwnershipProofResponseData = throw NoRelayException("issueOwnershipProof")

    override suspend fun issueNamespaceOwnershipProof(
        groupId: String,
        request: IssueNamespaceOwnershipProofRequest,
    ): IssueOwnershipProofResponseData = throw NoRelayException("issueNamespaceOwnershipProof")

    override suspend fun issueNamespaceOwnershipProof(
        groupId: String,
        request: Map<String, JsonElement>?,
    ): JsonElement = throw NoRelayException("issueNamespaceOwnershipProof")

    override suspend fun setMemberAutoFollow(
        groupId: String,
        identity: String,
        request: Map<String, JsonElement>,
    ): Unit = throw NoRelayException("setMemberAutoFollow")

    override suspend fun abortMigration(
        namespaceId: String,
        request: Map<String, JsonElement>?,
    ): JsonElement = throw NoRelayException("abortMigration")

    override suspend fun getApplicationAbi(
        applicationId: String,
        serviceName: String?,
    ): JsonElement? = throw NoRelayException("getApplicationAbi")

    override suspend fun performIntent(
        contextId: String,
        request: PerformIntentRequest,
    ): PerformIntentResponseData = throw NoRelayException("performIntent")

    override suspend fun listAccountDevices(): AccountDevices = throw NoRelayException("listAccountDevices")

    override suspend fun listAccountApplications(): AccountApplications = throw NoRelayException("listAccountApplications")

    override suspend fun listMemberDevices(
        groupId: String,
        offset: Int?,
        limit: Int?,
    ): MemberDevices = throw NoRelayException("listMemberDevices")

    override suspend fun listGroupMemberDevices(
        groupId: String,
        offset: Int?,
        limit: Int?,
    ): List<MemberDeviceGroup> = throw NoRelayException("listGroupMemberDevices")

    override suspend fun pairInit(
        request: PairInitRequest,
    ): PairInitResponseData = throw NoRelayException("pairInit")

    override suspend fun pairComplete(
        request: PairCompleteRequest,
    ): PairCompleteResponseData = throw NoRelayException("pairComplete")

    override suspend fun relinkDevice(
        deviceId: String,
        request: RelinkDeviceRequest,
    ): RelinkDeviceResponseData = throw NoRelayException("relinkDevice")

    override suspend fun rescopeDevice(
        deviceId: String,
        request: RescopeDeviceRequest,
    ): RescopeDeviceResponseData = throw NoRelayException("rescopeDevice")

    override suspend fun labelDevice(
        deviceId: String,
        request: LabelDeviceRequest,
    ): LabelDeviceResponseData = throw NoRelayException("labelDevice")

    override suspend fun revokeAccountDevice(
        namespaceId: String,
        request: RevokeDeviceRequest,
    ): RevokeDeviceResponseData = throw NoRelayException("revokeAccountDevice")

    override suspend fun listNamespaceRevokedDevices(
        namespaceId: String,
    ): NamespaceRevokedDevices = throw NoRelayException("listNamespaceRevokedDevices")

    override suspend fun admitJoin(
        namespaceId: String,
        request: AdmitJoinRequest,
    ): AdmitJoinResponseData = throw NoRelayException("admitJoin")

    override suspend fun queryContext(
        contextId: String,
        request: QueryContextRequest,
    ): QueryContextResponseData = throw NoRelayException("queryContext")

    override suspend fun getIntentRelay(
        contextId: String,
    ): IntentRelayInfo = throw NoRelayException("getIntentRelay")

    override suspend fun createContextIntent(
        groupId: String,
        request: CreateContextIntentRequest,
    ): CreateContextIntentResponseData = throw NoRelayException("createContextIntent")

    override suspend fun getContextIntentRelay(
        groupId: String,
        author: String?,
    ): ContextIntentRelayInfo = throw NoRelayException("getContextIntentRelay")

    override suspend fun governanceIntent(
        groupId: String,
        request: GovernanceIntentRequest,
    ): GovernanceIntentResponseData = throw NoRelayException("governanceIntent")

    override suspend fun getGovernanceIntentRelay(
        groupId: String,
    ): GovernanceIntentRelayInfo = throw NoRelayException("getGovernanceIntentRelay")

    override suspend fun presenceIntent(
        contextId: String,
        request: PresenceIntentRequest,
    ): Unit = throw NoRelayException("presenceIntent")

    override suspend fun signWithAccountRoot(
        request: AccountSignWithRootRequest,
    ): AccountSignWithRootResponseData = throw NoRelayException("signWithAccountRoot")

    override suspend fun linkAccountDevice(
        namespaceId: String,
        request: LinkAccountDeviceRequest,
    ): LinkAccountDeviceResponseData = throw NoRelayException("linkAccountDevice")

    override suspend fun linkAccountDevice(
        namespaceId: String,
        credential: String,
        root: DeviceSigner,
        applications: List<String>,
        scopeEpoch: Long,
    ): LinkAccountDeviceResponseData = throw NoRelayException("linkAccountDevice")

    override suspend fun sealToAccount(
        groupId: String,
        account: String,
        request: SealToAccountRequest,
    ): SealedEnvelope = throw NoRelayException("sealToAccount")

    override suspend fun transferOwnership(
        groupId: String,
        request: TransferOwnershipRequest,
    ): Unit = throw NoRelayException("transferOwnership")

    override suspend fun changeNamespaceAdmin(
        namespaceId: String,
        request: ChangeNamespaceAdminRequest,
    ): Unit = throw NoRelayException("changeNamespaceAdmin")

    override suspend fun ownerDeleteGroup(
        groupId: String,
        request: OwnerDeleteGroupRequest,
    ): Unit = throw NoRelayException("ownerDeleteGroup")

    override suspend fun setTeeAuthoringPolicy(
        groupId: String,
        request: SetTeeAuthoringPolicyRequest,
    ): Unit = throw NoRelayException("setTeeAuthoringPolicy")

    override suspend fun disableTeeAuthoringPolicy(
        groupId: String,
        request: DisableTeeAuthoringPolicyRequest,
    ): Unit = throw NoRelayException("disableTeeAuthoringPolicy")

    override suspend fun transferOwnership(
        groupId: String,
        newOwner: String,
        root: DeviceSigner,
        keyEpoch: Long,
        chain: List<String>,
    ): Unit = throw NoRelayException("transferOwnership")

    override suspend fun changeNamespaceAdmin(
        namespaceId: String,
        newAdmin: String,
        root: DeviceSigner,
        keyEpoch: Long,
        chain: List<String>,
    ): Unit = throw NoRelayException("changeNamespaceAdmin")

    override suspend fun ownerDeleteGroup(
        groupId: String,
        root: DeviceSigner,
        keyEpoch: Long,
        chain: List<String>,
    ): Unit = throw NoRelayException("ownerDeleteGroup")

    override suspend fun setTeeAuthoringPolicy(
        groupId: String,
        allowedMrtd: List<String>,
        root: DeviceSigner,
        keyEpoch: Long,
        chain: List<String>,
    ): Unit = throw NoRelayException("setTeeAuthoringPolicy")

    override suspend fun disableTeeAuthoringPolicy(
        groupId: String,
        root: DeviceSigner,
        keyEpoch: Long,
        chain: List<String>,
    ): Unit = throw NoRelayException("disableTeeAuthoringPolicy")

    override suspend fun openToDelegatedExecution(
        groupId: String,
    ): CapabilityChange = throw NoRelayException("openToDelegatedExecution")

    override suspend fun grantAuthorship(
        groupId: String,
        account: String,
    ): CapabilityChange = throw NoRelayException("grantAuthorship")

    override suspend fun redeemInvitation(
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
        groupName: String?,
        teamName: String?,
    ): RedeemOutcome = throw NoRelayException("redeemInvitation")
}
