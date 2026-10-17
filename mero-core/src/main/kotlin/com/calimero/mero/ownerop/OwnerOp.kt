package com.calimero.mero.ownerop

import com.calimero.mero.account.DeviceCert
import com.calimero.mero.crypto.BorshWriter
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.checkedSignature
import com.calimero.mero.crypto.concat
import com.calimero.mero.crypto.domainHash
import com.calimero.mero.crypto.resolveSigner
import com.calimero.mero.crypto.u32le
import com.calimero.mero.crypto.u64le

/*
 * Root proofs for owner-level governance ops (core schema 18) — port of mero-js
 * `src/owner-op/owner-op.ts`.
 *
 * A device key speaks for its account everywhere in governance, so a stolen device used to
 * be enough to take a group from its owner. Core now refuses the ops that can do that
 * unless they carry an authorisation signed by the account's **root** key, which a device
 * does not hold:
 *
 * - `GroupOp::TransferOwnership`, `GroupOp::GroupDelete` and `RootOp::AdminChanged`, which
 *   are owner-only;
 * - the TEE admission, release-admission and authoring policies, which stay admin-level but
 *   need the signing admin's own root.
 *
 * A node that holds the account root (as `merod init` provisions) signs the proof itself, so
 * a caller on such a node passes nothing. This file is for the other case: a nodeless
 * account, or a root kept cold, signing offline and passing the hex proof as `rootProof`.
 *
 * What the root signs binds the account, the namespace, the group, the kind of op, a digest
 * of the op's own borsh bytes, the root epoch, and the group's guarded-op counter
 * (`GroupInfo.ownerOpCounter`). The node applies a proof only while the group is still at
 * that count, so each proof is single-use. For `AdminChanged` the group is the namespace root.
 *
 * Byte layouts follow core's `calimero_account::OwnerOpAuthorization` /
 * `SignedOwnerOp = AccountProof<OwnerOpAuthorization>` (`crates/account/src/owner_op.rs`)
 * and `calimero_governance_types::{GroupOp, RootOp}`, pinned against core's own vectors in
 * `OwnerOpTest`.
 */

/** core's `OWNER_OP_SIGN_DOMAIN`: what the root signs under. */
private const val OWNER_OP_SIGN_DOMAIN = "calimero.account.owner-op.v1"

/** core's `OWNER_OP_BODY_DOMAIN`: what the op's bytes are hashed under. */
private const val OWNER_OP_BODY_DOMAIN = "calimero.account.owner-op.body.v1"

/** A `RootKeyHandoff`: account 32, from_epoch u32, new root key 32, signature 64. */
private const val HANDOFF_BYTES = 32 + 4 + 32 + 64

/**
 * Which owner-level op a proof authorises. [tag] is the byte core signs — its `OwnerOpKind`
 * discriminant — and [jsName] the key mero-js's `OWNER_OP_KIND` uses.
 */
enum class OwnerOpKind(
    val tag: Int,
    val jsName: String,
) {
    TRANSFER_OWNERSHIP(0, "transferOwnership"),
    ADMIN_CHANGED(1, "adminChanged"),
    GROUP_DELETE(2, "groupDelete"),
    TEE_ADMISSION_POLICY(3, "teeAdmissionPolicy"),
    TEE_AUTHORING_POLICY(4, "teeAuthoringPolicy"),
    TEE_RELEASE_ADMISSION_POLICY(5, "teeReleaseAdmissionPolicy"),
}

/** mero-js `OWNER_OP_KIND`: each kind's name to the byte core signs. */
val OWNER_OP_KIND: Map<String, Int> = OwnerOpKind.entries.associate { it.jsName to it.tag }

/** Which plane the op is on: a `GroupOp` on a group, or a `RootOp` on the namespace. */
enum class OwnerOpPlane { GROUP, ROOT }

/**
 * An encoded owner-level op: its borsh [bytes] (exactly what the node builds, and what the
 * digest covers), its [kind] and its [plane].
 */
