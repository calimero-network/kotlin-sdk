package com.calimero.mero

import com.calimero.mero.http.HttpException
import com.calimero.mero.http.NetworkException
import com.calimero.mero.rpc.RpcException
import com.calimero.mero.rpc.callFailureReason
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * A contract that returns an error, or panics, answers `{"type":"FunctionCallError","data":"<reason>"}`
 * with no `message`: the reason an app wants to show is in `data`. It has to be one property away.
 * (== mero-swift-sdk `RpcErrorReasonTests`.)
 */
class RpcErrorReasonTest {
    private lateinit var server: MockWebServer
    private lateinit var mero: Mero

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        mero = Mero(MeroConfig(baseUrl = server.url("/").toString().trimEnd('/'), tokenStore = MemoryTokenStore()))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun rpcError(json: String): RpcException =
        runBlocking {
            server.enqueue(MockResponse().setBody(json))
            try {
                mero.rpc.executeRaw("ctx", "transfer")
                fail("expected an error")
                error("unreachable")
            } catch (e: RpcException) {
                e
            }
        }

    /** The shape core sends (`ExecutionError::FunctionCallError(String)`). */
    @Test
    fun `a contract error carries its reason`() {
        val error =
            rpcError("""{"jsonrpc":"2.0","id":1,"error":{"type":"FunctionCallError","data":"insufficient balance"}}""")
        assertTrue(error.isFunctionCallError)
        assertEquals("insufficient balance", error.reason)
        assertEquals("FunctionCallError: insufficient balance", error.message)
        assertEquals("insufficient balance", error.callFailureReason)
    }

    /** A nested `{type, data}` (core's tagged-enum shape) is not reduced to the bare type. */
    @Test
    fun `a nested reason is unwrapped`() {
        val error =
            rpcError(
                """{"jsonrpc":"2.0","id":1,"error":{"type":"FunctionCallError",""" +
                    """"data":{"type":"HostError","data":{"type":"Panic","data":{"message":"index out of bounds"}}}}}""",
            )
        assertNotEquals("FunctionCallError", error.message)
        assertEquals("HostError: Panic: index out of bounds", error.reason)
        assertEquals("FunctionCallError: HostError: Panic: index out of bounds", error.message)
    }

    @Test
    fun `a node message is kept and the reason still readable`() {
        val error =
            rpcError(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"boom","type":"FunctionCallError","data":"why"}}""",
            )
        assertEquals("boom", error.message)
        assertEquals("why", error.reason)
    }

    @Test
    fun `with no data the reason is the message`() {
        val error = RpcException(code = -32000, message = "method not found")
        assertEquals("method not found", error.reason)
        assertFalse(error.isFunctionCallError)
    }

    /** `/query` and `/intents` refuse a failed method with a `400` carrying the same `type`/`data` pair. */
    @Test
    fun `an http refusal carries the reason too`() {
        val http =
            HttpException(
                400,
                """{"error":"method failed","type":"FunctionCallError","data":"panicked: nope"}""",
                emptyMap(),
                "https://node.test/admin-api/contexts/c/query",
            )
        assertEquals("panicked: nope", http.callFailureReason)
        val bare = HttpException(400, """{"error":"bad input"}""", emptyMap(), "u")
        assertEquals("bad input", bare.callFailureReason)
        assertNull(NetworkException("offline").callFailureReason)
    }
}
