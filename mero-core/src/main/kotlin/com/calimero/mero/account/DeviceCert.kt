package com.calimero.mero.account

import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Ed25519
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.crypto.checkedSignature
import com.calimero.mero.crypto.concat
import com.calimero.mero.crypto.domainHash
import com.calimero.mero.crypto.readU32le
import com.calimero.mero.crypto.u32le

/**
 * A device certificate: an account root vouching for one device's keys.
 *
 * Port of mero-js `src/device-cert/device-cert.ts` (24.5.0), itself derived from core:
 * `DeviceCert::signing_payload` (`crates/account/src/device.rs`), `DeviceId::mint`
 * (`crates/primitives/src/identity.rs`) and the `AccountProof` / `AccountGenesis` borsh
 * layout (`crates/account/src/{signed,account}.rs`).
 *
 * On a phone the root never exists: the wallet holds it and certifies this device's key
 * over a redirect. What the app does with this file is **check** the certificate it is
 * handed ([verifyDeviceCredential]); [signDeviceCert] is here for tests and mock wallets.
 */
object DeviceCert {
    private const val CERT_DOMAIN = "calimero.device.cert.v1"
    private const val ACCOUNT_ID_DOMAIN = "calimero.account.genesis.v1"
    private const val DEVICE_ID_DOMAIN = "calimero.device.id.v1"

    /**
     * Core's `ACCOUNT_GENESIS_VERSION`. Part of the account-id preimage, so a mismatch
     * silently names a different account rather than failing as a version error.
     */
    const val ACCOUNT_GENESIS_VERSION = 2

    /** Byte length of an `AccountProof<DeviceCert>` with an empty handoff chain. */
    const val ACCOUNT_PROOF_BYTES = 237

    /** The account a root owns: the content address of its genesis (`version ‖ root_pk`). */
    fun accountForRootPublicKey(rootPublicKey: String): String =
        Hex.encode(
            domainHash(
                ACCOUNT_ID_DOMAIN,
                listOf(concat(byteArrayOf(ACCOUNT_GENESIS_VERSION.toByte()), Hex.decode(rootPublicKey, "rootPublicKey", 32))),
            ),
        )

    /** [accountForRootPublicKey] from the root's secret seed. */
    fun accountForRoot(rootSecret: String): String = accountForRootPublicKey(SeedSigner(rootSecret).publicKey)

    /** Mint a device id: `nonce ‖ H(DEVICE_ID_DOMAIN, account ‖ nonce)[..16]`. */
    fun mintDeviceId(
        account: String,
        nonce: ByteArray,
    ): String {
        require(nonce.size == 16) { "nonce must be 16 bytes, got ${nonce.size}" }
        val binding = domainHash(DEVICE_ID_DOMAIN, listOf(Hex.decode(account, "account", 32), nonce))
        return Hex.encode(concat(nonce, binding.copyOfRange(0, 16)))
    }

    /** The 32 bytes a root signs. Both device keys are covered, so neither can be swapped. */
    fun payload(
        account: String,
        device: String,
        signPublicKey: String,
        kemPublicKey: String,
        keyEpoch: Long,
        deviceEpoch: Long,
    ): ByteArray =
        domainHash(
            CERT_DOMAIN,
            listOf(
                Hex.decode(account, "account", 32),
                Hex.decode(device, "device", 32),
                Hex.decode(signPublicKey, "signPublicKey", 32),
                Hex.decode(kemPublicKey, "kemPublicKey", 32),
                u32le(keyEpoch),
                u32le(deviceEpoch),
            ),
        )

    /**
     * Certify a device, returning the hex `AccountProof<DeviceCert>` that `merod account
     * sign-cert` prints. For tests and mock wallets: a real app never holds a root.
     */
    fun sign(
        root: DeviceSigner,
        device: String,
        signPublicKey: String,
        kemPublicKey: String,
        deviceEpoch: Long = 0,
    ): String {
        val account = accountForRootPublicKey(root.publicKey)
        val keyEpoch = 0L
        val signature =
            checkedSignature(root.sign(payload(account, device, signPublicKey, kemPublicKey, keyEpoch, deviceEpoch)))
        return Hex.encode(
            concat(
                byteArrayOf(ACCOUNT_GENESIS_VERSION.toByte()),
                Hex.decode(root.publicKey, "root.publicKey", 32),
                u32le(0),
                Hex.decode(account, "account", 32),
                Hex.decode(device, "device", 32),
                Hex.decode(signPublicKey, "signPublicKey", 32),
                Hex.decode(kemPublicKey, "kemPublicKey", 32),
                u32le(keyEpoch),
                u32le(deviceEpoch),
                signature,
            ),
        )
    }

