package com.calimero.mero.relay

import com.calimero.mero.crypto.BorshWriter
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.concat
import com.calimero.mero.crypto.domainHash
import com.calimero.mero.crypto.randomBytes
import com.calimero.mero.crypto.u32le

/** A group member's role. Borsh discriminants follow core's `GroupMemberRole`. */
enum class GovernanceMemberRole(
    val byte: Int,
) {
    Admin(0),
    Member(1),
    ReadOnly(2),
}

/** A subgroup's create: its derived id, the salt it was derived with, and the op. */
data class SubgroupCreation(
    val groupId: String,
    val salt: String,
    val op: GovernanceOp,
)

/**
 * Encoders for the governance ops a member may have a relay publish for them.
 *
 * Port of mero-js `src/warrant/governance-op.ts` (24.5.0). A governance warrant commits to
 * an op's **borsh bytes**, so these produce exactly the bytes a node decodes. Every
 * discriminant is the variant's position in core's enum (0.11.0-rc.83
 * `crates/governance-types/src/lib.rs`, `GroupOp` / `RootOp`); the layouts are pinned
 * against core's vectors in `GovernanceOpsTest`.
 *
 * **Delegable form.** A removal, a leave and a cascade delete carry fields only the
 * publisher can compute (post-state hashes, the enumerated subtree). The member signs
 * them cleared, and these encoders only ever produce the cleared form, which is the one a
 * relay accepts.
 */
@Suppress("TooManyFunctions")
object GovernanceOps {
    /** core's `MemberCapabilities::CAN_AUTHOR_ON_BEHALF` (bit 9): never changed through a relay. */
    const val CAN_AUTHOR_ON_BEHALF: Long = 512L

    private const val U32_MAX = 0xFFFF_FFFFL
    private const val U8_MAX = 0xFF

    // `GroupOp` discriminants.
    private const val MEMBER_ADDED = 1
    private const val MEMBER_REMOVED = 2
    private const val MEMBER_LEFT = 3
    private const val MEMBER_ROLE_SET = 4
    private const val MEMBER_CAPABILITY_SET = 5
    private const val DEFAULT_CAPABILITIES_SET = 6
    private const val TARGET_APPLICATION_SET = 7
    private const val CONTEXT_DETACHED = 9
    private const val SUBGROUP_VISIBILITY_SET = 10
    private const val GROUP_METADATA_SET = 11
    private const val MEMBER_METADATA_SET = 12
    private const val CONTEXT_METADATA_SET = 13
    private const val CONTEXT_CAPABILITY_GRANTED = 16
    private const val CONTEXT_CAPABILITY_REVOKED = 17

    // `RootOp` discriminants.
    private const val GROUP_CREATED = 0
    private const val GROUP_REPARENTED = 1
    private const val GROUP_DELETED = 2
    private const val MEMBER_JOINED_OPEN = 7
    private const val NAMESPACE_CREATED_V2 = 9

    /** core's `NAMESPACE_ID_DOMAIN`. */
    private val NAMESPACE_ID_DOMAIN = "calimero.namespace.id.v1".encodeToByteArray()

    /** core's `SUBGROUP_ID_DOMAIN`. */
    private val SUBGROUP_ID_DOMAIN = "calimero.subgroup.id.v1".encodeToByteArray()

    /** A post-state hash left for the relay to compute. */
    private val CLEARED_HASH = ByteArray(32)

    /** An empty borsh `Vec`. */
    private val EMPTY_VEC = u32le(0)

    // ---- Group ops ---------------------------------------------------------------------------

    /** Add [member] (an account, hex) with [role]. Needs `MANAGE_MEMBERS` (or admin for an Admin). */
    fun memberAddedOp(
        member: String,
        role: GovernanceMemberRole,
    ): GovernanceOp = group(tag(MEMBER_ADDED), id(member, "member"), tag(role.byte))

    /** Remove [member], in delegable form: both post-state claims cleared. */
    fun memberRemovedOp(member: String): GovernanceOp = group(tag(MEMBER_REMOVED), id(member, "member"), CLEARED_HASH, EMPTY_VEC)

    /** The author leaves the group, in delegable form. [member] must be the author's own account. */
    fun memberLeftOp(member: String): GovernanceOp = group(tag(MEMBER_LEFT), id(member, "member"), CLEARED_HASH, EMPTY_VEC)

    /** Set [member]'s role. */
    fun memberRoleSetOp(
        member: String,
        role: GovernanceMemberRole,
    ): GovernanceOp = group(tag(MEMBER_ROLE_SET), id(member, "member"), tag(role.byte))