class OwnerOp(
    val kind: OwnerOpKind,
    val plane: OwnerOpPlane,
    bytes: ByteArray,
) {
    private val encoded = bytes.copyOf()

    /** The op's borsh bytes (a copy). */
    val bytes: ByteArray get() = encoded.copyOf()

    override fun equals(other: Any?): Boolean =
        other is OwnerOp && other.kind == kind && other.plane == plane && other.encoded.contentEquals(encoded)

    override fun hashCode(): Int = (kind.hashCode() * 31 + plane.hashCode()) * 31 + encoded.contentHashCode()

    override fun toString(): String = "OwnerOp(kind=$kind, plane=$plane, bytes=${Hex.encode(encoded)})"
}

/** `GroupOp` discriminants, by position in core's enum. */
private object GroupOpTag {
    const val GROUP_DELETE = 14
    const val TRANSFER_OWNERSHIP = 21
    const val TEE_AUTHORING_POLICY_SET = 33
    const val TEE_ADMISSION_POLICY_SET_V2 = 37
    const val TEE_RELEASE_ADMISSION_POLICY_SET_V2 = 38
    const val ROOT_GUARDED = 42
}

/** `RootOp` discriminants, by position in core's enum. */
private object RootOpTag {
    const val ADMIN_CHANGED = 3
    const val ROOT_GUARDED = 12
}

private fun tag(byte: Int): ByteArray = byteArrayOf(byte.toByte())

private fun borshString(value: String): ByteArray {
    val bytes = value.encodeToByteArray()
    return concat(u32le(bytes.size), bytes)
}

private fun borshStrings(values: List<String>): ByteArray =
    BorshWriter()
        .u32(values.size)
        .apply { values.forEach { string(it) } }
        .toByteArray()

private fun borshBool(value: Boolean): ByteArray = tag(if (value) 1 else 0)

/** core's `TeeAdmissionMode`: `replica` (0, the default) or `relay` (1). */
private fun teeMode(mode: String?): ByteArray =
    when (mode ?: "replica") {
        "replica" -> tag(0)
        "relay" -> tag(1)
        else -> throw IllegalArgumentException("mode must be replica or relay, got $mode")
    }

/** Hand the group to [newOwner] (an account, hex), who must already be an admin. */
fun transferOwnershipOp(newOwner: String): OwnerOp =
    OwnerOp(
        OwnerOpKind.TRANSFER_OWNERSHIP,
        OwnerOpPlane.GROUP,
        concat(tag(GroupOpTag.TRANSFER_OWNERSHIP), Hex.decode(newOwner, "newOwner", 32)),
    )

/** The owner-only deletion of a group that holds no contexts. */
fun groupDeleteOp(): OwnerOp = OwnerOp(OwnerOpKind.GROUP_DELETE, OwnerOpPlane.GROUP, tag(GroupOpTag.GROUP_DELETE))

/** Repoint the namespace's admin pin at [newAdmin] (an account, hex). A root op. */
fun adminChangedOp(newAdmin: String): OwnerOp =
    OwnerOp(
        OwnerOpKind.ADMIN_CHANGED,
        OwnerOpPlane.ROOT,
        concat(tag(RootOpTag.ADMIN_CHANGED), Hex.decode(newAdmin, "newAdmin", 32)),
    )

/** The TEE authoring policy `PUT` / `DELETE …/settings/tee-authoring-policy` publishes (empty = off). */
fun teeAuthoringPolicyOp(allowedMrtd: List<String>): OwnerOp =
    OwnerOp(
        OwnerOpKind.TEE_AUTHORING_POLICY,
        OwnerOpPlane.GROUP,
        concat(tag(GroupOpTag.TEE_AUTHORING_POLICY_SET), borshStrings(allowedMrtd)),
    )

/** A measurement-list admission policy, as the node publishes it (the V2 form). */
data class TeeAdmissionPolicyOpInput(
    val allowedMrtd: List<String>,
    val allowedRtmr0: List<String>,
    val allowedRtmr1: List<String>,
    val allowedRtmr2: List<String>,
    val allowedRtmr3: List<String>,
    val allowedTcbStatuses: List<String>,
    val acceptMock: Boolean,
    /** `replica` (default when `null`) or `relay`. */
    val mode: String? = null,
)

