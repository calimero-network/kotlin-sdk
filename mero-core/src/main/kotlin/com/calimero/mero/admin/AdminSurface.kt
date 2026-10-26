package com.calimero.mero.admin

import com.calimero.mero.crypto.DeviceSigner
import kotlinx.serialization.json.JsonElement

/**
 * The admin surface an app codes against — every method of [AdminApi], by name and shape —
 * so the same code can run against a node's admin API or an account's
 * ([com.calimero.mero.account.createAccountAdmin], which answers reads from the relay and
 * turns writes into delegated governance). mero-js has one `AdminApiClient` type for both;
 * this interface is that type.
 *
 * [AdminApi] is the node implementation; each method's documentation lives there.
 */
@Suppress("TooManyFunctions", "ComplexInterface")
interface AdminSurface {
    suspend fun healthCheck(): HealthStatus

    suspend fun isAuthed(): AdminAuthStatus

    suspend fun isReady(): HealthStatus

    suspend fun getNodeIdentity(): NodeIdentity

    suspend fun installApplication(
        request: InstallApplicationRequest,
    ): InstallApplicationResponseData

    suspend fun installFromRegistry(
        packageName: String,
        version: String,
    ): InstallApplicationResponseData

    suspend fun getRegistryVersions(
        registryUrl: String,
        packageName: String,
    ): List<String>

    suspend fun installDevApplication(
        request: InstallDevApplicationRequest,
    ): InstallApplicationResponseData

    suspend fun uninstallApplication(
        appId: String,
    ): UninstallApplicationResponseData

    suspend fun listApplications(): ListApplicationsResponseData

    suspend fun getApplication(
        appId: String,
    ): GetApplicationResponseData

    suspend fun listApplicationVersions(
        applicationId: String,
    ): List<ApplicationVersionEntry>

    suspend fun listPackages(): ListPackagesResponseData

    suspend fun listPackageVersions(
        packageName: String,
    ): ListVersionsResponseData

    suspend fun getLatestPackageVersion(
        packageName: String,
    ): GetLatestVersionResponseData

    suspend fun createContext(
        request: CreateContextRequest,
    ): CreateContextResponseData

    suspend fun deleteContext(
        contextId: String,
        request: DeleteContextRequest? = null,
    ): DeleteContextResponseData

    suspend fun getContexts(): GetContextsResponseData

    suspend fun getContext(
        contextId: String,
    ): Context

    suspend fun getContextsForApplication(
        applicationId: String,
    ): GetContextsResponseData

    suspend fun generateContextIdentity(): GenerateContextIdentityResponseData

    suspend fun getContextIdentities(
        contextId: String,
    ): GetContextIdentitiesResponseData

    suspend fun getContextIdentitiesOwned(
        contextId: String,
    ): GetContextIdentitiesResponseData

    suspend fun joinContext(
        contextId: String,
    ): JoinContextResponseData

    suspend fun getContextGroup(
        contextId: String,
    ): ContextGroupResponseData

    suspend fun getContextStorage(
        contextId: String,
    ): ContextStorageResponseData

    suspend fun syncContext(
        contextId: String? = null,
    )

    suspend fun resyncContext(
        contextId: String,
        request: ResyncContextRequest = ResyncContextRequest(),
    ): ResyncContextResponseData

    suspend fun updateContextApplication(
        contextId: String,
        request: UpdateContextApplicationRequest,
    )

    suspend fun getContextsWithExecutorsForApplication(
        applicationId: String,
    ): ContextsWithExecutorsResponseData

    suspend fun uploadBlob(
        request: UploadBlobRequest,
    ): UploadBlobResponseData

    suspend fun deleteBlob(
        blobId: String,
    ): DeleteBlobResponseData

    suspend fun listBlobs(): ListBlobsResponseData

    suspend fun getBlob(
        blobId: String,
        contextId: String? = null,
    ): ByteArray

    suspend fun getBlobInfo(
        blobId: String,
        contextId: String? = null,
    ): GetBlobInfoResponseData

    suspend fun createContextAlias(
        request: CreateContextAliasRequest,
    ): CreateAliasResponseData

    suspend fun createApplicationAlias(
        request: CreateApplicationAliasRequest,
    ): CreateAliasResponseData

    suspend fun lookupContextAlias(
        name: String,
    ): LookupAliasResponseData

    suspend fun lookupApplicationAlias(
        name: String,
    ): LookupAliasResponseData

    suspend fun deleteContextAlias(
        name: String,
    ): DeleteAliasResponseData

    suspend fun deleteApplicationAlias(
        name: String,
    ): DeleteAliasResponseData

