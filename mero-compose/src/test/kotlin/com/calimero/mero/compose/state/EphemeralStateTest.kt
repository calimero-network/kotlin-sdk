package com.calimero.mero.compose.state

import com.calimero.mero.admin.GetContextIdentitiesResponseData
import com.calimero.mero.compose.MeroSession
import com.calimero.mero.compose.PresenceSurface
import com.calimero.mero.ephemeral.Codec
import com.calimero.mero.ephemeral.EphemeralEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of mero-react `hooks/ephemeral.test.tsx`. */
@OptIn(ExperimentalCoroutinesApi::class)
class EphemeralStateTest {
    /** The presence slice the tests publish: a cursor. */
    data class Cursor(
        val x: Int = 0,
        val y: Int = 0,
    )

    private object NoCodec : Codec<Cursor> {
        override fun encode(value: Cursor) = ByteArray(0)

        override fun decode(bytes: ByteArray) = Cursor()
    }

    @Suppress("UNCHECKED_CAST")
    private class FakePresence : PresenceSurface {
        val entries = MutableSharedFlow<EphemeralEntry<Cursor>>(extraBufferCapacity = 64)
        val published = mutableListOf<Pair<String, Cursor>>()
        var subscribers = 0
        var setFails: Throwable? = null

        override suspend fun <T> set(
            contextId: String,
            state: T,
            codec: Codec<T>,
        ) {
            setFails?.let { throw it }
            published += contextId to (state as Cursor)
        }

        override fun <T> subscribe(
            contextId: String,
            codec: Codec<T>,
        ): Flow<EphemeralEntry<T>> =
            (entries.onSubscription { subscribers++ }.onCompletion { subscribers-- }) as Flow<EphemeralEntry<T>>
    }

    private class Rig(
        self: String? = "me",
    ) {
        val presence = FakePresence()
        val identity = CompletableDeferred<String?>().apply { complete(self) }
        val admin =
            object : StateFakeAdmin() {
                override suspend fun getContextIdentitiesOwned(contextId: String) =
                    GetContextIdentitiesResponseData(listOfNotNull(identity.await()))
            }
        val sessions = MutableStateFlow(MeroSession(admin = admin, rawAdmin = admin, presence = presence))
    }

    private fun TestScope.state(
        rig: Rig,
        contextId: String? = "ctx",
        includeSelf: Boolean = false,
        throttleMs: Long = 30,
        initial: Cursor? = null,
    ) = EphemeralState(rig.sessions, backgroundScope, contextId, NoCodec, initial, throttleMs, includeSelf, clock = { testScheduler.currentTime })

    private fun entry(
        author: String,
        x: Int = 1,
        ageMs: Long? = null,
        removed: Boolean = false,
    ) = EphemeralEntry(author, state = if (removed) null else Cursor(x), removed = removed, ageMs = ageMs)

    @Test
    fun `peers are seeded from a replay, updated by deltas and dropped on removal`() =
        runTest {
            val rig = Rig()
            val s = state(rig)
            runCurrent()
            rig.presence.entries.emit(entry("alice", 1, ageMs = 200))
            runCurrent()
            assertEquals(mapOf("alice" to Cursor(1)), s.peers.value)
            rig.presence.entries.emit(entry("alice", 2))
            rig.presence.entries.emit(entry("bob", 3))
            runCurrent()
            assertEquals(mapOf("alice" to Cursor(2), "bob" to Cursor(3)), s.peers.value)
            rig.presence.entries.emit(entry("bob", removed = true))
            runCurrent()
            assertEquals(setOf("alice"), s.peers.value.keys)
            s.close()
        }

    @Test
    fun `age comes from a replayed entry's ageMs, a live delta is fresh, and 0 is fresh too`() =
        runTest {
            val rig = Rig()
            val s = state(rig)
            runCurrent()
            rig.presence.entries.emit(entry("old", ageMs = 4_000))
            rig.presence.entries.emit(entry("live"))
            rig.presence.entries.emit(entry("zero", ageMs = 0))
            runCurrent()
            assertEquals(4_000L, s.ageOf("old"))
            assertEquals(0L, s.ageOf("live"))
            assertEquals(0L, s.ageOf("zero"))
            assertNull(s.ageOf("nobody"))
            advanceTimeBy(1_000)
            assertEquals(1_000L, s.ageOf("live"))
            s.close()
        }

    @Test
    fun `a null context subscribes to nothing, and close unsubscribes`() =
        runTest {
            val rig = Rig()
            val none = state(rig, contextId = null)
            runCurrent()
            assertEquals(0, rig.presence.subscribers)
            none.close()
            val s = state(rig)
            runCurrent()
            assertEquals(1, rig.presence.subscribers)
            s.close()
            runCurrent()
            assertEquals(0, rig.presence.subscribers)
        }

