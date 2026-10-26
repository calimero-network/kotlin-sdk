package com.calimero.mero.compose

import com.calimero.mero.Credentials
import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
import com.calimero.mero.account.CloudAccount
import com.calimero.mero.account.DeviceCert
import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.auth.PendingAuthStates
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.relay.DcapRelayKeyVerifier
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.net.URLEncoder

/**
 * [MeroClient] as mero-react's `MeroProvider`: a node session from an SSO callback (node-URL trust,
 * token adoption, `/auth/validate`), its restore and logout, and the session the state holders
 * read for either kind of connection. Ports `MeroContext.test.tsx`'s callback-binding and logout cases.
 */
class MeroClientSessionTest {
    private lateinit var node: FakeNode
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val tokens = MemoryTokenStore()
    private val storage = MeroAppStorage(MemoryKeyValueStore())
    private val pending = PendingAuthStates()
    private var opened: String? = null
    private val clients = mutableListOf<MeroClient>()
    private val callback = "https://app.example/auth"

    @Before
    fun setUp() {
        node = FakeNode()
        server = node.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        runBlocking { clients.forEach { it.signOut() } }
        server.shutdown()
    }

    private fun client(
        config: MeroAppConfig = MeroAppConfig(packageName = "com.example.app"),
        resolve: suspend (String, String) -> String = { _, _ -> error("no registry") },
    ): MeroClient {
        val account = CloudAccount(MemorySecureStore(), relayKeyVerifier = DcapRelayKeyVerifier(allowMock = true), cloudBaseUrl = base, walletUrl = "https://wallet.example/account-enroll")
        return MeroClient(
            account,
            tokens,
            launchWallet = { _, url -> opened = url },
            config = config,
            storage = storage,
            launchLogin = { _, url -> opened = url },
            pendingAuth = pending,
            resolveApplicationId = resolve,
        ).also { clients += it }
    }

