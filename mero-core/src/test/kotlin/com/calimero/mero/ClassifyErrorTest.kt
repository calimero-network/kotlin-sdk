package com.calimero.mero

import com.calimero.mero.account.JoinException
import com.calimero.mero.account.JoinStep
import com.calimero.mero.cloud.AccountHaRefusedException
import com.calimero.mero.http.AuthRevokedException
import com.calimero.mero.http.ClassifiedError
import com.calimero.mero.http.ErrorKind
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.NetworkException
import com.calimero.mero.http.classifyError
import com.calimero.mero.relay.IntentRefusedException
import com.calimero.mero.relay.NotAViewException
import com.calimero.mero.rpc.ReadOnlyWriteRefusedException
import com.calimero.mero.rpc.RpcException
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

/** Port of mero-js `src/errors/classify.test.ts`, plus the Kotlin-only exception types. */
class ClassifyErrorTest {
    private fun httpError(
        status: Int,
        error: String? = null,
    ) = HttpException(status, error?.let { """{"error":"$it"}""" }.orEmpty(), emptyMap(), "http://node/admin-api/x")

    @Test
    fun `reads each status as its kind`() {
        val table =
            listOf(
                Triple(400, ErrorKind.INVALID, false),
                Triple(401, ErrorKind.UNAUTHORIZED, true),
                Triple(403, ErrorKind.FORBIDDEN, false),
                Triple(404, ErrorKind.NOT_FOUND, true),
                Triple(409, ErrorKind.CONFLICT, false),
                Triple(410, ErrorKind.GONE, false),
                Triple(413, ErrorKind.TOO_LARGE, false),
                Triple(422, ErrorKind.INVALID, false),
                Triple(429, ErrorKind.RATE_LIMITED, true),
                Triple(500, ErrorKind.SERVER, true),
                Triple(502, ErrorKind.SERVER, true),
                Triple(503, ErrorKind.UNAVAILABLE, true),
                Triple(504, ErrorKind.UNAVAILABLE, true),
                Triple(0, ErrorKind.UNREACHABLE, true),
            )
        for ((status, kind, retryable) in table) {
            val c = classifyError(httpError(status, "no"))
            assertEquals("$status", kind, c.kind)
            assertEquals("$status", status, c.status)
            assertEquals("$status", retryable, c.retryable)
        }
    }

    @Test
    fun `kinds carry mero-js's names`() {
        assertEquals(
            listOf(
                "invalid", "unauthorized", "forbidden", "not-found", "conflict", "gone", "too-large",
                "rate-limited", "unavailable", "server", "unreachable", "unknown",
            ),
            ErrorKind.entries.map { it.wire },
        )
    }

    @Test
    fun `gives the node's explanation without the status prefix`() {
        val err = httpError(409, "invitation for group ab12 expired at 1759000000 (unix seconds)")
        assertEquals("invitation for group ab12 expired at 1759000000 (unix seconds)", classifyError(err).message)
    }

    @Test
    fun `reads a proxy's nested error and the cloud's detail`() {
        val nested = HttpException(502, """{"error":{"message":"upstream down"}}""", emptyMap(), "u")
        assertEquals("upstream down", classifyError(nested).message)
        val cloud = HttpException(403, """{"detail":"not linked to a cloud login"}""", emptyMap(), "u")
        assertEquals("not linked to a cloud login", classifyError(cloud).message)
    }

    @Test
    fun `falls back to the status line when the body said nothing`() {
        assertEquals("HTTP 500 (http://node/admin-api/x)", classifyError(httpError(500)).message)
        val html = HttpException(502, "<html>bad gateway</html>", emptyMap(), "u")
        assertEquals("HTTP 502 (u)", classifyError(html).message)
    }

    @Test
    fun `a revoked token family is an unauthorized refusal`() {
        val c = classifyError(AuthRevokedException("token_reuse", 401, "", emptyMap(), "u"))
        assertEquals(ErrorKind.UNAUTHORIZED, c.kind)
        assertEquals(401, c.status)
    }

    @Test
    fun `a request that never connected is unreachable`() {
        assertEquals(
            ClassifiedError(ErrorKind.UNREACHABLE, 0, true, "Failed to connect"),
            classifyError(NetworkException("Failed to connect")),
        )
        assertEquals(ClassifiedError(ErrorKind.UNREACHABLE, null, true, "refused"), classifyError(ConnectException("refused")))
        assertEquals(ErrorKind.UNREACHABLE, classifyError(SocketTimeoutException("timeout")).kind)
    }

    @Test
    fun `reads the status the account and relay exceptions carry`() {
        val refused = classifyError(IntentRefusedException("not a member", retryable = false, status = 403))
        assertEquals(ClassifiedError(ErrorKind.FORBIDDEN, 403, false, "not a member"), refused)
        // Refused for its nonce: the same intent re-signed with a fresh one can go through.
        assertTrue(classifyError(IntentRefusedException("nonce already used", retryable = true, status = 400)).retryable)
        assertEquals(ErrorKind.CONFLICT, classifyError(NotAViewException("ctx", "set")).kind)
        val join = classifyError(JoinException(JoinStep.ADMIT, "admit refused", status = 503))
        assertEquals(ErrorKind.UNAVAILABLE, join.kind)
        assertEquals("admit refused", join.message)
        assertEquals(ErrorKind.UNKNOWN, classifyError(JoinException(JoinStep.NO_NODES, "nobody")).kind)
        val ha = classifyError(AccountHaRefusedException(AccountHaRefusedException.ACCOUNT_NOT_LINKED, 403, ""))
        assertEquals(ErrorKind.FORBIDDEN, ha.kind)
        assertEquals(AccountHaRefusedException.ADVICE[AccountHaRefusedException.ACCOUNT_NOT_LINKED], ha.message)
    }

    @Test
    fun `a read-only node refusing a write is not retryable here`() {
        val c = classifyError(ReadOnlyWriteRefusedException("ctx", -32000, "discarded", JsonNull))
        assertEquals(ErrorKind.FORBIDDEN, c.kind)
        assertFalse(c.retryable)
    }

    @Test
    fun `keeps anything else retryable, with its message`() {
        assertEquals(ClassifiedError(ErrorKind.UNKNOWN, null, true, "boom"), classifyError(IllegalStateException("boom")))
        assertEquals(ErrorKind.UNKNOWN, classifyError(RpcException(-32000, "method failed")).kind)
        assertEquals("no creds", classifyError(MeroStateException("no creds")).message)
        assertEquals(ClassifiedError(ErrorKind.UNKNOWN, null, true, "Request failed"), classifyError(null))
        assertEquals("Request failed", classifyError(RuntimeException()).message)
    }
}
