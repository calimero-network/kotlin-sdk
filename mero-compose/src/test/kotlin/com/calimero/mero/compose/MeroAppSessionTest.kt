package com.calimero.mero.compose

import com.calimero.mero.account.CloudAccount
import com.calimero.mero.account.DeviceCert
import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.account.NoRelayException
import com.calimero.mero.admin.GroupContextEntry
import com.calimero.mero.admin.GroupMember
import com.calimero.mero.admin.MetadataRecord
import com.calimero.mero.app.AppBlob
import com.calimero.mero.app.AppContextEntry
import com.calimero.mero.app.AppMember
import com.calimero.mero.app.AppMetadata
import com.calimero.mero.app.AppNamespace
import com.calimero.mero.app.NoRelayContextContract
import com.calimero.mero.app.RelayContextContract
import com.calimero.mero.auth.PendingAuthStates
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.relay.DcapRelayKeyVerifier
import com.calimero.mero.relay.GovernanceMemberRole
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [MeroAppSession] over the SDK's real [MeroClient], signed in through a wallet that certifies this
 * device's key, against [FakeNode]'s cloud manager and hosted relay. (Swift `MeroAppSessionTests`.)
 */
class MeroAppSessionTest {
    private lateinit var node: FakeNode
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private var opened: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = mutableListOf<MeroAppSession>()
    private val root = SeedSigner("5c".repeat(32))
    private val pkg = "com.example.board"
    private val mine = "ad".repeat(32)
    private val theirs = "be".repeat(32)

    @Before
    fun setUp() {
        node = FakeNode()
        server = node.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        runBlocking { sessions.forEach { it.signOut() } }
        scope.cancel()
        server.shutdown()
    }

    private fun session(
        packageName: String? = pkg,
        options: MeroAppSessionOptions = MeroAppSessionOptions(),
        resolve: suspend (String, String) -> String = { _, _ -> error("no registry") },
    ): MeroAppSession {
        val account = CloudAccount(MemorySecureStore(), relayKeyVerifier = DcapRelayKeyVerifier(allowMock = true), cloudBaseUrl = base, walletUrl = "https://wallet.example/account-enroll")
        val client =
            MeroClient(
                account,
                MemoryTokenStore(),
                launchWallet = { _, url -> opened = url },
                config = MeroAppConfig(packageName = packageName, registryUrl = "https://registry.test"),
                storage = MeroAppStorage(MemoryKeyValueStore()),
                pendingAuth = PendingAuthStates(),
                resolveApplicationId = resolve,
            )
        return MeroAppSession(client, "board://enrol", options, scope).also { sessions += it }
    }

    /** The wallet's redirect for the sign-in [opened] started. */
    private fun walletAnswer(): String {
        val url = opened!!.toHttpUrl()
        val acc = DeviceCert.accountForRootPublicKey(root.publicKey)
        val device = DeviceCert.mintDeviceId(acc, ByteArray(16) { 9 })
        val credential = DeviceCert.sign(root, device, url.queryParameter("enrol-device")!!, url.queryParameter("enrol-kem")!!)
        return "board://enrol#credential=$credential&account=$acc&device=$device&state=${url.queryParameter("state")}"
    }

    private suspend fun signIn(session: MeroAppSession) {
        session.signIn(android.content.ContextWrapper(null))
        assertTrue(session.handle(walletAnswer()))
    }

    private fun namespace(
        id: String,
        app: String,
        name: String,
    ) = buildJsonObject {
        put("namespaceId", id)
        put("appKey", "k")
        put("targetApplicationId", app)
        put("createdAt", 1)
        put("name", name)
        put("memberCount", 2)
        put("contextCount", 0)
        put("subgroupCount", 0)
    }

    @Test
    fun `signed out there is no backend and the contract is relayless`() =
        runBlocking {
            val s = session()
            assertFalse(s.isSignedIn)
            assertNull(s.backend.value)
            assertFalse(s.hasRelay)
            assertFalse(s.canSubscribe)
            try {
                s.contract.bound(mine).query("feed", "")
                fail("expected NoRelayException")
            } catch (e: NoRelayException) {
                assertEquals("feed", e.method)
            }
        }