    suspend fun listContextAliases(): ListAliasesResponseData

    suspend fun listApplicationAliases(): ListAliasesResponseData

    suspend fun listDeviceAliases(): ListAliasesResponseData

    suspend fun createDeviceAlias(
        request: CreateDeviceAliasRequest,
    ): CreateAliasResponseData

    suspend fun lookupDeviceAlias(
        name: String,
    ): LookupAliasResponseData

    suspend fun deleteDeviceAlias(
        name: String,
    ): DeleteAliasResponseData

    suspend fun listNamespaces(): ListNamespacesResponseData

    suspend fun getNamespace(
        namespaceId: String,
    ): Namespace

    suspend fun listNamespacesForApplication(
        applicationId: String,
    ): ListNamespacesResponseData

    suspend fun createNamespace(
        request: CreateNamespaceRequest,
    ): CreateNamespaceResponseData

    suspend fun deleteNamespace(
        namespaceId: String,
        request: DeleteNamespaceRequest? = null,
    ): DeleteNamespaceResponseData

    /** What [namespaceId] approves for outbound requests, one entry per publisher. */
    suspend fun listOutboundApprovals(
        namespaceId: String,
    ): List<OutboundApproval>

    /**
     * Set what [namespaceId] approves for the publisher of [SetOutboundApprovalRequest.applicationId]
     * to exactly [SetOutboundApprovalRequest.approve]; an empty list revokes everything.
     */
    suspend fun setOutboundApproval(
        namespaceId: String,
        request: SetOutboundApprovalRequest,
    ): SetOutboundApprovalResponseData

    suspend fun createNamespaceInvitation(
        namespaceId: String,
        request: CreateNamespaceInvitationRequest? = null,
    ): CreateNamespaceInvitationResult

    suspend fun joinNamespace(
        namespaceId: String,
        request: JoinNamespaceRequest,
    ): JoinNamespaceResponseData

    suspend fun createGroupInNamespace(
        namespaceId: String,
        request: CreateGroupInNamespaceRequest? = null,
    ): CreateGroupInNamespaceResponseData

    suspend fun listNamespaceGroups(
        namespaceId: String,
    ): List<SubgroupEntry>

    suspend fun getGroupInfo(
        groupId: String,
    ): GroupInfoResponseData

    suspend fun getDefaultCapabilities(
        groupId: String,
    ): Int

    suspend fun getSubgroupVisibility(
        groupId: String,
    ): String

    suspend fun deleteGroup(
        groupId: String,
        request: DeleteGroupRequest? = null,
    ): DeleteGroupResponseData

    suspend fun listGroupMembers(
        groupId: String,
    ): ListGroupMembersResponseData

    suspend fun listGroupContexts(
        groupId: String,
    ): ListGroupContextsResponseData

    suspend fun addGroupMembers(
        groupId: String,
        request: AddGroupMembersRequest,
    )

    suspend fun removeGroupMembers(
        groupId: String,
        request: RemoveGroupMembersRequest,
    )

    suspend fun updateMemberRole(
        groupId: String,
        identity: String,
        request: UpdateMemberRoleRequest,
    )

    suspend fun getMemberCapabilities(
        groupId: String,
        identity: String,
    ): MemberCapabilities

    suspend fun setMemberCapabilities(
        groupId: String,
        identity: String,
        request: SetMemberCapabilitiesRequest,
    )

    suspend fun setDefaultCapabilities(
        groupId: String,
        request: SetDefaultCapabilitiesRequest,
    )

    suspend fun setSubgroupVisibility(
        groupId: String,
        request: SetSubgroupVisibilityRequest,
    )

    suspend fun setTeeAdmissionPolicy(
        groupId: String,
        request: SetTeeAdmissionPolicyRequest,
    )

    suspend fun setTeeAdmissionPolicy(
        groupId: String,
        request: SetTeeAdmissionPolicyRequest,
        root: DeviceSigner,
        keyEpoch: Long = 0,
        chain: List<String> = emptyList(),
    )

    suspend fun getTeeAdmissionPolicy(
        groupId: String,
    ): GetTeeAdmissionPolicyResponseData

    suspend fun setGroupMetadata(
        groupId: String,
        request: SetGroupMetadataRequest,
    )

    suspend fun getGroupMetadata(
        groupId: String,
    ): MetadataRecord?

    suspend fun setMemberMetadata(
        groupId: String,
        identity: String,
        request: SetMemberMetadataRequest,
    )

    suspend fun getMemberMetadata(
        groupId: String,
        identity: String,
    ): MetadataRecord?

