package com.calimero.mero.cloud

import com.calimero.mero.account.DeviceCert
import com.calimero.mero.crypto.Ed25519
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.relay.Audience
import com.calimero.mero.relay.CreationWarrantInput
import com.calimero.mero.relay.GovernanceOp
import com.calimero.mero.relay.GovernanceOpKind
import com.calimero.mero.relay.GovernanceWarrantInput
import com.calimero.mero.relay.LoginStatement
import com.calimero.mero.relay.WarrantInput
import com.calimero.mero.relay.Warrants
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-for-byte conformance with mero-js 24.5.0's golden vectors, which are core's own
 * pinned fixtures (the `*_fixture.rs` files in `crates/account/src/tests`) or MDMA's verifier output.
 * Every constant below is copied from the named mero-js test, not produced by this code.
 * A failure here is a warrant, certificate or statement a node would refuse.
 */
class SigningVectorsTest {
    private fun hex(b: ByteArray) = Hex.encode(b)

    // ---- crypto ----------------------------------------------------------------------------

    @Test
    fun `derives the Ed25519 public key core's key(7) has`() {
        // warrant.test.ts EXPECTED_DEVICE_KEY
        assertEquals(
            "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c",
            SeedSigner("07".repeat(32)).publicKey,
        )
        // login.test.ts DEVICE_KEY
        assertEquals(
            "fd1724385aa0c75b64fb78cd602fa1d991fdebf76b13c58ed702eac835e9f618",
            SeedSigner("09".repeat(32)).publicKey,
        )
    }

    // ---- device-cert.test.ts ---------------------------------------------------------------

    private val coreSdkCredential =
        "02ed6a47a39da869b5446155e40b2d93f1e3f0167be26732bae7a3ef9d8e3a3fd300000000ca999783990fd7f4ea0c192135f78c17ac77745bf580b2ed20fea455a8133845a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1044305da225179a277d6d96e07ff21ea8b237d788e8eaaef550c6d125823fa45f1fd5fc29b2c88bdf871119471fc13123a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a3a00000000000000004ba6c450e21f28d01b03c2fcb10a5c188a388ef6298fcb1ec23d381b7acc8f3386f08d81672c9089d2ad7bf0ff180ccad61163baa852a021c6773e625aed8a00"

    @Test
    fun `mints the device id core mints`() {
        assertEquals(
            "222222222222222222222222222222227042c913b557a30a2cbabcaccdbecd10",
            DeviceCert.mintDeviceId("11".repeat(32), ByteArray(16) { 0x22 }),
        )
    }

    @Test
    fun `computes the payload a root signs`() {
        assertEquals(
            "543ba0d195c857628b5279468c304239853d417550d69d7c0fe96887f91b51f3",
            hex(DeviceCert.payload("11".repeat(32), "33".repeat(32), "44".repeat(32), "55".repeat(32), 0, 7)),
        )
    }

    @Test
    fun `signs the credential core pins for the SDK, and verifies it`() {
        val root = SeedSigner("5c".repeat(32))
        val device = DeviceCert.mintDeviceId(DeviceCert.accountForRootPublicKey(root.publicKey), ByteArray(16) { 0xa1.toByte() })
        val credential = DeviceCert.sign(root, device, SeedSigner("6d".repeat(32)).publicKey, "3a".repeat(32), 0)
        assertEquals(coreSdkCredential, credential)
        assertEquals(237 * 2, credential.length)

        val parsed = DeviceCert.verify(coreSdkCredential)
        assertEquals(root.publicKey, parsed.rootPublicKey)
        assertEquals(device, parsed.device)
        assertEquals("3a".repeat(32), parsed.kemPublicKey)
    }