/**
 * The measurement-list admission policy `PUT …/settings/tee-admission-policy` publishes.
 * Pass the fields exactly as you send them: the node signs over the op it builds from them,
 * and any difference is a different op.
 */
fun teeAdmissionPolicyOp(input: TeeAdmissionPolicyOpInput): OwnerOp =
    OwnerOp(
        OwnerOpKind.TEE_ADMISSION_POLICY,
        OwnerOpPlane.GROUP,
        concat(
            tag(GroupOpTag.TEE_ADMISSION_POLICY_SET_V2),
            borshStrings(input.allowedMrtd),
            borshStrings(input.allowedRtmr0),
            borshStrings(input.allowedRtmr1),
            borshStrings(input.allowedRtmr2),
            borshStrings(input.allowedRtmr3),
            borshStrings(input.allowedTcbStatuses),
            borshBool(input.acceptMock),
            teeMode(input.mode),
        ),
    )

/** A signed-release admission policy, as the node publishes it (the V2 form). */
data class TeeReleaseAdmissionPolicyOpInput(
    val allowedProfiles: List<String>,
    /**
     * The oldest release admitted, in the node's normalised form (`X.Y.Z`, no
     * `mero-tee-v` prefix): the node normalises what it is sent before it builds the op,
     * and the proof must be for the op it builds.
     */
    val minReleaseVersion: String? = null,
    val allowedTcbStatuses: List<String>,
    val acceptMock: Boolean,
    /** `replica` (default when `null`) or `relay`. */
    val mode: String? = null,
)

/** The signed-release admission policy `PUT …/settings/tee-admission-policy` publishes. */
fun teeReleaseAdmissionPolicyOp(input: TeeReleaseAdmissionPolicyOpInput): OwnerOp {
    val minRelease = input.minReleaseVersion?.let { concat(tag(1), borshString(it)) } ?: tag(0)
    return OwnerOp(
        OwnerOpKind.TEE_RELEASE_ADMISSION_POLICY,
        OwnerOpPlane.GROUP,
        concat(
            tag(GroupOpTag.TEE_RELEASE_ADMISSION_POLICY_SET_V2),
            borshStrings(input.allowedProfiles),
            minRelease,
            borshStrings(input.allowedTcbStatuses),
            borshBool(input.acceptMock),
            teeMode(input.mode),
        ),
    )
}

/** core's `OwnerOpAuthorization::op_digest`: the op's bytes, hashed under the body domain. */
fun ownerOpDigest(opBytes: ByteArray): ByteArray = domainHash(OWNER_OP_BODY_DOMAIN, listOf(opBytes))

/** core's `OwnerOpTerms::signing_payload`: the 32 bytes the root signs. */
@Suppress("LongParameterList")
fun ownerOpSigningPayload(
    account: String,
    namespaceId: String,
    groupId: String,
    kind: OwnerOpKind,
    opDigest: ByteArray,
    counter: Long,
    keyEpoch: Long,
): ByteArray =
    domainHash(
        OWNER_OP_SIGN_DOMAIN,
        listOf(
            Hex.decode(account, "account", 32),
            Hex.decode(namespaceId, "namespaceId", 32),
            Hex.decode(groupId, "groupId", 32),
            tag(kind.tag),
            opDigest,
            u64le(counter),
            u32le(keyEpoch),
        ),
    )

/**
 * What an owner-op proof binds, apart from the op and the key that signs it. Give the root
 * as [rootSecret] (64 hex, never sent anywhere) **or** [signer] (e.g. an
 * `AccountRootSigner`), not both.
 */
