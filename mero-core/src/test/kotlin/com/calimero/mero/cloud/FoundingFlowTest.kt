package com.calimero.mero.cloud

import com.calimero.mero.account.CloudAccount
import com.calimero.mero.account.DelegatedSession
import com.calimero.mero.account.DeviceCert
import com.calimero.mero.account.Invitations
import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.crypto.Ed25519
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.crypto.concat
import com.calimero.mero.crypto.fromBase64
import com.calimero.mero.relay.ApplicationTarget
import com.calimero.mero.relay.GovernanceOps
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLEncoder

/**
 * Founding a namespace and inviting into it as a Cloud account, against [FakeNode]'s cloud +
 * relay routes. Request shapes are asserted on the exact key set (core rejects unknown fields).
 */
class FoundingFlowTest {
    private lateinit var node: FakeNode
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private lateinit var account: CloudAccount
    private lateinit var session: DelegatedSession

    private val root = SeedSigner("5c".repeat(32))
    private val app = ApplicationTarget("88".repeat(32), "com.calimero.chat", "1.2.3")

    @Before
    fun setUp() =
        runBlocking {
            node = FakeNode()
            server = node.start()
            base = server.url("/").toString().trimEnd('/')
            account = CloudAccount(MemorySecureStore(), cloudBaseUrl = base, walletUrl = "https://wallet.example/account-enroll")
            session = account.completeEnrolment(walletApproves(account.beginEnrolment("mero-sample://enrol")))!!
        }

    @After
    fun tearDown() = server.shutdown()

    private fun walletApproves(walletUrl: String): String {
        val url = walletUrl.toHttpUrl()
        val acc = DeviceCert.accountForRootPublicKey(root.publicKey)
        val device = DeviceCert.mintDeviceId(acc, ByteArray(16) { 7 })
        val credential = DeviceCert.sign(root, device, url.queryParameter("enrol-device")!!, url.queryParameter("enrol-kem")!!)
        val state = URLEncoder.encode(url.queryParameter("state")!!, "UTF-8")
        return "${url.queryParameter("callback-url")}#credential=$credential&account=$acc&device=$device&state=$state"
    }

    private fun governancePosts() = node.cloud.recorded("/governance-intents").filter { it.method == "POST" }

    @Test
    fun `founds a namespace with its genesis, application, mask and name, then enables HA`() =
        runBlocking {
            val result = account.foundNamespace(defaultCapabilities = 231, application = app, name = "Team")
            val founded = result.founded
            assertEquals(GovernanceOps.foundedNamespaceId(session.account, founded.salt), founded.namespaceId)
            assertTrue(founded.teeEnabled)
            assertEquals(true, founded.applicationSet)
            assertEquals(true, founded.defaultCapabilitiesSet)
            assertTrue(result.haEnabled)
            assertNull(result.haError)
            assertNull(result.nameError)

            val posts = governancePosts()
            assertEquals(4, posts.size)
            posts.forEach {
                assertEquals("/admin-api/groups/${founded.namespaceId}/governance-intents", it.path)
                assertEquals(setOf("warrant", "authorProof", "op"), it.bodyJson!!.keys)
                assertEquals(session.credential, it.bodyJson!!["authorProof"]!!.jsonPrimitive.content)
            }

            fun op(i: Int) = posts[i].bodyJson!!["op"]!!.jsonPrimitive.content
            assertEquals(Hex.encode(GovernanceOps.namespaceCreatedOp(session.account, session.credential, founded.salt).bytes), op(0))
            assertEquals(Hex.encode(GovernanceOps.targetApplicationSetOp(app.applicationId, app.packageName, app.version).bytes), op(1))
            assertEquals("06e7000000", op(2))
            assertEquals(Hex.encode(GovernanceOps.groupMetadataSetOp(name = "Team").bytes), op(3))

            // The genesis warrant: scope = the derived id, root plane, the relay's account and its ATTESTED key.
            val genesis = posts[0].bodyJson!!["warrant"]!!.jsonPrimitive.content
            assertEquals(founded.namespaceId, genesis.substring(0, 64))
            assertEquals("01", genesis.substring(64, 66))
            assertEquals(session.account, genesis.substring(66, 130))
            assertEquals(account.deviceKeys.load()!!.signPublicKey, genesis.substring(130, 194))
            assertEquals(node.cloud.relayAccount, genesis.substring(194, 258))
            assertEquals(node.cloud.nodeKey, genesis.substring(258, 322))
            // The founding asks discovery nothing; the follow-ups do.
            assertEquals(3, node.cloud.recorded("/governance-intents").count { it.method == "GET" })

            // enable-ha: the founder's claim, anonymous, on the account route.
            val ha = node.cloud.recorded("/enable-ha").single()
            assertEquals("/api/cloud/accounts/${session.account}/namespaces/${founded.namespaceId}/enable-ha", ha.path)
            assertNull(ha.headers["Authorization"])
            assertEquals(setOf("ownership_proof"), ha.bodyJson!!.keys)
            val proof = ha.bodyJson!!["ownership_proof"]!!.jsonObject
            assertEquals(setOf("kind", "credential", "signed_payload", "signature"), proof.keys)
            assertEquals("account", proof["kind"]!!.jsonPrimitive.content)
            assertEquals(session.credential, proof["credential"]!!.jsonPrimitive.content)
            val payloadBytes = fromBase64(proof["signed_payload"]!!.jsonPrimitive.content)
            val payload = Json.parseToJsonElement(payloadBytes.decodeToString()).jsonObject
            assertEquals(
                listOf("v", "audience", "group_id", "account_id", "salt", "nonce", "issued_at_ms", "expires_at_ms", "relay_url"),
                payload.keys.toList(),
            )
            assertEquals("mdma:enable-ha-namespace-as-account", payload["audience"]!!.jsonPrimitive.content)
            assertEquals(founded.namespaceId, payload["group_id"]!!.jsonPrimitive.content)
            assertEquals(session.account, payload["account_id"]!!.jsonPrimitive.content)
            assertEquals(founded.salt, payload["salt"]!!.jsonPrimitive.content)
            assertEquals(base, payload["relay_url"]!!.jsonPrimitive.content)
            assertEquals(60_000L, payload["expires_at_ms"]!!.jsonPrimitive.long - payload["issued_at_ms"]!!.jsonPrimitive.long)
            val signed = concat("calimero.mdma.account-ownership-claim.v1\u0000".encodeToByteArray(), payloadBytes)
            assertTrue(
                Ed25519.verify(
                    Hex.decode(account.deviceKeys.load()!!.signPublicKey, "pk", 32),
                    fromBase64(proof["signature"]!!.jsonPrimitive.content),
                    signed,
                ),
            )
        }

