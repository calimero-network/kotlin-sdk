package com.calimero.mero.compose

import com.calimero.mero.TokenData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Ports of mero-react `auth/node-trust.test.ts`, `auth/token-adoption.test.ts` and the `getPermissionsForMode` cases. */
class NodeTrustTest {
    // ---- sameOrigin / resolveTrustedNodeUrl ------------------------------------------------

    @Test
    fun `sameOrigin compares scheme, host and port, ignoring the path`() {
        assertTrue(sameOrigin("https://node.example.com/a", "https://node.example.com/b"))
        assertFalse(sameOrigin("https://node.example.com", "https://evil.com"))
        assertFalse(sameOrigin("https://node.example.com:2428", "https://node.example.com:9999"))
        assertFalse(sameOrigin("http://node.example.com", "https://node.example.com"))
        assertFalse(sameOrigin("not a url", "https://node.example.com"))
        assertFalse(sameOrigin("https://node.example.com", ""))
    }

    private val initiated = "https://my-node.example.com"

    @Test
    fun `the initiated node is used when the callback carries no node_url`() {
        assertEquals(TrustedNodeResult(initiated, false), resolveTrustedNodeUrl(null, initiated))
    }

    @Test
    fun `a callback node_url on the initiated origin is accepted`() {
        val r = resolveTrustedNodeUrl("https://my-node.example.com/cb", initiated)
        assertEquals("https://my-node.example.com/cb", r.url)
        assertFalse(r.rejected)
    }

    @Test
    fun `a callback node_url on another origin is rejected (exfiltration attempt)`() {
        assertEquals(TrustedNodeResult(null, true), resolveTrustedNodeUrl("https://evil.com", initiated))
        val port = resolveTrustedNodeUrl("https://my-node.example.com:9999", "https://my-node.example.com:2428")
        assertTrue(port.rejected)
        assertNull(port.url)
    }

    @Test
    fun `without an initiated node, the allowlist decides`() {
        val allowed = listOf("https://trusted.example.com")
        assertEquals(
            TrustedNodeResult("https://trusted.example.com/cb", false),
            resolveTrustedNodeUrl("https://trusted.example.com/cb", null, allowed),
        )
        assertEquals(TrustedNodeResult(null, true), resolveTrustedNodeUrl("https://evil.com", null, allowed))
    }

    @Test
    fun `with no trust anchor at all a candidate is rejected`() {
        assertEquals(TrustedNodeResult(null, true), resolveTrustedNodeUrl("https://unknown.example.com", null))
    }

    // ---- JWT claims -----------------------------------------------------------------------

