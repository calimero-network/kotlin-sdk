package com.calimero.mero.relay

import com.calimero.mero.crypto.BorshWriter
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.checkedSignature
import com.calimero.mero.crypto.domainHash
import com.calimero.mero.crypto.randomBytes
import com.calimero.mero.crypto.u64le
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The three warrants a device signs so a relay may act for its account, byte-identical
 * with core 0.11.0-rc.83 (`crates/account/src/{warrant,creation,governance}.rs`).
 *
 * Port of mero-js `src/warrant/{warrant,creation-warrant,governance-warrant}.ts`. Each
 * is signed over a `domainHash` preimage in which lengths are u64, and carried as a
 * borsh encoding in which lengths are u32 — the two are not interchangeable, and the
 * golden vectors in the tests pin both.
 *
 * ## Arguments and their JSON
 *
 * A warrant commits to the call's arguments by hashing their JSON text, exactly as
 * mero-js hashes `JSON.stringify(args)`. [canonicalJson] is that text: compact, in the
 * key order the [JsonElement] was built in. Build numbers as integers where the app
 * means integers — kotlinx prints `1.0` where JavaScript prints `1`.
 */
@Suppress("TooManyFunctions")
object Warrants {
    private const val WARRANT_DOMAIN = "calimero.warrant.v2"
    private const val INTENT_DOMAIN = "calimero.warrant.intent.v1"
    private const val CREATION_DOMAIN = "calimero.context-creation-warrant.v1"
    private const val CREATION_INIT_DOMAIN = "calimero.context-creation.init.v1"
    private const val GOVERNANCE_DOMAIN = "calimero.governance-warrant.v1"
    private const val GOVERNANCE_OP_DOMAIN = "calimero.governance-warrant.op.v1"

    /** A node refuses more cited heads than this in either list. */
    const val MAX_CITED_HEADS = 64

    /** A node refuses a release version (or creation label) longer than this many UTF-8 bytes. */
    const val MAX_LABEL_BYTES = 256

    private val compact = Json { prettyPrint = false }

    /** The JSON text a warrant commits to: what `JSON.stringify` would produce for the same value. */
    fun canonicalJson(value: JsonElement): String = compact.encodeToString(JsonElement.serializer(), value)

    /** `domainHash("calimero.warrant.intent.v1", [method, json(args)])`. */
    fun intentHash(
        method: String,
        argsJson: JsonElement,
    ): ByteArray =
        domainHash(INTENT_DOMAIN, listOf(method.encodeToByteArray(), canonicalJson(argsJson).encodeToByteArray()))

    /**
     * Sign a data-write warrant (v2) for `POST /admin-api/contexts/{ctx}/intents`.
     * Returns the warrant as hex.
     */
    fun signWarrant(input: WarrantInput): String {
        val context = Hex.decode(input.context, "context", 32)
        val authorAccount = Hex.decode(input.authorAccount, "authorAccount", 32)
        val executor = Hex.decode(input.executor, "executor", 32)
        val executorKey = Hex.decode(input.executorKey, "executorKey", 32)
        val releaseBytecodeId = Hex.decode(input.releaseBytecodeId, "releaseBytecodeId", 32)
        val releaseVersion = labelBytes(input.releaseVersion, "releaseVersion")
        val accountHeads = citedHeads(input.accountHeads, "accountHeads")
        val governanceFloor = citedHeads(input.governanceFloor, "governanceFloor")
        val deviceKey = Hex.decode(input.signer.publicKey, "signer.publicKey", 32)
        val method = input.method.encodeToByteArray()
        val commitment = intentHash(input.method, input.argsJson)

        val preimage =
            domainHash(
                WARRANT_DOMAIN,
                listOf(context, authorAccount, deviceKey, executor, executorKey, releaseBytecodeId, releaseVersion, method, commitment) +
                    headsPreimage(accountHeads, governanceFloor) +
                    listOf(u64le(input.nonce), u64le(input.notAfter)),
            )
        val signature = checkedSignature(input.signer.sign(preimage))

        return Hex.encode(
            BorshWriter()
                .raw(context)
                .raw(authorAccount)
                .raw(deviceKey)
                .raw(executor)
                .raw(executorKey)
                .raw(releaseBytecodeId)
                .bytes(releaseVersion)
                .bytes(method)
                .raw(commitment)
                .heads(accountHeads)
                .heads(governanceFloor)
                .u64(input.nonce)
                .u64(input.notAfter)
                .raw(signature)
                .toByteArray(),
        )
    }

