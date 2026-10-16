package com.calimero.mero.compose.invitations

import com.calimero.mero.compose.KeyValueStore
import com.calimero.mero.compose.MemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of mero-react `invitations/capture.test.ts` and `invitation-raw.test.ts`. */
class InvitationCaptureTest {
    private val store = MemoryKeyValueStore()

    /** One cold open of the app carrying [token]: a fresh capture over the persisted store. */
    private fun openWith(
        token: String,
        appKey: String? = null,
        kv: KeyValueStore = store,
    ): List<CapturedInvitation> {
        val capture = InvitationCapture(kv, appKey)
        capture.capture("calimero://com.example.app/join?invitation=$token")
        val seen = mutableListOf<CapturedInvitation>()
        capture.onInvitation { seen += it }
        return seen
    }

    @Test
    fun `an invitation link is captured and delivered`() {
        val seen = openWith("TOKEN123")
        assertEquals(1, seen.size)
        assertEquals("TOKEN123", seen[0].token)
        assertEquals("TOKEN123", seen[0].params[INVITATION_PARAM])
    }

    @Test
    fun `an invitation on a deeper page of the app is captured too`() {
        val capture = InvitationCapture(store)
        capture.capture("https://app.example.com/teams/t1/projects?invitation=DEEP")
        val got = mutableListOf<String>()
        capture.onInvitation { got += it.token }
        assertEquals(listOf("DEEP"), got)
    }

    @Test
    fun `a listener that subscribes late is replayed the invitation, once`() {
        val capture = InvitationCapture(store)
        capture.capture("calimero://com.example.app/join?invitation=LATE")
        capture.capture("calimero://com.example.app/join?invitation=LATE")
        val seen = mutableListOf<String>()
        capture.onInvitation { seen += it.token }
        capture.onInvitation { seen += "second:" + it.token }
        assertEquals(listOf("LATE"), seen)
    }

