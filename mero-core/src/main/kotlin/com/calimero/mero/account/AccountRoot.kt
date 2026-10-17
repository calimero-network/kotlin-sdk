package com.calimero.mero.account

import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Ed25519
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.base64
import com.calimero.mero.crypto.checkedSignature
import com.calimero.mero.crypto.concat
import com.calimero.mero.crypto.resolveSigner
import com.calimero.mero.crypto.sha256
import com.calimero.mero.crypto.signerFromSecret
import com.calimero.mero.http.MeroStateException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale

/*
 * Creating an account root, and proving you hold one — port of mero-js
 * `src/account/account.ts`.
 *
 * - [generateAccountRoot] mints a root and hands back a 24-word recovery phrase. The phrase
 *   *is* the key (see the interop note there), so a holder who writes it down can restore
 *   the account on any device.
 * - [signAccountLink] signs a cloud-issued challenge with that root: how Calimero Cloud
 *   binds a billing login to a Calimero account.
 * - [signAccountLogin] signs a different cloud challenge with the same root to claim the
 *   account outright and open a session as it.
 *
 * All offline and stateless except the [AccountRootKeyStore], which is where an Android app
 * keeps a root it must not lose: the seed lives only in a [SecureStore] (use
 * [EncryptedPrefsSecureStore] on a device) and the [AccountRootSigner] reads it per signature
 * without ever exposing it.
 *
 * Derived from mdma's `ACCOUNT_LINK_DOMAIN` / `ACCOUNT_LOGIN_DOMAIN` and
 * `verify_account_link_proof` / `verify_login_proof` (`manager/app/account_proof.py`), and
 * core's `AccountId` preimage ([DeviceCert.accountForRootPublicKey]).
 */

/**
 * mdma's `ACCOUNT_LINK_DOMAIN`, trailing NUL included. Prefixed raw, not run through
 * `domainHash`: mdma verifies `ed25519_verify(root_pk, ACCOUNT_LINK_DOMAIN ‖ nonce_utf8)`.
 */
private val ACCOUNT_LINK_DOMAIN = "calimero.mdma.account-link.v1\u0000".encodeToByteArray()

/** mdma's `ACCOUNT_LOGIN_DOMAIN`, trailing NUL included — separate so a link signature never replays as a login. */
private val ACCOUNT_LOGIN_DOMAIN = "calimero.mdma.account-login.v1\u0000".encodeToByteArray()

/** Entropy carried by a phrase: 256 bits, an Ed25519 seed exactly. */
private const val ENTROPY_BYTES = 32

/** 11 bits per word, so (256 + 8) / 11 = 24 words. */
private const val PHRASE_WORDS = 24

private const val BITS_PER_WORD = 11

private val WORD_INDEX: Map<String, Int> by lazy { ENGLISH_WORDLIST.withIndex().associate { (i, w) -> w to i } }

/**
 * An account root: the secret, the key it derives, and the account it names.
 *
 * [secret] is the whole account — it signs device certificates and cloud links, and there
 * is no recovery path that does not go through it or its phrase. Never send it anywhere, and
 * never log it ([toString] leaves it out).
 */
open class AccountRoot(
    /** The root signing secret (the Ed25519 seed), 64 hex. */
    val secret: String,
    /** The root's Ed25519 public key, 64 hex. Safe to publish. */
    val publicKey: String,
    /** The account this root names, 64 hex — the content address of its genesis. */
    val accountId: String,
) {
    override fun equals(other: Any?): Boolean =
        other is AccountRoot && other.javaClass == javaClass && other.secret == secret && other.accountId == accountId

    override fun hashCode(): Int = accountId.hashCode()

    override fun toString(): String = "AccountRoot(publicKey=$publicKey, accountId=$accountId)"
}

/** An account root together with the phrase that restores it. */
class RecoverableAccountRoot(
    secret: String,
    publicKey: String,
    accountId: String,
    /**
     * A 24-word BIP39 English recovery phrase, single-space separated. Show it once, have
     * the holder write it down, and do not keep it.
     */
    val phrase: String,
) : AccountRoot(secret, publicKey, accountId) {
    override fun toString(): String = "RecoverableAccountRoot(publicKey=$publicKey, accountId=$accountId)"
}

