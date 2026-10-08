package com.calimero.mero.relay

import com.calimero.mero.account.DeviceCert
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The governance op encoders against core's pinned vectors, copied from mero-js 24.5.0
 * (`governance-op-vectors.test.ts`, `governance-warrant.test.ts`, `relay-client.test.ts`),
 * which are core's own (`delegable_governance_op_vectors_for_non_rust_encoders_are_stable`,
 * `delegated_governance_op_vectors_are_stable`, `delegable_target_application_set_vector_is_stable`,
 * `founded_namespace_id_has_a_known_answer`, `created_subgroup_id_has_a_known_answer`) or
 * produced by a scratch program against core's crates. Every constant is copied, not computed.
 */
class GovernanceOpsTest {
    private val a = "44".repeat(32)
    private val g = "55".repeat(32)
    private val p = "11".repeat(32)
    private val c = "66".repeat(32)
    private val data = mapOf("topic" to "rust")
    private val fixtureCredential =
        "02" + "77".repeat(32) + "00000000" + "44".repeat(32) + "88".repeat(32) + "99".repeat(32) + "aa".repeat(32) +
            "00000000" + "01000000" + "bb".repeat(64)

    private fun check(
        op: GovernanceOp,
        bytes: String,
        opHash: String,
    ) {
        assertEquals(bytes, Hex.encode(op.bytes))
        assertEquals(opHash, Hex.encode(Warrants.governanceOpHash(op)))
    }

    @Test
    @Suppress("LongMethod")
    fun `delegable ops match core's pinned vectors`() {
        val o = GovernanceOps
        check(
            o.memberRemovedOp(a),
            "024444444444444444444444444444444444444444444444444444444444444444000000000000000000000000000000000000000000000000000000000000000000000000",
            "fdc08345155864f7a9481194c7f12b313994b1a95955ee0969422f0147ab2779",
        )
        check(
            o.memberLeftOp(a),
            "034444444444444444444444444444444444444444444444444444444444444444000000000000000000000000000000000000000000000000000000000000000000000000",
            "05392aaa36e0e7d08fc9150536d1c9906cd6f5445b0afb6cedec2f03938c6b3d",
        )
        check(
            o.memberRoleSetOp(a, GovernanceMemberRole.Admin),
            "04444444444444444444444444444444444444444444444444444444444444444400",
            "79a70a143fb990f2f92478812b0c142ec8e94a75769d415b3e3bbf769168b2bb",
        )
        check(
            o.memberRoleSetOp(a, GovernanceMemberRole.ReadOnly),
            "04444444444444444444444444444444444444444444444444444444444444444402",
            "843978d9ed8c38856a33df18ed3ca9a1e60800b3c0985949ce0db831dd57c75d",
        )
        check(
            o.memberCapabilitySetOp(a, 1),
            "05444444444444444444444444444444444444444444444444444444444444444401000000",
            "4266c138e10d5aa8837fcb1d1e7bb81b88c215d4ddea5f7ec413c7a87c995758",
        )
        check(
            o.memberCapabilitySetOp(a, 231),
            "054444444444444444444444444444444444444444444444444444444444444444e7000000",
            "9225ce03739ea127393321b4b517b5badd05290131e865bd6bb2a5a34af57287",
        )
        check(o.defaultCapabilitiesSetOp(231), "06e7000000", "0bc00f5f7627b34a104b6aa059887c2cf30f59f468711462176f35592fcf95fd")
        check(
            o.contextDetachedOp(c),
            "096666666666666666666666666666666666666666666666666666666666666666",
            "363e49ed17f870151deed61caa14f493fad3f4e1d9c4781f35b21b833a4f2bf3",
        )
        check(o.subgroupVisibilitySetOp(false), "0a00", "99d379e4656d5711132d0d44491446ab93480b6ad58bc216aba9358bf693d57b")
        check(o.subgroupVisibilitySetOp(true), "0a01", "42b90a291fb2b5e5d8a5da5a1096facde5d34c2d3ad92903507e1709434020a1")
        check(
            o.groupMetadataSetOp("general", data),
            "0b010700000067656e6572616c0100000005000000746f7069630400000072757374",
            "2d789be70265d50780fc8cac9b1c3ff847352fccd2ad36e76ffdc464134d4bc9",
        )
        check(o.groupMetadataSetOp(), "0b0000000000", "4fc9cfd6f2af5690ff47fc685c8ef1408c2c49f41aac2a2a8151223e5dfe2e1d")
        check(
            o.memberMetadataSetOp(a, "alice", data),
            "0c44444444444444444444444444444444444444444444444444444444444444440105000000616c6963650100000005000000746f7069630400000072757374",
            "58a5290db3c57d34bb40679071c4739eff0ff81e0e728ff98d707ed570c1eeab",
        )
        check(
            o.memberMetadataSetOp(a),
            "0c44444444444444444444444444444444444444444444444444444444444444440000000000",
            "d682f73eacf1b2b04aa85e1abde1b37c5e16399a5f41b2d385581a0c401db32e",
        )
        check(
            o.contextMetadataSetOp(c, "general", data),
            "0d6666666666666666666666666666666666666666666666666666666666666666010700000067656e6572616c0100000005000000746f7069630400000072757374",
            "81768a47949bec9245da34acbfc9d90ff8152e65df38026e99691f2d0f4e3f86",
        )
        check(
            o.contextMetadataSetOp(c),
            "0d66666666666666666666666666666666666666666666666666666666666666660000000000",
            "4de9794df28cbcfb3fae1c960eaf4a8244b5f415a1a7506b963ec82c1bf1033d",
        )
        check(
            o.contextCapabilityGrantedOp(c, a, 1),
            "106666666666666666666666666666666666666666666666666666666666666666444444444444444444444444444444444444444444444444444444444444444401",
            "af5093cfd553f9431d4c2c6eacc8c5ac90c709d520122fa5420982d2594f0ff1",
        )
        check(
            o.contextCapabilityRevokedOp(c, a, 231),
            "1166666666666666666666666666666666666666666666666666666666666666664444444444444444444444444444444444444444444444444444444444444444e7",
            "1ba014f559da55169bdcb663f3cd79b5b6519aecf7a09dc5976b9c28e3cd94fd",
        )
        check(
            o.groupReparentedOp(g, p),
            "0155555555555555555555555555555555555555555555555555555555555555551111111111111111111111111111111111111111111111111111111111111111",
            "a52c81b6b15c534a10db72373a4213f0ea75c7b99dbea1c93d510169b804d427",
        )
        check(
            o.groupDeletedOp(g),
            "0255555555555555555555555555555555555555555555555555555555555555550000000000000000",
            "b2dd53fba1c8b83ef704de1fcc868cc68d4f74cd2a54cd1db4998213500fddd0",
        )
        check(
            o.memberJoinedOpenOp(a, g, fixtureCredential),
            "07$a$g$fixtureCredential",
            "b9642890aae8b920d6bd9376e532423b6074becd9eb568843b2de025fd294192",
        )
    }