    @Test
    fun `signing in builds the backend over the relay`() =
        runBlocking {
            val s = session()
            val before = s.revision.value
            signIn(s)

            assertTrue(s.isSignedIn)
            assertEquals(64, s.account?.length)
            val backend = checkNotNull(s.backend.value) { "no backend after sign-in" }
            assertEquals(s.account, backend.account)
            assertTrue(s.hasRelay)
            assertTrue("the relay session was minted", s.canSubscribe)
            assertTrue(s.revision.value > before)
            assertTrue(backend.contract is RelayContextContract)

            node.cloud.outputs["feed"] = buildJsonObject { put("posts", 42) }
            assertEquals("""{"posts":42}""", s.contract.bound(mine).query("feed", "{}"))

            s.signOut()
            assertFalse(s.isSignedIn)
            assertNull(s.backend.value)
        }

    @Test
    fun `u64s round-trip exactly through the relay, by query and by warrant`() =
        runBlocking {
            val sent = """{ "max": 18446744073709551615, "at": 1761650000123456789 }"""
            val exact = """{"max":18446744073709551615,"at":1761650000123456789}"""
            node.cloud.outputs["stamps"] = Json.parseToJsonElement(exact)
            node.cloud.outputs["stamp"] = Json.parseToJsonElement(exact)
            node.cloud.writeMethods += "stamp"
            val s = session()
            signIn(s)
            val bound = s.contract.bound(mine)

            assertEquals(exact, bound.query("stamps", sent))
            assertEquals(exact, bound.execute("stamp", sent))

            val bodies =
                node.cloud.requests
                    .filter { it.path.startsWith("/admin-api/contexts/$mine/") && it.method == "POST" }
                    .map { it.body }
            assertEquals("the query and the warrant both carry the exact args", 2, bodies.count { it.contains("\"argsJson\":$exact") })
        }

    @Test
    fun `an account no relay serves gets a relayless backend`() =
        runBlocking {
            node.cloud.accountHasRelay = false
            val s = session()
            signIn(s)
            val backend = s.backend.value!!
            assertFalse(backend.hasRelay)
            assertFalse(backend.canSubscribe)
            assertSame(NoRelayContextContract, backend.contract)
            assertEquals(emptyList<Any>(), backend.listNamespaces())
        }

    @Test
    fun `rebuild is a no-op when nothing changed`() =
        runBlocking {
            val s = session()
            signIn(s)
            s.rebuild()
            val settled = s.revision.value
            val backend = s.backend.value
            s.rebuild()
            s.rebuild()
            assertEquals("open screens are not reset by an unchanged session", settled, s.revision.value)
            assertSame(backend, s.backend.value)
        }

    @Test
    fun `restore with nothing stored stays signed out`() =
        runBlocking {
            val s = session()
            s.restore()
            assertFalse(s.isRestoring.value)
            assertFalse(s.isSignedIn)
            assertNull(s.backend.value)
        }

    @Test
    fun `the backend follows the client, and lists only this app's namespaces once its id is known`() =
        runBlocking {
            node.cloud.listedNamespaces += namespace(mine, "BoardApp", "Board")
            node.cloud.listedNamespaces += namespace(theirs, "ChatApp", "Chat")
            val s = session(resolve = { _, _ -> "boardapp" })
            signIn(s)
            // The application id is learned after sign-in returns; the session rebuilds on its own.
            val backend = withTimeout(5_000) { s.backend.first { (it as? DelegatedAppBackend)?.applicationId != null } }!!
            assertEquals("boardapp", s.applicationId)
            assertEquals(listOf(AppNamespace(mine, "Board", "BoardApp", 2)), backend.listNamespaces())

            val every = DelegatedAppBackend(s.client, packageName = pkg)
            assertEquals(listOf(mine, theirs), every.listNamespaces().map { it.id })
        }

