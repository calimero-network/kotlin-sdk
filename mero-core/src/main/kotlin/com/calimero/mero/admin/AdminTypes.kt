package com.calimero.mero.admin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Admin API wire types — ported 1:1 from the Swift MeroKit `AdminTypes.swift`
 * (itself a 1:1 port of mero-js `admin-types.ts`).
 *
 * IMPORTANT wire-format note: the core admin API serializes these DTOs with
 * `#[serde(rename_all = "camelCase")]`, so the JSON on the wire is camelCase for
 * essentially every type here — NOT snake_case. Kotlin property names therefore
 * map 1:1 to the wire without any `@SerialName` remapping.
 *
 * The genuine snake_case quirks are handled explicitly:
 *   - `Application.signerId`  <- wire `signer_id` (see `@SerialName` below).
 *   - blob DTOs use `blob_id` on the wire — decoded via internal wire structs in
 *     AdminApi and surfaced here as clean camelCase (`blobId`).
 *   - the `package` fields are renamed to `packageName` in Kotlin because
 *     `package` is a reserved keyword; the wire name is preserved via `@SerialName("package")`.
 */

/** Serializes to/decodes from an empty JSON object `{}` (`Record<string, never>`). */
@Serializable
class Empty

// ---- Health and Status -----------------------------------------------------

@Serializable
data class HealthStatus(
    val status: String,
)

/**
 * NOTE: unlike most reads this is NOT unwrapped — `isAuthed` returns the whole
 * envelope, which core shapes as `{ data: { status } }`.
 */
@Serializable
data class AdminAuthStatus(
    val data: StatusInner,
) {
    @Serializable
    data class StatusInner(
        val status: String,
    )
}

// ---- Applications ----------------------------------------------------------

@Serializable
data class InstallApplicationRequest(
    val url: String,
    val hash: String? = null,
    val metadata: List<Int>,
    @SerialName("package") val packageName: String? = null,
    val version: String? = null,
)

@Serializable
data class InstallDevApplicationRequest(
    val path: String,
    val metadata: List<Int>,
    @SerialName("package") val packageName: String? = null,
    val version: String? = null,
)

@Serializable
data class InstallApplicationResponseData(
    val applicationId: String,
)

@Serializable
data class UninstallApplicationResponseData(
    val applicationId: String,
)

@Serializable
data class ApplicationBlob(
    val bytecode: String,
    val compiled: String,
)

@Serializable
data class Application(
    val id: String,
    val blob: ApplicationBlob,
    val size: Int,
    val source: String,
    val metadata: List<Int>,
    /** QUIRK: lone `signer_id` (snake_case) field inside an otherwise camelCase DTO. */
    @SerialName("signer_id") val signerId: String,
    @SerialName("package") val packageName: String,
    val version: String,
)

@Serializable
data class ListApplicationsResponseData(
    val apps: List<Application>,
)

@Serializable
data class GetApplicationResponseData(
    val application: Application? = null,
)

/** One installed blob for an application (distinct from the package registry). */
@Serializable
data class ApplicationVersionEntry(
    val version: String,
    val blobId: String,
    val size: Int,
    @SerialName("package") val packageName: String,
)

@Serializable
data class ListApplicationVersionsResponseData(
    val data: List<ApplicationVersionEntry>,
)

// ---- Packages --------------------------------------------------------------

@Serializable
data class GetLatestVersionResponseData(
    val applicationId: String? = null,
    val version: String? = null,
)

@Serializable
data class ListPackagesResponseData(
    val packages: List<String>,
)

@Serializable
data class ListVersionsResponseData(
    val versions: List<String>,
)

// ---- Bundle migration metadata ---------------------------------------------

/**
 * Per-service migration descriptor carried in a multi-service bundle manifest.
 * `toSchemaVersion` is the CRDT schema version the migrate targets (engine gate);
 * `toVersion` is the user-facing bundle semver; `method` is the migrate entrypoint.
 */
@Serializable
data class BundleMigration(
    val method: String,
    val toSchemaVersion: Int,
    val toVersion: String? = null,
)

/**
 * Subset of a registry bundle manifest that `installFromRegistry` consumes to
 * resolve an artifact URL. Served at `GET {registry}/api/v2/bundles/{package}/{version}`.
 */
@Serializable
data class RegistryBundleManifest(
    @SerialName("package") val packageName: String,
    val appVersion: String,
    /** Present when this bundle's app declares a migration. */
    val migration: BundleMigration? = null,
)

// ---- Contexts --------------------------------------------------------------