    @Test
    fun `refuses a re-pointed account, a foreign device id, a bad version and a chain`() {
        val secret = "77".repeat(32)
        val root = SeedSigner(secret)
        val account = DeviceCert.accountForRoot(secret)
        val nonce = ByteArray(16) { 0x33 }
        val good = DeviceCert.sign(root, DeviceCert.mintDeviceId(account, nonce), "44".repeat(32), "55".repeat(32), 7)
        DeviceCert.verify(good)

        val foreign = DeviceCert.sign(root, DeviceCert.mintDeviceId("11".repeat(32), nonce), "44".repeat(32), "55".repeat(32), 7)
        assertThrowsMessage("not minted for account") { DeviceCert.verify(foreign) }

        val repointed = good.substring(0, 74) + "ff".repeat(32) + good.substring(138)
        assertThrowsMessage("re-pointed at another account") { DeviceCert.verify(repointed) }

        assertThrowsMessage("genesis version 1") { DeviceCert.parse("01" + good.substring(2)) }
        assertThrowsMessage("root chain") { DeviceCert.parse(good.substring(0, 66) + "01000000" + good.substring(74)) }
        assertThrowsMessage("474 hex characters") { DeviceCert.parse(good.substring(0, 200)) }

        // A flipped signature byte: everything else consistent, only the signature check can catch it.
        val last = good.length - 2
        val flipped = good.substring(0, last) + (if (good.substring(last) == "00") "01" else "00")
        assertThrowsMessage("did not sign") { DeviceCert.verify(flipped) }
    }

    // ---- routing-proof.test.ts -------------------------------------------------------------

    @Test
    fun `produces the routing signature MDMA verifies`() {
        val signer = SeedSigner("ef26085f1651bd1f4bba0832bf981c93cc00de33a037fb432b49b5fd4d552c88")
        assertEquals(
            "h5qmCVwG9B8Kgi1Fb0ipYgtQr6BsKM/MCPJfjpaR9UfmO+xDe3asjzvHvFraP/Ph5At9i2+ST2hGY9bTH8CyDQ==",
            RoutingProof.signRoutingChallenge("test-nonce-abc", signer),
        )
        assertNotEquals(
            RoutingProof.signRoutingChallenge("test-nonce-abc", signer),
            RoutingProof.signRoutingChallenge("test-nonce-abd", signer),
        )
    }

    // ---- warrant.test.ts -------------------------------------------------------------------

    private val head = "55".repeat(32)
    private val floor = "66".repeat(32)
    private val deviceKey = "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c"

    @Test
    fun `warrant v2 matches core's wire fixture`() {
        val args =
            buildJsonObject {
                put("key", "k")
                put("value", "v")
            }
        val intentHash = "dc066cc8524c74dc21714174009df536376e3151f5b92f0a676defde599dbae5"
        assertEquals(intentHash, hex(Warrants.intentHash("set", args)))

        val warrant =
            Warrants.signWarrant(
                WarrantInput(
                    context = "11".repeat(32),
                    authorAccount = "22".repeat(32),
                    executor = "33".repeat(32),
                    executorKey = "77".repeat(32),
                    releaseBytecodeId = "44".repeat(32),
                    releaseVersion = "1.0.0",
                    method = "set",
                    argsJson = args,
                    nonce = 42u,
                    notAfter = 1_700_000_000u,
                    signer = SeedSigner("07".repeat(32)),
                    accountHeads = listOf(head),
                    governanceFloor = listOf(floor),
                ),
            )
        val expected =
            "11".repeat(32) + "22".repeat(32) + deviceKey + "33".repeat(32) + "77".repeat(32) + "44".repeat(32) +
                "05000000" + "312e302e30" + "03000000" + "736574" + intentHash +
                "01000000" + head + "01000000" + floor + "2a00000000000000" + "00f1536500000000" +
                "e42f753e1a30657fe036b0c0a07030f3f6d92ea56749921c5a6ae07eb966cb50" +
                "1ed439f7a8007dfce0ccb6b5a8b94bdda9f48db9c84f181e9fbaa0d208726b02"
        assertEquals(784, warrant.length)
        assertEquals(expected, warrant)
    }

