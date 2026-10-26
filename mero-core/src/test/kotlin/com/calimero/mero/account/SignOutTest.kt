package com.calimero.mero.account

import com.calimero.mero.relay.PersistedNonceSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/** What [CloudAccount.signOut] leaves behind, with and without `forgetDevice` (SEC-11). */
class SignOutTest {
    private val store = MemorySecureStore()
    private val account = CloudAccount(store)
    private val acc = "aa".repeat(32)
    private val relay = "https://relay-a.example"
    private val otherRelay = "https://relay-b.example"
    private val session = DelegatedSession(acc, "dd".repeat(16), "00", relayUrl = "$relay/")

    private fun signedIn() {
        account.saveSession(session)
        account.deviceKeys.loadOrCreate()
        account.rememberRelay(acc, otherRelay, namespaceId = "n1", contextId = "c1")
        account.rememberGroupNamespace(acc, "g1", "n1")
        account.pinRelayNodeKey(relay, "ab".repeat(32))
        account.pinRelayNodeKey(otherRelay, "cd".repeat(32))
        runBlocking {
            account.persistedNonces(relay).next()
            PersistedNonceSource(store, CloudAccount.joinNonceKey(acc)).next()
            account.governanceNonce(relay, "g1").next()
        }
    }

    @Test
    fun `forgetting an account drops its map, groups and relay pins only`() {
        account.rememberRelay(acc, relay, namespaceId = "n1", contextId = "c1")
        account.rememberGroupNamespace(acc, "g1", "n1")
        account.pinRelayNodeKey(relay, "ab".repeat(32))
        account.pinRelayNodeKey(otherRelay, "cd".repeat(32))
        account.pinRelayNodeKey("https://unrelated.example", "ef".repeat(32))
        val other = "bb".repeat(32)
        account.rememberRelay(other, otherRelay, namespaceId = "n2")

        account.forgetAccount(acc, listOf("$otherRelay/"))

        assertEquals(RelayMap(), account.readRelayMap(acc))
        assertNull(account.namespaceOfGroup(session, "g1"))
        assertNull(account.readPinnedRelayNodeKey(relay))
        assertNull(account.readPinnedRelayNodeKey(otherRelay))
        assertEquals("ef".repeat(32), account.readPinnedRelayNodeKey("https://unrelated.example"))
        assertEquals("another account's map stays", mapOf("n2" to otherRelay), account.readRelayMap(other).namespaces)
    }

    @Test
    fun `sign-out forgets the account but keeps the device and its nonces`() {
        signedIn()
        val keys = account.deviceKeys.load()
        account.signOut()

        assertNull(account.session())
        assertEquals(RelayMap(), account.readRelayMap(acc))
        assertNull(account.namespaceOfGroup(session, "g1"))
        assertNull("the session's relay", account.readPinnedRelayNodeKey(relay))
        assertNull("a relay in its map", account.readPinnedRelayNodeKey(otherRelay))
        assertEquals("the device key stays", keys, account.deviceKeys.load())
        assertEquals("nonces spent under that key stay with it", 2uL, runBlocking { account.persistedNonces(relay).next() })
    }

    @Test
    fun `sign-out forgetting the device drops its keys and every nonce`() {
        signedIn()
        account.signOut(forgetDevice = true)

        assertNull(account.session())
        assertEquals(RelayMap(), account.readRelayMap(acc))
        assertNull(account.readPinnedRelayNodeKey(relay))
        assertNull("the next sign-in certifies a fresh key", account.deviceKeys.load())
        assertEquals("a fresh key starts a fresh sequence", 1uL, runBlocking { account.persistedNonces(relay).next() })
        assertNull(store.get(CloudAccount.joinNonceKey(acc)))
        assertNull(store.get("calimero.governance-nonce.$relay.g1"))
    }

    @Test
    fun `a store that cannot list its keys still loses every nonce signOut can name`() {
        val plain = KeysOnlyStore()
        val account = CloudAccount(plain)
        account.saveSession(session)
        runBlocking {
            account.persistedNonces(relay).next()
            PersistedNonceSource(plain, CloudAccount.joinNonceKey(acc)).next()
        }
        account.signOut(forgetDevice = true)
        assertNull(plain.get("calimero.nonce.$relay"))
        assertNull(plain.get(CloudAccount.joinNonceKey(acc)))
    }

    @Test
    fun `the memory store removes by prefix`() {
        store.put("calimero.nonce.a", "1")
        store.put("calimero.nonce.b", "2")
        store.put("unrelated", "keep")
        store.removeAll("calimero.nonce.")
        assertNull(store.get("calimero.nonce.a"))
        assertNull(store.get("calimero.nonce.b"))
        assertNotNull(store.get("unrelated"))
    }

    /** A [SecureStore] without [SecureStore.removeAll], as an app's own might be. */
    private class KeysOnlyStore : SecureStore {
        private val map = ConcurrentHashMap<String, String>()

        override fun get(key: String): String? = map[key]

        override fun put(
            key: String,
            value: String,
        ) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }
    }
}