@Serializable
data class CreateContextRequest(
    val applicationId: String,
    val groupId: String,
    val serviceName: String? = null,
    val contextSeed: String? = null,
    val initializationParams: List<Int>? = null,
    val identitySecret: String? = null,
    /** Optional human-readable label for the context. */
    val name: String? = null,
)

@Serializable
data class CreateContextResponseData(
    val contextId: String,
    val memberPublicKey: String,
    val groupId: String? = null,
    val groupCreated: Boolean? = null,
)

@Serializable
data class DeleteContextRequest(
    val requester: String? = null,
)

@Serializable
data class DeleteContextResponseData(
    val isDeleted: Boolean,
)

@Serializable
data class Context(
    val id: String,
    val applicationId: String,
    val serviceName: String? = null,
    /** Context state/root hash (wire key `contextStateHash`). */
    val contextStateHash: String,
    val dagHeads: List<List<Int>>,
    /** Bundle semver of the installed application. Absent on older nodes. */
    val applicationVersion: String? = null,
)

/** Swift/Kotlin have no struct inheritance, so this repeats `Context`'s fields plus `groupId`. */
@Serializable
data class ContextWithGroup(
    val id: String,
    val applicationId: String,
    val serviceName: String? = null,
    val contextStateHash: String,
    val dagHeads: List<List<Int>>,
    val applicationVersion: String? = null,
    val groupId: String? = null,
)

@Serializable
data class GetContextsResponseData(
    val contexts: List<ContextWithGroup>,
)

// ---- Context Identity ------------------------------------------------------

@Serializable
data class GenerateContextIdentityResponseData(
    val publicKey: String,
)

@Serializable
data class GetContextIdentitiesResponseData(
    val identities: List<String>,
)

// ---- Context join ----------------------------------------------------------

@Serializable
data class JoinContextResponseData(
    val contextId: String,
    val memberPublicKey: String,
)

@Serializable
data class JoinSubgroupInheritanceResponseData(
    val groupId: String,
    val memberPublicKey: String,
    /**
     * `true` if the call had to publish a `MemberJoinedOpen` op to materialise
     * inherited membership; `false` if the caller was already a direct member.
     */
    val wasInherited: Boolean,
)

// ---- Context group / storage / sync ----------------------------------------

/** `ContextGroupResponseData` is `string | null`. */
typealias ContextGroupResponseData = String?

@Serializable
data class ContextStorageResponseData(
    val sizeInBytes: Int,
)

// ---- Specialized Node Invite -----------------------------------------------

@Serializable
data class InviteSpecializedNodeRequest(
    val contextId: String,
    val inviterId: String? = null,
)

@Serializable
data class InviteSpecializedNodeResponseData(
    val nonce: String,
)

// ---- Update Context Application --------------------------------------------

@Serializable
data class UpdateContextApplicationRequest(
    val applicationId: String,
    val executorPublicKey: String,
)

// ---- Resync Context --------------------------------------------------------

@Serializable
data class ResyncContextRequest(
    /** Force a full re-pull even if the context is not detected as stranded. */
    val force: Boolean? = null,
)

@Serializable
data class ResyncContextResponseData(
    val contextId: String,
    val resyncStarted: Boolean,
)

// ---- Contexts With Executors -----------------------------------------------

@Serializable
data class ContextWithExecutors(
    val contextId: String,
    val executors: List<String>,
)

typealias ContextsWithExecutorsResponseData = List<ContextWithExecutors>

// ---- Blobs -----------------------------------------------------------------

/**
 * Upload request. `data` is the raw blob bytes, streamed verbatim as the request
 * body (octet-stream) — it is NOT JSON-encoded, so this is a plain (non-serializable) value type.
 * A regular class (not `data class`) since it holds a [ByteArray].
 */
class UploadBlobRequest(
    /** Raw blob bytes; streamed verbatim as the request body (octet-stream). */
    val data: ByteArray,
    /** Optional expected blob hash; sent as the `hash` query param. */
    val hash: String? = null,
    /** Optional context to announce the blob to; sent as the `context_id` query param. */
    val contextId: String? = null,
)

@Serializable
data class BlobInfo(
    val blobId: String,
    val size: Int,
)

typealias UploadBlobResponseData = BlobInfo

/**
 * QUIRK: core's `BlobDeleteResponse` is a flat, snake_case payload (`{ blob_id, deleted }`).
 * AdminApi decodes it via an internal wire struct and maps to this clean camelCase shape.
 */