    /** Have the fake node mint a token pair, as its login page would. */
    private fun mintTokens(): Pair<String, String> {
        runBlocking { Mero(MeroConfig(baseUrl = base)).authenticate(Credentials("admin", "pw")) }
        return node.accessToken to node.refreshToken
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** The redirect the node's login page sends back for the login [opened] started. */
    private fun ssoAnswer(
        nodeUrl: String = base,
        extra: String = "",
    ): String {
        val (access, refresh) = mintTokens()
        val back = opened!!.toHttpUrl().queryParameter("callback-url")!!
        return "$back#access_token=$access&refresh_token=$refresh&node_url=${enc(nodeUrl)}$extra"
    }

    @Test
    fun `connectToNode opens the node's login page with this app's mode, grants and package`() {
        val c = client()
        c.connectToNode(android.content.ContextWrapper(null), "$base/", callback)
        val url = opened!!.toHttpUrl()
        assertEquals("/auth/login", url.encodedPath)
        assertEquals("multi-context", url.queryParameter("mode"))
        assertEquals(permissionsFor(AppMode.MULTI_CONTEXT).joinToString(","), url.queryParameter("permissions"))
        assertEquals("com.example.app", url.queryParameter("package-name"))
        assertTrue(url.queryParameter("callback-url")!!.startsWith("$callback?auth_state="))
        // The trust anchor for the callback.
        assertEquals(base, storage.nodeUrl)
        assertTrue(c.state.value.isLoading)
    }

    @Test
    fun `connectToNode refuses plain http across the network`() {
        val c = client()
        c.connectToNode(android.content.ContextWrapper(null), "http://node.provider.test", callback)
        assertEquals(MeroClient.INSECURE_NODE_URL, c.state.value.error)
        assertNull("the login page is never opened", opened)
    }

    @Test
    fun `a custom-scheme callback is used only when the app allows it`() =
        runBlocking {
            val refused = client()
            refused.connectToNode(android.content.ContextWrapper(null), base, "myapp://auth")
            assertEquals(MeroClient.CUSTOM_SCHEME_CALLBACK, refused.state.value.error)
            assertNull("any app can claim myapp://", opened)

            val allowed = client(config = MeroAppConfig(packageName = "com.example.app", allowCustomSchemeCallbacks = true))
            allowed.connectToNode(android.content.ContextWrapper(null), base, "myapp://auth")
            val answer = ssoAnswer()
            assertFalse("the default config refuses it", refused.handleAuthCallback(answer))
            assertTrue(allowed.handleAuthCallback(answer))
            assertTrue(allowed.state.value.isAuthenticated)
        }

    @Test
    fun `a trusted callback adopts its tokens, validates them and connects`() =
        runBlocking {
            val c = client()
            c.connectToNode(android.content.ContextWrapper(null), base, callback)
            val answer = ssoAnswer(extra = "&application_id=app-1&context_id=ctx-1&context_identity=me")
            assertTrue(c.handleDeepLink(answer))

            val state = c.state.value
            assertTrue(state.isAuthenticated)
            assertTrue(state.isNodeSession)
            assertFalse(state.isDelegated)
            assertFalse(state.isDevNodeSession)
            assertEquals(base, state.nodeUrl)
            assertEquals("app-1", state.applicationId)
            assertEquals("ctx-1", state.contextId)
            assertEquals("me", state.contextIdentity)
            assertEquals(base, storage.tokenNodeUrl)
            assertEquals(node.accessToken, c.mero!!.getTokenData()!!.accessToken)

            val session = c.session.value
            assertNotNull(session.admin)
            assertNotNull(session.rpc)
            assertNotNull(session.events)
            assertEquals(MeroCapabilities.NODE, c.can)
        }

    @Test
    fun `a callback naming another node stores nothing and connects nothing`() =
        runBlocking {
            val c = client()
            c.connectToNode(android.content.ContextWrapper(null), base, callback)
            assertTrue(c.handleAuthCallback(ssoAnswer(nodeUrl = "https://evil.example.com")))
            assertFalse(c.state.value.isAuthenticated)
            assertEquals(MeroClient.UNTRUSTED_CALLBACK, c.state.value.error)
            assertNull(tokens.getTokens())
            assertNull(c.mero)
        }

    @Test
    fun `a callback with no login started here is not ours`() =
        runBlocking {
            val c = client()
            val (access, refresh) = mintTokens()
            assertFalse(c.handleDeepLink("$callback#access_token=$access&refresh_token=$refresh&node_url=${enc(base)}"))
            assertFalse(c.state.value.isAuthenticated)
        }

    @Test
    fun `restore brings back the stored node session, and logout clears every trace of it`() =
        runBlocking {
            val c = client()
            c.connectToNode(android.content.ContextWrapper(null), base, callback)
            c.handleAuthCallback(ssoAnswer(extra = "&application_id=app-1"))

            val again = client()
            assertTrue(again.restore())
            assertTrue(again.state.value.isNodeSession)
            assertEquals("app-1", again.state.value.applicationId)

            again.logout()
            assertFalse(again.state.value.isAuthenticated)
            assertNull(storage.nodeUrl)
            assertNull(storage.applicationId)
            assertNull(tokens.getTokens())
            assertNull(again.admin)
            assertFalse(client().restore())
        }

    @Test
    fun `restore with nothing saved is signed out`() =
        runBlocking {
            assertFalse(client().restore())
        }

    @Test
    fun `a dead stored session does not restore`() =
        runBlocking {
            val c = client()
            c.connectToNode(android.content.ContextWrapper(null), base, callback)
            c.handleAuthCallback(ssoAnswer())
            node.revokeFamily()
            assertFalse(client().restore())
        }

    @Test
    fun `selectContext persists the app's choice`() {
        val c = client()
        c.selectContext("ctx-9", "id-9")
        assertEquals("ctx-9", storage.contextId)
        assertEquals("ctx-9", c.state.value.contextId)
        assertEquals("id-9", c.state.value.contextIdentity)
    }

    // ---- The account session ---------------------------------------------------------------

    private val root = SeedSigner("5c".repeat(32))

    private fun walletAnswer(): String {
        val url = opened!!.toHttpUrl()
        val acc = DeviceCert.accountForRootPublicKey(root.publicKey)
        val device = DeviceCert.mintDeviceId(acc, ByteArray(16) { 9 })
        val credential = DeviceCert.sign(root, device, url.queryParameter("enrol-device")!!, url.queryParameter("enrol-kem")!!)
        return "mero-sample://enrol#credential=$credential&account=$acc&device=$device&state=${url.queryParameter("state")}"
    }

    @Test
    fun `an account session gets the account admin, may invite and may not upgrade`() =
        runBlocking {
            val c = client(resolve = { pkg, _ -> "app-of-$pkg" })
            c.signInWithCloud(android.content.ContextWrapper(null), "mero-sample://enrol")
            assertTrue(c.handleDeepLink(walletAnswer()))
            val session = c.session.value
            assertTrue(session.isDelegated)
            assertNotNull(session.admin)
            assertNotNull(session.delegated)
            assertEquals(MeroCapabilities.account(hasPackage = true), c.can)
            assertFalse(c.can.upgrade)
            assertTrue(c.state.value.isDelegated)
            // The application id comes from the app's package, through the registry.
            val learned = withTimeout(5_000) { c.state.first { it.applicationId != null } }
            assertEquals("app-of-com.example.app", learned.applicationId)
        }

    @Test
    fun `an account with no relay yet still gets an admin - a member of nothing`() =
        runBlocking {
            node.cloud.accountHasRelay = false
            val c = client(config = MeroAppConfig())
            c.signInWithCloud(android.content.ContextWrapper(null), "mero-sample://enrol")
            c.handleEnrolmentCallback(walletAnswer())
            val session = c.session.value
            assertTrue(session.isDelegated)
            assertNotNull(session.admin)
            assertNull(session.rpc)
            assertEquals(emptyList<Any>(), session.admin!!.listNamespaces())
            assertFalse(c.can.createNamespace)
        }
}
