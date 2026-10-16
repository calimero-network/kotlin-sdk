package com.calimero.mero.presence

import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Ed25519
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.NetworkException
import com.calimero.mero.relay.IntentRefusedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Port of mero-js `presence/relay-presence.test.ts`, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayPresenceClientTest {
    private data class Sent(
        val contextId: String,
        val state: String?,
        val seq: Long,
        val sentAtMs: Long,
        val signature: String,
    )

    /** A relay that records each POST and answers with the next queued status (`null` = 204, `0` = network failure). */
    private class StubRelay(
        statuses: List<Int?> = emptyList(),
    ) : PresenceRelay {
        private val queued = statuses.toMutableList()
        val calls = CopyOnWriteArrayList<Sent>()
        var hold: CompletableDeferred<Unit>? = null
        override val authorProof = "aa"
        override val authorSigner: DeviceSigner = SeedSigner("22".repeat(32))

        override suspend fun presenceIntent(
            contextId: String,
            state: String?,
            seq: Long,
            sentAtMs: Long,
            signature: String,
        ) {
            calls += Sent(contextId, state, seq, sentAtMs, signature)
            hold?.let {
                hold = null
                it.await()
            }
            when (val status = if (queued.isEmpty()) null else queued.removeAt(0)) {
                null -> Unit
                0 -> throw NetworkException("offline")
                400, 403 -> throw IntentRefusedException("refused", retryable = false, status = status)
                else -> throw HttpException(status, "", emptyMap(), "http://relay")
            }
        }
    }

    private val ctx = "11".repeat(32)

    private fun TestScope.client(
        relay: StubRelay,
        now: Long = 1_000,
    ) = RelayPresenceClient(relay, events = { error("no event stream in this test") }, now = { now }, scope = backgroundScope)

    private fun TestScope.heartbeat() {
        advanceTimeBy(RelayPresenceClient.HEARTBEAT_MS)
        runCurrent()
    }

    private fun TestScope.settle(ms: Long = 10_000) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `resends every 2_5 s with a rising seq, and one retract stops it`() =
        runTest {
            val relay = StubRelay()
            val p = client(relay)
            p.set(ctx, mapOf("typing" to true))
            heartbeat()
            heartbeat()
            assertEquals(3, relay.calls.size)
            assertTrue(relay.calls[1].seq > relay.calls[0].seq)
            assertTrue(relay.calls[2].seq > relay.calls[1].seq)
            p.clear(ctx)
            val after = relay.calls.size
            assertNull(relay.calls.last().state)
            settle()
            assertEquals(after, relay.calls.size)
        }

    @Test
    fun `sends the encoded state as hex, signed by the device over the statement`() =
        runTest {
            val relay = StubRelay()
            val p = client(relay)
            p.set(ctx, mapOf("typing" to true))
            val sent = relay.calls.single()
            assertEquals(Hex.encode("""{"typing":true}""".encodeToByteArray()), sent.state)
            val statement =
                PresenceStatement.presenceStatementBytes(ctx, relay.authorSigner.publicKey, sent.seq, sent.sentAtMs, Hex.decodeUnsized(sent.state!!, "state"))
            assertTrue(Ed25519.verify(Hex.decode(relay.authorSigner.publicKey, "pk", 32), Hex.decode(sent.signature, "sig", 64), statement))
            p.close()
        }

    @Test
    fun `seeds seq from the wall clock, so a relay restart does not refuse it`() =
        runTest {
            val relay = StubRelay()
            val p = client(relay, now = 1_700_000_000_000)
            p.set(ctx, mapOf("typing" to false))
            assertTrue(relay.calls[0].seq >= 1_700_000_000_000)
            p.close()
        }

    @Test
    fun `does not retry a 429 at once, and stops resending after a 403`() =
        runTest {
            val relay = StubRelay(listOf(429, 403))
            val p = client(relay)
            p.set(ctx, mapOf("typing" to true)) // 429: swallowed, the resend covers it
            assertEquals(1, relay.calls.size)
            heartbeat() // the resend gets 403
            assertEquals(2, relay.calls.size)
            settle()
            assertEquals(2, relay.calls.size)
            assertFalse(p.isActive(ctx))
        }

    @Test
    fun `throws a 403 from set, and sends nothing more`() =
        runTest {
            val relay = StubRelay(listOf(403))
            val p = client(relay)
            val error = runCatching { p.set(ctx, mapOf("typing" to true)) }.exceptionOrNull()
            assertEquals(403, (error as IntentRefusedException).status)
            settle()
            assertEquals(1, relay.calls.size)
        }

    @Test
    fun `stops every resend on close`() =
        runTest {
            val relay = StubRelay()
            val p = client(relay)
            p.set(ctx, mapOf("typing" to true))
            p.close()
            settle()
            assertEquals(1, relay.calls.size)
        }

    @Test
    fun `opens the event stream only when subscribed to`() =
        runTest {
            var asked = 0
            val p =
                RelayPresenceClient(StubRelay(), events = {
                    asked++
                    error("no event stream in this test")
                }, now = { 1_000 }, scope = backgroundScope)
            assertEquals(0, asked)
            assertTrue(runCatching { p.subscribe<String>(ctx) }.exceptionOrNull()?.message!!.contains("no event stream"))
            assertEquals(1, asked)
        }

    @Test
    fun `a retract made while an earlier set is in flight is not undone by it`() =
        runTest {
            val relay = StubRelay()
            val release = CompletableDeferred<Unit>()
            relay.hold = release
            val p = client(relay)
            val typing = launch { p.set(ctx, mapOf("typing" to true)) }
            runCurrent()
            assertEquals(1, relay.calls.size)
            p.clear(ctx)
            release.complete(Unit)
            typing.join()
            settle()
            assertEquals(listOf(false, true), relay.calls.map { it.state == null })
        }

    @Test
    fun `two overlapping sets leave one heartbeat, which close stops`() =
        runTest {
            val relay = StubRelay()
            val p = client(relay)
            val a = launch { p.set(ctx, mapOf("typing" to true)) }
            val b = launch { p.set(ctx, mapOf("typing" to false)) }
            a.join()
            b.join()
            heartbeat()
            // Two heartbeats would have sent two resends here.
            assertEquals(3, relay.calls.size)
            p.close()
            val sent = relay.calls.size
            settle()
            assertEquals(sent, relay.calls.size)
        }

    @Test
    fun `keeps resending after a transient failure of the first send`() =
        runTest {
            for (status in listOf(503, 0)) {
                val relay = StubRelay(listOf(status))
                val p = client(relay)
                p.set(ctx, mapOf("typing" to true)) // not thrown: the resend covers it
                heartbeat()
                assertEquals(2, relay.calls.size)
                p.close()
            }
        }

    @Test
    fun `stops resending on a 400, which resending cannot fix`() =
        runTest {
            val relay = StubRelay(listOf(null, 400))
            val p = client(relay)
            p.set(ctx, mapOf("typing" to true))
            heartbeat()
            assertEquals(2, relay.calls.size)
            settle()
            assertEquals(2, relay.calls.size)
        }
}
