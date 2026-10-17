package com.calimero.mero.account

import com.calimero.mero.relay.PersistedNonceSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** mero-js `session.ts` relay map, `govern.ts` group namespaces and `nonces.test.ts`. */
class RelayMapTest {
    private val store = MemorySecureStore()
    private val account = CloudAccount(store)
    private val acc = "aa".repeat(32)
    private val session = DelegatedSession(acc, "dd".repeat(16), "00", relayUrl = "https://relay-a.example/")

    @Test
    fun `remembers which relay serves a namespace and a context, under mero-js's key`() {
        account.rememberRelay(acc, "https://relay-b.example/", namespaceId = "n1", contextId = "c1")
        assertEquals(RelayMap(mapOf("n1" to "https://relay-b.example"), mapOf("c1" to "https://relay-b.example")), account.readRelayMap(acc))
        assertEquals("https://relay-b.example", account.relayForContext(acc, "c1"))
        assertNull(account.relayForContext(acc, "c2"))
        assertEquals(
            """{"namespaces":{"n1":"https://relay-b.example"},"contexts":{"c1":"https://relay-b.example"}}""",
            store.get("calimero.delegated.relays.$acc"),
        )
    }

    @Test
    fun `an unreadable map reads as empty`() {
        store.put("calimero.delegated.relays.$acc", "not json")
        assertEquals(RelayMap(), account.readRelayMap(acc))
    }

    @Test
    fun `knows every relay, the session's first, then the map's, each once`() {
        account.rememberRelay(acc, "https://relay-b.example", namespaceId = "n1")
        account.rememberRelay(acc, "https://relay-a.example", contextId = "c1")
        account.rememberRelay(acc, "https://relay-c.example/", contextId = "c2")
        assertEquals(
            listOf("https://relay-a.example", "https://relay-b.example", "https://relay-c.example"),
            account.knownRelays(session),
        )
        assertEquals(
            listOf("https://relay-b.example", "https://relay-a.example", "https://relay-c.example"),
            account.knownRelays(session.copy(relayUrl = null)),
        )
    }

    @Test
    fun `carries the executor account to the same relay and leaves it behind on another`() {
        val prev = session.copy(executorAccount = "ee".repeat(32))
        val same = session.copy(relayUrl = "https://relay-a.example")
        assertEquals("ee".repeat(32), carryExecutorAccount(prev, same).executorAccount)
        val other = session.copy(relayUrl = "https://relay-b.example")
        assertSame(other, carryExecutorAccount(prev, other))
        val named = same.copy(executorAccount = "ff".repeat(32))
        assertSame(named, carryExecutorAccount(prev, named))
        assertSame(same, carryExecutorAccount(null, same))
    }

    @Test
    fun `a group's namespace is itself when the account is in it, else what was remembered`() {
        assertNull(account.namespaceOfGroup(session, "g1"))
        account.rememberRelay(acc, "https://relay-a.example", namespaceId = "n1")
        assertEquals("n1", account.namespaceOfGroup(session, "n1"))
        account.rememberGroupNamespace(acc, "g1", "n1")
        assertEquals("n1", account.namespaceOfGroup(session, "g1"))
        // Persisted, so another CloudAccount over the same store knows it too.
        assertEquals("n1", CloudAccount(store).namespaceOfGroup(session, "g1"))
    }

    @Test
    fun `draws one rising sequence per relay however many clients ask, and keeps relays apart`() =
        runBlocking {
            val a1 = account.persistedNonces("https://relay-a.example")
            val a2 = account.persistedNonces("https://relay-a.example/")
            assertEquals(1uL, a1.next())
            assertEquals(2uL, a2.next())
            assertEquals(3uL, a1.next())
            assertEquals(1uL, account.persistedNonces("https://relay-b.example").next())
        }

    @Test
    fun `starts above a creation nonce spent in a new context`() =
        runBlocking {
            account.markContextNonceSpent("https://relay-a.example", "c1", 41uL)
            assertEquals(42uL, PersistedNonceSource.forRelay(store, "https://relay-a.example").next())
            // A lower one moves nothing.
            account.markContextNonceSpent("https://relay-a.example", "c2", 3uL)
            assertEquals(43uL, account.persistedNonces("https://relay-a.example").next())
        }
}
