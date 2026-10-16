package com.calimero.mero.compose.state

import com.calimero.mero.compose.MeroSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * A blob's bytes as a file in [cacheDir], ready for an image loader, a share intent (through a
 * `FileProvider`) or a viewer — mero-react `useBlobUrl`, with a cached file standing in for the
 * browser's object URL.
 *
 * The file's lifecycle is handled here: it is deleted when the session changes, on [refetch]'s
 * replacement and on [close], and [file] goes back to null at once, so a deleted file is never
 * handed out as the current one. An answer for a superseded request writes nothing.
 *
 * Without [contextId] a local read; with one, discovery among the context's peers (up to core's
 * ~30 s deadline) plus the transfer — so render [ResourceState.loading]. [notFound] marks the
 * node's 404 ("no peer holds this") apart from a transport failure; `error` is set for both.
 */
class BlobFileState(
    private val sessions: StateFlow<MeroSession>,
    scope: CoroutineScope,
    private val blobId: String?,
    private val cacheDir: File,
    private val contextId: String? = null,
    /** The file's extension (`"png"`), for consumers that go by name; the blob read carries no type. */
    private val extension: String? = null,
) : AutoCloseable {
    private val _state = MutableStateFlow(ResourceState<File?>(null))
    val state: StateFlow<ResourceState<File?>> = _state.asStateFlow()
    val file: File? get() = _state.value.data

    /** The node answered 404: no holder. */
    val notFound: Boolean get() = isBlobNotFound(_state.value.error)

    private val sequence = AtomicLong()
    private val lock = Any()
    private var current: File? = null

    private val binding: Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.map { it.admin }.distinctUntilChanged().collect {
                sequence.incrementAndGet()
                discard()
                _state.value = ResourceState(null)
                launch { refetch() }
            }
        }

    /** Download again; the file, once written, replaces the previous one. */
    suspend fun refetch() {
        val seq = sequence.incrementAndGet()
        val admin = sessions.value.admin ?: return
        val id = blobId ?: return
        _state.value = _state.value.copy(loading = true, error = null)
        try {
            val bytes = admin.getBlob(id, contextId)
            // Checked BEFORE writing: a loser writes nothing, so there is nothing to leak.
            if (seq != sequence.get()) return
            val written = withContext(Dispatchers.IO) { write(id, bytes) }
            if (seq != sequence.get()) {
                written.delete()
                return
            }
            synchronized(lock) {
                current?.delete()
                current = written
            }
            _state.value = ResourceState(written)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (seq == sequence.get()) _state.value = ResourceState(null, loading = false, error = e)
        } finally {
            if (seq == sequence.get()) _state.value = _state.value.copy(loading = false)
        }
    }

    private fun write(
        id: String,
        bytes: ByteArray,
    ): File {
        cacheDir.mkdirs()
        val safe = id.filter { it.isLetterOrDigit() }.take(MAX_NAME)
        val out = File.createTempFile("blob-$safe-", extension?.let { ".$it" } ?: ".bin", cacheDir)
        out.writeBytes(bytes)
        return out
    }

    private fun discard() {
        synchronized(lock) {
            current?.delete()
            current = null
        }
    }

    override fun close() {
        binding.cancel()
        sequence.incrementAndGet()
        discard()
        _state.value = ResourceState(null)
    }

    private companion object {
        const val MAX_NAME = 32
    }
}