@Serializable
data class DeleteBlobResponseData(
    val blobId: String,
    val deleted: Boolean,
)

@Serializable
data class ListBlobsResponseData(
    val blobs: List<BlobInfo>,
)

typealias GetBlobResponseData = BlobInfo

/** Extends `BlobInfo` with the extra HEAD-header fields (`x-blob-hash` / `x-blob-mime-type`). */
@Serializable
data class GetBlobInfoResponseData(
    val blobId: String,
    val size: Int,
    val hash: String? = null,
    val mimeType: String? = null,
)

// ---- Aliases ---------------------------------------------------------------

@Serializable
data class CreateContextAliasRequest(
    val alias: String,
    val contextId: String,
)

@Serializable
data class CreateApplicationAliasRequest(
    val alias: String,
    val applicationId: String,
)

@Serializable
data class CreateContextIdentityAliasRequest(
    val alias: String,
    val identity: String,
)

@Serializable
data class AliasEntry(
    val name: String,
    val value: String,
)

@Serializable
data class ListAliasesResponseData(
    val aliases: List<AliasEntry>,
)

typealias CreateAliasResponseData = Empty
typealias DeleteAliasResponseData = Empty

@Serializable
data class LookupAliasResponseData(
    val value: String? = null,
)

// ---- Context identity aliases ----------------------------------------------

typealias ListContextIdentityAliasesResponseData = ListAliasesResponseData
typealias CreateContextIdentityAliasResponseData = Empty

@Serializable
data class LookupContextIdentityAliasResponseData(
    val value: String? = null,
)

typealias DeleteContextIdentityAliasResponseData = Empty

// ---- Shared invitation types -----------------------------------------------

/*
 * Unlike the camelCase admin DTOs, the invitation types are serialized **snake_case** by core: they
 * live in `calimero_context_config`, which has no `#[serde(rename_all = "camelCase")]`. Decoding
 * them as camelCase fails with "Fields [inviterIdentity, groupId, …] are required", which is how
 * "Invite people" used to break.
 */

@Serializable
data class GroupInvitationFromAdmin(
    @SerialName("inviter_identity") val inviterIdentity: List<Int>,
    @SerialName("group_id") val groupId: List<Int>,
    @SerialName("expiration_timestamp") val expirationTimestamp: Long,
    @SerialName("secret_salt") val secretSalt: List<Int>,
    @SerialName("invited_role") val invitedRole: Int? = null,
)

@Serializable
data class SignedGroupOpenInvitation(
    val invitation: GroupInvitationFromAdmin,
    @SerialName("inviter_signature") val inviterSignature: String,
)

@Serializable
data class RecursiveInvitationEntry(
    val groupId: String,
    val invitation: SignedGroupOpenInvitation,
    val groupName: String? = null,
)

// ---- Namespaces ------------------------------------------------------------

@Serializable
data class Namespace(
    val namespaceId: String,
    val appKey: String,
    val targetApplicationId: String,
    val upgradePolicy: String,
    val createdAt: Int,
    val name: String? = null,
    val memberCount: Int,
    val contextCount: Int,
    val subgroupCount: Int,
)

typealias ListNamespacesResponseData = List<Namespace>

@Serializable
data class NamespaceIdentity(
    val namespaceId: String,
    val publicKey: String,
)

/** Core's `UpgradePolicy` enum — how a namespace/group adopts new app versions. */
@Serializable
enum class UpgradePolicy {
    @SerialName("Automatic")
    AUTOMATIC,

    @SerialName("LazyOnAccess")
    LAZY_ON_ACCESS,
}

@Serializable
data class CreateNamespaceRequest(
    val applicationId: String,
    val upgradePolicy: UpgradePolicy,
    val name: String? = null,
    /** Hex 32-byte blob id; pins the namespace to a specific installed version. */
    val appKey: String? = null,
)

@Serializable
data class CreateNamespaceResponseData(
    val namespaceId: String,
)

@Serializable
data class DeleteNamespaceRequest(
    val requester: String? = null,
)

@Serializable
data class DeleteNamespaceResponseData(
    val isDeleted: Boolean,
)

@Serializable
data class CreateNamespaceInvitationRequest(
    val requester: String? = null,
    val expirationTimestamp: Int? = null,
    val recursive: Boolean? = null,
)

@Serializable
data class CreateNamespaceInvitationResponseData(
    val invitation: SignedGroupOpenInvitation,
    val groupName: String? = null,
)

@Serializable
data class CreateRecursiveInvitationResponseData(
    val invitations: List<RecursiveInvitationEntry>,
)