    @Test
    fun `MemberAdded, GroupCreated and the subgroup id match core`() {
        val added = GovernanceOps.memberAddedOp(a, GovernanceMemberRole.Member)
        assertEquals(GovernanceOpKind.GROUP, added.kind)
        check(added, "01${a}01", "c48dce4ba9da20980a86832b133e7040ec67c293987c0f131140291a71041cd6")

        val created = GovernanceOps.groupCreatedOp("55".repeat(32), "11".repeat(32), true, "22".repeat(32), "33".repeat(32))
        assertEquals(GovernanceOpKind.ROOT, created.kind)
        check(
            created,
            "00" + "55".repeat(32) + "11".repeat(32) + "01" + "22".repeat(32) + "33".repeat(32),
            "3a30eacf28687109de2b63b649cb0d8a532f344212066897547f52a416a1be0e",
        )
        assertEquals(
            "6c949a0f0e0c3c55310223f4cd03f888b6e84c7e963f9171a5639fc54ea93b1a",
            GovernanceOps.createdSubgroupId("11".repeat(32), "33".repeat(32), true, "22".repeat(32)),
        )
        val creation = GovernanceOps.subgroupCreation("11".repeat(32), restricted = false, admin = "22".repeat(32))
        assertEquals(GovernanceOps.createdSubgroupId("22".repeat(32), "11".repeat(32), false, creation.salt), creation.groupId)
        assertEquals("00" + creation.groupId + "11".repeat(32) + "00" + "22".repeat(32) + creation.salt, Hex.encode(creation.op.bytes))
    }

    @Test
    fun `TargetApplicationSet matches core's pinned vector`() {
        val op = GovernanceOps.targetApplicationSetOp("88".repeat(32), "com.example.app", "1.2.3")
        assertEquals(GovernanceOpKind.GROUP, op.kind)
        check(
            op,
            "07000000000000000000000000000000000000000000000000000000000000000088888888888888888888888888888888888888888888888888888888888888880f000000636f6d2e6578616d706c652e61707005000000312e322e33",
            "904984c8f39e4172ea8864a65511faead68baa76af18d31e1d442a0b9fcb656b",
        )
        assertThrows(IllegalArgumentException::class.java) { GovernanceOps.targetApplicationSetOp("88".repeat(32), "", "1") }
        assertThrows(IllegalArgumentException::class.java) { GovernanceOps.targetApplicationSetOp("88".repeat(32), "p", "") }
    }

    // ---- The delegated genesis (governance-warrant.test.ts "namespace genesis conformance") -

