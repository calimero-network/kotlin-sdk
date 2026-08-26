package com.calimero.mero

import com.calimero.mero.admin.JoinNamespaceResponseData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * core 0.11.0-rc.25 (core#3598) renamed the namespace-join response field from
 * `groupId` to `namespaceId`. Declared as a required `groupId`, this threw
 * `MissingFieldException` on every rc.25 join, so `joinNamespace` failed
 * outright. `ignoreUnknownKeys` does not help: the problem is a missing
 * *required* field, not an unknown one. Both spellings are pinned here.
 */
class JoinNamespaceCompatTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private fun decode(body: String) = json.decodeFromString<JoinNamespaceResponseData>(body)

    @Test
    fun `decodes the rc25 spelling`() {
        val body = """{"namespaceId":"ns-1","memberIdentity":"id-1","memberAccount":"acct-1"}"""
        val data = decode(body)
        assertEquals("ns-1", data.namespaceId)
        assertEquals("id-1", data.memberIdentity)
        assertEquals("acct-1", data.memberAccount)
    }

    @Test
    fun `decodes the pre rc25 spelling`() {
        val data = decode("""{"groupId":"ns-1","memberIdentity":"id-1"}""")
        assertEquals("ns-1", data.namespaceId)
        assertNull(data.memberAccount)
    }

    @Test
    fun `prefers the new spelling when a node sends both`() {
        val data = decode("""{"namespaceId":"new","groupId":"old","memberIdentity":"id"}""")
        assertEquals("new", data.namespaceId)
    }

    @Test
    fun `a missing governanceOp is not fatal`() {
        // It was required before; a node that omits it used to throw.
        val data = decode("""{"namespaceId":"ns","memberIdentity":"id"}""")
        assertNull(data.governanceOp)
    }

    @Test(expected = IllegalStateException::class)
    fun `fails clearly when neither spelling is present`() {
        decode("""{"memberIdentity":"id-1"}""").namespaceId
    }
}
