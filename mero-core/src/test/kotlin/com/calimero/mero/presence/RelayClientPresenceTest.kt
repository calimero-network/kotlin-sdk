package com.calimero.mero.presence

import com.calimero.mero.crypto.Ed25519
import com.calimero.mero.crypto.Hex
import com.calimero.mero.http.HttpException
import com.calimero.mero.relay.IntentRefusedException
import com.calimero.mero.relay.MemoryNonceSource
import com.calimero.mero.relay.RelayClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [RelayClient.publishPresence]: the SDK signs, the caller no longer hand-supplies a signature. */
class RelayClientPresenceTest {
    private lateinit var server: MockWebServer
    private val ctx = "11".repeat(32)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun relay() = RelayClient(server.url("/").toString(), "33".repeat(32), "aa55", "22".repeat(32), MemoryNonceSource())

    @Test
    fun `publishPresence posts a device-signed statement to presence-intents`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(204))
            val relay = relay()
            relay.publishPresence(ctx, byteArrayOf(1, 2, 3), seq = 42, sentAtMs = 1_234)
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/admin-api/contexts/$ctx/presence-intents", req.path)
            val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
            assertEquals("010203", body.getValue("state").jsonPrimitive.content)
            assertEquals(42, body.getValue("seq").jsonPrimitive.long)
            assertEquals(1_234, body.getValue("sentAtMs").jsonPrimitive.long)
            assertEquals("aa55", body.getValue("authorProof").jsonPrimitive.content)
            val statement = PresenceStatement.presenceStatementBytes(ctx, relay.authorSigner.publicKey, 42, 1_234, byteArrayOf(1, 2, 3))
            val signature = Hex.decode(body.getValue("signature").jsonPrimitive.content, "signature", 64)
            assertTrue(Ed25519.verify(Hex.decode(relay.authorSigner.publicKey, "pk", 32), signature, statement))
        }

    @Test
    fun `a retract sends state null`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(204))
            relay().publishPresence(ctx, null, seq = 1, sentAtMs = 2)
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(JsonNull, body.getValue("state"))
        }

    @Test
    fun `a 429 is not retried, and a 403 is a refusal carrying its status`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(429))
            val limited = runCatching { relay().publishPresence(ctx, byteArrayOf(1), seq = 1, sentAtMs = 2) }.exceptionOrNull()
            assertEquals(429, (limited as HttpException).status)
            assertEquals(1, server.requestCount)

            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"not a member"}"""))
            val refused = runCatching { relay().publishPresence(ctx, byteArrayOf(1), seq = 2, sentAtMs = 3) }.exceptionOrNull()
            assertEquals(403, (refused as IntentRefusedException).status)
            assertEquals("not a member", refused.reason)
        }
}
