package com.calimero.mero.admin

import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.NetworkException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** mero-js `redeem-invitation.test.ts`. */
class RedeemInvitationTest {
    private val ns = "77".repeat(32)
    private val invitation = SignedGroupOpenInvitation(JsonObject(emptyMap()))

    private class Redeemer(
        val joinError: Exception? = null,
        val listed: List<String>? = emptyList(),
    ) : InviteRedeemer {
        var joins = 0

        override suspend fun join(
            namespaceId: String,
            invitation: SignedGroupOpenInvitation,
        ) {
            joins++
            joinError?.let { throw it }
        }

        override suspend fun memberships(): List<String> = listed ?: throw NetworkException("could not list")
    }

    private fun http(
        status: Int,
        body: String = "",
    ) = HttpException(status, body, emptyMap(), "http://node/admin-api/namespaces/$ns/join")

    private suspend fun failed(
        error: Exception,
        listed: List<String>? = emptyList(),
    ) = redeemInvitation(ns, invitation, Redeemer(error, listed)) as RedeemOutcome.Failed

    @Test
    fun `reports a clean join`() =
        runBlocking {
            val outcome = redeemInvitation(ns, invitation, Redeemer(), teamName = "Team")
            assertEquals(RedeemOutcome.Joined(ns, "Team"), outcome)
            assertTrue(isSettled(outcome))
            assertFalse(shouldRetain(outcome))
        }

    @Test
    fun `reports already-member when the request failed but the node joined, and sends one join`() =
        runBlocking {
            val redeemer = Redeemer(NetworkException("timeout"), listOf(ns))
            assertEquals(RedeemOutcome.AlreadyMember(ns), redeemInvitation(ns, invitation, redeemer))
            assertEquals(1, redeemer.joins)
        }

    @Test
    fun `keeps a resolved join even when the membership check throws`() =
        runBlocking {
            assertEquals(RedeemOutcome.Joined(ns), redeemInvitation(ns, invitation, Redeemer(listed = null)))
        }

    @Test
    fun `treats an unreadable membership list as unknown, not as absent`() =
        runBlocking {
            val outcome = failed(http(503), listed = null)
            assertEquals(InviteFailureReason.NO_ONE_ONLINE, outcome.reason)
            assertTrue(outcome.retryable)
            assertTrue(shouldRetain(outcome))
        }

    @Test
    fun `carries the node's own words, not the status line`() =
        runBlocking {
            assertEquals("no member is online", failed(http(503, """{"error":"no member is online"}""")).message)
        }

    @Test
    fun `reads the node's status`() =
        runBlocking {
            assertEquals(InviteFailureReason.SIGNED_OUT to true, failed(http(401)).let { it.reason to it.retryable })
            assertEquals(InviteFailureReason.INVALID to false, failed(http(400)).let { it.reason to it.retryable })
            assertEquals(InviteFailureReason.EXPIRED to false, failed(http(410)).let { it.reason to it.retryable })
            assertEquals(InviteFailureReason.REFUSED to false, failed(http(403)).let { it.reason to it.retryable })
            assertEquals(InviteFailureReason.REFUSED to false, failed(http(409)).let { it.reason to it.retryable })
            val expired409 = failed(http(409, """{"error":"invitation for group abc expired at 1700000000"}"""))
            assertEquals(InviteFailureReason.EXPIRED, expired409.reason)
            assertEquals(InviteFailureReason.UNKNOWN to true, failed(http(404)).let { it.reason to it.retryable })
        }

    @Test
    fun `still recognises an old node's refusal by its message`() =
        runBlocking {
            val old = failed(http(500, """{"error":"invitation has expired"}"""))
            assertEquals(InviteFailureReason.EXPIRED to false, old.reason to old.retryable)
            assertEquals(InviteFailureReason.INVALID, failed(http(500, """{"error":"malformed invitation"}""")).reason)
            assertEquals(InviteFailureReason.UNKNOWN to true, failed(http(500, """{"error":"boom"}""")).let { it.reason to it.retryable })
        }

    @Test
    fun `treats a request that never reached the node as unreachable`() =
        runBlocking {
            val outcome = failed(NetworkException("connection refused"))
            assertEquals(InviteFailureReason.NODE_UNREACHABLE to true, outcome.reason to outcome.retryable)
            assertEquals("node-unreachable", outcome.reason.wire)
        }

    // SEC-02: the id comes from the invite, so a crafted one must not reach a path.
    @Test
    fun `refuses an invite whose namespace id is not one`() =
        runBlocking {
            for (bad in listOf("abc/leave?x=", "../contexts/x", "$ns/leave?x=", "AB".repeat(32), "")) {
                val redeemer = Redeemer()
                val outcome = redeemInvitation(bad, invitation, redeemer, teamName = "Team")
                assertEquals(bad, RedeemOutcome.Failed::class, outcome::class)
                outcome as RedeemOutcome.Failed
                assertEquals(bad, null to (InviteFailureReason.INVALID to false), outcome.namespaceId to (outcome.reason to outcome.retryable))
                assertEquals(bad, 0, redeemer.joins)
            }
        }

    @Test
    fun `AdminApi redeemInvitation joins and settles by the namespace listing`() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(504))
            server.enqueue(MockResponse().setBody("""{"data":[{"namespaceId":"$ns","appKey":"a","targetApplicationId":"b","createdAt":0,"memberCount":1,"contextCount":0,"subgroupCount":0}]}"""))
            server.start()
            try {
                val mero = Mero(MeroConfig(baseUrl = server.url("/").toString()))
                assertEquals(RedeemOutcome.AlreadyMember(ns, "T"), mero.admin.redeemInvitation(ns, invitation, groupName = "g", teamName = "T"))
                val join = server.takeRequest()
                assertEquals("/admin-api/namespaces/$ns/join", join.path)
                assertTrue(join.body.readUtf8().contains("\"groupName\":\"g\""))
                assertEquals("/admin-api/namespaces", server.takeRequest().path)
            } finally {
                server.shutdown()
            }
        }
}
