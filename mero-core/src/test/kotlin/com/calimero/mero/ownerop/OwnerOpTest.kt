package com.calimero.mero.ownerop

import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner-op proofs, pinned byte for byte to core — every constant is mero-js
 * `owner-op.test.ts`'s, which are core's own (`crates/account/src/tests/owner_op.rs`
 * `signing_payload_and_encoding_are_pinned`, `crates/governance-types/src/tests.rs`
 * `root_guarded_vectors_are_stable`). A wrong byte is a proof the node refuses.
 */
class OwnerOpTest {
    private val root = "07".repeat(32)
    private val account = "9f9d3474c108b0bc7809ba35b5434980dd5b34f4a5042bdbb4de5519102324fb"
    private val namespace = "11".repeat(32)
    private val group = "22".repeat(32)
    private val someone = "44".repeat(32)

    // core signs over arbitrary op bytes here, so the op is hand-built.
    private val op = OwnerOp(OwnerOpKind.TRANSFER_OWNERSHIP, OwnerOpPlane.GROUP, "transfer to bob".encodeToByteArray())

    @Test
    fun `digests the op under the owner-op body domain`() {
        assertEquals("350519af50455451fe2a1f181ec2f4c5363104f168055c6e9088c5a5b65384df", Hex.encode(ownerOpDigest(op.bytes)))
    }

    @Test
    fun `signs the payload core verifies`() {
        val payload = ownerOpSigningPayload(account, namespace, group, op.kind, ownerOpDigest(op.bytes), counter = 3, keyEpoch = 0)
        assertEquals("a0855fcab12a8f8000345a0f25658f87d7650da170c50924be55fa780ca345a8", Hex.encode(payload))
    }

    @Test
    fun `encodes the whole proof as core does`() {
        val proof = signOwnerOpProof(OwnerOpProofInput(namespace, group, op, counter = 3, rootSecret = root))
        assertEquals(
            "02ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d2" +
                "2c000000009f9d3474c108b0bc7809ba35b5434980dd5b34f4a5042bdbb4de55" +
                "19102324fb111111111111111111111111111111111111111111111111111111" +
                "1111111111222222222222222222222222222222222222222222222222222222" +
                "222222222200350519af50455451fe2a1f181ec2f4c5363104f168055c6e9088" +
                "c5a5b65384df030000000000000000000000c62ebe7140bdffe975203603a4dc" +
                "27d4ced893a0e337698479885822deccf5ea97f1d4efdf071656a5ea50cc4446" +
                "abbb1a2f2a2e6720b8a1f2ac416bbf32020f",
            proof,
        )
        // The same bytes through a signer.
        assertEquals(proof, signOwnerOpProof(OwnerOpProofInput(namespace, group, op, counter = 3, signer = SeedSigner(root))))
    }

    @Test
    fun `refuses a key epoch its chain cannot reach`() {
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                signOwnerOpProof(OwnerOpProofInput(namespace, group, op, counter = 0, rootSecret = root, keyEpoch = 1))
            }
        assertTrue(e.message!!.contains("needs 1 handoffs"))
    }

    @Test
    fun `refuses a handoff of the wrong size and two roots at once`() {
        assertThrows(IllegalArgumentException::class.java) {
            signOwnerOpProof(OwnerOpProofInput(namespace, group, op, counter = 0, rootSecret = root, chain = listOf("00")))
        }
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                signOwnerOpProof(OwnerOpProofInput(namespace, group, op, counter = 0, rootSecret = root, signer = SeedSigner(root)))
            }
        assertTrue(e.message!!.contains("not both"))
    }

    @Test
    fun `encodes a transfer, and its RootGuarded wrapper, as core does`() {
        val transfer = transferOwnershipOp(someone)
        assertEquals("a8d53a53c6d1007d055fbfd972aa7a99b27d5a816defdf826d49d1b0799b2c8f", Hex.encode(ownerOpDigest(transfer.bytes)))
        val proof = signOwnerOpProof(OwnerOpProofInput(namespace, group, transfer, counter = 5, rootSecret = root))
        assertEquals(
            "2a15444444444444444444444444444444444444444444444444444444444444" +
                "444402ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea6914" +
                "46d22c000000009f9d3474c108b0bc7809ba35b5434980dd5b34f4a5042bdbb4" +
                "de5519102324fb11111111111111111111111111111111111111111111111111" +
                "1111111111111122222222222222222222222222222222222222222222222222" +
                "2222222222222200a8d53a53c6d1007d055fbfd972aa7a99b27d5a816defdf82" +
                "6d49d1b0799b2c8f050000000000000000000000d9fefff69e034d07a996f2f0" +
                "dac5052865a93a780b58f935b3ff7a509ab88ed1f8171ee9c65b2b66a946ec53" +
                "d8aba76d53c4fd41553973d2fc87a05215f09502",
            Hex.encode(rootGuardedOpBytes(transfer, proof)),
        )
    }

    @Test
    fun `encodes an admin change as a root op`() {
        val op = adminChangedOp(someone)
        assertEquals(OwnerOpPlane.ROOT, op.plane)
        assertEquals("bf73aaf8507327573528f38fdf5b003399a4773a7272539548188bc844d8a598", Hex.encode(ownerOpDigest(op.bytes)))
    }

    @Test
    fun `encodes the TEE policies and the deletion as core does`() {
        assertEquals("2101000000020000006161", Hex.encode(teeAuthoringPolicyOp(listOf("aa")).bytes))
        assertEquals(
            "2501000000020000006161000000000000000000000000010000000200000062" +
                "6201000000080000005570546f446174650001",
            Hex.encode(
                teeAdmissionPolicyOp(
                    TeeAdmissionPolicyOpInput(
                        allowedMrtd = listOf("aa"),
                        allowedRtmr0 = emptyList(),
                        allowedRtmr1 = emptyList(),
                        allowedRtmr2 = emptyList(),
                        allowedRtmr3 = listOf("bb"),
                        allowedTcbStatuses = listOf("UpToDate"),
                        acceptMock = false,
                        mode = "relay",
                    ),
                ).bytes,
            ),
        )
        assertEquals(
            "2601000000100000006c6f636b65642d726561642d6f6e6c790106000000322e" +
                "332e3732000000000100",
            Hex.encode(
                teeReleaseAdmissionPolicyOp(
                    TeeReleaseAdmissionPolicyOpInput(
                        allowedProfiles = listOf("locked-read-only"),
                        minReleaseVersion = "2.3.72",
                        allowedTcbStatuses = emptyList(),
                        acceptMock = true,
                    ),
                ).bytes,
            ),
        )
        assertEquals("0e", Hex.encode(groupDeleteOp().bytes))
    }

    @Test
    fun `names each kind by the byte core signs`() {
        assertEquals(
            mapOf(
                "transferOwnership" to 0,
                "adminChanged" to 1,
                "groupDelete" to 2,
                "teeAdmissionPolicy" to 3,
                "teeAuthoringPolicy" to 4,
                "teeReleaseAdmissionPolicy" to 5,
            ),
            OWNER_OP_KIND,
        )
        assertThrows(IllegalArgumentException::class.java) {
            teeAdmissionPolicyOp(TeeAdmissionPolicyOpInput(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false, "bogus"))
        }
    }
}