    @Test
    fun `a connection with no presence surface says so`() =
        runTest {
            val rig = Rig()
            rig.sessions.value = MeroSession(admin = rig.admin)
            val s = state(rig)
            runCurrent()
            assertEquals(EphemeralState.NO_PRESENCE_SURFACE, s.error.value!!.message)
            // ...until a client that has one arrives.
            rig.sessions.value = MeroSession(admin = rig.admin, rawAdmin = rig.admin, presence = rig.presence)
            runCurrent()
            assertNull(s.error.value)
            s.close()
        }

    @Test
    fun `setPresence merges over the last value and publishes the union`() =
        runTest {
            val rig = Rig()
            val s = state(rig, throttleMs = 0, initial = Cursor(0, 0))
            runCurrent()
            s.setPresence { it!!.copy(x = 5) }
            s.setPresence { it!!.copy(y = 9) }
            runCurrent()
            assertEquals(listOf("ctx" to Cursor(5, 0), "ctx" to Cursor(5, 9)), rig.presence.published)
            s.close()
        }

    @Test
    fun `throttling publishes the LATEST value, not the first`() =
        runTest {
            val rig = Rig()
            val s = state(rig, throttleMs = 30)
            runCurrent()
            s.setPresence(Cursor(1))
            s.setPresence(Cursor(2))
            s.setPresence(Cursor(3))
            runCurrent()
            assertTrue(rig.presence.published.isEmpty())
            advanceTimeBy(31)
            runCurrent()
            assertEquals(listOf("ctx" to Cursor(3)), rig.presence.published)
            s.close()
        }

    @Test
    fun `your own echo is excluded by default and included with includeSelf`() =
        runTest {
            val rig = Rig(self = "me")
            val s = state(rig)
            val all = state(rig, includeSelf = true)
            runCurrent()
            rig.presence.entries.emit(entry("me"))
            rig.presence.entries.emit(entry("bob"))
            runCurrent()
            assertEquals(setOf("bob"), s.peers.value.keys)
            assertEquals(setOf("me", "bob"), all.peers.value.keys)
            s.close()
            all.close()
        }

    @Test
    fun `self is dropped retroactively when the identity resolves after its entry arrived`() =
        runTest {
            val rig = Rig()
            val late = CompletableDeferred<String?>()
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun getContextIdentitiesOwned(contextId: String) = GetContextIdentitiesResponseData(listOfNotNull(late.await()))
                }
            rig.sessions.value = MeroSession(admin = admin, rawAdmin = admin, presence = rig.presence)
            val s = state(rig)
            runCurrent()
            rig.presence.entries.emit(entry("me"))
            runCurrent()
            assertEquals(setOf("me"), s.peers.value.keys)
            late.complete("me")
            runCurrent()
            assertTrue(s.peers.value.isEmpty())
            s.close()
        }

    @Test
    fun `an unresolved identity is an error only while self-filtering needs it`() =
        runTest {
            val rig = Rig(self = null)
            val s = state(rig)
            val all = state(rig, includeSelf = true)
            runCurrent()
            assertEquals(EphemeralState.COULD_NOT_RESOLVE_IDENTITY, s.error.value!!.message)
            assertNull(all.error.value)
            s.close()
            all.close()
        }

    @Test
    fun `a later replay that no longer mentions a peer drops it, live arrivals in the window survive`() =
        runTest {
            val rig = Rig()
            val s = state(rig)
            runCurrent()
            rig.presence.entries.emit(entry("alice", ageMs = 10))
            rig.presence.entries.emit(entry("bob", ageMs = 10))
            runCurrent()
            advanceTimeBy(EphemeralState.REPLAY_RECONCILE_MS + 1)
            runCurrent()
            assertEquals(setOf("alice", "bob"), s.peers.value.keys)
            // Reconnect: the replay lists only alice; carol publishes live meanwhile.
            rig.presence.entries.emit(entry("alice", ageMs = 10))
            rig.presence.entries.emit(entry("carol"))
            runCurrent()
            advanceTimeBy(EphemeralState.REPLAY_RECONCILE_MS + 1)
            runCurrent()
            assertEquals(setOf("alice", "carol"), s.peers.value.keys)
            assertNull(s.ageOf("bob"))
            s.close()
        }

    @Test
    fun `only live deltas never sweep, and a replayed removal is a removal`() =
        runTest {
            val rig = Rig()
            val s = state(rig)
            runCurrent()
            rig.presence.entries.emit(entry("alice"))
            rig.presence.entries.emit(entry("bob"))
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, s.peers.value.size)
            rig.presence.entries.emit(entry("alice", ageMs = 5, removed = true))
            runCurrent()
            assertEquals(setOf("bob"), s.peers.value.keys)
            s.close()
        }

    @Test
    fun `a failed publish is reported and cleared by the next success`() =
        runTest {
            val rig = Rig()
            val s = state(rig, throttleMs = 0)
            runCurrent()
            rig.presence.setFails = IllegalStateException("SliceTooLarge")
            s.setPresence(Cursor(1))
            runCurrent()
            assertEquals("SliceTooLarge", s.error.value!!.message)
            rig.presence.setFails = null
            s.setPresence(Cursor(2))
            runCurrent()
            assertNull(s.error.value)
            assertNotNull(rig.presence.published.single())
            s.close()
        }
}