/**
 * `createNamespaceInvitation` returns one of two shapes depending on whether the
 * invitation was recursive. Modeled as a tagged union with a lenient decode
 * (recursive payloads carry `invitations`; single ones carry `invitation`).
 *
 * Mirrors the Swift `enum` `.single`/`.recursive`. The decode inspects the JSON
 * for an `invitations` key in AdminApi rather than a fragile custom serializer.
 */
sealed interface CreateNamespaceInvitationResult {
    data class Single(
        val data: CreateNamespaceInvitationResponseData,
    ) : CreateNamespaceInvitationResult

    data class Recursive(
        val data: CreateRecursiveInvitationResponseData,
    ) : CreateNamespaceInvitationResult
}

@Serializable
data class JoinNamespaceRequest(
    val invitation: SignedGroupOpenInvitation,
    val groupName: String? = null,
)

@Serializable
data class JoinNamespaceResponseData(
    val groupId: String,
    val memberIdentity: String,
    val governanceOp: String,
)

@Serializable
data class CreateGroupInNamespaceRequest(
    val groupId: String? = null,
    val name: String? = null,
)

@Serializable
data class CreateGroupInNamespaceResponseData(
    val groupId: String,
)

@Serializable
data class SubgroupEntry(
    val groupId: String,
    val name: String? = null,
)

// ---- Groups ----------------------------------------------------------------

@Serializable
data class CreateGroupRequest(
    val applicationId: String,
    val upgradePolicy: String,
    val groupId: String? = null,
    val appKey: String? = null,
    val name: String? = null,
    val parentGroupId: String? = null,
)

@Serializable
data class CreateGroupResponseData(
    val groupId: String,
)

@Serializable
data class GroupUpgradeStatus(
    val fromVersion: String,
    val toVersion: String,
    val initiatedAt: Int,
    val initiatedBy: String,
    val status: String,
    val total: Int? = null,
    val completed: Int? = null,
    val failed: Int? = null,
    val completedAt: Int? = null,
)

// ---- Migration status ------------------------------------------------------

@Serializable
enum class MemberMigrationState {
    @SerialName("migrated")
    MIGRATED,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("unknown")
    UNKNOWN,

    @SerialName("failed")
    FAILED,
}

/** Why a member's migration did not complete. */
@Serializable
enum class MigrationFailureReason {
    @SerialName("check_aborted")
    CHECK_ABORTED,

    @SerialName("apply_failed")
    APPLY_FAILED,

    @SerialName("no_migration_path")
    NO_MIGRATION_PATH,
}

@Serializable
data class MemberMigrationReport(
    val schemaVersion: Int,
    val residueAuto: Int,
    val residueIdentity: Int,
    val syncedUpToHlc: Int,
    val reportedAt: Int,
    /** Member's self-reported pending-authored count (best-effort). */
    val authoredRemaining: Int,
    /** Set when the member's migrate did not complete. Absent otherwise. */
    val migrationFailed: MigrationFailureReason? = null,
)

@Serializable
data class MemberMigrationStatusEntry(
    val peer: String,
    /** Freshest reported facts, or `null` when the member's state is `unknown`. */
    val report: MemberMigrationReport? = null,
    val state: MemberMigrationState,
)

@Serializable
data class MigrationStatusRollup(
    val migrated: Int,
    val inProgress: Int,
    val unknown: Int,
    /** Members whose migrate aborted (migration-check failed or apply errored). */
    val failed: Int,
    val total: Int,
    val allMigrated: Boolean,
    /** Count of members with authoredRemaining > 0 (owners still to re-sign). */
    val membersPendingSignature: Int,
)

@Serializable
data class MigrationStatus(
    val targetVersion: Int,
    val expectedMembers: Int,
    val cohortPinnedAtHlc: String? = null,
    val rollup: MigrationStatusRollup,
    val members: List<MemberMigrationStatusEntry>,
)

// ---- Cascade status --------------------------------------------------------

@Serializable
data class CascadeStatusEntry(
    val groupId: String,
    val upgrade: GroupUpgradeStatus,
    val cascadeHlc: String? = null,
)

@Serializable
data class GroupInfo(
    val groupId: String,
    val appKey: String,
    val targetApplicationId: String,
    val upgradePolicy: String,
    val memberCount: Int,
    val contextCount: Int,
    val activeUpgrade: GroupUpgradeStatus? = null,
    val defaultCapabilities: Int,
    val subgroupVisibility: String,
    /** The group's generic metadata record. `null` if never set. */
    val metadata: MetadataRecord? = null,
)

