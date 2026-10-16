package com.calimero.mero.cloud

import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.account.SecureStore
import com.calimero.mero.crypto.SeedSigner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The redirect half of account linking: mero-js `link-redirect.test.ts`,
 * `link-callback-state.test.ts`, the handoff cases of `account-link-grant.test.ts`, and
 * `callbackIsForeign` from `enrol-redirect.test.ts`.
 */
class CloudLinkTest {
    private val account = "1f".repeat(32)
    private val other = "2e".repeat(32)
    private val returnTo = "https://app.example/connected"

    private var now = 1_700_000_000_000L
    private lateinit var storage: SecureStore
    private lateinit var pending: PendingCloudLinks

    @Before
    fun setUp() {
        storage = MemorySecureStore()
        pending = PendingCloudLinks(storage, clock = { now })
    }

    /** What the cloud sends the person back to: our callback URL plus the fragment. */
    private fun callbackFor(
        consentUrl: String,
        account: String,
    ): String = consentUrl.toHttpUrl().queryParameter("callback-url")!! + "#grant=attacker-grant&account=$account"

    private fun consent(acc: String = account): String = CloudLink.cloudLinkUrl(acc, returnTo, pending = pending)

    private fun assertUnexpected(block: () -> Unit) {
        try {
            block()
            fail("expected UnexpectedCloudLinkException")
        } catch (e: UnexpectedCloudLinkException) {
            assertTrue(e.message!!.contains("did not make"))
        }
    }

    @Test
    fun `cloudLinkUrl names the account and where to come back to`() {
        val url = consent().toHttpUrl()
        assertEquals("cloud.calimero.network", url.host)
        assertEquals(account, url.queryParameter("link-account"))
        val back = url.queryParameter("callback-url")!!
        assertTrue(back.startsWith("$returnTo?link_state="))
        assertTrue(Regex("^[0-9a-f]{32}$").matches(back.substringAfter("link_state=")))
    }

    @Test
    fun `refuses an account id the cloud would reject, and an invalid return URL begins nothing`() {
        val back = callbackFor(consent(), account)
        for ((acc, ret) in listOf(account.uppercase() to returnTo, other to "/relative", other to "$returnTo#frag")) {
            try {
                CloudLink.cloudLinkUrl(acc, ret, pending = pending)
                fail("accepted $acc $ret")
            } catch (e: IllegalArgumentException) {
                if (acc != other) assertTrue(e.message!!.contains("64 lowercase hex"))
            }
        }
        assertEquals(account, CloudLink.readCloudLinkCallback(back, pending)?.account)
    }

    @Test
    fun `keeps a callback URL's own query and an app scheme`() {
        val url = CloudLink.cloudLinkUrl(account, "mero-sample://link?keep=1&link_state=old", pending = pending).toHttpUrl()
        val back = url.queryParameter("callback-url")!!
        assertTrue(back.startsWith("mero-sample://link?keep=1&link_state="))
        assertFalse(back.contains("old"))
    }

    @Test
    fun `is null on an ordinary load and reads the grant the cloud handed back, once`() {
        assertNull(CloudLink.readCloudLinkCallback("https://app.example/connected", pending))
        val back = callbackFor(consent(), account)
        assertEquals(CloudLinkCallback("attacker-grant", account), CloudLink.readCloudLinkCallback(back, pending))
        assertUnexpected { CloudLink.readCloudLinkCallback(back, pending) }
    }

    @Test
    fun `throws when the person declined, and forgets the pending link`() {
        val back = callbackFor(consent(), account)
        val query = back.substringBefore('#')
        try {
            CloudLink.readCloudLinkCallback("$query#error=denied", pending)
            fail("expected a decline")
        } catch (e: CloudLinkDeclinedException) {
            assertTrue(e.declined)
            assertTrue(e.message!!.contains("declined"))
        }
        assertUnexpected { CloudLink.readCloudLinkCallback(back, pending) }
        try {
            CloudLink.readCloudLinkCallback("$returnTo#error=server_error", pending)
            fail("expected a refusal")
        } catch (e: CloudLinkDeclinedException) {
            assertFalse(e.declined)
            assertEquals("server_error", e.reason)
        }
    }