    @Test
    fun `warrant refuses a release version over 256 bytes and more than 64 heads`() {
        val base =
            WarrantInput(
                context = "11".repeat(32), authorAccount = "22".repeat(32), executor = "33".repeat(32),
                executorKey = "77".repeat(32), releaseBytecodeId = "44".repeat(32), method = "set",
                argsJson = buildJsonObject { }, nonce = 1u, notAfter = 2u, signer = SeedSigner("07".repeat(32)),
            )
        Warrants.signWarrant(base.copy(releaseVersion = "x".repeat(256)))
        assertThrowsMessage("over the 256") { Warrants.signWarrant(base.copy(releaseVersion = "é".repeat(129))) }
        assertThrowsMessage("over the 64") { Warrants.signWarrant(base.copy(accountHeads = List(65) { head })) }
    }

    // ---- creation-warrant.test.ts ----------------------------------------------------------

    @Test
    fun `creation warrant matches core's wire fixture`() {
        val initArgs = buildJsonObject { put("name", "general") }
        assertEquals("{\"name\":\"general\"}", Warrants.canonicalJson(initArgs))
        val initHash = "074dc4be8c7abe685532a48947430edd0301b42f60222e715a834e723d2a055e"
        assertEquals(initHash, hex(Warrants.creationInitHash(initArgs)))

        val preimage =
            Warrants.creationPreimage(
                group = Hex.decode("11".repeat(32), "g", 32),
                seed = Hex.decode("12".repeat(32), "s", 32),
                authorAccount = Hex.decode("22".repeat(32), "a", 32),
                deviceKey = Hex.decode(deviceKey, "d", 32),
                executor = Hex.decode("33".repeat(32), "e", 32),
                executorKey = Hex.decode("77".repeat(32), "k", 32),
                applicationId = Hex.decode("44".repeat(32), "app", 32),
                serviceName = null,
                name = "general".encodeToByteArray(),
                initHash = Hex.decode(initHash, "i", 32),
                accountHeads = listOf(Hex.decode(head, "h", 32)),
                governanceFloor = listOf(Hex.decode(floor, "f", 32)),
                nonce = 42u,
                notAfter = 1_700_000_000u,
            )
        assertEquals("7e1126b215a794e565bb59c630cb1a36e43590c4bd8f62c6111792f77c231598", hex(preimage))

        val signed =
            Warrants.signCreationWarrant(
                CreationWarrantInput(
                    group = "11".repeat(32),
                    seed = "12".repeat(32),
                    authorAccount = "22".repeat(32),
                    executor = "33".repeat(32),
                    executorKey = "77".repeat(32),
                    applicationId = "44".repeat(32),
                    name = "general",
                    initArgs = initArgs,
                    accountHeads = listOf(head),
                    governanceFloor = listOf(floor),
                    nonce = 42u,
                    notAfter = 1_700_000_000u,
                    signer = SeedSigner("07".repeat(32)),
                ),
            )
        val expected =
            "11".repeat(32) + "12".repeat(32) + "22".repeat(32) + deviceKey + "33".repeat(32) + "77".repeat(32) + "44".repeat(32) +
                "00" + "01" + "07000000" + "67656e6572616c" + initHash +
                "01000000" + head + "01000000" + floor + "2a00000000000000" + "00f1536500000000" +
                "19103d73752d052f747911b4b36e423221d89d120bf6f4d32122d3c4fd1fb030" +
                "9add40f3f3b80a5e5d6fefa02de5f4d6bb914c85c5c8b10a3a0e3a0e22c08400"
        assertEquals("12".repeat(32), signed.seed)
        assertEquals(421 * 2, signed.warrant.length)
        assertEquals(expected, signed.warrant)
    }

    // ---- governance-warrant.test.ts --------------------------------------------------------