    suspend fun setContextMetadata(
        groupId: String,
        contextId: String,
        request: SetContextMetadataRequest,
    )

    suspend fun getContextMetadata(
        groupId: String,
        contextId: String,
    ): MetadataRecord?

    suspend fun syncGroup(
        groupId: String,
        request: SyncGroupRequest? = null,
    ): SyncGroupResponseData

    suspend fun syncGroupContexts(
        groupId: String,
    ): ListGroupContextsResponseData

    suspend fun upgradeGroup(
        groupId: String,
        request: UpgradeGroupRequest,
    ): UpgradeGroupResponseData

    suspend fun getGroupUpgradeStatus(
        groupId: String,
    ): GroupUpgradeStatusResponseData

    suspend fun getMigrationStatus(
        namespaceId: String,
    ): MigrationStatus

    suspend fun getCascadeStatus(
        namespaceId: String,
    ): List<CascadeStatusEntry>

    suspend fun retryGroupUpgrade(
        groupId: String,
        request: RetryGroupUpgradeRequest? = null,
    ): RetryGroupUpgradeResponseData

    suspend fun reparentGroup(
        childGroupId: String,
        request: ReparentGroupRequest,
    ): ReparentGroupResponseData

    suspend fun listSubgroups(
        groupId: String,
    ): List<SubgroupEntry>

    suspend fun detachContextFromGroup(
        groupId: String,
        contextId: String,
        request: DetachContextFromGroupRequest? = null,
    )

    suspend fun createGroupInvitation(
        groupId: String,
        request: CreateGroupInvitationRequest? = null,
    ): CreateGroupInvitationResult

    suspend fun joinGroup(
        request: JoinGroupRequest,
    ): JoinGroupResponseData

    suspend fun joinSubgroupInheritance(
        groupId: String,
    ): JoinSubgroupInheritanceResponseData

    suspend fun getTeeInfo(): TeeInfoResponseData

    suspend fun teeAttest(
        request: TeeAttestRequest,
    ): TeeAttestResponseData

    suspend fun teeRegistrationAttest(
        request: TeeRegistrationAttestRequest,
    ): TeeAttestResponseData

    suspend fun getPeersCount(): PeersCountResponseData

    suspend fun getNetworkStatus(): JsonElement

    suspend fun getUsage(): JsonElement

    suspend fun createGroup(
        request: Map<String, JsonElement>,
    ): CreateGroupResponseData

    suspend fun createGroup(
        request: CreateGroupRequest,
    ): CreateGroupResponseData

    suspend fun leaveGroup(
        groupId: String,
        request: Map<String, JsonElement>? = null,
    )

    suspend fun leaveContext(
        contextId: String,
        request: Map<String, JsonElement>? = null,
    )

    suspend fun leaveNamespace(
        namespaceId: String,
        request: Map<String, JsonElement>? = null,
    )

    suspend fun issueOwnershipProof(
        groupId: String,
        request: Map<String, JsonElement>? = null,
    ): JsonElement

    suspend fun issueOwnershipProof(
        groupId: String,
        request: IssueOwnershipProofRequest,
    ): IssueOwnershipProofResponseData

    suspend fun issueNamespaceOwnershipProof(
        groupId: String,
        request: IssueNamespaceOwnershipProofRequest,
    ): IssueOwnershipProofResponseData

    suspend fun issueNamespaceOwnershipProof(
        groupId: String,
        request: Map<String, JsonElement>? = null,
    ): JsonElement

    suspend fun setMemberAutoFollow(
        groupId: String,
        identity: String,
        request: Map<String, JsonElement>,
    )

    suspend fun abortMigration(
        namespaceId: String,
        request: Map<String, JsonElement>? = null,
    ): JsonElement

    suspend fun getApplicationAbi(
        applicationId: String,
        serviceName: String? = null,
    ): JsonElement?

    suspend fun performIntent(
        contextId: String,
        request: PerformIntentRequest,
    ): PerformIntentResponseData

    suspend fun listAccountDevices(): AccountDevices

    suspend fun listAccountApplications(): AccountApplications

    suspend fun listMemberDevices(
        groupId: String,
        offset: Int? = null,
        limit: Int? = null,
    ): MemberDevices

    suspend fun listGroupMemberDevices(
        groupId: String,
        offset: Int? = null,
        limit: Int? = null,
    ): List<MemberDeviceGroup>

    suspend fun pairInit(
        request: PairInitRequest,
    ): PairInitResponseData

    suspend fun pairComplete(
        request: PairCompleteRequest,
    ): PairCompleteResponseData

