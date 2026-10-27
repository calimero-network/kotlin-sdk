package com.calimero.mero

import com.calimero.mero.admin.CreateContextRequest
import com.calimero.mero.admin.SetMetadataRequest
import com.calimero.mero.admin.UploadBlobRequest
import com.calimero.mero.auth.GenerateClientKeyRequest
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.NetworkException
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The routes [FakeNode] serves beyond auth and JSON-RPC, driven through the SDK's own clients, so a
 * shape the SDK cannot decode fails here first, and its transport errors. The Kotlin port of the
 * Swift `FakeNodeRoutesTests`.
 */
class FakeNodeRoutesTest {
    private lateinit var node: FakeNode
    private lateinit var server: MockWebServer
    private lateinit var mero: Mero

    @Before
    fun setUp() {
        node = FakeNode()
        server = node.start()
        mero = Mero(MeroConfig(baseUrl = server.url("/").toString().trimEnd('/'), tokenStore = MemoryTokenStore(), timeoutMs = 5_000))
        runBlocking { mero.authenticate(Credentials("dev", "pw")) }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- Client keys -----------------------------------------------------------------------

    @Test
    fun `a minted client key is recorded and accepted`() =
        runBlocking {
            val minted =
                mero.auth.generateClientKey(
                    GenerateClientKeyRequest(contextId = "ctx-1", permissions = listOf("context:execute"), applicationId = "app-1"),
                )

            assertTrue(minted.data.accessToken.isNotEmpty())
            assertTrue(minted.data.refreshToken.isNotEmpty())
            val key = node.clientKeys.single()
            assertEquals("ctx-1", key.contextId)
            assertEquals("app-1", key.applicationId)
            assertEquals(listOf("context:execute"), key.permissions)
            assertEquals(minted.data.accessToken, key.accessToken)

            assertTrue("the node accepts the key it minted", mero.auth.validateToken(minted.data.accessToken).valid)
        }

    @Test
    fun `minting a client key needs a session`() =
        runBlocking {
            node.revokeFamily()
            try {
                mero.auth.generateClientKey(GenerateClientKeyRequest(permissions = listOf("admin")))
                fail("expected a refusal")
            } catch (e: Exception) {
                assertTrue(node.clientKeys.isEmpty())
            }
        }

    // ---- Contexts --------------------------------------------------------------------------

    @Test
    fun `contexts are created, listed, read and deleted`() =
        runBlocking {
            assertEquals(
                "a new node holds no contexts", 0,
                mero.admin
                    .getContexts()
                    .contexts.size,
            )

            val created = mero.admin.createContext(CreateContextRequest(applicationId = "app-1", groupId = "g-1"))
            assertEquals("g-1", created.groupId)
            assertFalse(created.memberPublicKey.isEmpty())

            val listed = mero.admin.getContexts().contexts
            assertEquals(listOf(created.contextId), listed.map { it.id })
            assertEquals("app-1", listed.first().applicationId)
            assertEquals("g-1", listed.first().groupId)

            assertEquals("app-1", mero.admin.getContext(created.contextId).applicationId)

            assertTrue(mero.admin.deleteContext(created.contextId).isDeleted)
            assertEquals(emptyList<String>(), node.contextIds)
        }

    @Test
    fun `a seeded context is listed for its application`() =
        runBlocking {
            node.addContext(id = "c-a", applicationId = "app-a")
            node.addContext(id = "c-b", applicationId = "app-b")

            val forA = mero.admin.getContextsForApplication("app-a")
            assertEquals(listOf("c-a"), forA.contexts.map { it.id })
        }

    @Test
    fun `an unknown context is a 404`() =
        runBlocking {
            try {
                mero.admin.getContext("missing")
                fail("expected a 404")
            } catch (e: HttpException) {
                assertEquals(404, e.status)
            }
        }

    // ---- Applications ----------------------------------------------------------------------

    @Test
    fun `seeded applications are listed and read`() =
        runBlocking {
            node.addApplication(id = "app-1", packageName = "com.example.notes", version = "2.1.0")

            val apps = mero.admin.listApplications().apps
            assertEquals(listOf("app-1"), apps.map { it.id })
            assertEquals("com.example.notes", apps.first().packageName)
            assertEquals("2.1.0", apps.first().version)

            assertEquals(
                "com.example.notes",
                mero.admin
                    .getApplication("app-1")
                    .application
                    ?.packageName,
            )
            assertNull(mero.admin.getApplication("nope").application)
        }

    // ---- Blobs -----------------------------------------------------------------------------

    @Test
    fun `blobs are uploaded, listed, inspected and deleted`() =
        runBlocking {
            val bytes = "hello".toByteArray()

            val info = mero.admin.uploadBlob(UploadBlobRequest(data = bytes))
            assertEquals(5L, info.size)
            assertEquals(
                listOf(info.blobId),
                mero.admin
                    .listBlobs()
                    .blobs
                    .map { it.blobId },
            )
            assertArrayEquals(bytes, mero.admin.getBlob(info.blobId))
            assertEquals(5L, mero.admin.getBlobInfo(info.blobId).size)

            assertTrue(mero.admin.deleteBlob(info.blobId).deleted)
            assertNull(node.blob(info.blobId))
            try {
                mero.admin.getBlob(info.blobId)
                fail("expected a 404")
            } catch (e: HttpException) {
                assertEquals(404, e.status)
            }
        }

    @Test
    fun `a seeded blob is served`() =
        runBlocking {
            val id = node.putBlob(byteArrayOf(1, 2, 3))
            assertArrayEquals(byteArrayOf(1, 2, 3), mero.admin.getBlob(id))
        }

    // ---- Metadata --------------------------------------------------------------------------

    @Test
    fun `metadata is null until set and keeps its name across updates`() =
        runBlocking {
            assertNull(mero.admin.getGroupMetadata("g-1"))

            mero.admin.setGroupMetadata("g-1", SetMetadataRequest(name = "Team", data = mapOf("k" to "v")))
            mero.admin.setGroupMetadata("g-1", SetMetadataRequest(data = mapOf("k" to "w")))
            val group = mero.admin.getGroupMetadata("g-1")!!
            assertEquals("an omitted name keeps the current one", "Team", group.name)
            assertEquals(mapOf("k" to "w"), group.data)

            mero.admin.setContextMetadata("g-1", "c-1", SetMetadataRequest(name = "Room"))
            assertEquals("Room", mero.admin.getContextMetadata("g-1", "c-1")?.name)
            mero.admin.setMemberMetadata("g-1", "m-1", SetMetadataRequest(name = "Ana"))
            assertEquals("Ana", mero.admin.getMemberMetadata("g-1", "m-1")?.name)
            assertEquals("each target has its own record", "Team", mero.admin.getGroupMetadata("g-1")?.name)
        }

    // ---- Transport errors ------------------------------------------------------------------

    @Test
    fun `an offline node fails at the transport`() =
        runBlocking {
            node.goOffline()
            try {
                mero.admin.getContexts()
                fail("expected a transport error")
            } catch (e: NetworkException) {
                // No HTTP response at all: not a status, not a refusal.
            }

            node.goOnline()
            assertEquals(
                0,
                mero.admin
                    .getContexts()
                    .contexts.size,
            )
        }

    @Test
    fun `a delayed protected response still arrives`() =
        runBlocking {
            node.delayProtectedResponses(listOf(50L))
            val started = System.nanoTime()
            assertEquals(
                0,
                mero.admin
                    .getContexts()
                    .contexts.size,
            )
            assertTrue((System.nanoTime() - started) / 1_000_000 >= 50)
        }
}