    @Test
    fun `governance warrant matches core's wire fixture`() {
        val op = GovernanceOp(GovernanceOpKind.ROOT, byteArrayOf(1, 2, 3))
        val opHash = "d6bc121f9fcf7b85bea94d356d620c14dd8e1ae5fa2317cbcfc2c486cf04dfb3"
        assertEquals(opHash, hex(Warrants.governanceOpHash(op)))
        val warrant =
            Warrants.signGovernanceWarrant(
                GovernanceWarrantInput(
                    scope = "11".repeat(32),
                    op = op,
                    authorAccount = "22".repeat(32),
                    executor = "33".repeat(32),
                    executorKey = "77".repeat(32),
                    accountHeads = listOf(head),
                    governanceFloor = listOf(floor),
                    nonce = 42u,
                    notAfter = 1_700_000_000u,
                    signer = SeedSigner("07".repeat(32)),
                ),
            )
        val expected =
            "11".repeat(32) + "01" + "22".repeat(32) + deviceKey + "33".repeat(32) + "77".repeat(32) + opHash +
                "01000000" + head + "01000000" + floor + "2a00000000000000" + "00f1536500000000" +
                "03ce6c011f565924099b2c776032a1cfe540d1c4c09fad0b470723f8ee76138d" +
                "1d685a20948edf4dee2901d3f9187e3363a16e3b141b0bc94553f5948becae07"
        assertEquals(345 * 2, warrant.length)
        assertEquals(expected, warrant)
    }

    // ---- login.test.ts ---------------------------------------------------------------------

    private fun statement(audience: Audience) =
        LoginStatement.sign(
            node = "11".repeat(32),
            audience = audience,
            challenge = "22".repeat(32),
            sessionKey = "33".repeat(32),
            issuedAt = 1_700_000_000,
            expiresAt = 1_700_000_300,
            signer = SeedSigner("09".repeat(32)),
        )

    private val loginDeviceKey = "fd1724385aa0c75b64fb78cd602fa1d991fdebf76b13c58ed702eac835e9f618"

    @Test
    fun `login statement reproduces core's WebOrigin and Cli encodings`() {
        assertEquals(
            "11".repeat(32) + "00" + "18000000" + "68747470733a2f2f6170702e6578616d706c653a38343433" +
                "22".repeat(32) + "33".repeat(32) + loginDeviceKey + "00f1536500000000" + "2cf2536500000000" +
                "8302e61afe8f61c8471bc5f8ae9ff13cdd5b3e9bcd793cf8c46acb3ff9592aa4" +
                "37c1aae1b1ee9c9514f29b2340d13a547ea5e6cd4b4b65fbf09bafb55f4c7e00",
            statement(Audience.WebOrigin("https://app.example:8443")),
        )
        assertEquals(
            "11".repeat(32) + "02" + "22".repeat(32) + "33".repeat(32) + loginDeviceKey + "00f1536500000000" + "2cf2536500000000" +
                "085b4ee049f7f268a35ec1bfdfe779b94f3bda66cbbb48937735f9ab10c0ef71" +
                "cad6f5bbb9afc4b2b87b5ee87d06284e3c5bc77a6c541c56e62aa65ace8fdb0b",
            statement(Audience.Cli),
        )
    }

    @Test
    fun `login statement reproduces core's CodeSigningId signature`() {
        val s = statement(Audience.CodeSigningId("dev.calimero.client"))
        assertEquals(232 * 2, s.length)
        assertTrue(s.contains("01130000006465762e63616c696d65726f2e636c69656e74"))
        assertEquals(
            "4aa7dd84d3d7960647d015a9a4483f2690ab5dc0abd4733634445edd3d8673a9" +
                "193ccc3b9251bbd1c5990a59fc02847d3191514f5725f074a0edfafbb3eeff0b",
            s.takeLast(128),
        )
    }

    @Test
    fun `Ed25519 round-trips and rejects a tampered message`() {
        val seed = ByteArray(32) { 7 }
        val sig = Ed25519.sign(seed, byteArrayOf(1, 2, 3))
        assertTrue(Ed25519.verify(Ed25519.publicKey(seed), sig, byteArrayOf(1, 2, 3)))
        assertTrue(!Ed25519.verify(Ed25519.publicKey(seed), sig, byteArrayOf(1, 2, 4)))
    }

    private fun assertThrowsMessage(
        fragment: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            assertTrue("expected '$fragment' in '${e.message}'", e.message.orEmpty().contains(fragment))
            return
        }
        throw AssertionError("expected an IllegalArgumentException containing '$fragment'")
    }
}
