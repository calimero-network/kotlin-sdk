package com.calimero.mero.account

import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Ed25519
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.crypto.X25519
import com.calimero.mero.crypto.randomBytes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * This device's two keypairs, all hex:
 *
 * - an Ed25519 **signing** pair: what the wallet certifies, and what signs warrants,
 *   login statements, routing proofs and join ops;
 * - an X25519 **key-delivery** ("KEM") pair: where group keys are sealed to.
 *
 * The shape of mero-react's `DeviceKeys` (`signPk`, `signSk`, `kemPk`, `kemSk`). The
 * secrets never leave the device; only the public halves go to the wallet.
 */
@Serializable
data class DeviceKeys(
    val signPublicKey: String,
    /** 32-byte Ed25519 seed. */
    val signSecret: String,
    val kemPublicKey: String,
    val kemSecret: String,
) {
    /** A [DeviceSigner] over [signSecret]. */
    fun signer(): DeviceSigner = SeedSigner(signSecret)

    override fun toString(): String = "DeviceKeys(signPublicKey=$signPublicKey, kemPublicKey=$kemPublicKey)"

    companion object {
        /** Generate a fresh pair of pairs. */
        fun generate(): DeviceKeys {
            val seed = randomBytes(Ed25519.SEED_BYTES)
            val (kemSecret, kemPublic) = X25519.generate()
            return DeviceKeys(
                signPublicKey = Hex.encode(Ed25519.publicKey(seed)),
                signSecret = Hex.encode(seed),
                kemPublicKey = Hex.encode(kemPublic),
                kemSecret = Hex.encode(kemSecret),
            )
        }
    }
}

/**
 * Persists [DeviceKeys] in a [SecureStore] (`calimero.device`, as mero-react keys its
 * `localStorage` entry). Generated once and kept: the certificate the wallet returns is
 * only usable with the exact key it certified, so the keys must outlive the round trip.
 */
class DeviceKeyStore(
    private val store: SecureStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The stored keys, or null if none (or the stored value is unreadable). */
    fun load(): DeviceKeys? =
        store.get(KEY)?.let { raw ->
            runCatching { json.decodeFromString(DeviceKeys.serializer(), raw) }
                .getOrNull()
                ?.takeIf { Hex.isHex32(it.signPublicKey) && Hex.isHex32(it.kemPublicKey) }
        }

    /** The stored keys, generating and persisting a fresh pair the first time. */
    @Synchronized
    fun loadOrCreate(): DeviceKeys =
        load() ?: DeviceKeys.generate().also { store.put(KEY, json.encodeToString(DeviceKeys.serializer(), it)) }

    /** Forget the keys. Any credential certifying them becomes unusable on this device. */
    fun clear() = store.remove(KEY)

    private companion object {
        const val KEY = "calimero.device"
    }
}