typealias GroupInfoResponseData = GroupInfo

@Serializable
data class GroupMember(
    val identity: String,
    val role: String,
    val name: String? = null,
)

@Serializable
data class ListGroupMembersResponseData(
    val members: List<GroupMember>,
    val selfIdentity: String? = null,
    /** @deprecated The server response uses `members`, not `data`; never populated. */
    val data: List<GroupMember>? = null,
)

@Serializable
data class GroupContextEntry(
    val contextId: String,
    val name: String? = null,
)

typealias ListGroupContextsResponseData = List<GroupContextEntry>

@Serializable
data class DeleteGroupRequest(
    val requester: String? = null,
)

@Serializable
data class DeleteGroupResponseData(
    val isDeleted: Boolean,
)

// ---- Group Members ---------------------------------------------------------

@Serializable
data class GroupMemberInput(
    val identity: String,
    val role: String,
)

@Serializable
data class AddGroupMembersRequest(
    val members: List<GroupMemberInput>,
    val requester: String? = null,
)

@Serializable
data class RemoveGroupMembersRequest(
    val members: List<String>,
    val requester: String? = null,
)

@Serializable
data class UpdateMemberRoleRequest(
    val role: String,
    val requester: String? = null,
)

// ---- Group Capabilities & Settings -----------------------------------------

@Serializable
data class MemberCapabilities(
    val capabilities: Int,
)

@Serializable
data class SetMemberCapabilitiesRequest(
    val capabilities: Int,
    val requester: String? = null,
)

@Serializable
data class SetDefaultCapabilitiesRequest(
    val defaultCapabilities: Int,
    val requester: String? = null,
)

@Serializable
data class SetSubgroupVisibilityRequest(
    val subgroupVisibility: String,
    val requester: String? = null,
)

@Serializable
data class SetTeeAdmissionPolicyRequest(
    val allowedMrtd: List<String>,
    val allowedRtmr0: List<String>,
    val allowedRtmr1: List<String>,
    val allowedRtmr2: List<String>,
    val allowedRtmr3: List<String>,
    val allowedTcbStatuses: List<String>,
    val acceptMock: Boolean,
    val requester: String? = null,
)

@Serializable
data class GetTeeAdmissionPolicyResponseData(
    val allowedMrtd: List<String>,
    val allowedRtmr0: List<String>,
    val allowedRtmr1: List<String>,
    val allowedRtmr2: List<String>,
    val allowedRtmr3: List<String>,
    val allowedTcbStatuses: List<String>,
    val acceptMock: Boolean,
)

@Serializable
data class UpdateGroupSettingsRequest(
    val upgradePolicy: String,
    val requester: String? = null,
)

// ---- Group / member / context metadata -------------------------------------

/**
 * Generic metadata record attached to a group, group member, or context.
 * `data` is application-defined and opaque to core. Server-enforced limits:
 * `name` <= 64 bytes; at most 64 entries; each key <= 64 bytes; each value <= 4096 bytes.
 */
@Serializable
data class MetadataRecord(
    val name: String? = null,
    val data: Map<String, String>,
    val updatedAt: Int,
    /** Public key (hex) of the member that last updated the record. */
    val updatedBy: String,
)

/**
 * Request body for setting a metadata record. **This wholly replaces the record**:
 * `data` defaults to `{}` server-side and replaces the stored map, while omitting
 * `name` keeps the current name.
 */
@Serializable
data class SetMetadataRequest(
    val name: String? = null,
    val data: Map<String, String>? = null,
    val requester: String? = null,
)

typealias SetGroupMetadataRequest = SetMetadataRequest
typealias SetMemberMetadataRequest = SetMetadataRequest
typealias SetContextMetadataRequest = SetMetadataRequest

/** Inner payload of a GET metadata response. `data` is `null` if never set. */
@Serializable
data class GetMetadataResponseData(
    val data: MetadataRecord? = null,
)

// ---- Group Sync, Signing & Upgrades ----------------------------------------

@Serializable
data class SyncGroupRequest(
    val requester: String? = null,
)

@Serializable
data class SyncGroupResponseData(
    val groupId: String,
    val appKey: String,
    val targetApplicationId: String,
    val memberCount: Int,
    val contextCount: Int,
)

@Serializable
data class RegisterGroupSigningKeyRequest(
    val signingKey: String,
)

@Serializable
data class RegisterGroupSigningKeyResponseData(
    val publicKey: String,
)

