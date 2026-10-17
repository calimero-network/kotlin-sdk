package com.calimero.mero.ownerop

import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
import com.calimero.mero.account.DeviceCert
import com.calimero.mero.account.DeviceScopeInput
import com.calimero.mero.account.signDeviceScope
import com.calimero.mero.admin.SetTeeAdmissionPolicyRequest
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The root-signer overloads on `AdminApi`: they read the group's `namespaceId` and
 * `ownerOpCounter`, encode the op the node builds, and send the proof the root signs —
 * the same bytes core's vectors pin (`OwnerOpTest`).
 */
class AdminRootProofsTest {
    private lateinit var server: MockWebServer
    private lateinit var mero: Mero
    private val writes = CopyOnWriteArrayList<RecordedRequest>()
    private val root = SeedSigner("07".repeat(32))
    private val namespace = "11".repeat(32)
    private val group = "22".repeat(32)
    private val someone = "44".repeat(32)

    @Volatile private var groupInfo: (String) -> String = { id ->
        """{"data":{"groupId":"$id","appKey":"a","targetApplicationId":"t","memberCount":1,"contextCount":0,""" +
            """"defaultCapabilities":0,"subgroupVisibility":"open","namespaceId":"$namespace","ownerOpCounter":5}}"""
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (request.method == "GET" && path.startsWith("/admin-api/groups/")) {
                        return MockResponse().setBody(groupInfo(path.removePrefix("/admin-api/groups/")))
                    }
                    writes += request
                    return MockResponse().setBody(
                        """{"data":{"accountId":"${"aa".repeat(32)}","deviceId":"${"bb".repeat(32)}","alreadyBound":false}}""",
                    )
                }
            }
        server.start()
        mero = Mero(MeroConfig(baseUrl = server.url("/").toString().trimEnd('/'), tokenStore = MemoryTokenStore(), timeoutMs = 5_000))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun body(req: RecordedRequest): JsonObject = Json.parseToJsonElement(req.body.clone().readUtf8()).jsonObject

    private fun proofOf(req: RecordedRequest): String = body(req)["rootProof"]!!.jsonPrimitive.content

    @Test
    fun `transferOwnership signs the proof core's vector pins`() =
        runBlocking {
            mero.admin.transferOwnership(group, someone, root)
            val req = writes.single()
            assertEquals("/admin-api/groups/$group/transfer-ownership", req.path)
            val sent = body(req)
            assertEquals(someone, sent["newOwner"]!!.jsonPrimitive.content)
            // `root_guarded_vectors_are_stable`: group 0x22, namespace 0x11, counter 5.
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
                Hex.encode(rootGuardedOpBytes(transferOwnershipOp(someone), sent["rootProof"]!!.jsonPrimitive.content)),
            )
        }

    @Test
    fun `changeNamespaceAdmin signs a root op on the namespace root`() =
        runBlocking {
            mero.admin.changeNamespaceAdmin(namespace, someone, root)
            val req = writes.single()
            assertEquals("/admin-api/namespaces/$namespace/admin", req.path)
            val expected =
                signOwnerOpProof(OwnerOpProofInput(namespace, namespace, adminChangedOp(someone), counter = 5, signer = root))
            assertEquals(expected, proofOf(req))
        }

    @Test
    fun `ownerDeleteGroup and the TEE authoring calls sign their own ops`() =
        runBlocking {
            mero.admin.ownerDeleteGroup(group, root)
            mero.admin.setTeeAuthoringPolicy(group, listOf("aa"), root)
            mero.admin.disableTeeAuthoringPolicy(group, root)
            val (delete, set, disable) = writes.toList()

            fun expect(op: OwnerOp) = signOwnerOpProof(OwnerOpProofInput(namespace, group, op, counter = 5, signer = root))
            assertEquals("POST", delete.method)
            assertEquals(expect(groupDeleteOp()), proofOf(delete))
            assertEquals("PUT", set.method)
            assertEquals(expect(teeAuthoringPolicyOp(listOf("aa"))), proofOf(set))
            assertEquals("DELETE", disable.method)
            assertEquals(expect(teeAuthoringPolicyOp(emptyList())), proofOf(disable))
        }

    @Test
    fun `setTeeAdmissionPolicy signs the release op the node normalises to`() =
        runBlocking {
            val request = SetTeeAdmissionPolicyRequest.signedRelease(listOf("locked-read-only"), minReleaseVersion = "mero-tee-v2.3.72", acceptMock = true)
            mero.admin.setTeeAdmissionPolicy(group, request, root)
            val req = writes.single()
            val op =
                teeReleaseAdmissionPolicyOp(
                    TeeReleaseAdmissionPolicyOpInput(listOf("locked-read-only"), "2.3.72", emptyList(), acceptMock = true),
                )
            assertEquals(signOwnerOpProof(OwnerOpProofInput(namespace, group, op, counter = 5, signer = root)), proofOf(req))
            // What was sent is the request as given, plus the proof.
            assertEquals("mero-tee-v2.3.72", body(req)["signedRelease"]!!.jsonObject["minReleaseVersion"]!!.jsonPrimitive.content)
        }

    @Test
    fun `refuses to sign for a node that reports no counter`() {
        groupInfo = { id ->
            """{"data":{"groupId":"$id","appKey":"a","targetApplicationId":"t","memberCount":1,"contextCount":0,""" +
                """"defaultCapabilities":0,"subgroupVisibility":"open"}}"""
        }
        assertThrows(MeroStateException::class.java) { runBlocking { mero.admin.transferOwnership(group, someone, root) } }
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `linkAccountDevice signs the scope for the device the credential names`() =
        runBlocking {
            val rootSecret = "5c".repeat(32)
            val rootSigner = SeedSigner(rootSecret)
            val account = DeviceCert.accountForRoot(rootSecret)
            val device = DeviceCert.mintDeviceId(account, ByteArray(16) { 0xa1.toByte() })
            val credential = DeviceCert.sign(rootSigner, device, "33".repeat(32), "44".repeat(32))
            val apps = listOf("11".repeat(32))
            mero.admin.linkAccountDevice(namespace, credential, rootSigner, applications = apps, scopeEpoch = 7)
            val sent = body(writes.single())
            assertEquals(credential, sent["credential"]!!.jsonPrimitive.content)
            assertEquals(
                signDeviceScope(DeviceScopeInput(device, apps, 7, rootSecret = rootSecret)),
                sent["scope"]!!.jsonPrimitive.content,
            )
        }
}
