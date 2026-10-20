package com.calimero.mero

import com.calimero.mero.admin.UploadBlobRequest
import com.calimero.mero.http.OkHttpTransport
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.testkit.FakeNode
import com.calimero.mero.transport.BearerHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Blob bodies are binary and must reach the node, and come back from it, byte for byte.
 *
 * `uploadBlob` used to send `String(bytes, ISO_8859_1)`, which OkHttp then encoded as UTF-8,
 * and `getBlob` read the body as UTF-8 text: every byte `>= 0x80` was rewritten both ways. A
 * round trip through the SDK hid it (the two errors cancel), but the node stored the wrong
 * bytes. These tests look at the bytes on the wire, not at a round trip.
 */
class BlobBytesTest {
    private lateinit var server: MockWebServer
    private lateinit var mero: Mero

    /** Every byte value once, 0x00 to 0xFF. */
    private val allBytes = ByteArray(256) { it.toByte() }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        mero = meroAt(server)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun meroAt(server: MockWebServer) =
        Mero(MeroConfig(baseUrl = server.url("/").toString().trimEnd('/'), tokenStore = MemoryTokenStore(), timeoutMs = 5_000))

    @Test
    fun `uploadBlob sends the raw bytes as the request body`() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"blob_id":"b-1","size":256}}"""),
        )

        val info = runBlocking { mero.admin.uploadBlob(UploadBlobRequest(data = allBytes, contextId = "ctx-1")) }

        val req = server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request")
        assertEquals("PUT", req.method)
        assertEquals("/admin-api/blobs?context_id=ctx-1", req.path)
        assertEquals("application/octet-stream", req.getHeader("Content-Type"))
        assertEquals(256L, req.bodySize)
        assertArrayEquals(allBytes, req.body.readByteArray())
        assertEquals("b-1", info.blobId)
        assertEquals(256L, info.size)
    }

    @Test
    fun `getBlob returns the raw response bytes`() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(allBytes)),
        )

        val bytes = runBlocking { mero.admin.getBlob("b-1") }

        assertArrayEquals(allBytes, bytes)
    }

    @Test
    fun `the relay admin client carries blob bytes with its session`() {
        server.enqueue(MockResponse().setBody(Buffer().write(allBytes)))
        val http = BearerHttpClient(OkHttpTransport(server.url("/").toString(), OkHttpClient(), Json)) { "session-1" }

        val res = runBlocking { http.executeBytes("PUT", "/admin-api/blobs", allBytes) }

        val req = server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request")
        assertEquals("Bearer session-1", req.getHeader("Authorization"))
        assertArrayEquals(allBytes, req.body.readByteArray())
        assertArrayEquals(allBytes, res.body)
    }

    @Test
    fun `a blob round-trips through FakeNode unmodified`() {
        val node = FakeNode()
        val fake = node.start()
        try {
            val client = meroAt(fake)
            val id =
                runBlocking {
                    client.authenticate(Credentials("dev", "dev"))
                    client.admin.uploadBlob(UploadBlobRequest(data = allBytes)).blobId
                }

            // What the node stored, not what the SDK read back.
            assertArrayEquals(allBytes, node.blobs.getValue(id))
            runBlocking {
                assertArrayEquals(allBytes, client.admin.getBlob(id))
                assertEquals(256L, client.admin.getBlobInfo(id).size)
                assertEquals(
                    listOf(id),
                    client.admin
                        .listBlobs()
                        .blobs
                        .map { it.blobId },
                )
                assertEquals(true, client.admin.deleteBlob(id).deleted)
            }
        } finally {
            fake.shutdown()
        }
    }
}