@Serializable
data class UpgradeGroupRequest(
    val targetApplicationId: String,
    val requester: String? = null,
    /**
     * Fan the upgrade out to every descendant subgroup running the same app (one
     * atomic cascade op). Without it the upgrade applies to the target group only.
     * Server default: false.
     */
    val cascade: Boolean? = null,
)

@Serializable
data class UpgradeGroupResponseData(
    val groupId: String,
    val status: String,
    val total: Int? = null,
    val completed: Int? = null,
    val failed: Int? = null,
)

/** `GroupUpgradeStatusResponseData` is `GroupUpgradeStatus | null`. */
typealias GroupUpgradeStatusResponseData = GroupUpgradeStatus?

@Serializable
data class RetryGroupUpgradeRequest(
    val requester: String? = null,
)

/** Retry returns the same shape as upgrade. */
typealias RetryGroupUpgradeResponseData = UpgradeGroupResponseData

// ---- Group Reparent & Context Attachments ----------------------------------

@Serializable
data class ReparentGroupRequest(
    /** 64-char id of the destination parent group. */
    val newParentId: String,
    val requester: String? = null,
)

@Serializable
data class ReparentGroupResponseData(
    val reparented: Boolean,
)

@Serializable
data class DetachContextFromGroupRequest(
    val requester: String? = null,
)

// ---- Group Invitation & Join -----------------------------------------------

@Serializable
data class CreateGroupInvitationRequest(
    val requester: String? = null,
    val expirationTimestamp: Int? = null,
    val recursive: Boolean? = null,
)

@Serializable
data class CreateGroupInvitationResponseData(
    val invitation: SignedGroupOpenInvitation,
    val groupName: String? = null,
)

@Serializable
data class CreateRecursiveGroupInvitationResponseData(
    val invitations: List<RecursiveInvitationEntry>,
)

/**
 * `createGroupInvitation` returns one of two shapes (single vs recursive). Same
 * tagged-union treatment as [CreateNamespaceInvitationResult].
 */
sealed interface CreateGroupInvitationResult {
    data class Single(
        val data: CreateGroupInvitationResponseData,
    ) : CreateGroupInvitationResult

    data class Recursive(
        val data: CreateRecursiveGroupInvitationResponseData,
    ) : CreateGroupInvitationResult
}

@Serializable
data class JoinGroupRequest(
    val invitation: SignedGroupOpenInvitation,
    val groupName: String? = null,
)

@Serializable
data class JoinGroupResponseData(
    val groupId: String,
    val memberIdentity: String,
    val governanceOp: String,
)

// ---- TEE -------------------------------------------------------------------

@Serializable
data class TeeInfoResponseData(
    val cloudProvider: String,
    val osImage: String,
    val mrtd: String,
)

@Serializable
data class TeeAttestRequest(
    val nonce: String,
    val applicationId: String? = null,
)

@Serializable
data class QuoteHeader(
    val version: Int,
    val attestationKeyType: Int,
    val teeType: Int,
    val qeVendorId: String,
    val userData: String,
)

@Serializable
data class QuoteBody(
    val tdxVersion: String,
    val teeTcbSvn: String,
    val mrseam: String,
    val mrsignerseam: String,
    val seamattributes: String,
    val tdattributes: String,
    val xfam: String,
    val mrtd: String,
    val mrconfigid: String,
    val mrowner: String,
    val mrownerconfig: String,
    val rtmr0: String,
    val rtmr1: String,
    val rtmr2: String,
    val rtmr3: String,
    val reportdata: String,
    val teeTcbSvn2: String? = null,
    val mrservicetd: String? = null,
)

@Serializable
data class Quote(
    val header: QuoteHeader,
    val body: QuoteBody,
    val signature: String,
    val attestationKey: String,
    /** Arbitrary JSON (`unknown` in the TS). Optional to tolerate omission. */
    val certificationData: JsonElement? = null,
)

@Serializable
data class TeeAttestResponseData(
    val quoteB64: String,
    val quote: Quote,
)

@Serializable
data class TeeVerifyQuoteRequest(
    val quoteB64: String,
    val nonce: String,
    val expectedApplicationHash: String? = null,
)

@Serializable
data class TeeVerifyQuoteResponseData(
    val quoteVerified: Boolean,
    val nonceVerified: Boolean,
    val applicationHashVerified: Boolean? = null,
    val quote: Quote,
)

// ---- Network ---------------------------------------------------------------

@Serializable
data class PeersCountResponseData(
    val count: Int,
)