/**
 * Mint a brand-new account root from the platform CSPRNG.
 *
 * **Interop note, and it matters.** The phrase encodes the root secret as BIP39 *entropy*,
 * not as a BIP39 *seed*. A tool restoring it must call the equivalent of
 * `mnemonicToEntropy(phrase)` — 32 bytes — and use those bytes as the Ed25519 secret
 * directly. `mnemonicToSeed(phrase)` runs PBKDF2 and returns 64 different bytes; feeding
 * those in names a different account. There is no passphrase and no BIP32 derivation path:
 * the 24 words are the key. (Identical to mero-js.)
 */
fun generateAccountRoot(random: SecureRandom = SecureRandom()): RecoverableAccountRoot {
    val entropy = ByteArray(ENTROPY_BYTES).also { random.nextBytes(it) }
    return withPhrase(Hex.encode(entropy), encodePhrase(entropy))
}

/**
 * Restore an account root from its recovery phrase. Whitespace and case are normalised; a
 * word outside the list, a wrong word count or a failed checksum throws rather than
 * returning some other account.
 *
 * @throws IllegalArgumentException naming what is wrong with the phrase.
 */
fun accountRootFromPhrase(phrase: String): RecoverableAccountRoot {
    val words = normalisePhrase(phrase)
    return withPhrase(Hex.encode(decodePhrase(words)), words.joinToString(" "))
}

/**
 * The public half of a root you already hold.
 *
 * @throws IllegalArgumentException when [secret] is not 64 hex characters.
 */
fun accountRootFromSecret(secret: String): AccountRoot {
    Hex.decode(secret, "secret", ENTROPY_BYTES)
    val publicKey = signerFromSecret(secret, "secret").publicKey
    return AccountRoot(secret, publicKey, DeviceCert.accountForRootPublicKey(publicKey))
}

/**
 * An account root held as a key this app may use and cannot read back — mero-js
 * `AccountRootSigner`. It is itself a [DeviceSigner] (pass it anywhere a root signer is
 * taken: [DeviceCert.sign], [signDeviceScope], [signAccountLink], the root-signer
 * overloads on [com.calimero.mero.admin.AdminApi]), and there is deliberately no `secret`
 * beside it.
 *
 * On Android the seed is read from the backing [SecureStore] for each signature and
 * zeroed after; nothing on this object holds it. Ed25519 keys only arrived in
 * AndroidKeyStore at API 33 and the SDK supports 24, so the seed cannot be a
 * non-exportable Keystore key: [EncryptedPrefsSecureStore] — values encrypted at rest under
 * a Keystore-backed AES master key — is the hardware-anchored option on every API level.
 */
open class AccountRootSigner internal constructor(
    /** The root's Ed25519 public key, 64 hex. Safe to publish. */
    final override val publicKey: String,
    /** The account this root names, 64 hex. */
    val accountId: String,
    private val seed: () -> ByteArray,
) : DeviceSigner {
    /** This signer, under mero-js's field name. */
    val signer: DeviceSigner get() = this

    override fun sign(message: ByteArray): ByteArray {
        val bytes = seed()
        return try {
            Ed25519.sign(bytes, message)
        } finally {
            bytes.fill(0)
        }
    }

    override fun toString(): String = "AccountRootSigner(publicKey=$publicKey, accountId=$accountId)"
}

/**
 * A freshly minted root, with the phrase that is its only backup — mero-js
 * `NewAccountRootSigner`. Show [phrase] **once** and store it nowhere: the signer cannot be
 * exported, so a holder who does not write these words down has no path back to the account
 * from another device.
 */
class NewAccountRootSigner internal constructor(
    publicKey: String,
    accountId: String,
    seed: () -> ByteArray,
    /** The 24-word phrase. */
    val phrase: String,
) : AccountRootSigner(publicKey, accountId, seed) {
    override fun toString(): String = "NewAccountRootSigner(publicKey=$publicKey, accountId=$accountId)"
}

/**
 * Where an [AccountRootSigner]'s seed lives: one entry ([key]) of a [SecureStore]. Use an
 * [EncryptedPrefsSecureStore] on a device so the root survives the process and is encrypted
 * at rest; the [MemorySecureStore] default of [createAccountRootSigner] forgets it with the
 * process (the phrase is then the only way back).
 */