    suspend fun relinkDevice(
        deviceId: String,
        request: RelinkDeviceRequest = RelinkDeviceRequest(),
    ): RelinkDeviceResponseData

    suspend fun rescopeDevice(
        deviceId: String,
        request: RescopeDeviceRequest,
    ): RescopeDeviceResponseData

    suspend fun labelDevice(
        deviceId: String,
        request: LabelDeviceRequest,
    ): LabelDeviceResponseData

    suspend fun revokeAccountDevice(
        namespaceId: String,
        request: RevokeDeviceRequest,
    ): RevokeDeviceResponseData

    suspend fun listNamespaceRevokedDevices(
        namespaceId: String,
    ): NamespaceRevokedDevices

    suspend fun admitJoin(
        namespaceId: String,
        request: AdmitJoinRequest,
    ): AdmitJoinResponseData

    suspend fun queryContext(
        contextId: String,
        request: QueryContextRequest,
    ): QueryContextResponseData

    suspend fun getIntentRelay(
        contextId: String,
    ): IntentRelayInfo

    suspend fun createContextIntent(
        groupId: String,
        request: CreateContextIntentRequest,
    ): CreateContextIntentResponseData

    suspend fun getContextIntentRelay(
        groupId: String,
        author: String? = null,
    ): ContextIntentRelayInfo

    suspend fun governanceIntent(
        groupId: String,
        request: GovernanceIntentRequest,
    ): GovernanceIntentResponseData

    suspend fun getGovernanceIntentRelay(
        groupId: String,
    ): GovernanceIntentRelayInfo

    suspend fun presenceIntent(
        contextId: String,
        request: PresenceIntentRequest,
    )

    suspend fun signWithAccountRoot(
        request: AccountSignWithRootRequest,
    ): AccountSignWithRootResponseData

    suspend fun linkAccountDevice(
        namespaceId: String,
        request: LinkAccountDeviceRequest,
    ): LinkAccountDeviceResponseData

    suspend fun linkAccountDevice(
        namespaceId: String,
        credential: String,
        root: DeviceSigner,
        applications: List<String> = emptyList(),
        scopeEpoch: Long = 0,
    ): LinkAccountDeviceResponseData

    suspend fun sealToAccount(
        groupId: String,
        account: String,
        request: SealToAccountRequest,
    ): SealedEnvelope

    suspend fun transferOwnership(
        groupId: String,
        request: TransferOwnershipRequest,
    )

    suspend fun changeNamespaceAdmin(
        namespaceId: String,
        request: ChangeNamespaceAdminRequest,
    )

    suspend fun ownerDeleteGroup(
        groupId: String,
        request: OwnerDeleteGroupRequest = OwnerDeleteGroupRequest(),
    )

    suspend fun setTeeAuthoringPolicy(
        groupId: String,
        request: SetTeeAuthoringPolicyRequest,
    )

    suspend fun disableTeeAuthoringPolicy(
        groupId: String,
        request: DisableTeeAuthoringPolicyRequest = DisableTeeAuthoringPolicyRequest(),
    )

    suspend fun transferOwnership(
        groupId: String,
        newOwner: String,
        root: DeviceSigner,
        keyEpoch: Long = 0,
        chain: List<String> = emptyList(),
    )

    suspend fun changeNamespaceAdmin(
        namespaceId: String,
        newAdmin: String,
        root: DeviceSigner,
        keyEpoch: Long = 0,
        chain: List<String> = emptyList(),
    )

    suspend fun ownerDeleteGroup(
        groupId: String,
        root: DeviceSigner,
        keyEpoch: Long = 0,
        chain: List<String> = emptyList(),
    )

    suspend fun setTeeAuthoringPolicy(
        groupId: String,
        allowedMrtd: List<String>,
        root: DeviceSigner,
        keyEpoch: Long = 0,
        chain: List<String> = emptyList(),
    )

    suspend fun disableTeeAuthoringPolicy(
        groupId: String,
        root: DeviceSigner,
        keyEpoch: Long = 0,
        chain: List<String> = emptyList(),
    )

    suspend fun openToDelegatedExecution(
        groupId: String,
    ): CapabilityChange

    suspend fun grantAuthorship(
        groupId: String,
        account: String,
    ): CapabilityChange

    /**
     * Join the namespace an invitation names and report what happened — whether the join
     * landed, even when its request failed — see [redeemInvitation]. Never throws for an
     * ordinary failure: the [RedeemOutcome] carries it.
     */
    suspend fun redeemInvitation(
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
        groupName: String? = null,
        teamName: String? = null,
    ): RedeemOutcome
}
