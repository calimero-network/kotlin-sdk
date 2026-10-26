package com.calimero.mero.compose

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** mero-react `LoginModal.test.tsx`'s node section, and the small view helpers. */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeLoginTest {
    @Test
    fun `a node URL is http(s) with localhost, an IPv4 or a domain`() {
        assertTrue(isValidNodeUrl("http://localhost:2428"))
        assertTrue(isValidNodeUrl("node.calimero.network"))
        assertTrue(isValidNodeUrl("http://10.0.2.2:2428/"))
        assertFalse(isValidNodeUrl("http://300.1.1.1"))
        assertFalse(isValidNodeUrl("   "))
        assertEquals("https://node.example.com", normalizeNodeUrl(" node.example.com/ "))
        assertEquals("https://localhost:2428", normalizeNodeUrl("localhost:2428"))
        assertTrue(isValidNodeUrl("HTTPS://Node.Example.com"))
    }

    @Test
    fun `a URL with a scheme keeps it, and only http(s) passes`() {
        assertEquals("ftp://node.example", normalizeNodeUrl("ftp://node.example/"))
        assertFalse(isValidNodeUrl("ftp://node.example"))
        assertFalse(isValidNodeUrl("ws://node.example"))
        assertFalse(isValidNodeUrl("calimero://node.example"))
        assertTrue(isValidNodeUrl("https://node.example"))
        assertEquals("localhost:2428", displayNodeUrl("http://localhost:2428/"))
    }

    @Test
    fun `discovered nodes are offered first, the first selected, and connect at once`() =
        runTest {
            val probed = mutableListOf<String>()
            val login =
                NodeLoginState(backgroundScope, discover = { listOf("http://10.0.2.2:2428", "http://10.0.2.2:2429") }, probe = {
                    probed += it
                    true
                })
            login.rescan()
            assertTrue(login.discovering.value)
            assertFalse(login.canConnect)
            runCurrent()
            assertEquals(2, login.discovered.value.size)
            assertEquals("http://10.0.2.2:2428", login.selected.value)
            assertEquals("http://10.0.2.2:2428", login.connect())
            assertEquals(emptyList<String>(), probed)
        }

    @Test
    fun `with nothing found the manual URL is used, once the node answers`() =
        runTest {
            var answers = false
            val login = NodeLoginState(backgroundScope, initialUrl = "node.example.com", discover = { emptyList() }, probe = { answers })
            login.rescan()
            runCurrent()
            assertNull(login.selected.value)
            assertTrue(login.canConnect)
            assertNull(login.connect())
            assertEquals("Failed to connect. Please check the URL and try again.", login.error.value)
            answers = true
            assertEquals("https://node.example.com", login.connect())
            assertNull(login.error.value)
            login.setCustomUrl("not a url!")
            assertFalse(login.canConnect)
            assertNull(login.connect())
        }

    @Test
    fun `a typed node across the network must be https`() =
        runTest {
            val login = NodeLoginState(backgroundScope, initialUrl = "http://node.example:2428", discover = { emptyList() }, probe = { true })
            assertTrue("a password would cross the network in the clear", login.isInsecure)
            assertNull(login.connect())
            assertEquals(MeroClient.INSECURE_NODE_URL, login.error.value)
            for (url in listOf("https://node.example", "node.example", "http://192.168.1.5:2428", "http://localhost:2428")) {
                login.setCustomUrl(url)
                assertFalse(url, login.isInsecure)
            }
        }

    @Test
    fun `a failed scan falls back to the manual URL`() =
        runTest {
            val login = NodeLoginState(backgroundScope, discover = { error("offline") })
            login.rescan()
            runCurrent()
            assertFalse(login.discovering.value)
            assertEquals(emptyList<String>(), login.discovered.value)
        }

    @Test
    fun `view helpers`() {
        assertEquals("abcdef…123456", shortAccount("abcdef" + "0".repeat(52) + "123456"))
        assertEquals("short", shortAccount("short"))
        assertEquals("You have 1 entry to re-sign after a recent upgrade.", migrationPendingMessage(1))
        assertEquals("You have 3 entries to re-sign after a recent upgrade.", migrationPendingMessage(3))
    }
}
