package com.calimero.mero.account

import com.calimero.mero.crypto.Ed25519
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.crypto.fromBase64
import com.calimero.mero.crypto.sha256
import com.calimero.mero.http.MeroStateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** mero-js `account.test.ts`: BIP39 phrases, the account derivation and the cloud proofs. */
class AccountRootTest {
    private val vectors =
        listOf(
            0x00 to "${"abandon ".repeat(23)}art",
            0x7f to
                "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful " +
                "legal winner thank year wave sausage worth title",
            0x80 to
                "letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic " +
                "avoid letter advice cage absurd amount doctor acoustic bless",
            0xff to "${"zoo ".repeat(23)}vote",
        )
    private val knownPhrase = vectors[1].second

    private fun fillSecret(byte: Int) = "%02x".format(byte).repeat(32)

    private val linkRoot = "42".repeat(32)
    private val mdmaNonce = "eyJleHAiOjE3MDAwMDAzMDAwMDAsImp0aSI6ImFiYyIsInN1YiI6ImRldkBleGFtcGxlLmNvbSJ9.dGFn"
    private val mdmaSignature = "DY8dielYG6sNnZOI7zgPy7aO1XFMa5bhOJCJOMOeFHuTtwaC95rG1W0E5U2nyxtQ37CeJdbVPALUf9RgHCwYCw=="

    // ---- the vendored wordlist ------------------------------------------------------------

    @Test
    fun `is the canonical BIP39 English list, byte for byte`() {
        val digest = sha256("${ENGLISH_WORDLIST.joinToString("\n")}\n".encodeToByteArray())
        assertEquals("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda", Hex.encode(digest))
        assertEquals(2048, ENGLISH_WORDLIST.size)
        assertEquals("abandon", ENGLISH_WORDLIST.first())
        assertEquals("zoo", ENGLISH_WORDLIST.last())
        assertEquals(ENGLISH_WORDLIST.sorted(), ENGLISH_WORDLIST)
    }

    // ---- recovery phrases -------------------------------------------------------------------

    @Test
    fun `encodes the standard 256-bit vectors`() {
        for ((fill, phrase) in vectors) {
            val restored = accountRootFromPhrase(phrase)
            assertEquals(fillSecret(fill), restored.secret)
            assertEquals(phrase, accountRootFromPhrase(restored.phrase).phrase)
        }
    }

    @Test
    fun `round-trips a freshly minted root, different every time`() {
        val minted = generateAccountRoot()
        assertEquals(24, minted.phrase.split(" ").size)
        val restored = accountRootFromPhrase(minted.phrase)
        assertEquals(minted.secret, restored.secret)
        assertEquals(minted.publicKey, restored.publicKey)
        assertEquals(minted.accountId, restored.accountId)
        assertNotEquals(minted.secret, generateAccountRoot().secret)
        assertFalse(minted.toString().contains(minted.secret))
    }

    @Test
    fun `normalises case and whitespace rather than refusing`() {
        val (phrase, secret) = generateAccountRoot().let { it.phrase to it.secret }
        val mangled = "  ${phrase.uppercase().split(" ").joinToString("   ")}\n"
        assertEquals(secret, accountRootFromPhrase(mangled).secret)
    }

    @Test
    fun `refuses a mistyped word, a word outside the list and a wrong count`() {
        val words = knownPhrase.split(" ").toMutableList()
        words[0] = "abandon"
        assertTrue(failure { accountRootFromPhrase(words.joinToString(" ")) }.contains("checksum failed"))

        val outside =
            generateAccountRoot()
                .phrase
                .split(" ")
                .toMutableList()
                .also { it[5] = "calimero" }
        assertTrue(failure { accountRootFromPhrase(outside.joinToString(" ")) }.contains("not a word in the recovery phrase list"))

        assertEquals("recovery phrase must be 24 words, got 0", failure { accountRootFromPhrase("") })
        assertEquals("recovery phrase must be 24 words, got 3", failure { accountRootFromPhrase("abandon abandon abandon") })
    }

    // ---- account derivation -----------------------------------------------------------------

    @Test
    fun `names the account mdma derives for the same root`() {
        val root = accountRootFromSecret(linkRoot)
        assertEquals("2152f8d19b791d24453242e15f2eab6cb7cffa7b6a5ed30097960e069881db12", root.publicKey)
        assertEquals("a71c3dd073939d81f972525e9788c6b958818e33f30674ef43b4184eef40aa50", root.accountId)
        assertTrue(failure { accountRootFromSecret("4242") }.contains("must be 64 hex characters"))
    }

    // ---- signAccountLink / signAccountLogin -------------------------------------------------

