package com.calimero.mero.account

import com.calimero.mero.crypto.BorshWriter
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.checkedSignature
import com.calimero.mero.crypto.domainHash
import com.calimero.mero.crypto.resolveSigner
import com.calimero.mero.crypto.u32le

/** core's `DEVICE_SCOPE_SIGN_DOMAIN` (`crates/account/src/domain.rs`). */
private const val SCOPE_DOMAIN = "calimero.device.scope.v1"

/**
 * What a root scopes one device to — mero-js `DeviceScopeInput`. Give the root as
 * [rootSecret] (64 hex) **or** [signer], not both.
 *
 * The certificate from [DeviceCert.sign] says *this key is a device of the account*; this
 * statement says *where*: a namespace binds a device only when both arrive together
 * (`POST /admin-api/namespaces/{id}/account/link-device`, [com.calimero.mero.admin.AdminApi.linkAccountDevice]).
 * A node that pairs a device signs the scope itself; an account with **no node** signs it
 * here, next to the certificate.
 */
data class DeviceScopeInput(
    /** The device being scoped, 64 hex — the one its certificate names. */
    val device: String,
    /**
     * Application ids the device may speak for, 64 hex each. **Empty means all of them**
     * (the default). A namespace binds the device only when its application is listed here
     * (or the list is empty): a scope naming the wrong application is a `400`, not a binding.
     */
    val applications: List<String> = emptyList(),
    /** Orders scopes for this device: only a higher one supersedes. 0 for a first scope. */
    val scopeEpoch: Long = 0,
    /** The account root's signing secret, 64 hex. Or pass [signer]. */
    val rootSecret: String? = null,
    /** The account root as a signer — for a root that is not held as hex. */
    val signer: DeviceSigner? = null,
) {
    override fun toString(): String = "DeviceScopeInput(device=$device, applications=$applications, scopeEpoch=$scopeEpoch)"
}

/**
 * The 32 bytes a root signs to scope a device: `domain_hash` over account, device,
 * `scope_epoch` (u32 LE), `key_epoch` (u32 LE), then each application id as its own
 * length-prefixed part. core's `DeviceScope::signing_payload` (`crates/account/src/scope.rs`).
 */
fun deviceScopePayload(
    account: String,
    device: String,
    applications: List<String>,
    scopeEpoch: Long,
    keyEpoch: Long,
): ByteArray =
    domainHash(
        SCOPE_DOMAIN,
        listOf(
            Hex.decode(account, "account", 32),
            Hex.decode(device, "device", 32),
            u32le(scopeEpoch),
            u32le(keyEpoch),
        ) + applications.mapIndexed { i, a -> Hex.decode(a, "applications[$i]", 32) },
    )

/**
 * Scope a device, returning the hex `AccountProof<DeviceScope>` that `link-device` takes
 * beside the credential — mero-js `signDeviceScope`.
 *
 * ```
 * AccountProof { genesis: AccountGenesis, chain: Vec<RootKeyHandoff>, statement: DeviceScope }
 *   AccountGenesis { version: u8, root_sign_pk: [u8; 32] }
 *   chain          → u32-LE count (0 here)
 *   DeviceScope    { account, device: [u8; 32] x2, applications: Vec<[u8; 32]>,
 *                    scope_epoch: u32, key_epoch: u32, signature: [u8; 64] }
 * ```
 *
 * `chain` is empty and `key_epoch` 0, as in [DeviceCert.sign]: a root never handed off
 * signs directly.
 *
 * @throws IllegalArgumentException on malformed hex, a [DeviceScopeInput.scopeEpoch] that
 *   is not a u32, or both/neither root forms.
 */
fun signDeviceScope(input: DeviceScopeInput): String {
    val signer = resolveSigner(input.rootSecret, input.signer, "rootSecret")
    val rootPublicKey = Hex.decode(signer.publicKey, "signer.publicKey", 32)
    val account = DeviceCert.accountForRootPublicKey(signer.publicKey)
    require(input.scopeEpoch in 0..0xFFFF_FFFFL) { "scopeEpoch must be a u32, got ${input.scopeEpoch}" }
    val keyEpoch = 0L
    val signature =
        checkedSignature(signer.sign(deviceScopePayload(account, input.device, input.applications, input.scopeEpoch, keyEpoch)))
    val out =
        BorshWriter()
            .u8(DeviceCert.ACCOUNT_GENESIS_VERSION)
            .raw(rootPublicKey)
            .u32(0)
            .raw(Hex.decode(account, "account", 32))
            .raw(Hex.decode(input.device, "device", 32))
            .u32(input.applications.size)
            .apply { input.applications.forEachIndexed { i, a -> raw(Hex.decode(a, "applications[$i]", 32)) } }
            .u32(input.scopeEpoch)
            .u32(keyEpoch)
            .raw(signature)
    return Hex.encode(out.toByteArray())
}