class AccountRootKeyStore(
    private val store: SecureStore,
    private val key: String = DEFAULT_KEY,
) {
    /** Mint a root, keep its seed in the store, and hand back the signer and its phrase. */
    fun create(random: SecureRandom = SecureRandom()): NewAccountRootSigner {
        val entropy = ByteArray(ENTROPY_BYTES).also { random.nextBytes(it) }
        try {
            val phrase = encodePhrase(entropy)
            val signer = save(entropy)
            return NewAccountRootSigner(signer.publicKey, signer.accountId, ::readSeed, phrase)
        } finally {
            entropy.fill(0)
        }
    }

    /** Restore a root from its phrase into the store (replacing any held there). */
    fun restore(phrase: String): AccountRootSigner {
        val entropy = decodePhrase(normalisePhrase(phrase))
        try {
            return save(entropy)
        } finally {
            entropy.fill(0)
        }
    }

    /** The root this store holds, or `null` if none. */
    fun load(): AccountRootSigner? {
        val seed = store.get(key)?.takeIf { Hex.isHex32(it) } ?: return null
        val publicKey = Hex.encode(Ed25519.publicKey(Hex.decode(seed, "rootSecret", ENTROPY_BYTES)))
        return AccountRootSigner(publicKey, DeviceCert.accountForRootPublicKey(publicKey), ::readSeed)
    }

    /** Forget the root. Its signers stop working; only the phrase can bring it back. */
    fun clear() = store.remove(key)

    private fun save(entropy: ByteArray): AccountRootSigner {
        store.put(key, Hex.encode(entropy))
        val publicKey = Hex.encode(Ed25519.publicKey(entropy))
        return AccountRootSigner(publicKey, DeviceCert.accountForRootPublicKey(publicKey), ::readSeed)
    }

    private fun readSeed(): ByteArray {
        val seed = store.get(key) ?: throw MeroStateException("the account root was removed from its store; restore it from the phrase")
        return Hex.decode(seed, "rootSecret", ENTROPY_BYTES)
    }

    companion object {
        /** The default [SecureStore] key. */
        const val DEFAULT_KEY = "calimero.account-root"
    }
}

/**
 * Mint a root that stays in [store] and hand back its recovery phrase — mero-js
 * `createAccountRootSigner`. Same BIP39 entropy and account derivation as
 * [generateAccountRoot], but the secret never leaves as a value a caller could persist.
 */
fun createAccountRootSigner(
    store: SecureStore = MemorySecureStore(),
    key: String = AccountRootKeyStore.DEFAULT_KEY,
): NewAccountRootSigner = AccountRootKeyStore(store, key).create()

/**
 * Restore the same root on another device from its phrase — mero-js
 * `accountRootSignerFromPhrase`. The same 24 words reach the same account id anywhere.
 *
 * @throws IllegalArgumentException on a bad word count, an unknown word or a failed checksum.
 */
fun accountRootSignerFromPhrase(
    phrase: String,
    store: SecureStore = MemorySecureStore(),
    key: String = AccountRootKeyStore.DEFAULT_KEY,
): AccountRootSigner = AccountRootKeyStore(store, key).restore(phrase)

/** A root's identity and how it signs, however the caller supplied it — mero-js `ResolvedRoot`. */
data class ResolvedRoot(
    val signer: DeviceSigner,
    /** The root's Ed25519 public key, 64 hex. */
    val publicKey: String,
    /** The account it names, 64 hex. */
    val accountId: String,
)

/** mero-js `resolveRoot` for a 64-hex root secret. */
fun resolveRoot(secret: String): ResolvedRoot = identify(signerFromSecret(secret, "rootSecret"))

/** mero-js `resolveRoot` for a root held as a signer. */
fun resolveRoot(signer: DeviceSigner): ResolvedRoot = identify(signer)

/**
 * mero-js `resolveRootPair`: exactly one of [rootSecret] and [signer].
 *
 * @throws IllegalArgumentException with both or neither.
 */
fun resolveRootPair(
    rootSecret: String?,
    signer: DeviceSigner?,
): ResolvedRoot = identify(resolveSigner(rootSecret, signer, "rootSecret"))

private fun identify(signer: DeviceSigner) = ResolvedRoot(signer, signer.publicKey, DeviceCert.accountForRootPublicKey(signer.publicKey))

