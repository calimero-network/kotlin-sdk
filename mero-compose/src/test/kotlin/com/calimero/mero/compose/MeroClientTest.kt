package com.calimero.mero.compose

import com.calimero.mero.account.CloudAccount
import com.calimero.mero.account.DeviceCert
import com.calimero.mero.account.Enrolment
import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.account.persistedNonces
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.http.classifyError
import com.calimero.mero.relay.DcapRelayKeyVerifier
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        val account = CloudAccount(MemorySecureStore(), relayKeyVerifier = DcapRelayKeyVerifier(allowMock = true), cloudBaseUrl = base, walletUrl = "https://wallet.example/account-enroll")
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

    /** 2026-10-21: a relay login that failed names its cause, not "resume once it is attested". */
    @Test
    fun `a relay session that could not be minted says why reads are off`() =
        runBlocking {
            node.cloud.attestStatus = 503
            client.signInWithCloud(android.content.ContextWrapper(null), callback)
            assertTrue(client.handleEnrolmentCallback(walletAnswer()))

            val state = client.state.value
            assertTrue(state.isAuthenticated)
            assertFalse(state.sessionReady)
            val note = state.relayNote!!
            assertTrue(note, note.startsWith("Writes go through the relay, but reads and live updates are off: "))
            assertTrue(note, note.contains("503"))
        }

    /**
     * The sample's emulator test: after sign-in the client learns the account's application id
     * in the background. That lookup must not open a second relay login — FakeNode (like a relay
     * that keeps one session per device) then retires the connection's token, the query 401s and
     * the call falls through to a warrant. The call waits for the lookup to finish.
     */
    @Test
    fun `learning the application id in the background leaves the relay session usable`() =
        runBlocking {
            val base = server.url("/").toString().trimEnd('/')
            // The sample's mock setup: a relay whose (mock) attestation is accepted, so the
            // account's relays can be listed.
            val account =
                CloudAccount(
                    MemorySecureStore(),
                    cloudBaseUrl = base,
                    walletUrl = "https://wallet.example/account-enroll",
                    relayKeyVerifier = DcapRelayKeyVerifier(allowMock = true),
                )
            val background = SupervisorJob()
            val inline = MeroClient(account, MemoryTokenStore(), launchWallet = { _, url -> opened = url }, scope = CoroutineScope(background + Dispatchers.IO))
            inline.signInWithCloud(android.content.ContextWrapper(null), callback)
            assertTrue(inline.handleEnrolmentCallback(walletAnswer()))
            val logins = node.tokenCalls
            background.children.forEach { it.join() }
            assertEquals(JsonPrimitive(42), inline.relay!!.call("demo-context", "get"))
            assertEquals("no further relay login", logins, node.tokenCalls)
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

    /** SEC-01: a wallet answer for this device's keys that this app never asked for signs nobody in. */
    @Test
    fun `an unsolicited enrolment link is refused`() =
        runBlocking {
            val keys = client.account.deviceKeys.loadOrCreate()
            opened = Enrolment.deviceEnrolmentUrl(keys.signPublicKey, keys.kemPublicKey, callback, "attacker-chosen")
            assertTrue(client.handleEnrolmentCallback(walletAnswer()))
            val state = client.state.value
            assertFalse(state.isAuthenticated)
            assertNotNull(state.error)
            assertFalse(client.account.isSignedIn)
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

            assertTrue(again.connection.value!!.ensureSession())
            again.signOut()
            // The relay session was retired on the relay, not just dropped locally.
            assertEquals(1, node.logoutCalls)
            assertFalse(again.state.value.isAuthenticated)
            assertNull(again.relay)
            assertFalse(client.account.isSignedIn)
        }

    @Test
    fun `logout keeps the device unless told to forget it`() =
        runBlocking {
            client.signInWithCloud(android.content.ContextWrapper(null), callback)
            client.handleEnrolmentCallback(walletAnswer())
            val device = client.account.deviceKeys.load()
            assertNotNull(device)
            val relay = client.state.value.relayUrl!!
            client.account.persistedNonces(relay).next()
            client.logout()
            assertEquals("a plain logout keeps the device key", device, client.account.deviceKeys.load())
            assertEquals(2uL, client.account.persistedNonces(relay).next())

            client.signInWithCloud(android.content.ContextWrapper(null), callback)
            client.handleEnrolmentCallback(walletAnswer())
            assertTrue(client.state.value.isAuthenticated)
            client.logout(forgetDevice = true)
            assertFalse(client.state.value.isAuthenticated)
            assertNull("forgetDevice drops the key certified to the old account", client.account.deviceKeys.load())
            assertEquals("and the nonces spent under it", 1uL, client.account.persistedNonces(relay).next())
        }

    // ---- Development node login ----------------------------------------------------------

    @Test
    fun `login signs in to a development node with a username and password`() =
        runBlocking {
            val base = server.url("/").toString()
            assertTrue(client.login(base, "admin", "secret"))
            val state = client.state.value
            assertTrue(state.isAuthenticated)
            assertTrue(state.isDevNodeSession)
            assertFalse(state.signedInWithoutRelay)
            assertEquals(base.trimEnd('/'), state.nodeUrl)
            assertEquals("admin", state.username)
            assertNull(client.relay)

            val body = node.lastTokenRequestBody!!
            assertTrue(body, body.contains("\"auth_method\":\"user_password\""))
            assertTrue(body, body.contains("\"password\":\"secret\""))
            assertEquals(JsonPrimitive(42), client.mero!!.rpc.execute<JsonPrimitive>("ctx", "get"))

            client.signOut()
            assertEquals(1, node.logoutCalls)
            assertFalse(client.state.value.isAuthenticated)
            assertNull(client.mero)
        }

    @Test
    fun `login refuses a malformed url or empty credentials without a request`() =
        runBlocking {
            assertFalse(client.login("not a url", "admin", "secret"))
            assertTrue(
                client.state.value.error!!
                    .contains("valid node URL"),
            )
            assertFalse(client.login(server.url("/").toString(), "admin", ""))
            assertTrue(
                client.state.value.error!!
                    .contains("required"),
            )
            assertEquals(0, node.tokenCalls)
            assertFalse(client.state.value.isAuthenticated)
        }

    @Test
    fun `login refuses plain http across the network`() =
        runBlocking {
            assertFalse(client.login("http://node.test", "admin", "secret"))
            assertEquals(MeroClient.INSECURE_NODE_URL, client.state.value.error)
            assertEquals(MeroErrorReason.INVALID_INPUT, client.state.value.errorReason)
            assertEquals(0, node.tokenCalls)
        }

    @Test
    fun `login reports an unreachable node in words`() =
        runBlocking {
            val closed = java.net.ServerSocket(0).use { it.localPort }
            assertFalse(client.login("http://127.0.0.1:$closed", "admin", "secret"))
            val state = client.state.value
            assertFalse(state.isAuthenticated)
            assertFalse(state.isLoading)
            assertTrue(state.error!!, state.error!!.startsWith("Can't reach the node at http://127.0.0.1:$closed"))
            assertEquals(MeroErrorReason.UNREACHABLE, state.errorReason)
        }

    @Test
    fun `login says which input it refused`() =
        runBlocking {
            assertFalse(client.login("not a url", "admin", "secret"))
            assertEquals(MeroErrorReason.INVALID_INPUT, client.state.value.errorReason)
            client.clearError()
            assertNull(client.state.value.errorReason)
        }

    /** A node that answers every request with [status] and [body], and records what it was sent. */
    private fun refusingNode(
        status: Int,
        body: String,
    ): MockWebServer =
        MockWebServer().apply {
            dispatcher =
                object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                        okhttp3.mockwebserver
                            .MockResponse()
                            .setResponseCode(status)
                            .setHeader("Content-Type", "application/json")
                            .setBody(body)
                }
            start()
        }

    @Test
    fun `login tells bad credentials from a lockout by reason, keeping the message`() =
        runBlocking {
            val bad = refusingNode(401, """{"error":"Invalid username or password"}""")
            val locked = refusingNode(429, """{"error":"Too many failed login attempts"}""")
            try {
                assertFalse(client.login(bad.url("/").toString(), "admin", "wrong"))
                var state = client.state.value
                assertEquals(MeroErrorReason.INVALID_CREDENTIALS, state.errorReason)
                assertEquals(401, state.errorStatus)
                assertEquals("Login failed — check the username and password.", state.error)

                assertFalse(client.login(locked.url("/").toString(), "admin", "wrong"))
                state = client.state.value
                assertEquals(MeroErrorReason.RATE_LIMITED, state.errorReason)
                assertEquals(429, state.errorStatus)
                assertTrue(state.error!!, state.error!!.contains("Too many failed login attempts"))
            } finally {
                bad.shutdown()
                locked.shutdown()
            }
        }

    @Test
    fun `the built-in words for an error are public`() {
        val offline = java.io.IOException("offline")
        assertTrue(MeroClient.defaultMessage(offline, "https://node.test").startsWith("Can't reach the node at https://node.test: "))
    }

    @Test
    fun `errorMessages can replace the built-in words`() =
        runBlocking {
            val locked = refusingNode(429, """{"error":"Too many failed login attempts"}""")
            val bad = refusingNode(401, """{"error":"Invalid username or password"}""")
            try {
                client.errorMessages = { error ->
                    if (classifyError(error).status == 429) "Locked. Wait a moment." else null
                }
                assertFalse(client.login(locked.url("/").toString(), "admin", "wrong"))
                assertEquals("Locked. Wait a moment.", client.state.value.error)

                assertFalse(client.login(bad.url("/").toString(), "admin", "wrong"))
                assertEquals("null keeps the built-in message", "Login failed — check the username and password.", client.state.value.error)
            } finally {
                locked.shutdown()
                bad.shutdown()
            }
        }

    @Test
    fun `a password session survives a relaunch`() =
        runBlocking {
            val tokens = MemoryTokenStore()
            val storage = MeroAppStorage(MemoryKeyValueStore())
            val base = server.url("/").toString().trimEnd('/')

            fun launch() =
                MeroClient(
                    CloudAccount(MemorySecureStore(), relayKeyVerifier = DcapRelayKeyVerifier(allowMock = true), cloudBaseUrl = base, walletUrl = "https://wallet.example/account-enroll"),
                    tokens,
                    storage = storage,
                )

            assertTrue(launch().login(base, "admin", "secret"))
            assertEquals(base, storage.nodeUrl)
            assertEquals("admin", storage.username)

            val relaunched = launch()
            assertTrue(relaunched.restore())
            val state = relaunched.state.value
            assertTrue(state.isDevNodeSession)
            assertEquals(base, state.nodeUrl)
            assertEquals("admin", state.username)
            assertEquals(JsonPrimitive(42), relaunched.mero!!.rpc.execute<JsonPrimitive>("ctx", "get"))

            relaunched.signOut()
            assertNull(storage.nodeUrl)
            assertNull(storage.username)
            assertFalse(launch().restore())
        }

    @Test
    fun `login with permissions signs in with a client key instead of the admin token`() =
        runBlocking {
            val base = server.url("/").toString()
            val grants = permissionsFor(AppMode.MULTI_CONTEXT)
            assertTrue(client.login(base, "admin", "secret", permissions = grants))

            assertEquals(1, node.clientKeyCalls)
            val body = node.lastClientKeyRequestBody!!
            assertTrue(body, grants.all { body.contains("\"$it\"") })
            // The session runs on the client key's tokens, the last pair the node minted.
            assertEquals(node.accessToken, client.mero!!.getTokenData()!!.accessToken)
            assertTrue(client.state.value.isDevNodeSession)
        }

    @Test
    fun `a refused grant is its own reason and leaves no session behind`() =
        runBlocking {
            val tokens = MemoryTokenStore()
            val storage = MeroAppStorage(MemoryKeyValueStore())
            val base = server.url("/").toString().trimEnd('/')
            val c =
                MeroClient(
                    CloudAccount(MemorySecureStore(), relayKeyVerifier = DcapRelayKeyVerifier(allowMock = true), cloudBaseUrl = base, walletUrl = "https://wallet.example/account-enroll"),
                    tokens,
                    storage = storage,
                )
            assertFalse(c.login(base, "admin", "secret", permissions = listOf("keys:create")))
            val state = c.state.value
            assertFalse(state.isAuthenticated)
            assertEquals(MeroErrorReason.PERMISSIONS_REFUSED, state.errorReason)
            assertEquals(400, state.errorStatus)
            assertTrue(state.error!!, state.error!!.contains("keys:create"))
            assertNull(tokens.getTokens())
            assertNull(storage.nodeUrl)
        }

    @Test
    fun `node sessions are carried on the app's http client`() =
        runBlocking {
            val seen = refusingNode(401, "{}")
            try {
                val http =
                    okhttp3.OkHttpClient
                        .Builder()
                        .addInterceptor { chain ->
                            chain.proceed(
                                chain
                                    .request()
                                    .newBuilder()
                                    .header("X-App", "test")
                                    .build(),
                            )
                        }.build()
                val account = CloudAccount(MemorySecureStore(), relayKeyVerifier = DcapRelayKeyVerifier(allowMock = true), cloudBaseUrl = server.url("/").toString().trimEnd('/'))
                val c = MeroClient(account, MemoryTokenStore(), config = MeroAppConfig(httpClient = http))
                assertFalse(c.login(seen.url("/").toString(), "admin", "secret"))
                assertEquals("test", seen.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!.getHeader("X-App"))
            } finally {
                seen.shutdown()
            }
        }
}
