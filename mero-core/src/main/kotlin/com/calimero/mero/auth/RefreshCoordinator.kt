package com.calimero.mero.auth

import com.calimero.mero.TokenData
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.storage.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Serializes token refresh so a single-use refresh token (core#3083) is never replayed. Replaying a
 * consumed refresh token makes the node revoke the entire family → forced re-login. Three layers,
 * matching mero-js's `navigator.locks` + `refreshPromise` + store re-read:
 *
 *  1. **In-process single-flight** — a coroutine [Mutex] guarding a cached [Deferred]; concurrent
 *     401s await the same refresh instead of each firing one.
 *  2. **Cross-process lock** — an exclusive [java.nio.channels.FileLock] on [lockFile], since a
 *     coroutine Mutex doesn't span processes (WorkManager job, sync adapter, `android:process`).
 *  3. **Re-read inside the lock** — once held, re-read the store; if the stored access token differs
 *     from the one that triggered this refresh, another process already rotated — adopt it rather
 *     than replay a consumed refresh token.
 *
 * Refresh is **reactive only** (never proactive): the [TokenAuthenticator] drives it on a 401.
 */
class RefreshCoordinator(
    private val tokenStore: TokenStore,
    /** Performs the actual `/auth/refresh` call for the given (current) bundle. */
    private val refreshCall: suspend (TokenData) -> TokenData,
    private val lockFile: File? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val mutex = Mutex()
    private var inFlight: Deferred<TokenData>? = null

    /**
     * Refresh the bundle. [triggeringAccessToken] is the access token carried by the request that
     * hit the 401; if the store already holds a different one, another refresher won the race.
     */
    suspend fun refresh(triggeringAccessToken: String?): TokenData {
        val deferred =
            mutex.withLock {
                inFlight ?: scope.async { runLocked(triggeringAccessToken) }.also { inFlight = it }
            }
        try {
            return deferred.await()
        } finally {
            mutex.withLock { if (inFlight === deferred) inFlight = null }
        }
    }

    private suspend fun runLocked(triggering: String?): TokenData =
        withCrossProcessLock {
            val stored = tokenStore.getTokens()
            if (stored != null && triggering != null && stored.accessToken != triggering) {
                // Someone else already rotated. Reuse their bundle rather than replaying our
                // (now consumed) refresh token, which would revoke the family.
                stored
            } else {
                val current =
                    stored ?: throw MeroStateException("No refresh token available")
                val refreshed = refreshCall(current)
                // The refresh token rotates on every refresh — persist the new one immediately or
                // the next refresh replays a consumed token.
                tokenStore.setTokens(refreshed)
                refreshed
            }
        }

    private suspend fun <T> withCrossProcessLock(block: suspend () -> T): T {
        val file = lockFile ?: return block()
        return withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { raf ->
                val channel = raf.channel
                val lock = channel.lock() // blocking, exclusive, cross-process
                try {
                    block()
                } finally {
                    lock.release()
                }
            }
        }
    }
}
