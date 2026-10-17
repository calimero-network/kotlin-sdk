package com.calimero.mero

import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.account.SecureStore
import com.calimero.mero.auth.AuthLoginOptions
import com.calimero.mero.auth.PendingAuthStates
import com.calimero.mero.auth.buildAuthLoginUrl
import com.calimero.mero.auth.parseAuthCallback
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * Tokens in a login callback are only adopted for a login this app began. Port of mero-js
 * `src/auth/auth-callback-state.test.ts`.
 *
 * Anyone can send a link to the app with tokens of their choosing in the fragment; adopting
 * them signs the person in to the sender's node and account.
 */
class AuthCallbackStateTest {
    private val node = "https://node.example"
    private val tokens = "access_token=a&refresh_token=r&node_url=https%3A%2F%2Fnode.example"
    private val attacker = "access_token=att&refresh_token=att&node_url=https%3A%2F%2Fevil.example"

    private var now = 1_700_000_000_000L
    private val store = MemorySecureStore()
    private val pending = PendingAuthStates(store, clock = { now })

    private fun beginLogin(callbackUrl: String = "https://app.example/cb"): String =
        buildAuthLoginUrl(node, AuthLoginOptions(callbackUrl = callbackUrl, mode = "admin"), pending)

    /** What the node's login page sends the person back to: our callback URL plus the tokens. */
    private fun callbackFor(
        loginUrl: String,
        fragment: String = tokens,
    ): String = loginUrl.toHttpUrl().queryParameter("callback-url")!! + "#" + fragment

    @Test
    fun `refuses tokens when this app began no login`() {
        assertNull(parseAuthCallback("https://app.example/cb#$attacker", pending = pending))
    }

    @Test
    fun `refuses tokens carrying a state this app did not begin`() {
        beginLogin()
        assertNull(parseAuthCallback("https://app.example/cb?auth_state=guessed#$attacker", pending = pending))
    }

    @Test
    fun `adopts the tokens of the login this app began, and keeps the callback query`() {
        val back = callbackFor(beginLogin("https://app.example/cb?tab=files"))
        assertEquals("files", back.substringBefore('#').toHttpUrl().queryParameter("tab"))
        val result = parseAuthCallback(back, pending = pending)!!
        assertEquals("a", result.accessToken)
        assertEquals("r", result.refreshToken)
        assertEquals(node, result.nodeUrl)
    }

    @Test
    fun `adopts a callback once`() {
        val back = callbackFor(beginLogin())
        assertNotNull(parseAuthCallback(back, pending = pending))
        assertNull(parseAuthCallback(back, pending = pending))
    }

    @Test
    fun `refuses a callback for a login begun too long ago`() {
        val back = callbackFor(beginLogin())
        now += 11 * 60 * 1000
        assertNull(parseAuthCallback(back, pending = pending))
    }

    @Test
    fun `a callback without tokens does not spend the state`() {
        val login = beginLogin()
        assertNull(parseAuthCallback(callbackFor(login, "error=denied"), pending = pending))
        assertNotNull(parseAuthCallback(callbackFor(login), pending = pending))
    }

    @Test
    fun `works with an app-scheme callback and keeps its fragment out of the query`() {
        val login = beginLogin("myapp://auth-callback")
        val callback = URLDecoder.decode(login.substringAfter("callback-url=").substringBefore('&'), "UTF-8")
        assertTrue(callback, callback.matches(Regex("myapp://auth-callback\\?auth_state=[0-9a-f]{32}")))
        assertNotNull(parseAuthCallback("$callback#$tokens", pending = pending))
    }

    @Test
    fun `replaces an auth_state the callback url already carried`() {
        val login = beginLogin("https://app.example/cb?auth_state=stale")
        val callback = login.toHttpUrl().queryParameter("callback-url")!!.toHttpUrl()
        assertEquals(1, callback.queryParameterValues("auth_state").size)
        assertFalse(callback.queryParameter("auth_state") == "stale")
    }

    @Test
    fun `refuses when there is no storage to remember the login in`() {
        val broken =
            object : SecureStore {
                override fun get(key: String): String = error("blocked")

                override fun put(
                    key: String,
                    value: String,
                ): Unit = error("blocked")

                override fun remove(key: String): Unit = error("blocked")
            }
        val states = PendingAuthStates(broken)
        val login = buildAuthLoginUrl(node, AuthLoginOptions(callbackUrl = "https://app.example/cb", mode = "admin"), states)
        assertNull(parseAuthCallback(callbackFor(login), pending = states))
    }

    @Test
    fun `accepts unsolicited tokens only when the caller opts in for a host that injects them`() {
        val desktop = "https://app.example/#$tokens"
        assertNull(Mero.parseAuthCallback(desktop, pending = pending))
        val result = Mero.parseAuthCallback(desktop, allowUnsolicited = true, pending = pending)!!
        assertEquals("a", result.accessToken)
        assertEquals(node, result.nodeUrl)
    }

    @Test
    fun `pending logins persist in the store in mero-js's shape and survive a new instance`() {
        val back = callbackFor(beginLogin())
        val raw = store.get(PendingAuthStates.DEFAULT_KEY)!!
        assertTrue(raw, raw.matches(Regex("\\{\"[0-9a-f]{32}\":\\{\"expiresAt\":${now + PendingAuthStates.LIFETIME_MS}}}")))
        // A new process with the same (persistent) store still recognises the login.
        assertNotNull(parseAuthCallback(back, pending = PendingAuthStates(store, clock = { now })))
    }

    @Test
    fun `remembers at most MAX_PENDING logins, dropping the oldest`() {
        val first = callbackFor(beginLogin())
        repeat(PendingAuthStates.MAX_PENDING) { beginLogin() }
        assertNull(parseAuthCallback(first, pending = pending))
        val latest = callbackFor(beginLogin())
        assertNotNull(parseAuthCallback(latest, pending = pending))
    }

    @Test
    fun `the shared default refuses an unsolicited callback`() {
        assertNull(parseAuthCallback("myapp://cb#$attacker"))
    }
}
