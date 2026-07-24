package com.calimero.mero

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class JwtTest {
    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.signature"
    }

    @Test
    fun `parses exp from a valid JWT into epoch millis`() {
        val token = jwt("""{"exp":1893456000,"sub":"user"}""")
        assertEquals(1_893_456_000_000L, expiresAtFromJwt(token, fallbackMs = 1L))
    }

    @Test
    fun `falls back when token is not a JWT`() {
        assertEquals(42L, expiresAtFromJwt("not-a-jwt", fallbackMs = 42L))
    }

    @Test
    fun `falls back when exp is missing`() {
        val token = jwt("""{"sub":"user"}""")
        assertEquals(99L, expiresAtFromJwt(token, fallbackMs = 99L))
    }

    @Test
    fun `base64url decodes without padding`() {
        // "sub" -> c3Vi in base64url
        assertEquals("sub", base64UrlDecode("c3Vi").decodeToString())
    }
}
