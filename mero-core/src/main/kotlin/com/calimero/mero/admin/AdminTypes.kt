package com.calimero.mero.admin

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

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

/**
 * Install by registry coordinates. **No URL** — core 0.11.0-rc.31 (core#3652) made
 * application distribution registry-only, so the node fetches from its own
 * `[registry]` and nothing else. The request carries `deny_unknown_fields`, so a
 * body still naming `url` is not ignored, it is refused:
 *
 * ```
 * 400 unknown field `url`, expected `package` or `version`
 * ```
 *
 * Both fields are required, and both are now non-optional here for that reason.
 */
@Serializable
data class InstallApplicationRequest(
    @SerialName("package") val packageName: String,
    val version: String,
)

/**
 * Install from a local bundle path (development only).
 *
 * `metadata` / `package` / `version` went with the same change: the node reads all
 * three out of the bundle's own signed manifest. It must be an **`.mpk` bundle** —
 * a raw `.wasm` is refused with `not a signed application bundle`.
 */
@Serializable
data class InstallDevApplicationRequest(
    val path: String,
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
    val size: Long,
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
    val size: Long,
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

/**
 * Empty body. `requester` used to sit here; core has never had such a
 * field, and since 0.11.0-rc.38 closed the request bodies an extra key is a
 * 400 for the whole call. Kept as a type so `DeleteContextRequest()` still compiles.
 */
@Serializable
class DeleteContextRequest

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

/**
 * ⚠️ Since core 0.11.0-rc.41 (core#3941) `identities-owned` answers a different
 * question depending on who asks, and [identitiesOf] is how the node says which.
 * `owned` used to be a statement about the NODE — the identities it holds a
 * signing key for. For a delegated caller that is the wrong list twice over, so
 * it now means *"the identities **you** can act as here"*: the calling account's
 * certified, unrevoked devices in the group owning this context.
 *
 * A node-owner session keeps the node-wide reading, so the same call on the same
 * context returns different lists for different tokens. Read [identitiesOf]
 * rather than inferring the reading from your own token.
 */
@Serializable
data class GetContextIdentitiesResponseData(
    val identities: List<String>,
    /**
     * Which reading this list is: [IDENTITIES_OF_MEMBERS] (the `/identities`
     * roster, same for every caller), [IDENTITIES_OF_NODE] (keys this node
     * signs with) or [IDENTITIES_OF_CALLER] (keys the CLIENT holds).
     *
     * `null` from a node predating rc.41, which said nothing about it. Kept a
     * `String` rather than an enum so a reading added by a later core decodes
     * instead of throwing — the honest answer for an older node is "it did not
     * say", and for a newer one "something this SDK has no name for yet".
     */
    val identitiesOf: String? = null,
) {
    companion object {
        /** Every identity that is a member of the context. */
        const val IDENTITIES_OF_MEMBERS = "members"

        /** The identities this NODE holds a signing key for. */
        const val IDENTITIES_OF_NODE = "node"

        /** The calling account's certified, unrevoked devices — keys the client holds. */
        const val IDENTITIES_OF_CALLER = "caller"
    }
}

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
    val sizeInBytes: Long,
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
    val size: Long,
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
    val size: Long,
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

/** Alias a device. `device` is the third alias scope, alongside context and application. */
@Serializable
data class CreateDeviceAliasRequest(
    val alias: String,
    val deviceId: String,
)

/**
 * ⚠️ The list routes answer a **map**, `{"<alias>": "<value>"}`, not a list of
 * entries. Modeled as `List<AliasEntry>` this threw
 * `MissingFieldException: Field 'aliases' is required` on **every** call —
 * including the empty case, which is `{"data":{}}` — so the three alias list
 * methods had never worked. Kept as [AliasEntry] pairs for callers; the decode
 * is the map.
 */
@Serializable
data class AliasEntry(
    val name: String,
    val value: String,
)

typealias ListAliasesResponseData = List<AliasEntry>

typealias CreateAliasResponseData = Empty
typealias DeleteAliasResponseData = Empty

@Serializable
data class LookupAliasResponseData(
    val value: String? = null,
)

// ---- Shared invitation types -----------------------------------------------

/*
 * Unlike the camelCase admin DTOs, the invitation types are serialized **snake_case** by core: they
 * live in `calimero_context_config`, which has no `#[serde(rename_all = "camelCase")]`. Decoding
 * them as camelCase fails with "Fields [inviterIdentity, groupId, …] are required", which is how
 * "Invite people" used to break.
 *
 * ⚠️ **These two are verbatim passthroughs, deliberately.** An invitation is a
 * *signed* document that a client only ever carries: `createNamespaceInvitation`
 * returns it, an app puts it in a share link or the clipboard, and `joinNamespace`
 * hands it back. The node verifies by deserializing the JSON into its own struct,
 * re-encoding as **borsh**, and checking `inviter_signature` over those bytes — so
 * a field the model does not name is dropped on re-encode, the borsh differs, and
 * the signature no longer verifies.
 *
 * A typed model naming only the fields it knows had exactly that bug. Measured
 * against a live `merod 0.11.0-rc.32`: the model named 2 of the envelope's 6 keys
 * and 5 of the signed body's 6.
 *
 * ```
 * envelope:    invitation, inviter_signature,
 *              inviter_account, admitter_addrs, application_id, app_key
 * signed body: inviter_identity, group_id, expiration_timestamp,
 *              secret_salt, invited_role, admitters
 * ```
 *
 * `admitters` is inside the *signed* body, and core#3714 (rc.29) made it non-empty
 * on every invitation, even when the caller names nobody — before that it was
 * usually absent, so the drop was invisible. Side by side on one rc.32 pair, with
 * the same namespace and two freshly minted invitations:
 *
 * ```
 * stripped to the old model → 500  "invalid invitation signature:
 *                                   Verification equation was not satisfied"
 * carried verbatim          → 200  joined
 * ```
 *
 * Losing the *unsigned* hints costs something too: without `application_id` /
 * `app_key` the joiner records zeros and its group state hash diverges from the
 * originator's permanently; without `admitter_addrs` it has no address to dial.
 *
 * Hence: hold the raw [JsonObject], expose typed accessors for what callers read,
 * and re-encode byte-for-byte. Adding a key is as fatal as dropping one — core
 * mirrors `skip_serializing_if`, so an absent `admitters` must NOT come back as
 * `"admitters":[]` — which is another reason not to round-trip through a data
 * class with defaults.
 */

/** The signed body of an invitation. Unknown keys are preserved verbatim. */
@Serializable(with = GroupInvitationFromAdminSerializer::class)
class GroupInvitationFromAdmin(
    /** Every key exactly as core sent it. This, not the accessors, is what re-encodes. */
    val raw: JsonObject,
) {
    val inviterIdentity: List<Int> get() = raw.intList("inviter_identity")
    val groupId: List<Int> get() = raw.intList("group_id")
    val expirationTimestamp: Long get() = raw["expiration_timestamp"]?.jsonPrimitive?.long ?: 0L
    val secretSalt: List<Int> get() = raw.intList("secret_salt")
    val invitedRole: Int? get() = raw["invited_role"]?.jsonPrimitive?.intOrNull

    /**
     * Accounts permitted to admit a claim on this invitation (64 hex each), added
     * in core#3714. **Signed** — see the type docs for why it must survive a
     * round trip. Empty when core sent no such key.
     */
    val admitters: List<String>
        get() = (raw["admitters"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    override fun equals(other: Any?): Boolean = other is GroupInvitationFromAdmin && other.raw == raw

    override fun hashCode(): Int = raw.hashCode()

    override fun toString(): String = "GroupInvitationFromAdmin($raw)"
}

/** An invitation envelope: the signed body plus unsigned bootstrap hints. Verbatim. */
@Serializable(with = SignedGroupOpenInvitationSerializer::class)
class SignedGroupOpenInvitation(
    /** Every key exactly as core sent it. This, not the accessors, is what re-encodes. */
    val raw: JsonObject,
) {
    val invitation: GroupInvitationFromAdmin
        get() = GroupInvitationFromAdmin(raw["invitation"] as? JsonObject ?: JsonObject(emptyMap()))

    val inviterSignature: String get() = raw["inviter_signature"]?.jsonPrimitive?.content.orEmpty()

    /** Unsigned hint: the account that minted this invitation. */
    val inviterAccount: String? get() = raw["inviter_account"]?.jsonPrimitive?.contentOrNull

    /**
     * Unsigned hint: libp2p multiaddrs (each including its `/p2p/<peer-id>` suffix)
     * for the accounts in [GroupInvitationFromAdmin.admitters].
     *
     * rc.32 renamed this from `admitter_hints`, and changed the element type from a
     * tagged `{multiaddr}`/`{url}` enum to a plain multiaddr string.
     */
    val admitterAddrs: List<String>
        get() = (raw["admitter_addrs"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    /** Unsigned hint: the application the group targets, as 32 bytes. */
    val applicationId: List<Int>? get() = (raw["application_id"] as? JsonArray)?.let { raw.intList("application_id") }

    /** Unsigned hint: the bytecode the group is pinned to, as 32 bytes. */
    val appKey: List<Int>? get() = (raw["app_key"] as? JsonArray)?.let { raw.intList("app_key") }

    override fun equals(other: Any?): Boolean = other is SignedGroupOpenInvitation && other.raw == raw

    override fun hashCode(): Int = raw.hashCode()

    override fun toString(): String = "SignedGroupOpenInvitation($raw)"
}

private fun JsonObject.intList(key: String): List<Int> =
    (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty()

/**
 * Decodes to, and re-encodes from, the raw object. Nothing is normalized: no key
 * added, none dropped, no number reshaped — [JsonPrimitive] keeps the literal text,
 * so a u64 timestamp cannot lose precision through a `Double` on the way past.
 */
internal object GroupInvitationFromAdminSerializer : KSerializer<GroupInvitationFromAdmin> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): GroupInvitationFromAdmin =
        GroupInvitationFromAdmin(JsonObject.serializer().deserialize(decoder))

    override fun serialize(
        encoder: Encoder,
        value: GroupInvitationFromAdmin,
    ) = JsonObject.serializer().serialize(encoder, value.raw)
}

/** As [GroupInvitationFromAdminSerializer], for the envelope. */
internal object SignedGroupOpenInvitationSerializer : KSerializer<SignedGroupOpenInvitation> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): SignedGroupOpenInvitation =
        SignedGroupOpenInvitation(JsonObject.serializer().deserialize(decoder))

    override fun serialize(
        encoder: Encoder,
        value: SignedGroupOpenInvitation,
    ) = JsonObject.serializer().serialize(encoder, value.raw)
}

@Serializable
data class RecursiveInvitationEntry(
    val groupId: String,
    val invitation: SignedGroupOpenInvitation,
    val groupName: String? = null,
)

// ---- Namespaces ------------------------------------------------------------

/**
 * ⚠️ `upgradePolicy` is **gone** (core#3485). It always held `"LazyOnAccess"` —
 * the concept moved server-side — and it is not sent any more, so a model that
 * still declares it required throws `MissingFieldException` on every
 * `listNamespaces` / `getNamespace`. `ignoreUnknownKeys` does not help: the
 * problem is a missing *required* field, not an unknown one.
 *
 * `appVersion` arrived in its place.
 */
@Serializable
data class Namespace(
    val namespaceId: String,
    val appKey: String,
    val targetApplicationId: String,
    val createdAt: Long,
    val name: String? = null,
    val memberCount: Int,
    val contextCount: Int,
    val subgroupCount: Int,
    /** The release the target application was built from, e.g. `0.11.0-rc.32`. */
    val appVersion: String? = null,
)

typealias ListNamespacesResponseData = List<Namespace>

/**
 * `upgradePolicy` is not here either: core#3485 removed the concept, and a node
 * that no longer knows the field ignores it on the way in. Sending it is harmless
 * but meaningless, so the SDK stopped.
 */
@Serializable
data class CreateNamespaceRequest(
    val applicationId: String,
    val name: String? = null,
    /** Hex 32-byte blob id; pins the namespace to a specific installed version. */
    val appKey: String? = null,
)

@Serializable
data class CreateNamespaceResponseData(
    val namespaceId: String,
)

/**
 * Empty body. `requester` used to sit here; core has never had such a
 * field, and since 0.11.0-rc.38 closed the request bodies an extra key is a
 * 400 for the whole call. Kept as a type so `DeleteNamespaceRequest()` still compiles.
 */
@Serializable
class DeleteNamespaceRequest

@Serializable
data class DeleteNamespaceResponseData(
    val isDeleted: Boolean,
)

@Serializable
data class CreateNamespaceInvitationRequest(
    /**
     * Clamped to 24h by core (`MAX_INVITATION_VALIDITY_SECS`, rc.29) — a longer
     * value is silently lowered, not refused. It used to default to a year.
     */
    val expirationTimestamp: Long? = null,
    val recursive: Boolean? = null,
    /**
     * Accounts permitted to admit a claim on this invitation, 64 hex each; core
     * 400s on anything else. **Empty means core picks** the group's admins and TEE
     * nodes (core#3714), which is why every rc.29+ invitation comes back carrying a
     * non-empty `admitters` in its signed body.
     */
    val admitters: List<String>? = null,
    /**
     * libp2p multiaddrs (each with its `/p2p/<peer-id>`) for those accounts, taken
     * **as given** rather than merged with what the node knows. Unsigned, so a
     * wrong one costs a failed dial, never authority.
     *
     * ⚠️ Silent if misspelled. rc.32 renamed this from `admitterHints`, and the
     * request is not `deny_unknown_fields` — a node ignores the old key and
     * answers 200, so an unfixed client quietly mints invitations no joiner can
     * dial, exactly as core#3804 made `admitters` an authorization boundary.
     */
    val admitterAddrs: List<String>? = null,
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

/**
 * The result of joining a namespace.
 *
 * core 0.11.0-rc.25 (core#3598) renamed the namespace field on the wire from
 * `groupId` to `namespaceId`. Both are nullable here and resolved by
 * [namespaceId] so either node version decodes: declared as a required
 * `groupId`, this threw `MissingFieldException` on every rc.25 join — a hard
 * failure of the call, which `ignoreUnknownKeys` does not help with, since the
 * problem is a *missing required* field rather than an unknown one.
 *
 * `memberAccount` (rc.21, the AccountId rekey) and `governanceOp` are optional
 * for the same reason: a required field a node may not send is a latent throw.
 */
@Serializable
data class JoinNamespaceResponseData(
    @SerialName("namespaceId") private val namespaceIdField: String? = null,
    @SerialName("groupId") private val groupIdField: String? = null,
    val memberIdentity: String,
    /**
     * The account the joining key became, 64 hex characters. This — not
     * [memberIdentity] — is what member-addressing endpoints take.
     */
    val memberAccount: String? = null,
    val governanceOp: String? = null,
) {
    /** The namespace joined, under whichever spelling the node used. */
    val namespaceId: String
        get() =
            namespaceIdField
                ?: groupIdField
                ?: error("join response carried neither namespaceId (rc.25+) nor groupId (pre-rc.25)")

    @Deprecated("Renamed to namespaceId in core 0.11.0-rc.25", ReplaceWith("namespaceId"))
    val groupId: String
        get() = namespaceId
}

/**
 * Body for `POST /admin-api/namespaces/{id}/groups`.
 *
 * ⚠️ This route is NOT the group-create body. It reads [groupName] and
 * [visibility], and nothing else — a `name` or a `groupId` here is a **422**
 * from core 0.11.0-rc.38, which is every call that bothered to name the
 * subgroup. Only the empty body ever worked.
 */
@Serializable
data class CreateGroupInNamespaceRequest(
    /** The subgroup's name. Sent as `groupName`, which is what the route reads. */
    val groupName: String? = null,
    /** `"open"` or `"restricted"` — lowercase; the node rejects other spellings. */
    val visibility: String? = null,
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

/** `upgradePolicy` dropped here too — see [CreateNamespaceRequest]. */
@Serializable
data class CreateGroupRequest(
    val applicationId: String,
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
    val initiatedAt: Long,
    val initiatedBy: String,
    val status: String,
    val total: Int? = null,
    val completed: Int? = null,
    val failed: Int? = null,
    val completedAt: Long? = null,
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
    val syncedUpToHlc: Long,
    val reportedAt: Long,
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

/** `upgradePolicy` is gone from this response too (core#3485); `groupStateHash` is new. */
@Serializable
data class GroupInfo(
    val groupId: String,
    val appKey: String,
    val targetApplicationId: String,
    val memberCount: Int,
    val contextCount: Int,
    val activeUpgrade: GroupUpgradeStatus? = null,
    val defaultCapabilities: Int,
    val subgroupVisibility: String,
    /** The group's generic metadata record. `null` if never set. */
    val metadata: MetadataRecord? = null,
    /** Hex digest of the group's governance state, for comparing two replicas. */
    val groupStateHash: String? = null,
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

/**
 * Empty body. `requester` used to sit here; core has never had such a
 * field, and since 0.11.0-rc.38 closed the request bodies an extra key is a
 * 400 for the whole call. Kept as a type so `DeleteGroupRequest()` still compiles.
 */
@Serializable
class DeleteGroupRequest

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
)

@Serializable
data class RemoveGroupMembersRequest(
    val members: List<String>,
)

@Serializable
data class UpdateMemberRoleRequest(
    val role: String,
)

// ---- Group Capabilities & Settings -----------------------------------------

@Serializable
data class MemberCapabilities(
    val capabilities: Int,
)

@Serializable
data class SetMemberCapabilitiesRequest(
    val capabilities: Int,
)

@Serializable
data class SetDefaultCapabilitiesRequest(
    val defaultCapabilities: Int,
)

@Serializable
data class SetSubgroupVisibilityRequest(
    val subgroupVisibility: String,
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
    /**
     * ⚠️ **Milliseconds**, and `u64` in core — not seconds, and not an `Int`.
     * Declared `Int`, this overflowed on the very first real body that carried a
     * metadata record (`1788952411519`), taking every `getGroupInfo`,
     * `getGroupMetadata`, `getMemberMetadata` and `getContextMetadata` with it.
     */
    val updatedAt: Long,
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

/**
 * Empty body. `requester` used to sit here; core has never had such a
 * field, and since 0.11.0-rc.38 closed the request bodies an extra key is a
 * 400 for the whole call. Kept as a type so `SyncGroupRequest()` still compiles.
 */
@Serializable
class SyncGroupRequest

@Serializable
data class SyncGroupResponseData(
    val groupId: String,
    val appKey: String,
    val targetApplicationId: String,
    val memberCount: Int,
    val contextCount: Int,
)

@Serializable
data class UpgradeGroupRequest(
    val targetApplicationId: String,
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

/**
 * Empty body. `requester` used to sit here; core has never had such a
 * field, and since 0.11.0-rc.38 closed the request bodies an extra key is a
 * 400 for the whole call. Kept as a type so `RetryGroupUpgradeRequest()` still compiles.
 */
@Serializable
class RetryGroupUpgradeRequest

/** Retry returns the same shape as upgrade. */
typealias RetryGroupUpgradeResponseData = UpgradeGroupResponseData

// ---- Group Reparent & Context Attachments ----------------------------------

@Serializable
data class ReparentGroupRequest(
    /** 64-char id of the destination parent group. */
    val newParentId: String,
)

@Serializable
data class ReparentGroupResponseData(
    val reparented: Boolean,
)

/**
 * Empty body. `requester` used to sit here; core has never had such a
 * field, and since 0.11.0-rc.38 closed the request bodies an extra key is a
 * 400 for the whole call. Kept as a type so `DetachContextFromGroupRequest()` still compiles.
 */
@Serializable
class DetachContextFromGroupRequest

// ---- Group Invitation & Join -----------------------------------------------

@Serializable
data class CreateGroupInvitationRequest(
    /** Clamped to 24h by core (`MAX_INVITATION_VALIDITY_SECS`, rc.29). */
    val expirationTimestamp: Long? = null,
    val recursive: Boolean? = null,
    /** See [CreateNamespaceInvitationRequest.admitters]. */
    val admitters: List<String>? = null,
    /** See [CreateNamespaceInvitationRequest.admitterAddrs]. */
    val admitterAddrs: List<String>? = null,
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

// ---- Node identity, readiness (rc.23 / rc.26) ------------------------------

/**
 * Who this node is. Replaces the deleted `GET /namespaces/{id}/identity`
 * (core#3522, rc.23) — core's own commit message: *"ask the node who it is, and
 * delete the route that asked a namespace"*.
 */
@Serializable
data class NodeIdentity(
    /** The account this node writes as, 64 hex. */
    val accountId: String,
    /** `null` on a node that holds only an account root and no usable device. */
    val deviceId: String? = null,
    /** This node's namespace signing key, 64 hex. */
    val publicKey: String,
    val accountRootPublicKey: String,
    /** The device's KEM key — what certificates publish. `null` with no device. */
    val deviceAgreementKey: String? = null,
    /**
     * Whether this node holds the root of the account it speaks for (rc.32,
     * core#3774). A *paired* node adopted an account rooted on another machine
     * and may hold a root of its own besides, so "a root exists" is not the same
     * question — only the holder can certify another device into the account.
     */
    val holdsAccountRoot: Boolean? = null,
    /**
     * The account that withdrew this node's device (rc.41). `null` on a node no
     * revocation has reached — the field is skipped, never sent as null, so a
     * node predating it answers exactly as it did before.
     *
     * A node that reads this non-null is holding a device its account has
     * disowned: it can still speak locally, but nothing it publishes will be
     * accepted. Surface it rather than letting the writes fail one by one.
     */
    val revokedFrom: RevokedFrom? = null,
)

/** Which account withdrew this node's device, and which device it was. */
@Serializable
data class RevokedFrom(
    /** Hex-encoded account id the device spoke for. */
    val accountId: String,
    /** Hex-encoded device id that was withdrawn. */
    val deviceId: String,
)

// ---- Account: devices, applications, pairing (rc.27 / rc.28) ---------------

/**
 * ⚠️ Flat, not enveloped: `{"devices":[…]}`, with no `data` wrapper. So are
 * [AccountApplications] and [MemberDevices]. Not guessable from the neighbours.
 */
@Serializable
data class AccountDevices(
    val devices: List<AccountDevice>,
)

@Serializable
data class AccountDevice(
    val deviceId: String,
    val signingKey: String,
    /** Set only on the device this node itself presents. */
    val isSelf: Boolean = false,
    val revoked: Boolean = false,
    /** Applications this device may speak for. **Empty means every application.** */
    val applications: List<String> = emptyList(),
    /** Namespaces currently holding a live binding for this device, hex. */
    val namespaces: List<String> = emptyList(),
    /**
     * The replicated name the account gave this device (rc.41), absent while it
     * has none. Every device of the account reads the same one — see
     * [AdminApi.labelDevice].
     */
    val label: String? = null,
)

/** ⚠️ Flat: `{"applications":[…]}`. */
@Serializable
data class AccountApplications(
    val applications: List<AccountApplication>,
)

@Serializable
data class AccountApplication(
    val applicationId: String,
    /** Namespaces targeting this application, hex. */
    val namespaces: List<String> = emptyList(),
)

/**
 * Begin pairing a new device into an account.
 *
 * ⚠️ Pairing moved **off the namespace** in rc.28: a device pairs to an ACCOUNT
 * once (`/account/pair-*`) and then links into namespaces. Revocation stayed
 * per-namespace — that is where the group key rotates.
 */
@Serializable
data class PairInitRequest(
    val accountRootPublicKey: String,
    /** Namespaces to link the new device into. */
    val namespaces: List<String> = emptyList(),
)

@Serializable
data class PairInitResponseData(
    val accountId: String,
    val deviceId: String,
    val kemPublicKey: String,
    val signPublicKey: String,
    /** The statement the holder signs to certify this device. */
    val statement: String,
    /** Short code both sides compare out of band before completing. */
    val confirmationCode: String,
)

@Serializable
data class PairCompleteRequest(
    val deviceId: String,
    val kemPublicKey: String,
    val signPublicKey: String,
    val statement: String,
    val confirmationCode: String,
    /** Empty means the device may speak for every application. */
    val applications: List<String> = emptyList(),
)

@Serializable
data class PairCompleteResponseData(
    val accountId: String,
    val deviceId: String,
    val keyDelivered: Boolean,
    val confirmationCode: String,
    val credential: String,
)

/** Re-link an already-certified device, optionally narrowing its applications. */
@Serializable
data class RelinkDeviceRequest(
    /** Empty means every application. */
    val applications: List<String> = emptyList(),
)

@Serializable
data class RelinkDeviceResponseData(
    val accountId: String,
    val deviceId: String,
    val applications: List<String> = emptyList(),
    val linkedIn: List<RelinkOutcome> = emptyList(),
    val skipped: List<RelinkSkip> = emptyList(),
)

@Serializable
data class RelinkOutcome(
    val namespaceId: String,
    val keyDelivered: Boolean,
)

@Serializable
data class RelinkSkip(
    val namespaceId: String,
    val reason: String,
)

// ---- Account: device scope and name (rc.41) --------------------------------

/**
 * The scope half of a [RescopeDeviceRequest]: `"all"`, or `{"only":[…]}`.
 *
 * Tagged rather than a list whose emptiness means everything — which is the
 * shape [RelinkDeviceRequest] uses, and there the slip (`applications = []`)
 * is the *widest* possible ask. Here an empty [Only] is refused with a `400`
 * instead of silently granting every application.
 */
@Serializable(with = DeviceScopeSerializer::class)
sealed class DeviceScope {
    /** Every application, now and later. On the wire: the bare string `"all"`. */
    data object All : DeviceScope()

    /**
     * Only these applications, hex-encoded. On the wire: `{"only":["…"]}`.
     * An empty list is refused by the node (`400 ScopeReplacementEmpty`).
     */
    data class Only(
        val applications: List<String>,
    ) : DeviceScope()
}

/**
 * Serde's externally-tagged enum, which is a JSON **string** for a unit variant
 * and an object for a newtype one. Hand-written because kotlinx's polymorphism
 * cannot express that pair, and the route is `deny_unknown_fields` — a `type`
 * discriminator beside it would be a `422` for the whole call.
 */
internal object DeviceScopeSerializer : KSerializer<DeviceScope> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): DeviceScope {
        val element = JsonElement.serializer().deserialize(decoder)
        (element as? JsonObject)?.let { obj ->
            val only = obj["only"] as? JsonArray ?: error("device scope object has no `only` array")
            return DeviceScope.Only(only.mapNotNull { it.jsonPrimitive.contentOrNull })
        }
        val named = element.jsonPrimitive.contentOrNull
        require(named == "all") { "unknown device scope: $named" }
        return DeviceScope.All
    }

    override fun serialize(
        encoder: Encoder,
        value: DeviceScope,
    ) {
        val element: JsonElement =
            when (value) {
                is DeviceScope.All -> JsonPrimitive("all")
                is DeviceScope.Only ->
                    JsonObject(
                        mapOf("only" to JsonArray(value.applications.map { JsonPrimitive(it) })),
                    )
            }
        JsonElement.serializer().serialize(encoder, element)
    }
}

/**
 * Replace a device's scope outright — the direction [RelinkDeviceRequest]
 * deliberately cannot go, since relink is add-only. `PUT`, not `POST`: it
 * replaces rather than accumulates.
 *
 * ⚠️ Run on the node that **holds the account root** — only its key can sign the
 * replacement. The device itself is not consulted and need not be online.
 */
@Serializable
data class RescopeDeviceRequest(
    val scope: DeviceScope,
)

@Serializable
data class RescopeDeviceResponseData(
    val accountId: String,
    val deviceId: String,
    /** The scope after the request. Empty means every application. */
    val applications: List<String> = emptyList(),
    /** Namespaces the new scope no longer reaches, and whether the key rotated. */
    val descoped: List<RescopeDescope> = emptyList(),
    /** Namespaces the device was linked into by this call. */
    val linkedIn: List<RelinkOutcome> = emptyList(),
    /** Namespaces nothing was published into, and why. */
    val skipped: List<RelinkSkip> = emptyList(),
)

@Serializable
data class RescopeDescope(
    val namespaceId: String,
    /**
     * `false` means the device stopped writing there but still holds the key it
     * had, until an admin rotates.
     */
    val keyRotated: Boolean,
)

/**
 * Name a device of this account, for a listing to render. The name replicates:
 * every device of the account reads the same one, and it comes back on
 * [AccountDevice.label].
 *
 * Run on the node holding the account root to name any device; a paired node is
 * accepted only for that device's own id.
 */
@Serializable
data class LabelDeviceRequest(
    /** Trimmed, non-empty, bounded and free of control characters. */
    val label: String,
)

@Serializable
data class LabelDeviceResponseData(
    val accountId: String,
    val deviceId: String,
    val label: String,
    /**
     * Orders this name against a rename another device of the account made at
     * the same time. Higher wins.
     */
    val labelEpoch: Int,
)

/** Revoke a device from ONE namespace — this is where the group key rotates. */
@Serializable
data class RevokeDeviceRequest(
    val deviceId: String,
    /** Offline-root proof, when the revoking node is not the holder. */
    val proof: String? = null,
)

@Serializable
data class RevokeDeviceResponseData(
    val accountId: String,
    val deviceId: String,
    val keyRotated: Boolean,
    val revokedIn: List<RevocationOutcome> = emptyList(),
)

@Serializable
data class RevocationOutcome(
    val namespaceId: String,
    val keyRotated: Boolean,
)

/** ⚠️ Flat: `{"members":[…]}`. */
@Serializable
data class MemberDevices(
    val members: List<MemberDeviceGroup>,
)

@Serializable
data class MemberDeviceGroup(
    val account: String,
    val devices: List<MemberDevice>,
)

@Serializable
data class MemberDevice(
    val deviceId: String,
    val signingKey: String,
)

// ---- Direct admission (rc.29) ----------------------------------------------

/**
 * Carry a join that its author signed but cannot publish.
 *
 * The joiner may hold no node at all — an account, a key, a certificate and
 * nowhere to publish from. It signs its own join, hands it to a node the inviter
 * named in the invitation's `admitters`, and that node relays it.
 *
 * The response says **`published`**, not *joined*.
 */
@Serializable
data class AdmitJoinRequest(
    /** The invitation being claimed. Must name the admitting node in its `admitters`. */
    val invitation: SignedGroupOpenInvitation,
    /** The joiner's `SignedNamespaceOp`, borsh-encoded and hex. */
    val signedOp: String,
)

@Serializable
data class AdmitJoinResponseData(
    val published: Boolean,
)

// ---- Intents (rc.26) --------------------------------------------------------

/** Execute a method under a warrant rather than as this node's own identity. */
@Serializable
data class PerformIntentRequest(
    val method: String,
    val argsJson: JsonElement,
    val warrant: String,
    val authorProof: String,
)

@Serializable
data class PerformIntentResponseData(
    val rootHash: String,
    val returns: JsonElement? = null,
)