    /** Names, blobs, leaving and members map onto the account admin's calls, which the account admin turns into reads and governance ops on the relay. */
    @Test
    fun `the backend's names, blobs, leaving and members go through the admin`() =
        runBlocking {
            val s = session()
            signIn(s)
            val me = s.account!!
            val admin = RecordingAdmin()
            admin.contexts = listOf(GroupContextEntry("c1", "Roadmap"), GroupContextEntry("c2"))
            admin.members = listOf(GroupMember(me, "Admin", "Ana"), GroupMember("b", "Member"))
            admin.metadata =
                mapOf(
                    "g" to MetadataRecord("boards", mapOf("icon" to "grid"), 1, me),
                    "c1" to MetadataRecord("Roadmap", mapOf("color" to "red"), 1, me),
                    me to MetadataRecord("Ana", mapOf("avatar" to "a1"), 1, me),
                )
            val backend = DelegatedAppBackend(s.client, packageName = pkg, adminSurface = admin)

            assertEquals(listOf(AppContextEntry("c1", "Roadmap"), AppContextEntry("c2", null)), backend.listContextEntries("g"))
            assertEquals(AppMetadata("boards", mapOf("icon" to "grid")), backend.groupMetadata("g"))
            assertEquals("Roadmap", backend.contextMetadata("g", "c1")?.name)
            assertNull(backend.groupMetadata("unnamed"))
            backend.setGroupName("g", "planning")
            backend.setContextName("g", "c1", "Q3")
            backend.setMemberName("g", "Ana B")

            assertEquals(AppBlob("blob1", 5), backend.uploadBlob("c1", "hello".toByteArray()))
            assertArrayEquals("bytes".toByteArray(), backend.getBlob("blob1", "c1"))

            assertEquals(listOf(AppMember(me, "Admin", "Ana"), AppMember("b", "Member")), backend.listMembers("g"))
            backend.setMemberRole("g", "b", GovernanceMemberRole.ReadOnly)
            backend.removeMember("g", "b")

            backend.removeContext("g", "c2")
            backend.deleteSubgroup("g")
            backend.leaveSubgroup("g2")
            backend.leaveNamespace("ns")

            assertEquals(
                listOf(
                    "listGroupContexts g",
                    "getGroupMetadata g",
                    "getContextMetadata g c1",
                    "getGroupMetadata unnamed",
                    // A rename keeps the record's data: setting metadata replaces it whole.
                    "getGroupMetadata g",
                    "setGroupMetadata g planning [icon=grid]",
                    "getContextMetadata g c1",
                    "setContextMetadata g c1 Q3 [color=red]",
                    "getMemberMetadata g $me",
                    "setMemberMetadata g $me Ana B [avatar=a1]",
                    "uploadBlob c1 5",
                    "getBlob blob1 c1",
                    "listGroupMembers g",
                    "updateMemberRole g b ReadOnly",
                    "removeGroupMembers g [b]",
                    "detachContextFromGroup g c2",
                    "deleteGroup g",
                    "leaveGroup g2",
                    "leaveNamespace ns",
                ),
                admin.calls,
            )
        }

    @Test
    fun `an invitation for this app waits to be confirmed`() =
        runBlocking {
            val s = session()
            val link = "https://links.calimero.network/$pkg/join?invitation=abc"
            assertTrue(s.handle(link))
            assertEquals(link, s.pendingInvitation.value)
            s.dismissPendingInvitation()
            assertNull(s.pendingInvitation.value)
        }

    @Test
    fun `another app's invitation or a plain link is not taken`() =
        runBlocking {
            val s = session()
            assertFalse(s.handle("https://links.calimero.network/com.calimero.chat/join?invitation=abc"))
            assertFalse(s.handle("board://settings"))
            assertNull(s.pendingInvitation.value)
        }

    @Test
    fun `the invitation parser is pluggable`() =
        runBlocking {
            val s = session(options = MeroAppSessionOptions(isInvitation = { it.startsWith("board://invite/") }))
            assertTrue(s.handle("board://invite/xyz"))
            assertFalse("the app's parser decides alone", s.handle("https://links.calimero.network/$pkg/join?invitation=abc"))
            assertEquals("board://invite/xyz", s.pendingInvitation.value)
        }

    @Test
    fun `isInvitationLink`() {
        fun check(
            raw: String,
            packageName: String? = pkg,
        ) = MeroAppSession.isInvitationLink(raw, packageName)
        assertTrue(check("https://links.calimero.network/$pkg/join?invitation=t"))
        assertTrue(check("calimero://$pkg/join?invitation=t"))
        assertTrue("a mail client may change the case", check("https://links.calimero.network/COM.Example.Board/join?invitation=t"))
        assertFalse(check("https://links.calimero.network/$pkg-ar/join?invitation=t"))
        assertFalse(check("https://links.calimero.network/$pkg/join"))
        assertFalse(check("https://links.calimero.network/$pkg/join?invitation="))
        assertTrue(check("https://x.test/anything/join?invitation=t", packageName = null))
    }
}
