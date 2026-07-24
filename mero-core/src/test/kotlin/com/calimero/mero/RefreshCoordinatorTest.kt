package com.calimero.mero

import com.calimero.mero.auth.RefreshCoordinator
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RefreshCoordinatorTest {
    private fun store(
        access: String,
        refresh: String,
    ) =
        MemoryTokenStore().apply { setTokens(TokenData(access, refresh, Long.MAX_VALUE)) }

    @Test
    fun `concurrent refreshes collapse into a single network call`() =
        runBlocking {
            val store = store("old", "r1")
            val calls = AtomicInteger(0)
            val coordinator =
                RefreshCoordinator(
                    tokenStore = store,
                    refreshCall = { _ ->
                        calls.incrementAndGet()
                        delay(200) // hold the flight long enough for all callers to join
                        TokenData("new", "r2", Long.MAX_VALUE)
                    },
                )

            val results = (1..20).map { async(Dispatchers.Default) { coordinator.refresh("old") } }.awaitAll()

            assertEquals(1, calls.get())
            results.forEach { assertEquals("new", it.accessToken) }
            assertEquals("new", store.getTokens()?.accessToken)
            assertEquals("r2", store.getTokens()?.refreshToken)
        }

    @Test
    fun `adopts a bundle another process already rotated instead of replaying`() =
        runBlocking {
            // Store already holds a different (newer) access token than the one that triggered us.
            val store = store("already-rotated", "r2")
            val calls = AtomicInteger(0)
            val coordinator =
                RefreshCoordinator(
                    tokenStore = store,
                    refreshCall = { _ ->
                        calls.incrementAndGet()
                        TokenData("should-not-happen", "r3", Long.MAX_VALUE)
                    },
                )

            val result = coordinator.refresh(triggeringAccessToken = "old-consumed")

            assertEquals(0, calls.get())
            assertEquals("already-rotated", result.accessToken)
        }
}