    private val genesisCredential =
        "02c853ad0f0cd2b619aea92ceec4fd56a24d6499d584ce79257e45cfd8139b60" +
            "a700000000161e0b241fdac4166b442a199cb689e0b438938bbb30e31baca7f1" +
            "403095feff333333333333333333333333333333333333333333333333333333" +
            "3333333333ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea" +
            "691446d22c555555555555555555555555555555555555555555555555555555" +
            "55555555550000000001000000292c12a66bae1c2c32f62455037246da6f84b2" +
            "4345a590ef3db4bf15670afc5ea47b30ec242827257fb2f33660dda6f081b0ba" +
            "253c0f3d02014cbabeb5c9a70f"
    private val founder = "161e0b241fdac4166b442a199cb689e0b438938bbb30e31baca7f1403095feff"
    private val salt = "5c".repeat(32)
    private val namespaceId = "107c1c0ef0ca701608f0ec814572775e41197ba131b1263931d5789cf76bef69"

    @Test
    fun `mints the genesis credential core mints from the same keys`() {
        val credential = DeviceCert.sign(SeedSigner("77".repeat(32)), "33".repeat(32), SeedSigner("07".repeat(32)).publicKey, "55".repeat(32), 1)
        assertEquals(genesisCredential, credential)
        assertEquals(founder, DeviceCert.accountForRootPublicKey(SeedSigner("77".repeat(32)).publicKey))
    }

    @Test
    fun `derives the namespace id core derives`() {
        assertEquals(namespaceId, GovernanceOps.foundedNamespaceId(founder, salt))
        assertEquals(
            "35f5e77cc3c7cdb18eef50f1ea2808f27069dd25143e935994fcd58b3876c0bd",
            GovernanceOps.foundedNamespaceId("11".repeat(32), "22".repeat(32)),
        )
    }

    @Test
    fun `encodes the genesis as core does and signs the exact warrant core signs`() {
        val op = GovernanceOps.namespaceCreatedOp(founder, genesisCredential, salt)
        assertEquals(GovernanceOpKind.ROOT, op.kind)
        assertEquals(302, op.bytes.size)
        check(op, "09$founder$genesisCredential$salt", "eec5a1ad4daa778659cbde6395cd6cd5839a5ac97966e88f4affdd88b831376e")

        val warrant =
            Warrants.signGovernanceWarrant(
                GovernanceWarrantInput(
                    scope = namespaceId,
                    op = op,
                    authorAccount = founder,
                    executor = "33".repeat(32),
                    executorKey = "77".repeat(32),
                    nonce = 1u,
                    notAfter = 1_700_000_000u,
                    signer = SeedSigner("07".repeat(32)),
                ),
            )
        assertEquals(
            "107c1c0ef0ca701608f0ec814572775e41197ba131b1263931d5789cf76bef69" +
                "01161e0b241fdac4166b442a199cb689e0b438938bbb30e31baca7f1403095fe" +
                "ffea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d2" +
                "2c33333333333333333333333333333333333333333333333333333333333333" +
                "3377777777777777777777777777777777777777777777777777777777777777" +
                "77eec5a1ad4daa778659cbde6395cd6cd5839a5ac97966e88f4affdd88b83137" +
                "6e0000000000000000010000000000000000f15365000000001087244bdc6743" +
                "869f074896b30a22be0a19c4c2ed999454aff7c7f479d7f9bdab2eda608d8621" +
                "d61091c0fd79dec6d923cdc1d1d581733be3d51134e00f480e",
            warrant,
        )
    }

    @Test
    fun `guards refuse what a relay would`() {
        val e = assertThrows(IllegalArgumentException::class.java) { GovernanceOps.memberCapabilitySetOp(a, 512) }
        assertTrue(e.message!!.contains("CAN_AUTHOR_ON_BEHALF"))
        assertThrows(IllegalArgumentException::class.java) { GovernanceOps.defaultCapabilitiesSetOp(512) }
        assertThrows(IllegalArgumentException::class.java) { GovernanceOps.defaultCapabilitiesSetOp(1L shl 32) }
        assertThrows(IllegalArgumentException::class.java) { GovernanceOps.defaultCapabilitiesSetOp(-1) }
        assertEquals("06fffdffff", Hex.encode(GovernanceOps.defaultCapabilitiesSetOp(0xffff_fdffL).bytes))
        assertThrows(IllegalArgumentException::class.java) { GovernanceOps.contextCapabilityGrantedOp(c, a, 0) }
        assertThrows(IllegalArgumentException::class.java) { GovernanceOps.contextCapabilityRevokedOp(c, a, 256) }
        assertThrows(IllegalArgumentException::class.java) { GovernanceOps.namespaceCreatedOp(founder, "", salt) }
        assertThrows(IllegalArgumentException::class.java) { GovernanceOps.namespaceCreatedOp(founder, genesisCredential, "5c") }
        assertThrows(IllegalArgumentException::class.java) { GovernanceOps.memberAddedOp("44".repeat(31), GovernanceMemberRole.Member) }
        // Metadata keys sort by their bytes, as a BTreeMap does.
        assertEquals(
            Hex.encode(GovernanceOps.groupMetadataSetOp(data = linkedMapOf("b" to "2", "a" to "1")).bytes),
            Hex.encode(GovernanceOps.groupMetadataSetOp(data = linkedMapOf("a" to "1", "b" to "2")).bytes),
        )
    }
}
