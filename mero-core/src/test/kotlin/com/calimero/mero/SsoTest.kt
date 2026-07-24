package com.calimero.mero

import com.calimero.mero.auth.AuthLoginOptions
import com.calimero.mero.auth.buildAuthLoginUrl
import com.calimero.mero.auth.parseAuthCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsoTest {
    @Test
    fun `parses tokens and ids from the hash fragment`() {
        val url =
            "myapp://auth-callback#access_token=aaa&refresh_token=bbb&application_id=app1" +
                "&context_id=ctx1&context_identity=id1&node_url=https%3A%2F%2Fnode.example.com"
        val result = parseAuthCallback(url)!!
        assertEquals("aaa", result.accessToken)
        assertEquals("bbb", result.refreshToken)
        assertEquals("app1", result.applicationId)
        assertEquals("ctx1", result.contextId)
        assertEquals("id1", result.contextIdentity)
        assertEquals("https://node.example.com", result.nodeUrl)
    }

    @Test
    fun `returns null when access_token is absent`() {
        assertNull(parseAuthCallback("myapp://auth-callback#refresh_token=bbb"))
    }

    @Test
    fun `returns null when there is no hash fragment`() {
        assertNull(parseAuthCallback("myapp://auth-callback?foo=bar"))
    }

    @Test
    fun `builds a login url with callback, mode and permissions`() {
        val url =
            buildAuthLoginUrl(
                "https://node.example.com/",
                AuthLoginOptions(
                    callbackUrl = "myapp://auth-callback",
                    mode = "login",
                    permissions = listOf("admin", "context"),
                ),
            )
        assertTrue(url.startsWith("https://node.example.com/auth/login?"))
        assertTrue(url.contains("callback-url=myapp"))
        assertTrue(url.contains("mode=login"))
        assertTrue(url.contains("permissions=admin%2Ccontext"))
    }

    @Test
    fun `includes package metadata when provided`() {
        val url =
            buildAuthLoginUrl(
                "https://node.example.com",
                AuthLoginOptions(
                    callbackUrl = "myapp://cb",
                    mode = "install",
                    packageName = "com.calimero.demo",
                    packageVersion = "1.2.3",
                    registryUrl = "https://registry.example.com",
                ),
            )
        assertTrue(url.contains("package-name=com.calimero.demo"))
        assertTrue(url.contains("package-version=1.2.3"))
        assertTrue(url.contains("registry-url=https%3A%2F%2Fregistry.example.com"))
    }
}
