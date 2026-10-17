package com.calimero.mero.compose.state

import com.calimero.mero.compose.MeroSession
import com.calimero.mero.http.HttpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** mero-react `hooks/blob.test.tsx` for the file-backed blob read. */
@OptIn(ExperimentalCoroutinesApi::class)
class BlobFileStateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a blob lands in a cache file that is deleted on close`() =
        runTest {
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun getBlob(
                        blobId: String,
                        contextId: String?,
                    ) = byteArrayOf(1, 2, 3)
                }
            val state = BlobFileState(MutableStateFlow(MeroSession(admin = admin)), backgroundScope, "blob1", tmp.root, extension = "png")
            // The write runs on the IO dispatcher: wait for it.
            repeat(50) {
                runCurrent()
                if (state.file == null) Thread.sleep(10)
            }
            val file = state.file
            assertNotNull(file)
            assertTrue(file!!.name.endsWith(".png"))
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
            state.close()
            assertFalse(file.exists())
            assertNull(state.file)
        }

    @Test
    fun `a 404 is not found, and a superseded download writes nothing`() =
        runTest {
            val gate = CompletableDeferred<ByteArray>()
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun getBlob(
                        blobId: String,
                        contextId: String?,
                    ): ByteArray = gate.await()
                }
            val missingAdmin =
                object : StateFakeAdmin() {
                    override suspend fun getBlob(
                        blobId: String,
                        contextId: String?,
                    ): ByteArray = throw HttpException(404, "", emptyMap(), "u")
                }
            val sessions = MutableStateFlow(MeroSession(admin = admin))
            val state = BlobFileState(sessions, backgroundScope, "blob1", tmp.root)
            runCurrent()
            // A new session supersedes the download in flight.
            sessions.value = MeroSession(admin = missingAdmin)
            runCurrent()
            assertTrue(state.notFound)
            gate.complete(byteArrayOf(9))
            runCurrent()
            assertNull(state.file)
            assertTrue(tmp.root.listFiles()!!.isEmpty())
            state.close()
        }
}
