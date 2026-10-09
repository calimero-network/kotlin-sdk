package com.calimero.mero.compose

import com.calimero.mero.account.CloudAccount
import com.calimero.mero.account.DeviceCert
import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [MeroClient] is Cloud-only: sign-in opens the wallet, the redirect completes it, and the
 * client then talks to the account's relay. Driven against [FakeNode]'s cloud + relay routes
 * with a wallet that never opens a browser.
 */
class MeroClientTest {
    private lateinit var node: FakeNode
    private lateinit var server: MockWebServer
    private lateinit var client: MeroClient
    private var opened: String? = null

    private val callback = "mero-sample://enrol"
    private val root = SeedSigner("5c".repeat(32))

    @Before
    fun setUp() {
        node = FakeNode()
        server = node.start()
        val base = server.url("/").toString().trimEnd('/')
        val account = CloudAccount(MemorySecureStore(), cloudBaseUrl = base, walletUrl = "https://wallet.example/account-enroll")
        client = MeroClient(account, MemoryTokenStore(), launchWallet = { _, url -> opened = url })
    }

    @After
    fun tearDown() = server.shutdown()

    private fun walletAnswer(): String {
        val url = opened!!.toHttpUrl()
        val acc = DeviceCert.accountForRootPublicKey(root.publicKey)
        val device = DeviceCert.mintDeviceId(acc, ByteArray(16) { 9 })
        val credential = DeviceCert.sign(root, device, url.queryParameter("enrol-device")!!, url.queryParameter("enrol-kem")!!)
        return "$callback#credential=$credential&account=$acc&device=$device&state=${url.queryParameter("state")}"
    }

    @Test
    fun `signInWithCloud opens the wallet with this device's keys and the callback`() {
        client.signInWithCloud(android.content.ContextWrapper(null), callback)
        val url = opened!!.toHttpUrl()
        assertEquals(callback, url.queryParameter("callback-url"))
        assertEquals(
            client.account.deviceKeys
                .load()!!
                .signPublicKey,
            url.queryParameter("enrol-device"),
        )
        assertTrue(client.state.value.isLoading)
        assertFalse(client.state.value.isAuthenticated)
    }

    @Test
    fun `the wallet redirect signs in and connects to the relay`() =
        runBlocking {
            client.signInWithCloud(android.content.ContextWrapper(null), callback)
            assertTrue(client.handleEnrolmentCallback(walletAnswer()))

            val state = client.state.value
            assertTrue(state.isAuthenticated)
            assertFalse(state.isLoading)
            assertEquals(DeviceCert.accountForRootPublicKey(root.publicKey), state.account)
            assertNotNull(state.relayUrl)
            assertTrue(state.sessionReady)
            assertNull(state.error)

            assertEquals(JsonPrimitive(42), client.relay!!.call("demo-context", "get"))
            assertNotNull(client.mero!!.getTokenData())
        }

    @Test
    fun `a declined enrolment is cancelled, not an error`() =
        runBlocking {
            client.signInWithCloud(android.content.ContextWrapper(null), callback)
            assertTrue(client.handleEnrolmentCallback("$callback#error=cancelled"))
            val state = client.state.value
            assertFalse(state.isAuthenticated)
            assertTrue(state.cancelled)
            assertNull(state.error)
        }

    @Test
    fun `an unrelated deep link is ignored`() =
        runBlocking {
            assertFalse(client.handleEnrolmentCallback("mero-sample://somewhere-else"))
            assertFalse(client.state.value.isAuthenticated)
        }

    @Test
    fun `an account with no relay is signed in without one`() =
        runBlocking {
            node.cloud.accountHasRelay = false
            client.signInWithCloud(android.content.ContextWrapper(null), callback)
            client.handleEnrolmentCallback(walletAnswer())
            val state = client.state.value
            assertTrue(state.isAuthenticated)
            assertTrue(state.signedInWithoutRelay)
            assertNotNull(state.relayNote)
            assertNull(client.relay)
        }

    @Test
    fun `restore reconnects a persisted session, and signOut clears it`() =
        runBlocking {
            client.signInWithCloud(android.content.ContextWrapper(null), callback)
            client.handleEnrolmentCallback(walletAnswer())
            val again = MeroClient(client.account, MemoryTokenStore(), launchWallet = { _, _ -> })
            again.restore()
            assertTrue(again.state.value.isAuthenticated)
            assertNotNull(again.relay)

            again.signOut()
            assertFalse(again.state.value.isAuthenticated)
            assertNull(again.relay)
            assertFalse(client.account.isSignedIn)
        }
}