    @Test
    fun `reproduces the link signature mdma verifies, over the domain-prefixed nonce`() {
        val signature = signAccountLink(AccountLinkInput(mdmaNonce, rootSecret = linkRoot))
        assertEquals(mdmaSignature, signature)
        assertTrue(signature.matches(Regex("^[A-Za-z0-9+/]+={0,2}$")))
        val pk = Hex.decode("2152f8d19b791d24453242e15f2eab6cb7cffa7b6a5ed30097960e069881db12", "pk", 32)
        val bytes = fromBase64(signature)
        assertEquals(64, bytes.size)
        assertTrue(Ed25519.verify(pk, bytes, "calimero.mdma.account-link.v1\u0000$mdmaNonce".encodeToByteArray()))
        assertFalse(Ed25519.verify(pk, bytes, mdmaNonce.encodeToByteArray()))
        // And through the signer path, against the same pinned value.
        assertEquals(mdmaSignature, signAccountLink(AccountLinkInput(mdmaNonce, signer = resolveRoot(linkRoot).signer)))
    }

    @Test
    fun `refuses an empty nonce, both roots, and neither`() {
        assertTrue(failure { signAccountLink(AccountLinkInput("", rootSecret = linkRoot)) }.contains("nonce is required"))
        assertTrue(failure { signAccountLogin(AccountLoginInput("", rootSecret = linkRoot)) }.contains("nonce is required"))
        val signer = accountRootSignerFromPhrase(generateAccountRoot().phrase)
        assertTrue(
            failure { signAccountLink(AccountLinkInput("n", rootSecret = "07".repeat(32), signer = signer)) }
                .contains("either rootSecret or signer, not both"),
        )
        assertTrue(failure { signAccountLink(AccountLinkInput("n")) }.contains("rootSecret or signer is required"))
    }

    @Test
    fun `a login proof is a different domain over the same key`() {
        val phrase = generateAccountRoot().phrase
        val secret = accountRootFromPhrase(phrase).secret
        val signer = accountRootSignerFromPhrase(phrase)
        val nonce = "challenge-from-the-cloud"
        val login = signAccountLogin(AccountLoginInput(nonce, signer = signer))
        assertEquals(signAccountLogin(AccountLoginInput(nonce, rootSecret = secret)), login)
        assertEquals(signAccountLink(AccountLinkInput(nonce, rootSecret = secret)), signAccountLink(AccountLinkInput(nonce, signer = signer)))
        assertNotEquals(signAccountLink(AccountLinkInput(nonce, signer = signer)), login)
    }

    // ---- a root that stays in its store -----------------------------------------------------

    @Test
    fun `restores the same account from the phrase it hands back`() {
        val created = createAccountRootSigner()
        val restored = accountRootSignerFromPhrase(created.phrase)
        assertEquals(created.accountId, restored.accountId)
        assertEquals(created.publicKey, restored.publicKey)
        assertEquals(24, created.phrase.split(" ").size)
        val withSecret = accountRootFromPhrase(created.phrase)
        assertEquals(withSecret.accountId, created.accountId)
        assertEquals(withSecret.accountId, resolveRoot(created).accountId)
        assertEquals(withSecret.publicKey, resolveRootPair(null, created).publicKey)
    }

    @Test
    fun `certifies a device identically to the secret it never exposes`() {
        val phrase = generateAccountRoot().phrase
        val secret = accountRootFromPhrase(phrase).secret
        val signer = accountRootSignerFromPhrase(phrase)
        val device = "33".repeat(32)
        assertEquals(
            DeviceCert.sign(SeedSigner(secret), device, "44".repeat(32), "55".repeat(32), deviceEpoch = 1),
            DeviceCert.sign(signer, device, "44".repeat(32), "55".repeat(32), deviceEpoch = 1),
        )
        assertFalse(signer.toString().contains(secret))
    }

    @Test
    fun `keeps the seed only in its store, and loads it back`() {
        val store = MemorySecureStore()
        val keys = AccountRootKeyStore(store)
        val created = keys.create()
        val held = store.get(AccountRootKeyStore.DEFAULT_KEY)!!
        assertEquals(accountRootFromPhrase(created.phrase).secret, held)

        val loaded = keys.load()!!
        assertEquals(created.accountId, loaded.accountId)
        assertEquals(Hex.encode(created.sign(byteArrayOf(1))), Hex.encode(loaded.sign(byteArrayOf(1))))

        keys.clear()
        assertNull(keys.load())
        assertThrows(MeroStateException::class.java) { created.sign(byteArrayOf(1)) }
        assertEquals(created.accountId, keys.restore(created.phrase).accountId)
    }

    @Test
    fun `refuses a mistyped phrase rather than opening a different account`() {
        val words = knownPhrase.split(" ").toMutableList()
        words[0] = words[1].also { words[1] = words[0] }
        assertTrue(failure { accountRootSignerFromPhrase(words.joinToString(" ")) }.contains("checksum failed"))
    }

    private fun failure(block: () -> Unit): String = assertThrows(IllegalArgumentException::class.java) { block() }.message!!
}