/** What [signAccountLink] needs: the challenge, and the root as [rootSecret] **or** [signer]. */
data class AccountLinkInput(
    /** The challenge from `GET /api/cloud/me/accounts/challenge`, verbatim (signed as its UTF-8). */
    val nonce: String,
    /** The account root's signing secret, 64 hex. */
    val rootSecret: String? = null,
    /** The root's signer — use instead of [rootSecret]. */
    val signer: DeviceSigner? = null,
) {
    override fun toString(): String = "AccountLinkInput(nonce=$nonce)"
}

/** What [signAccountLogin] needs: the challenge, and the root as [rootSecret] **or** [signer]. */
data class AccountLoginInput(
    /** The challenge from `GET /api/auth/account/challenge`, verbatim UTF-8. */
    val nonce: String,
    /** The account root's signing secret, 64 hex. */
    val rootSecret: String? = null,
    /** The root's signer — use instead of [rootSecret]. */
    val signer: DeviceSigner? = null,
) {
    override fun toString(): String = "AccountLoginInput(nonce=$nonce)"
}

/**
 * Sign a cloud link challenge with the account root. Returns **standard** base64 (mdma
 * decodes with `validate=True`, so base64url is refused). Root-signed rather than
 * device-signed on purpose: it is what someone still holds after losing a device.
 */
fun signAccountLink(input: AccountLinkInput): String {
    require(input.nonce.isNotEmpty()) { "nonce is required to sign an account link" }
    val signer = resolveSigner(input.rootSecret, input.signer, "rootSecret")
    return base64(checkedSignature(signer.sign(concat(ACCOUNT_LINK_DOMAIN, input.nonce.encodeToByteArray()))))
}

/**
 * Sign a cloud login challenge with the account root — *"I hold the root of account X"*,
 * the one statement a device credential can never make. Standard base64.
 */
fun signAccountLogin(input: AccountLoginInput): String {
    require(input.nonce.isNotEmpty()) { "nonce is required to sign an account login" }
    val signer = resolveSigner(input.rootSecret, input.signer, "rootSecret")
    return base64(checkedSignature(signer.sign(concat(ACCOUNT_LOGIN_DOMAIN, input.nonce.encodeToByteArray()))))
}

private fun withPhrase(
    secret: String,
    phrase: String,
): RecoverableAccountRoot {
    val root = accountRootFromSecret(secret)
    return RecoverableAccountRoot(root.secret, root.publicKey, root.accountId, phrase)
}

private fun normalisePhrase(phrase: String): List<String> {
    // NFKD per BIP39: a no-op for the ASCII English list, but what the spec pins.
    val trimmed = Normalizer.normalize(phrase, Normalizer.Form.NFKD).lowercase(Locale.ROOT).trim()
    val words = if (trimmed.isEmpty()) emptyList() else trimmed.split(Regex("\\s+"))
    require(words.size == PHRASE_WORDS) { "recovery phrase must be $PHRASE_WORDS words, got ${words.size}" }
    return words
}

/** Entropy then its 8-bit checksum (the first byte of SHA-256), cut into 11-bit indices. */
private fun encodePhrase(entropy: ByteArray): String {
    val bits = concat(entropy, byteArrayOf(sha256(entropy)[0]))
    return (0 until PHRASE_WORDS).joinToString(" ") { word ->
        var index = 0
        for (b in 0 until BITS_PER_WORD) index = (index shl 1) or bitAt(bits, word * BITS_PER_WORD + b)
        ENGLISH_WORDLIST[index]
    }
}

private fun decodePhrase(words: List<String>): ByteArray {
    val bits = ByteArray(ENTROPY_BYTES + 1)
    words.forEachIndexed { w, word ->
        val index = WORD_INDEX[word] ?: throw IllegalArgumentException("\"$word\" is not a word in the recovery phrase list")
        for (b in 0 until BITS_PER_WORD) {
            if ((index shr (BITS_PER_WORD - 1 - b)) and 1 == 1) {
                val at = w * BITS_PER_WORD + b
                bits[at / 8] = (bits[at / 8].toInt() or (0x80 ushr (at % 8))).toByte()
            }
        }
    }
    val entropy = bits.copyOfRange(0, ENTROPY_BYTES)
    require(bits[ENTROPY_BYTES] == sha256(entropy)[0]) {
        "recovery phrase checksum failed — check for a mistyped or transposed word"
    }
    return entropy
}

private fun bitAt(
    bytes: ByteArray,
    index: Int,
): Int = (bytes[index / 8].toInt() ushr (7 - index % 8)) and 1
