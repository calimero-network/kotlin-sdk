package com.calimero.mero.sample.chat

import com.calimero.mero.account.CloudAccount
import com.calimero.mero.account.DeviceCert
import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.compose.MeroClient
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The Chat tab's spaces listing on a Calimero Cloud account (the 2026-10-21 incident): a
 * listing that failed, or a relay session that could not be minted, is never shown as
 * "No spaces yet" with a "New space" offer — that is how a duplicate space gets made.
 * The Swift sample's `ChatService.loadSpaces` follows the same rules; that sample has only UI
 * tests, so these cover the logic for both.
 */
class ChatSpacesLoadTest {
    private lateinit var node: FakeNode
    private lateinit var server: MockWebServer
    private lateinit var client: MeroClient

    private val callback = "mero-sample://enrol"
    private val root = SeedSigner("5c".repeat(32))

    @Before
    fun setUp() {
        node = FakeNode()
        server = node.start()
        val base = server.url("/").toString().trimEnd('/')
        val account = CloudAccount(MemorySecureStore(), cloudBaseUrl = base, walletUrl = "https://wallet.example/account-enroll")
        client = MeroClient(account, MemoryTokenStore(), launchWallet = { _, _ -> })
    }

    @After
    fun tearDown() = server.shutdown()

    private suspend fun signIn() {
        val url = client.enrolmentUrl(callback).toHttpUrl()
        val acc = DeviceCert.accountForRootPublicKey(root.publicKey)
        val device = DeviceCert.mintDeviceId(acc, ByteArray(16) { 9 })
        val credential = DeviceCert.sign(root, device, url.queryParameter("enrol-device")!!, url.queryParameter("enrol-kem")!!)
        val answer = "$callback#credential=$credential&account=$acc&device=$device&state=${url.queryParameter("state")}"
        assertTrue(client.handleEnrolmentCallback(answer))
    }

    private fun namespace(id: String) =
        buildJsonObject {
            put("namespaceId", id)
            put("appKey", "99".repeat(32))
            put("targetApplicationId", "88".repeat(32))
            put("name", "Team")
            put("createdAt", 1)
            put("memberCount", 1)
            put("contextCount", 0)
            put("subgroupCount", 0)
        }

    /** Every request that reached the server for the namespaces listing, authenticated or not. */
    private fun namespaceRequests(): Int {
        var count = 0
        while (true) {
            val request = server.takeRequest(0, TimeUnit.MILLISECONDS) ?: return count
            if (request.path?.substringBefore('?') == "/admin-api/namespaces") count++
        }
    }

    @Test
    fun `a ready relay session lists its spaces`() =
        runBlocking {
            node.cloud.listedNamespaces += namespace("aa".repeat(32))
            signIn()
            val chat = ChatService(client)
            assertEquals(SpacesLoad.PENDING, chat.spacesLoad)

            chat.loadSpaces()
            assertEquals(SpacesLoad.LOADED, chat.spacesLoad)
            assertEquals(listOf("aa".repeat(32)), chat.spaces.map { it.id })
            assertTrue(chat.offerNewSpace)
        }

    @Test
    fun `an account with no spaces is loaded and empty, and may create one`() =
        runBlocking {
            signIn()
            val chat = ChatService(client)
            chat.loadSpaces()
            assertEquals(SpacesLoad.LOADED, chat.spacesLoad)
            assertTrue(chat.spaces.isEmpty())
            assertTrue(chat.offerNewSpace)
        }

    @Test
    fun `an account no relay serves yet is new and empty, not a failure`() =
        runBlocking {
            node.cloud.accountHasRelay = false
            signIn()
            assertTrue(client.state.value.signedInWithoutRelay)
            val chat = ChatService(client)
            chat.loadSpaces()
            assertEquals(SpacesLoad.LOADED, chat.spacesLoad)
            assertTrue(chat.spaces.isEmpty())
            assertFalse(chat.statusIsError)
            assertEquals(0, namespaceRequests())
        }

    @Test
    fun `a failed listing is a failure, not an empty account, and offers no new space`() =
        runBlocking {
            signIn()
            node.cloud.namespacesStatus = 502
            val chat = ChatService(client)
            chat.loadSpaces()
            assertEquals(SpacesLoad.FAILED, chat.spacesLoad)
            assertTrue(chat.statusIsError)
            assertFalse(chat.offerNewSpace)

            // The relay answers again: the retry lists, and the failure is cleared.
            node.cloud.namespacesStatus = null
            node.cloud.listedNamespaces += namespace("bb".repeat(32))
            chat.loadSpaces()
            assertEquals(SpacesLoad.LOADED, chat.spacesLoad)
            assertEquals(1, chat.spaces.size)
            assertEquals("", chat.status)
        }

    @Test
    fun `a failed refresh keeps the spaces already shown`() =
        runBlocking {
            node.cloud.listedNamespaces += namespace("aa".repeat(32))
            signIn()
            val chat = ChatService(client)
            chat.loadSpaces()
            node.cloud.namespacesStatus = 503
            chat.loadSpaces()
            assertEquals(SpacesLoad.FAILED, chat.spacesLoad)
            assertEquals(listOf("aa".repeat(32)), chat.spaces.map { it.id })
        }

    @Test
    fun `a relay session that could not be minted lists nothing unauthenticated and reports why`() =
        runBlocking {
            node.cloud.attestStatus = 503
            signIn()
            assertFalse(client.state.value.sessionReady)
            val chat = ChatService(client)
            chat.loadSpaces()
            assertEquals(SpacesLoad.FAILED, chat.spacesLoad)
            assertEquals(0, namespaceRequests())
            assertEquals(client.state.value.relayNote, chat.status)
            assertFalse(chat.offerNewSpace)
        }
}