data class OwnerOpProofInput(
    /** The namespace the op is published in (`getGroupInfo(groupId).namespaceId`), hex. */
    val namespaceId: String,
    /** The group the op acts on, hex. For [adminChangedOp], the namespace root. */
    val groupId: String,
    /** The op, from one of the encoders here. */
    val op: OwnerOp,
    /** The group's current guarded-op counter (`getGroupInfo(groupId).ownerOpCounter`). */
    val counter: Long,
    /** The account root's signing secret, 64 hex. Or pass [signer]. */
    val rootSecret: String? = null,
    /** The account root as a signer, for a root that is not held as hex. */
    val signer: DeviceSigner? = null,
    /** The root epoch that signs: 0 for a root never handed off. A later epoch needs [chain]. */
    val keyEpoch: Long = 0,
    /**
     * The account's root-key handoffs, epoch 0 upward, each the hex borsh of a
     * `RootKeyHandoff` (132 bytes). It must reach at least [keyEpoch].
     */
    val chain: List<String> = emptyList(),
) {
    override fun toString(): String =
        "OwnerOpProofInput(namespaceId=$namespaceId, groupId=$groupId, op=$op, counter=$counter, " +
            "keyEpoch=$keyEpoch, chain=${chain.size} handoffs)"
}

/**
 * Sign a root proof for [OwnerOpProofInput.op] and return it as core's `rootProof`: the hex
 * borsh of an `AccountProof<OwnerOpAuthorization>` (core's `SignedOwnerOp`).
 *
 * Layout: `AccountGenesis` (version u8 + root pk 32), the handoff chain (u32 count + 132
 * bytes each), then `OwnerOpAuthorization` (account 32, namespace 32, group 32, kind u8, op
 * digest 32, counter u64, key epoch u32, signature 64).
 *
 * @throws IllegalArgumentException on malformed hex, a handoff of the wrong size, a
 *   [OwnerOpProofInput.keyEpoch] the chain cannot reach, or both/neither root forms.
 */
fun signOwnerOpProof(input: OwnerOpProofInput): String {
    val signer = resolveSigner(input.rootSecret, input.signer, "rootSecret")
    val rootPublicKey = Hex.decode(signer.publicKey, "signer.publicKey", 32)
    val genesis = concat(tag(DeviceCert.ACCOUNT_GENESIS_VERSION), rootPublicKey)
    val account = DeviceCert.accountForRootPublicKey(signer.publicKey)
    val chain =
        input.chain.mapIndexed { index, handoff ->
            val bytes = Hex.decodeUnsized(handoff, "chain[$index]")
            require(bytes.size == HANDOFF_BYTES) {
                "chain[$index] must be a $HANDOFF_BYTES-byte RootKeyHandoff, got ${bytes.size}"
            }
            bytes
        }
    require(input.keyEpoch <= chain.size) {
        "keyEpoch ${input.keyEpoch} needs ${input.keyEpoch} handoffs in chain, got ${chain.size}"
    }

    val opDigest = ownerOpDigest(input.op.bytes)
    val payload =
        ownerOpSigningPayload(account, input.namespaceId, input.groupId, input.op.kind, opDigest, input.counter, input.keyEpoch)
    val signature = checkedSignature(signer.sign(payload))

    val out =
        BorshWriter()
            .raw(genesis)
            .u32(chain.size)
            .apply { chain.forEach { raw(it) } }
            .raw(Hex.decode(account, "account", 32))
            .raw(Hex.decode(input.namespaceId, "namespaceId", 32))
            .raw(Hex.decode(input.groupId, "groupId", 32))
            .u8(input.op.kind.tag)
            .raw(opDigest)
            .u64(input.counter)
            .u32(input.keyEpoch)
            .raw(signature)
    return Hex.encode(out.toByteArray())
}

/**
 * The `RootGuarded` wrapper core publishes: the op and its proof, as one `GroupOp` or
 * `RootOp`. A client never sends this to a node (the endpoints take the proof and build the
 * wrapper); it is here so the whole encoding is checkable against core's vector.
 */
fun rootGuardedOpBytes(
    op: OwnerOp,
    rootProof: String,
): ByteArray {
    val wrapper = if (op.plane == OwnerOpPlane.GROUP) GroupOpTag.ROOT_GUARDED else RootOpTag.ROOT_GUARDED
    return concat(tag(wrapper), op.bytes, Hex.decodeUnsized(rootProof, "rootProof"))
}