    /** Set [member]'s capability mask (`MemberCapabilities` bits). [CAN_AUTHOR_ON_BEHALF] is refused. */
    fun memberCapabilitySetOp(
        member: String,
        capabilities: Long,
    ): GovernanceOp = group(tag(MEMBER_CAPABILITY_SET), id(member, "member"), u32le(relayableMask(capabilities)))

    /**
     * Set the group's default capability mask, which every member holds without a grant.
     * Needs admin. [CAN_AUTHOR_ON_BEHALF] is refused: a relay never carries a change to it.
     */
    fun defaultCapabilitiesSetOp(capabilities: Long): GovernanceOp =
        group(tag(DEFAULT_CAPABILITIES_SET), u32le(relayableMask(capabilities)))

    /**
     * Choose the application a group runs, in delegable form: `bytecode_id` is left for the
     * relay to fill from `package@version`. Accepted through a relay only as a group's
     * **first** application (a namespace an account founded).
     */
    fun targetApplicationSetOp(
        applicationId: String,
        packageName: String,
        version: String,
    ): GovernanceOp =
        group(
            tag(TARGET_APPLICATION_SET),
            CLEARED_HASH,
            id(applicationId, "applicationId"),
            nonEmptyString(packageName, "package"),
            nonEmptyString(version, "version"),
        )

    /** Detach a context from the group. */
    fun contextDetachedOp(contextId: String): GovernanceOp = group(tag(CONTEXT_DETACHED), id(contextId, "contextId"))

    /** Make a subgroup open (members of the parent may join it themselves) or restricted. */
    fun subgroupVisibilitySetOp(restricted: Boolean): GovernanceOp = group(tag(SUBGROUP_VISIBILITY_SET), tag(if (restricted) 1 else 0))

    /** Name the group, with optional key/value data. */
    fun groupMetadataSetOp(
        name: String? = null,
        data: Map<String, String> = emptyMap(),
    ): GovernanceOp = group(tag(GROUP_METADATA_SET), optionalString(name), stringMap(data))

    /** Name a member in the group. */
    fun memberMetadataSetOp(
        member: String,
        name: String? = null,
        data: Map<String, String> = emptyMap(),
    ): GovernanceOp = group(tag(MEMBER_METADATA_SET), id(member, "member"), optionalString(name), stringMap(data))

    /** Name a context in the group. */
    fun contextMetadataSetOp(
        contextId: String,
        name: String? = null,
        data: Map<String, String> = emptyMap(),
    ): GovernanceOp = group(tag(CONTEXT_METADATA_SET), id(contextId, "contextId"), optionalString(name), stringMap(data))

    /** Grant [member] a per-context capability (a non-zero u8). */
    fun contextCapabilityGrantedOp(
        contextId: String,
        member: String,
        capability: Int,
    ): GovernanceOp = group(tag(CONTEXT_CAPABILITY_GRANTED), id(contextId, "contextId"), id(member, "member"), contextCapability(capability))

    /** Revoke a per-context capability from [member]. */
    fun contextCapabilityRevokedOp(
        contextId: String,
        member: String,
        capability: Int,
    ): GovernanceOp = group(tag(CONTEXT_CAPABILITY_REVOKED), id(contextId, "contextId"), id(member, "member"), contextCapability(capability))

    // ---- Root ops ----------------------------------------------------------------------------

    /**
     * Create a subgroup, a root op posted to the namespace. [groupId] must be
     * [createdSubgroupId]`(admin, parentId, restricted, salt)`; use [subgroupCreation].
     */
    fun groupCreatedOp(
        groupId: String,
        parentId: String,
        restricted: Boolean,
        admin: String,
        salt: String,
    ): GovernanceOp =
        root(
            tag(GROUP_CREATED),
            id(groupId, "groupId"),
            id(parentId, "parentId"),
            tag(if (restricted) 1 else 0),
            id(admin, "admin"),
            id(salt, "salt"),
        )

    /** core's `created_subgroup_id`: `domain_hash("calimero.subgroup.id.v1", [admin, parent, [restricted], salt])`. */
    fun createdSubgroupId(
        admin: String,
        parentId: String,
        restricted: Boolean,
        salt: String,
    ): String =
        Hex.encode(
            domainHash(
                SUBGROUP_ID_DOMAIN,
                listOf(id(admin, "admin"), id(parentId, "parentId"), tag(if (restricted) 1 else 0), id(salt, "salt")),
            ),
        )