    /**
     * Read a credential apart. Checks the *shape* only: what the bytes claim, not
     * whether the claim is true — [verify] answers that.
     *
     * @throws IllegalArgumentException on a wrong length, genesis version or a non-empty
     *   handoff chain (verifying a handed-off root means walking the chain, which this SDK
     *   cannot do yet, and silently skipping it would hand a verifier the wrong root).
     */
    fun parse(credential: String): DeviceCredential {
        val bytes = Hex.decode(credential, "credential", ACCOUNT_PROOF_BYTES)
        val version = bytes[0].toInt() and 0xFF
        require(version == ACCOUNT_GENESIS_VERSION) {
            "this credential has account genesis version $version, not $ACCOUNT_GENESIS_VERSION — it names a " +
                "different account than its root key derives here, and core would refuse it"
        }
        val chainLength = readU32le(bytes, 33)
        require(chainLength == 0L) {
            "this credential carries a $chainLength-handoff root chain, which this SDK cannot verify yet"
        }

        fun at(
            offset: Int,
            length: Int,
        ) = Hex.encode(bytes.copyOfRange(offset, offset + length))
        return DeviceCredential(
            rootPublicKey = at(1, 32),
            account = at(37, 32),
            device = at(69, 32),
            signPublicKey = at(101, 32),
            kemPublicKey = at(133, 32),
            keyEpoch = readU32le(bytes, 165),
            deviceEpoch = readU32le(bytes, 169),
            signature = at(173, 64),
        )
    }

    /**
     * Check a credential says what it claims: its account is the one its root derives,
     * its device id was minted for that account, and the root signed it.
     *
     * Does **not** establish the account is the one the person wanted — anyone can mint a
     * root offline. That is what the enrolment `state` narrows (see [Enrolment]).
     *
     * @throws IllegalArgumentException naming the check that failed. Every failure is a
     *   credential that must not be stored.
     */
    fun verify(credential: String): DeviceCredential {
        val parsed = parse(credential)
        val derived = accountForRootPublicKey(parsed.rootPublicKey)
        require(derived == parsed.account) {
            "this credential names account ${parsed.account}, but its root key derives $derived — the " +
                "certificate was re-pointed at another account"
        }
        val nonce = Hex.decode(parsed.device, "device", 32).copyOfRange(0, 16)
        require(mintDeviceId(parsed.account, nonce) == parsed.device) {
            "this credential names device ${parsed.device}, which was not minted for account ${parsed.account}; core refuses it"
        }
        val signed =
            Ed25519.verify(
                Hex.decode(parsed.rootPublicKey, "rootPublicKey", 32),
                Hex.decode(parsed.signature, "signature", 64),
                payload(parsed.account, parsed.device, parsed.signPublicKey, parsed.kemPublicKey, parsed.keyEpoch, parsed.deviceEpoch),
            )
        require(signed) { "the root key of account ${parsed.account} did not sign this certificate" }
        return parsed
    }
}

/** A credential read back apart, field for field. All keys and ids are 64 lowercase hex. */
data class DeviceCredential(
    val rootPublicKey: String,
    val account: String,
    val device: String,
    val signPublicKey: String,
    val kemPublicKey: String,
    val keyEpoch: Long,
    val deviceEpoch: Long,
    /** 128 hex. */
    val signature: String,
)

/** mero-js-named alias for [DeviceCert.parse]. */
fun parseDeviceCredential(credential: String): DeviceCredential = DeviceCert.parse(credential)

/** mero-js-named alias for [DeviceCert.verify]. */
fun verifyDeviceCredential(credential: String): DeviceCredential = DeviceCert.verify(credential)