    /** `domainHash("calimero.context-creation.init.v1", [json(initArgs)])`. */
    fun creationInitHash(initArgs: JsonElement): ByteArray =
        domainHash(CREATION_INIT_DOMAIN, listOf(canonicalJson(initArgs).encodeToByteArray()))

    /**
     * Sign a context-creation warrant for `POST /admin-api/groups/{g}/context-intents`.
     * A fresh random seed is drawn when [CreationWarrantInput.seed] is null; it is returned
     * because it decides the new context's id.
     */
    fun signCreationWarrant(input: CreationWarrantInput): SignedCreationWarrant {
        val group = Hex.decode(input.group, "group", 32)
        val seed = input.seed?.let { Hex.decode(it, "seed", 32) } ?: randomBytes(32)
        val authorAccount = Hex.decode(input.authorAccount, "authorAccount", 32)
        val executor = Hex.decode(input.executor, "executor", 32)
        val executorKey = Hex.decode(input.executorKey, "executorKey", 32)
        val applicationId = Hex.decode(input.applicationId, "applicationId", 32)
        val serviceName = input.serviceName?.let { labelBytes(it, "serviceName") }
        val name = input.name?.let { labelBytes(it, "name") }
        val accountHeads = citedHeads(input.accountHeads, "accountHeads")
        val governanceFloor = citedHeads(input.governanceFloor, "governanceFloor")
        val deviceKey = Hex.decode(input.signer.publicKey, "signer.publicKey", 32)
        val initHash = creationInitHash(input.initArgs)

        val preimage =
            creationPreimage(
                group, seed, authorAccount, deviceKey, executor, executorKey, applicationId,
                serviceName, name, initHash, accountHeads, governanceFloor, input.nonce, input.notAfter,
            )
        val signature = checkedSignature(input.signer.sign(preimage))
        val wire =
            BorshWriter()
                .raw(group)
                .raw(seed)
                .raw(authorAccount)
                .raw(deviceKey)
                .raw(executor)
                .raw(executorKey)
                .raw(applicationId)
                .option(serviceName) { bytes(it) }
                .option(name) { bytes(it) }
                .raw(initHash)
                .heads(accountHeads)
                .heads(governanceFloor)
                .u64(input.nonce)
                .u64(input.notAfter)
                .raw(signature)
                .toByteArray()
        return SignedCreationWarrant(Hex.encode(wire), Hex.encode(seed))
    }

    /** The creation warrant's signing preimage. Exposed for the conformance tests. */
    @Suppress("LongParameterList")
    fun creationPreimage(
        group: ByteArray,
        seed: ByteArray,
        authorAccount: ByteArray,
        deviceKey: ByteArray,
        executor: ByteArray,
        executorKey: ByteArray,
        applicationId: ByteArray,
        serviceName: ByteArray?,
        name: ByteArray?,
        initHash: ByteArray,
        accountHeads: List<ByteArray>,
        governanceFloor: List<ByteArray>,
        nonce: ULong,
        notAfter: ULong,
    ): ByteArray =
        domainHash(
            CREATION_DOMAIN,
            listOf(
                group, seed, authorAccount, deviceKey, executor, executorKey, applicationId,
                byteArrayOf(if (serviceName == null) 0 else 1), serviceName ?: ByteArray(0),
                byteArrayOf(if (name == null) 0 else 1), name ?: ByteArray(0),
                initHash,
            ) + headsPreimage(accountHeads, governanceFloor) + listOf(u64le(nonce), u64le(notAfter)),
        )

    /** `domainHash("calimero.governance-warrant.op.v1", [kind, op])`. */
    fun governanceOpHash(op: GovernanceOp): ByteArray =
        domainHash(GOVERNANCE_OP_DOMAIN, listOf(byteArrayOf(op.kind.byte.toByte()), op.bytes))

    /** Sign a governance warrant for `POST /admin-api/groups/{g}/governance-intents`. Returns hex. */
    fun signGovernanceWarrant(input: GovernanceWarrantInput): String {
        val scope = Hex.decode(input.scope, "scope", 32)
        val authorAccount = Hex.decode(input.authorAccount, "authorAccount", 32)
        val executor = Hex.decode(input.executor, "executor", 32)
        val executorKey = Hex.decode(input.executorKey, "executorKey", 32)
        val accountHeads = citedHeads(input.accountHeads, "accountHeads")
        val governanceFloor = citedHeads(input.governanceFloor, "governanceFloor")
        val deviceKey = Hex.decode(input.signer.publicKey, "signer.publicKey", 32)
        val kind =
            byteArrayOf(
                input.op.kind.byte
                    .toByte(),
            )
        val opHash = governanceOpHash(input.op)

        val preimage =
            domainHash(
                GOVERNANCE_DOMAIN,
                listOf(scope, kind, authorAccount, deviceKey, executor, executorKey, opHash) +
                    headsPreimage(accountHeads, governanceFloor) +
                    listOf(u64le(input.nonce), u64le(input.notAfter)),
            )
        val signature = checkedSignature(input.signer.sign(preimage))
        return Hex.encode(
            BorshWriter()
                .raw(scope)
                .raw(kind)
                .raw(authorAccount)
                .raw(deviceKey)
                .raw(executor)
                .raw(executorKey)
                .raw(opHash)
                .heads(accountHeads)
                .heads(governanceFloor)
                .u64(input.nonce)
                .u64(input.notAfter)
                .raw(signature)
                .toByteArray(),
        )
    }