    /** A subgroup create: draws a salt (unless given), derives the id and encodes the op. */
    fun subgroupCreation(
        parentId: String,
        restricted: Boolean,
        admin: String,
        salt: String = Hex.encode(randomBytes(32)),
    ): SubgroupCreation {
        val groupId = createdSubgroupId(admin, parentId, restricted, salt)
        return SubgroupCreation(groupId, salt.lowercase(), groupCreatedOp(groupId, parentId, restricted, admin, salt))
    }

    /** Move [childGroupId] under [newParentId]. */
    fun groupReparentedOp(
        childGroupId: String,
        newParentId: String,
    ): GovernanceOp = root(tag(GROUP_REPARENTED), id(childGroupId, "childGroupId"), id(newParentId, "newParentId"))

    /** Delete [rootGroupId] and its subtree, in delegable form: both cascade lists empty. */
    fun groupDeletedOp(rootGroupId: String): GovernanceOp = root(tag(GROUP_DELETED), id(rootGroupId, "rootGroupId"), EMPTY_VEC, EMPTY_VEC)

    /** Join an open subgroup yourself; [member] is the author and [credential] its own `AccountProof<DeviceCert>`. */
    fun memberJoinedOpenOp(
        member: String,
        groupId: String,
        credential: String,
    ): GovernanceOp =
        root(tag(MEMBER_JOINED_OPEN), id(member, "member"), id(groupId, "groupId"), Hex.decodeUnsized(credential, "credential"))

    /**
     * The id of the namespace [founder] founds with [salt]: core's `founded_namespace_id`,
     * `domain_hash("calimero.namespace.id.v1", [founder, salt])`. Only the founder's account
     * hashes to it, so a relay founding for a member cannot choose the id.
     */
    fun foundedNamespaceId(
        founder: String,
        salt: String,
    ): String = Hex.encode(domainHash(NAMESPACE_ID_DOMAIN, listOf(id(founder, "founder"), id(salt, "salt"))))

    /**
     * Found a namespace: core's `RootOp::NamespaceCreatedV2` genesis,
     * `[9] ‖ founder ‖ credential ‖ salt`. The credential is a nested struct, written inline
     * (no length prefix). The warrant's scope must be [foundedNamespaceId]`(founder, salt)`.
     */
    fun namespaceCreatedOp(
        founder: String,
        credential: String,
        salt: String,
    ): GovernanceOp {
        val proof = Hex.decodeUnsized(credential, "credential")
        require(proof.isNotEmpty()) { "credential must be the founder's AccountProof<DeviceCert>, got nothing" }
        return root(tag(NAMESPACE_CREATED_V2), id(founder, "founder"), proof, id(salt, "salt"))
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private fun group(vararg parts: ByteArray) = GovernanceOp(GovernanceOpKind.GROUP, concat(*parts))

    private fun root(vararg parts: ByteArray) = GovernanceOp(GovernanceOpKind.ROOT, concat(*parts))

    private fun tag(byte: Int) = byteArrayOf(byte.toByte())

    private fun id(
        value: String,
        label: String,
    ) = Hex.decode(value, label, 32)

    private fun borshString(value: String): ByteArray {
        val bytes = value.encodeToByteArray()
        return concat(u32le(bytes.size), bytes)
    }

    private fun nonEmptyString(
        value: String,
        label: String,
    ): ByteArray {
        require(value.isNotEmpty()) { "$label must not be empty" }
        return borshString(value)
    }

    private fun optionalString(value: String?) = if (value == null) tag(0) else concat(tag(1), borshString(value))

    /** A borsh `BTreeMap<String, String>`: count, then entries ordered by key **bytes**. */
    private fun stringMap(data: Map<String, String>): ByteArray {
        val entries = data.entries.map { it.key.encodeToByteArray() to it.value }.sortedWith { a, b -> compareBytes(a.first, b.first) }
        val w = BorshWriter().u32(entries.size)
        entries.forEach { (k, v) -> w.bytes(k).string(v) }
        return w.toByteArray()
    }

    private fun compareBytes(
        a: ByteArray,
        b: ByteArray,
    ): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and U8_MAX) - (b[i].toInt() and U8_MAX)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private fun relayableMask(capabilities: Long): Long {
        require(capabilities in 0..U32_MAX) { "capabilities must be a u32 bit mask, got $capabilities" }
        require(capabilities and CAN_AUTHOR_ON_BEHALF == 0L) {
            "capabilities may not include CAN_AUTHOR_ON_BEHALF (512): a relay never carries a change to it"
        }
        return capabilities
    }

    private fun contextCapability(capability: Int): ByteArray {
        require(capability in 1..U8_MAX) { "capability must be a non-zero u8, got $capability" }
        return tag(capability)
    }
}