    @Test
    fun `refuses a grant when this app began no link, or with the wrong state`() {
        assertUnexpected { CloudLink.readCloudLinkCallback("$returnTo#grant=g&account=$account", pending) }
        consent()
        assertUnexpected { CloudLink.readCloudLinkCallback("$returnTo?link_state=guessed#grant=g&account=$account", pending) }
        for (state in listOf("__proto__", "constructor", "toString", "hasOwnProperty")) {
            assertUnexpected { CloudLink.readCloudLinkCallback("$returnTo?link_state=$state#grant=g&account=$account", pending) }
        }
    }

    @Test
    fun `drops the pending link when its callback names a different account`() {
        val url = consent()
        assertUnexpected { CloudLink.readCloudLinkCallback(callbackFor(url, other), pending) }
        assertUnexpected { CloudLink.readCloudLinkCallback(callbackFor(url, account), pending) }
    }

    @Test
    fun `keeps the pending link when a foreign grant is refused`() {
        val back = callbackFor(consent(), account)
        assertUnexpected { CloudLink.readCloudLinkCallback("$returnTo#grant=x&account=$account", pending) }
        assertEquals("attacker-grant", CloudLink.readCloudLinkCallback(back, pending)?.grant)
    }

    @Test
    fun `keeps an earlier consent URL valid, and links for two accounts apart`() {
        val first = callbackFor(consent(), account)
        consent()
        val b = callbackFor(consent(other), other)
        assertEquals(other, CloudLink.readCloudLinkCallback(b, pending)?.account)
        assertEquals(account, CloudLink.readCloudLinkCallback(first, pending)?.account)
    }

    @Test
    fun `survives the process when backed by a persistent store`() {
        val back = callbackFor(consent(), account)
        val relaunched = PendingCloudLinks(storage, clock = { now })
        assertEquals(account, CloudLink.readCloudLinkCallback(back, relaunched)?.account)
    }

    @Test
    fun `refuses a callback after the link has expired`() {
        val back = callbackFor(consent(), account)
        now += 11 * 60 * 1000
        assertUnexpected { CloudLink.readCloudLinkCallback(back, pending) }
    }

    @Test
    fun `gives each consent URL its own state and bounds how many are kept`() {
        val state = { u: String -> u.toHttpUrl().queryParameter("callback-url")!!.substringAfter("link_state=") }
        assertNotEquals(state(consent()), state(consent()))
        repeat(30) { consent() }
        val kept = Json.parseToJsonElement(storage.get(PendingCloudLinks.DEFAULT_KEY)!!).jsonObject
        assertEquals(PendingCloudLinks.MAX_PENDING, kept.size)
        assertEquals(
            setOf("account", "expiresAt"),
            kept.values
                .first()
                .jsonObject.keys,
        )
    }

    @Test
    fun `survives corrupted storage, and refuses the callback when storage is broken`() {
        storage.put(PendingCloudLinks.DEFAULT_KEY, """{"x":null}""")
        consent()
        storage.put(PendingCloudLinks.DEFAULT_KEY, "null")
        consent()
        val broken =
            object : SecureStore {
                override fun get(key: String): String = error("blocked")

                override fun put(
                    key: String,
                    value: String,
                ) = error("blocked")

                override fun remove(key: String) = error("blocked")
            }
        val nowhere = PendingCloudLinks(broken)
        val back = callbackFor(CloudLink.cloudLinkUrl(account, returnTo, pending = nowhere), account)
        assertUnexpected { CloudLink.readCloudLinkCallback(back, nowhere) }
    }

    @Test
    fun `strips the fragment and link_state once a callback is read`() {
        assertEquals("https://app.example/connected?keep=1", CloudLink.withoutLinkState("https://app.example/connected?keep=1&link_state=abc#grant=g"))
        assertEquals("mero-sample://link", CloudLink.withoutLinkState("mero-sample://link?link_state=abc"))
    }

    // ---- completeCloudLink ---------------------------------------------------------------