    @Test
    fun `nothing is delivered for a link without an invitation`() {
        val capture = InvitationCapture(store)
        capture.capture("https://app.example.com/")
        capture.capture("https://app.example.com/teams")
        capture.capture(null as String?)
        val seen = mutableListOf<String>()
        capture.onInvitation { seen += it.token }
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun `an unacked invitation survives a restart, and an acked one does not`() {
        val first = InvitationCapture(store)
        first.capture("calimero://com.example.app/join?invitation=KEEP")
        val again = mutableListOf<CapturedInvitation>()
        InvitationCapture(store).onInvitation { again += it }
        assertEquals("KEEP", again.single().token)
        again.single().resolve()
        val third = mutableListOf<String>()
        InvitationCapture(store).onInvitation { third += it.token }
        assertEquals(emptyList<String>(), third)
    }

    @Test
    fun `acking an invitation replayed after a relaunch clears the stored link and the attempt ledger`() {
        InvitationCapture(store).capture("calimero://com.example.app/join?invitation=RELAUNCH")
        // A relaunch: the first capture is gone, a fresh one replays the stored link.
        var replayed: CapturedInvitation? = null
        InvitationCapture(store).onInvitation { replayed = it }
        assertTrue(store.get("calimero:invitation-attempts")!!.contains("RELAUNCH"))
        // Only the invitation is kept; nothing else holds the capture that made it.
        replayed!!.resolve()
        assertFalse(store.get("calimero:invitation-attempts")!!.contains("RELAUNCH"))
        assertEquals(PendingIntentStore(store).all(), emptyList<StoredIntent>())
        val third = mutableListOf<String>()
        InvitationCapture(store).onInvitation { third += it.token }
        assertEquals(emptyList<String>(), third)
    }

    @Test
    fun `auto-join stops after the attempt cap, but the token is still handed over`() {
        repeat(MAX_AUTO_JOIN_ATTEMPTS) { assertTrue(openWith("LOOPY")[0].autoJoin) }
        val exhausted = openWith("LOOPY")[0]
        assertFalse(exhausted.autoJoin)
        assertEquals("LOOPY", exhausted.token)
    }

    @Test
    fun `attempts count per invitation, are forgotten on ack, and are kept per app`() {
        repeat(MAX_AUTO_JOIN_ATTEMPTS) { openWith("ONE") }
        assertFalse(openWith("ONE")[0].autoJoin)
        assertTrue(openWith("TWO")[0].autoJoin)

        openWith("USED")
        openWith("USED")[0].resolve()
        assertFalse(store.get("calimero:invitation-attempts")!!.contains("USED"))
        assertTrue(openWith("USED")[0].autoJoin)

        val shared = MemoryKeyValueStore()
        repeat(MAX_AUTO_JOIN_ATTEMPTS) { openWith("SHARED", "app-a", shared) }
        assertFalse(openWith("SHARED", "app-a", shared)[0].autoJoin)
        assertTrue(openWith("SHARED", "app-b", shared)[0].autoJoin)
    }

    @Test
    fun `a store that throws still lets the invitation through this session`() {
        val broken =
            object : KeyValueStore {
                override fun get(key: String): String = error("blocked")

                override fun put(
                    key: String,
                    value: String,
                ) = error("blocked")

                override fun remove(key: String) = error("blocked")
            }
        val capture = InvitationCapture(broken)
        capture.capture("calimero://com.example.app/join?invitation=NOSTORE")
        val seen = mutableListOf<String>()
        capture.onInvitation { seen += it.token }
        assertEquals(listOf("NOSTORE"), seen)
    }

    @Test
    fun `a warm link for this app is joined, one for another app is acked and dropped`() {
        val capture = InvitationCapture(store, "app", listOf("com.calimero.docs"))
        val seen = mutableListOf<String>()
        capture.onInvitation { seen += it.token }
        capture.capture("calimero://com.calimero.docs/join?invitation=WARM")
        capture.capture("calimero://com.calimero.chat/join?invitation=FOREIGN")
        capture.capture("https://links.calimero.network/com.calimero.chat/join?invitation=FOREIGN2")
        assertEquals(listOf("WARM"), seen)
        seen.clear()
        // The foreign ones do not replay on the next launch.
        InvitationCapture(store, "app", listOf("com.calimero.docs")).onInvitation { seen += it.token }
        assertEquals(listOf("WARM"), seen)
    }

    @Test
    fun `a watcher learns an invitation is pending without taking it from the redeemer`() {
        val capture = InvitationCapture(store, "app")
        var changes = 0
        val off = capture.subscribeInvitation { changes++ }
        assertNull(capture.peekInvitation())
        capture.capture("calimero://com.example.app/join?invitation=WATCHED")
        assertTrue(changes > 0)
        assertEquals("WATCHED", capture.peekInvitation()!!.token)
        val seen = mutableListOf<String>()
        capture.onInvitation { seen += it.token }
        assertEquals(listOf("WATCHED"), seen)
        val before = changes
        capture.peekInvitation()!!.resolve()
        assertEquals(before + 1, changes)
        assertNull(capture.peekInvitation())
        off()
    }

    @Test
    fun `an invitation acked before anyone subscribed is not replayed`() {
        val capture = InvitationCapture(store, "app")
        capture.capture("calimero://com.example.app/join?invitation=DECLINED")
        capture.peekInvitation()!!.resolve()
        val seen = mutableListOf<String>()
        capture.onInvitation { seen += it.token }
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun `the shareable link carries extra parameters beside the token`() {
        assertEquals(
            "https://links.calimero.network/com.calimero.docs/join?invitation=a%2Bb%2Fc",
            buildInviteLink("com.calimero.docs", "a+b/c"),
        )
        assertEquals(
            "https://links.calimero.network/com.calimero.docs/join?invitation=T&name=Q3+plan",
            buildInviteLink("com.calimero.docs", "T", mapOf("name" to "Q3 plan", "id" to null)),
        )
        assertEquals("calimero://com.calimero.docs/join?invitation=T", buildInviteLink("com.calimero.docs", "T", host = "calimero://"))
    }

    @Test
    fun `a bare-scheme host roots the link at scheme slash slash`() {
        for (host in listOf("calimero://", "calimero:/", "calimero:", "calimero:///")) {
            assertEquals(host, "calimero://com.calimero.docs/join?invitation=T", buildInviteLink("com.calimero.docs", "T", host = host))
        }
        assertEquals(
            "https://links.example.com/base/com.calimero.docs/join?invitation=T",
            buildInviteLink("com.calimero.docs", "T", host = "https://links.example.com/base//"),
        )
    }

    @Test
    fun `invitationFromRaw reads a link, accepts a bare code and refuses a URL with none`() {
        assertEquals("ABC", invitationFromRaw("https://links.calimero.network/x/join?invitation=ABC"))
        assertEquals("ABC", invitationFromRaw("calimero://x/join?invitation=ABC&name=n"))
        assertEquals("CODE123", invitationFromRaw("  CODE123  "))
        assertNull(invitationFromRaw("https://app.example.com/teams"))
        assertNull(invitationFromRaw("/teams?x=1"))
        assertNull(invitationFromRaw(""))
    }

    @Test
    fun `urlWithoutInvitation drops only the invitation parameter`() {
        assertEquals("https://a/b?keep=1#f", urlWithoutInvitation("https://a/b?invitation=T&keep=1#f"))
        assertEquals("https://a/b", urlWithoutInvitation("https://a/b?invitation=T"))
        assertEquals("https://a/b?x=1", urlWithoutInvitation("https://a/b?x=1"))
    }

    @Test
    fun `the dedup nonce is mero-platform's FNV-1a`() {
        // FNV-1a 32 of "" is the offset basis; of "a" is 0xe40c292c.
        assertEquals("811c9dc5", hashNonce(""))
        assertEquals("e40c292c", hashNonce("a"))
        assertEquals(ParsedIntent("com.calimero.curb", "join", mapOf("invitation" to "T")), parseIntent("calimero://com.calimero.curb/join?invitation=T"))
    }
}