    /**
     * Each head list is preceded by its own count (u64 here; u32 on the wire), so
     * `accountHeads=[a,b], floor=[]` cannot hash like `[a], [b]`.
     */
    private fun headsPreimage(
        accountHeads: List<ByteArray>,
        governanceFloor: List<ByteArray>,
    ): List<ByteArray> =
        listOf(u64le(accountHeads.size.toLong())) + accountHeads +
            listOf(u64le(governanceFloor.size.toLong())) + governanceFloor

    private fun BorshWriter.heads(heads: List<ByteArray>): BorshWriter =
        apply {
            u32(heads.size)
            heads.forEach { raw(it) }
        }

    private fun citedHeads(
        heads: List<String>,
        label: String,
    ): List<ByteArray> {
        require(heads.size <= MAX_CITED_HEADS) {
            "$label cites ${heads.size} heads, over the $MAX_CITED_HEADS a node accepts"
        }
        return heads.mapIndexed { i, head -> Hex.decode(head, "$label[$i]", 32) }
    }

    private fun labelBytes(
        value: String,
        label: String,
    ): ByteArray {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MAX_LABEL_BYTES) { "$label is ${bytes.size} bytes, over the $MAX_LABEL_BYTES a node accepts" }
        return bytes
    }
}

/** Inputs to [Warrants.signWarrant]. Ids and keys are 64 hex. */
data class WarrantInput(
    val context: String,
    /** Whose consent this is. */
    val authorAccount: String,
    /** The relay's account (discovery's `executorAccount`). */
    val executor: String,
    /** The one key of [executor] that may spend this warrant (discovery's `executorKey`). */
    val executorKey: String,
    /** The release the context's group names (discovery's `releaseBytecodeId`). */
    val releaseBytecodeId: String,
    /** That release's semver (discovery's `releaseVersion`); signed, never compared. */
    val releaseVersion: String = "",
    val method: String,
    val argsJson: JsonElement,
    /** Monotonic per author device. */
    val nonce: ULong,
    /** Unix seconds after which the relay must refuse it. */
    val notAfter: ULong,
    val signer: DeviceSigner,
    val accountHeads: List<String> = emptyList(),
    val governanceFloor: List<String> = emptyList(),
)

/** Inputs to [Warrants.signCreationWarrant]. */
data class CreationWarrantInput(
    val group: String,
    val authorAccount: String,
    val executor: String,
    val executorKey: String,
    val applicationId: String,
    val initArgs: JsonElement,
    val nonce: ULong,
    val notAfter: ULong,
    val signer: DeviceSigner,
    /** 32 bytes hex; random when null. */
    val seed: String? = null,
    val serviceName: String? = null,
    val name: String? = null,
    val accountHeads: List<String> = emptyList(),
    val governanceFloor: List<String> = emptyList(),
)

/** A signed creation warrant and the seed it used. */
data class SignedCreationWarrant(
    val warrant: String,
    val seed: String,
)

/** Which plane a governance op belongs to. */
enum class GovernanceOpKind(
    val byte: Int,
) {
    GROUP(0),
    ROOT(1),
}

/** A borsh-encoded `GroupOp` or `RootOp`, opaque to the warrant. */
class GovernanceOp(
    val kind: GovernanceOpKind,
    val bytes: ByteArray,
)

/** Inputs to [Warrants.signGovernanceWarrant]. */
data class GovernanceWarrantInput(
    /** The group (or namespace) the op governs. */
    val scope: String,
    val op: GovernanceOp,
    val authorAccount: String,
    val executor: String,
    val executorKey: String,
    val nonce: ULong,
    val notAfter: ULong,
    val signer: DeviceSigner,
    val accountHeads: List<String> = emptyList(),
    val governanceFloor: List<String> = emptyList(),
)