    @Test
    fun `refuses a grant minted for a different account, before sending anything`() =
        runBlocking {
            val server = MockWebServer().apply { start() }
            try {
                CloudLink.completeCloudLink(CloudLinkCallback("abc.def", "aa".repeat(32)), "07".repeat(32), server.url("/").toString())
                fail("expected a refusal")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("minted for a different account"))
            }
            assertEquals(0, server.requestCount)
            server.shutdown()
        }

    @Test
    fun `spends the grant anonymously for the account the root derives`() =
        runBlocking {
            val server = MockWebServer().apply { start() }
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"linked_at":"t","already_linked":false}"""))
            val root = SeedSigner("07".repeat(32))
            val acc =
                com.calimero.mero.account
                    .resolveRoot(root)
                    .accountId
            val link = CloudLink.completeCloudLink(CloudLinkCallback("abc.def", acc), root, server.url("/").toString())
            assertEquals(acc, link.accountId)
            val req = server.takeRequest()
            assertEquals("/api/cloud/accounts/link", req.path)
            assertNull(req.getHeader("Authorization"))
            server.shutdown()
        }

    // ---- the portal handoff --------------------------------------------------------------

    @Test
    fun `builds the handoff URL the portal parses`() {
        val handoff =
            CloudClient.accountLinkHandoff(account, "https://demo.example/app?keep=this", "https://cloud.calimero.network/", pending)
        assertEquals(account, handoff.accountId)
        val url = handoff.url.toHttpUrl()
        assertEquals(
            "https://cloud.calimero.network/",
            url
                .newBuilder()
                .query(null)
                .build()
                .toString(),
        )
        assertEquals(account, url.queryParameter("link-account"))
        val back = url.queryParameter("callback-url")!!.toHttpUrl()
        assertEquals("this", back.queryParameter("keep"))
        assertTrue(Regex("^[0-9a-f]{32}$").matches(back.queryParameter("link_state")!!))
    }

    @Test
    fun `reads a handoff grant out of the fragment, once`() {
        val handoff = CloudClient.accountLinkHandoff(account, "https://demo.example/app", pending = pending)
        val back = callbackFor(handoff.url, account)
        assertEquals(AccountLinkCallback("attacker-grant", account), CloudClient.readAccountLinkCallback(back, pending))
        assertEquals(CloudClient.UNEXPECTED_CALLBACK, CloudClient.readAccountLinkCallback(back, pending).error)
    }

    @Test
    fun `reports a grant never asked for, or for another account, as unexpected`() {
        val out = CloudClient.readAccountLinkCallback("$returnTo#grant=attacker-grant&account=$account", pending)
        assertNull(out.grant)
        assertEquals("unexpected_callback", out.error)
        val handoff = CloudClient.accountLinkHandoff(account, returnTo, pending = pending)
        assertEquals("unexpected_callback", CloudClient.readAccountLinkCallback(callbackFor(handoff.url, other), pending).error)
    }

    @Test
    fun `reports a refusal, nothing on an ordinary load, and ignores a grant in the query`() {
        assertEquals(AccountLinkCallback(error = "denied"), CloudClient.readAccountLinkCallback("https://demo.example/app#error=denied", pending))
        assertEquals(AccountLinkCallback(), CloudClient.readAccountLinkCallback("https://demo.example/app", pending))
        assertEquals(AccountLinkCallback(), CloudClient.readAccountLinkCallback("not a url", pending))
        assertEquals(AccountLinkCallback(), CloudClient.readAccountLinkCallback("https://demo.example/app?grant=sealed.token", pending))
    }

    @Test
    fun `refuses a handoff for an account id the cloud would reject`() {
        try {
            CloudClient.accountLinkHandoff(account.uppercase(), returnTo, pending = pending)
            fail("accepted uppercase")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("64 lowercase hex"))
        }
    }

    // ---- callbackIsForeign ---------------------------------------------------------------

    @Test
    fun `callbackIsForeign compares origins and says nothing when it cannot tell`() {
        assertFalse(callbackIsForeign("https://app.example/cb", "https://app.example"))
        assertTrue(callbackIsForeign("https://elsewhere.example/cb", "https://app.example"))
        assertTrue(callbackIsForeign("https://app.example:8443/cb", "https://app.example"))
        assertTrue(callbackIsForeign("http://localhost:3000/cb", "http://localhost:3001"))
        assertFalse(callbackIsForeign("https://app.example:443/cb", "https://app.example"))
        assertFalse(callbackIsForeign("https://app.example/cb", null))
        assertFalse(callbackIsForeign("not a url", "https://app.example"))
        // An app scheme has an opaque origin, as in a browser.
        assertTrue(callbackIsForeign("mero-sample://enrol", "https://app.example"))
    }
}