    @Test
    fun `reports an HA refusal in words a person can act on, and keeps the namespace`() =
        runBlocking {
            node.cloud.haRefusal = AccountHaRefusedException.ACCOUNT_NOT_LINKED
            val result = account.foundNamespace()
            assertFalse(result.haEnabled)
            assertEquals(AccountHaRefusedException.ADVICE[AccountHaRefusedException.ACCOUNT_NOT_LINKED], result.haError)
            assertEquals(1, governancePosts().size)
        }

    @Test
    fun `asks for no HA when the relay did not attest the founding`() =
        runBlocking {
            node.cloud.teeOnFounding = false
            val result = account.foundNamespace()
            assertFalse(result.haEnabled)
            assertTrue(result.haError!!.contains("did not attest"))
            assertTrue(node.cloud.recorded("/enable-ha").isEmpty())
        }

    @Test
    fun `mints an invitation naming the namespace's relays, signed by this device`() =
        runBlocking {
            val ns = "77".repeat(32)
            node.cloud.groupMembers += session.account to "Admin"
            val connection = account.connect(session, MemoryTokenStore())
            val invitation = account.createNamespaceInvitation(ns, connection)

            val body = invitation.invitation
            assertEquals(listOf(node.cloud.relayAccount), body.admitters)
            assertEquals(List(32) { 0x77 }, body.groupId)
            assertEquals(Invitations.ROLE_MEMBER, body.invitedRole)
            assertEquals(session.account, invitation.inviterAccount)
            assertEquals(List(32) { 0x88 }, invitation.applicationId)
            assertEquals(List(32) { 0x99 }, invitation.appKey)
            assertTrue(invitation.admitterAddrs.isEmpty())
            val deviceKey = account.deviceKeys.load()!!.signPublicKey
            assertEquals(deviceKey, Hex.encode(ByteArray(32) { body.inviterIdentity[it].toByte() }))
            assertTrue(
                Ed25519.verify(
                    Hex.decode(deviceKey, "pk", 32),
                    Hex.decode(invitation.inviterSignature, "sig", 64),
                    Invitations.invitationHash(body),
                ),
            )
            // Members and info read over the Bearer session.
            assertTrue(
                node.cloud
                    .recorded("/members")
                    .single()
                    .headers["Authorization"]!!
                    .startsWith("Bearer "),
            )
        }

    @Test
    fun `an invitation to a namespace the cloud does not host says where its relay is`() =
        runBlocking {
            node.cloud.namespacesRouted = false
            val invitation = account.createNamespaceInvitation("77".repeat(32), account.connect(session, MemoryTokenStore()))
            assertEquals(listOf(base), invitation.admitterAddrs)
            assertEquals(JsonPrimitive(base), invitation.raw["admitter_addrs"]!!.jsonArray.single())
        }
}
