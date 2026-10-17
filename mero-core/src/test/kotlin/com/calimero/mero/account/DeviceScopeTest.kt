package com.calimero.mero.account

import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors from mero-js `device-scope.test.ts`, which are core's own `DeviceScope::sign` and
 * `borsh::to_vec(&AccountProof<DeviceScope>)` output. Ed25519 is deterministic, so the whole
 * proof, signature included, is pinned.
 */
class DeviceScopeTest {
    private val rootSecret = "5c".repeat(32)
    private val account = "ca999783990fd7f4ea0c192135f78c17ac77745bf580b2ed20fea455a8133845"
    private val device = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1044305da225179a277d6d96e07ff21ea"
    private val apps = listOf("11".repeat(32), "22".repeat(32))

    private val proofEmpty =
        "02ed6a47a39da869b5446155e40b2d93f1e3f0167be26732bae7a3ef9d8e3a3fd300000000ca999783990fd7f4ea0c192135f78c17ac77745bf580b2ed20fea455a8133845a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1044305da225179a277d6d96e07ff21ea0000000000000000000000000d5f35a9d8060be917868b9c98efef2b8e3a3908c22c4faf17eb950da490fa64c9a7c3c52c49944c2f624cbbf0d4c24a5faf1aa57ec3a8005279b94e738ebf05"
    private val proofTwoApps =
        "02ed6a47a39da869b5446155e40b2d93f1e3f0167be26732bae7a3ef9d8e3a3fd300000000ca999783990fd7f4ea0c192135f78c17ac77745bf580b2ed20fea455a8133845a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1044305da225179a277d6d96e07ff21ea02000000111111111111111111111111111111111111111111111111111111111111111122222222222222222222222222222222222222222222222222222222222222220700000000000000ea5ac9c4c48801393fb2161892b2f267c65b23bf75fb33de559178644d2214c783ffdcb5e3c4ad4d310c235663c811b06762f829f0c7b9d87fcc6315bd590101"

    @Test
    fun `derives the account and device core derives`() {
        assertEquals(account, DeviceCert.accountForRoot(rootSecret))
        assertEquals(device, DeviceCert.mintDeviceId(account, ByteArray(16) { 0xa1.toByte() }))
    }

    @Test
    fun `computes the payload core signs`() {
        assertEquals(
            "99d56fe161047241aedab847a4325f64fe678ab4883460c502bec57a2ab11261",
            Hex.encode(deviceScopePayload(account, device, emptyList(), 0, 0)),
        )
        assertEquals(
            "14ab8d9fa7c2cb5fd8957fcb8dcc02c7347b40c9871d73ab1dc3e6ed8a812476",
            Hex.encode(deviceScopePayload(account, device, apps, 7, 0)),
        )
    }

    @Test
    fun `encodes the proof core encodes, with and without applications`() {
        assertEquals(proofEmpty, signDeviceScope(DeviceScopeInput(device, rootSecret = rootSecret)))
        assertEquals(proofTwoApps, signDeviceScope(DeviceScopeInput(device, apps, scopeEpoch = 7, rootSecret = rootSecret)))
    }

    @Test
    fun `signs the same bytes through a signer`() {
        assertEquals(proofEmpty, signDeviceScope(DeviceScopeInput(device, signer = SeedSigner(rootSecret, "rootSecret"))))
    }

    @Test
    fun `refuses both a secret and a signer, and an epoch that is not a u32`() {
        val both =
            assertThrows(IllegalArgumentException::class.java) {
                signDeviceScope(DeviceScopeInput(device, rootSecret = rootSecret, signer = SeedSigner(rootSecret)))
            }
        assertTrue(both.message!!.contains("not both"))
        val epoch =
            assertThrows(IllegalArgumentException::class.java) {
                signDeviceScope(DeviceScopeInput(device, scopeEpoch = -1, rootSecret = rootSecret))
            }
        assertTrue(epoch.message!!.contains("u32"))
    }
}