    private fun b64(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    private fun jwt(
        iat: Long? = null,
        exp: Long? = null,
        sub: String? = null,
    ): String {
        val claims = listOfNotNull(iat?.let { "\"iat\":$it" }, exp?.let { "\"exp\":$it" }, sub?.let { "\"sub\":\"$it\"" })
        return "${b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}")}.${b64("{${claims.joinToString(",")}}")}.sig"
    }

    @Test
    fun `decodeJwtClaims reads iat and exp, and drops what is not a positive number`() {
        assertEquals(JwtClaims(1000, 2000), decodeJwtClaims(jwt(iat = 1000, exp = 2000)))
        assertNull(decodeJwtClaims("opaque-token"))
        assertNull(decodeJwtClaims("a.b.c"))
        assertNull(decodeJwtClaims(""))
        assertEquals(JwtClaims(null, null), decodeJwtClaims(jwt(iat = 0, exp = -1)))
        assertEquals(JwtClaims(null, null), decodeJwtClaims(jwt(sub = "no-timestamps")))
    }

    // ---- resolveTokenAdoption ---------------------------------------------------------------

    private val node = "https://node-a.example.com"
    private val otherNode = "https://node-b.example.com"
    private val nowSec = System.currentTimeMillis() / 1000

    private fun t(offset: Long) = nowSec + offset

    private fun stored(
        access: String = jwt(iat = t(-60), exp = t(3540)),
        refresh: String = "stored-refresh",
        expiresAt: Long = t(3540) * 1000,
    ) = TokenData(access, refresh, expiresAt)

    @Test
    fun `a fresh login seeds from the callback`() {
        val access = jwt(iat = t(0), exp = t(3600))
        val d = resolveTokenAdoption(access, "hash-refresh", node, null, null)
        assertEquals(TokenAdoptionDecision(true, TokenAdoptionReason.FRESH_LOGIN, TokenData(access, "hash-refresh", t(3600) * 1000)), d)
    }

    @Test
    fun `a stale callback does not overwrite a newer stored bundle`() {
        val d = resolveTokenAdoption(jwt(iat = t(-600), exp = t(3000)), "stale-refresh", node, stored(refresh = "rotated-refresh"), node)
        assertEquals(TokenAdoptionDecision(false, TokenAdoptionReason.STALE), d)
    }

    @Test
    fun `a callback replaying the stored access token is not re-adopted`() {
        val s = stored(refresh = "rotated-refresh")
        assertEquals(TokenAdoptionDecision(false, TokenAdoptionReason.STALE), resolveTokenAdoption(s.accessToken, "old-refresh", node, s, node))
    }

    @Test
    fun `a genuinely newer callback is adopted`() {
        val fresh = jwt(iat = t(60), exp = t(3660))
        val d = resolveTokenAdoption(fresh, "new-refresh", node, stored(), node)
        assertEquals(TokenAdoptionDecision(true, TokenAdoptionReason.NEWER, TokenData(fresh, "new-refresh", t(3660) * 1000)), d)
    }

    @Test
    fun `a node change re-seeds without carrying the old node's refresh token`() {
        val access = jwt(iat = t(-600), exp = t(3000))
        val d = resolveTokenAdoption(access, "", otherNode, stored(refresh = "node-a-refresh"), node)
        assertEquals(TokenAdoptionDecision(true, TokenAdoptionReason.NODE_CHANGED, TokenData(access, "", t(3000) * 1000)), d)
    }

    @Test
    fun `a same-origin callback with another path is the same node`() {
        val d = resolveTokenAdoption(jwt(iat = t(-600), exp = t(3000)), "x", "$node/callback", stored(), node)
        assertEquals(TokenAdoptionReason.STALE, d.reason)
    }

    @Test
    fun `an access-only newer callback keeps the stored refresh token`() {
        val fresh = jwt(iat = t(60), exp = t(3660))
        val d = resolveTokenAdoption(fresh, "", node, stored(refresh = "rotated-refresh"), node)
        assertEquals(TokenAdoptionDecision(true, TokenAdoptionReason.NEWER, TokenData(fresh, "rotated-refresh", t(3660) * 1000)), d)
    }

    @Test
    fun `a missing stored refresh token is filled without downgrading the access token`() {
        val s = stored(refresh = "")
        val d = resolveTokenAdoption(jwt(iat = t(-600), exp = t(3000)), "hash-refresh", node, s, node)
        assertEquals(TokenAdoptionDecision(true, TokenAdoptionReason.FILL_REFRESH, s.copy(refreshToken = "hash-refresh")), d)
    }

    @Test
    fun `an opaque stored token is compared on its recorded expiry`() {
        val s = TokenData("opaque", "r", t(3600) * 1000)
        assertEquals(TokenAdoptionReason.STALE, resolveTokenAdoption(jwt(exp = t(3000)), "x", node, s, node).reason)
        val fresh = jwt(exp = t(7200))
        assertEquals(
            TokenAdoptionDecision(true, TokenAdoptionReason.NEWER, TokenData(fresh, "x", t(7200) * 1000)),
            resolveTokenAdoption(fresh, "x", node, s, node),
        )
    }

    @Test
    fun `an undecodable callback token is never adopted over a live bundle`() {
        assertEquals(TokenAdoptionReason.STALE, resolveTokenAdoption("opaque-callback", "x", node, stored(), node).reason)
    }

    // ---- AppMode ----------------------------------------------------------------------------

    @Test
    fun `MULTI_CONTEXT asks for the grants every hook needs, including subscribe and delete`() {
        assertEquals(
            listOf(
                "context:create", "context:delete", "context:list", "context:execute", "context:subscribe",
                "application:list", "namespace", "group", "blob", "context:alias",
            ),
            permissionsFor(AppMode.MULTI_CONTEXT),
        )
        assertEquals(listOf("admin"), permissionsFor(AppMode.ADMIN))
        assertEquals("multi-context", AppMode.MULTI_CONTEXT.wire)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `SINGLE_CONTEXT requests execute and list plus blob and alias`() {
        val p = permissionsFor(AppMode.SINGLE_CONTEXT)
        assertEquals(listOf("context:execute", "context:list", "context:subscribe", "application:list", "blob", "context:alias"), p)
    }

    @Test
    fun `an account may found only with a package, and never upgrade`() {
        assertEquals(MeroCapabilities(false, true, true, false), MeroCapabilities.account(hasPackage = false))
        assertTrue(MeroCapabilities.account(hasPackage = true).createNamespace)
        assertTrue(MeroCapabilities.NODE.upgrade)
    }

    // ---- MeroAppStorage ---------------------------------------------------------------------

    @Test
    fun `MeroAppStorage keeps mero-react's keys and clears them all`() {
        val kv = MemoryKeyValueStore()
        val storage = MeroAppStorage(kv)
        storage.nodeUrl = "http://n"
        storage.tokenNodeUrl = "http://t"
        storage.applicationId = "app"
        storage.contextId = "ctx"
        storage.contextIdentity = "id"
        assertEquals("http://n", kv.get("mero:node_url"))
        assertEquals("app", kv.get("mero:application_id"))
        storage.contextId = null
        assertNull(kv.get("mero:context_id"))
        storage.clearAll()
        assertNull(storage.nodeUrl)
        assertNull(storage.tokenNodeUrl)
        assertNull(storage.applicationId)
        assertNull(storage.contextIdentity)
    }
}
