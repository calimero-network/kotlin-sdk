package com.calimero.mero.compose.invitations

import com.calimero.mero.admin.InviteFailureReason
import com.calimero.mero.admin.InviteRedeemer
import com.calimero.mero.admin.RedeemOutcome
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.compose.MemoryKeyValueStore
import com.calimero.mero.http.HttpException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Ports of mero-react `invitations/useInviteRedemption.test.tsx`. */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteRedemptionTest {
    private val ns = "7d847b7afeab53bef1899496014ce7dae7cecc6e4fda9901b4bdcc706afcc807"
    private val store = MemoryKeyValueStore()
    private val invitation = SignedGroupOpenInvitation(JsonObject(emptyMap()))

    /** The app's codec: "BAD" is unreadable; anything else names [ns]. */
    private val parse: (String, Map<String, String>) -> ParsedInvitation? = { token, _ ->
        if (token == "BAD") null else BasicParsedInvitation(ns, invitation, "Design")
    }

    private class Redeemer(
        val joinError: Exception? = null,
        val listed: List<String>,
    ) : InviteRedeemer {
        var joins = 0

        override suspend fun join(
            namespaceId: String,
            invitation: SignedGroupOpenInvitation,
        ) {
            joins++
            joinError?.let { throw it }
        }

        override suspend fun memberships() = listed
    }

    @Before
    fun setUp() = JustJoinedNamespaces.resetForTests()

    @After
    fun tearDown() = JustJoinedNamespaces.resetForTests()

    private fun openWith(token: String): InvitationCapture =
        InvitationCapture(store).also { it.capture("calimero://com.example.app/join?invitation=$token") }

    @Suppress("LongParameterList")
    private fun TestScope.redemption(
        capture: InvitationCapture,
        redeemer: InviteRedeemer? = Redeemer(listed = listOf(ns)),
        redeem: (suspend (ParsedInvitation) -> RedeemOutcome)? = null,
        confirm: Boolean = false,
        onJoined: ((String, String?) -> Unit)? = null,
        enabled: MutableStateFlow<Boolean> = MutableStateFlow(true),
        parser: (String, Map<String, String>) -> ParsedInvitation? = parse,
    ) = InviteRedemption(backgroundScope, parser, redeemer, redeem, confirm, onJoined, enabled, capture)

    @Test
    fun `goes joining then joined, reports the namespace once, and marks it just joined`() =
        runTest {
            val joined = mutableListOf<Pair<String, String?>>()
            val r = redemption(openWith("TOK"), onJoined = { id, team -> joined += id to team })
            runCurrent()
            assertEquals(InviteState.Joined(ns, "Design"), r.state.value)
            assertEquals(listOf(ns to "Design"), joined)
            assertTrue(isNamespaceJustJoined(ns))
        }

    @Test
    fun `the app's own redeem flow runs with its parsed fields, and a throw is a retryable unknown`() =
        runTest {
            val seen = mutableListOf<String>()
            val r =
                redemption(openWith("TOK"), redeem = { p ->
                    seen += p.namespaceId
                    RedeemOutcome.Joined(p.namespaceId, p.teamName)
                })
            runCurrent()
            assertEquals(listOf(ns), seen)
            assertTrue(r.state.value is InviteState.Joined)

            val failing = redemption(openWith("TOK2"), redeem = { error("boom") })
            runCurrent()
            assertEquals(InviteState.Failed("boom", InviteFailureReason.UNKNOWN, true), failing.state.value)
        }

    @Test
    fun `a failed request whose join landed is already-member`() =
        runTest {
            val r = redemption(openWith("TOK"), Redeemer(HttpException(504, "", emptyMap(), "u"), listOf(ns)))
            runCurrent()
            assertEquals(InviteState.AlreadyMember(ns, "Design"), r.state.value)
        }

    @Test
    fun `a real failure surfaces the node's reason and keeps the token for another try`() =
        runTest {
            val capture = openWith("TOK")
            val r = redemption(capture, Redeemer(HttpException(503, "{\"error\":\"no member online\"}", emptyMap(), "u"), emptyList()))
            runCurrent()
            val failed = r.state.value as InviteState.Failed
            assertEquals(InviteFailureReason.NO_ONE_ONLINE, failed.reason)
            assertTrue(failed.retryable)
            assertEquals("TOK", r.token.value)
            // Retained: it replays on the next launch.
            assertEquals("TOK", InvitationCapture(store).peekInvitation()!!.token)
        }

    @Test
    fun `an unreadable invitation is acked instead of replaying, even when confirming`() =
        runTest {
            val r = redemption(openWith("BAD"), confirm = true)
            runCurrent()
            assertEquals(InviteState.Failed("That invitation could not be read.", InviteFailureReason.INVALID, false), r.state.value)
            assertNull(InvitationCapture(store).peekInvitation())
        }

    @Test
    fun `nothing joins until enabled`() =
        runTest {
            val enabled = MutableStateFlow(false)
            val redeemer = Redeemer(listed = listOf(ns))
            val r = redemption(openWith("TOK"), redeemer, enabled = enabled)
            runCurrent()
            assertEquals(InviteState.Idle, r.state.value)
            assertEquals(0, redeemer.joins)
            enabled.value = true
            runCurrent()
            assertTrue(r.state.value is InviteState.Joined)
        }

    @Test
    fun `retry joins again past the automatic cap`() =
        runTest {
            val redeemer = Redeemer(HttpException(503, "", emptyMap(), "u"), emptyList())
            val r = redemption(openWith("TOK"), redeemer)
            runCurrent()
            assertEquals(1, redeemer.joins)
            r.retry()
            runCurrent()
            assertEquals(2, redeemer.joins)
        }

    @Test
    fun `a confirmed invitation is retried straight away, without asking again`() =
        runTest {
            val redeemer = Redeemer(HttpException(503, "", emptyMap(), "u"), emptyList())
            val r = redemption(openWith("TOK"), redeemer, confirm = true)
            runCurrent()
            r.accept()
            runCurrent()
            assertTrue(r.state.value is InviteState.Failed)
            r.retry()
            runCurrent()
            assertEquals(2, redeemer.joins)
            assertTrue(r.state.value is InviteState.Failed)
        }

    @Test
    fun `it asks first by default, even once the automatic attempts are spent`() =
        runTest {
            store.put("calimero:invitation-attempts", "{\"TOK\":99}")
            val redeemer = Redeemer(listed = listOf(ns))
            val r = redemption(openWith("TOK"), redeemer, confirm = true)
            runCurrent()
            assertEquals(InviteState.AwaitingConfirmation("Design"), r.state.value)
            assertEquals("Design", r.parsed.value!!.teamName)
            assertEquals(0, redeemer.joins)
            r.accept()
            runCurrent()
            assertTrue(r.state.value is InviteState.Joined)
            assertEquals(1, redeemer.joins)
        }

    @Test
    fun `without confirming, a spent invitation is not auto-joined`() =
        runTest {
            store.put("calimero:invitation-attempts", "{\"TOK\":99}")
            val redeemer = Redeemer(listed = listOf(ns))
            val r = redemption(openWith("TOK"), redeemer)
            runCurrent()
            assertEquals(InviteState.Idle, r.state.value)
            assertEquals("TOK", r.token.value)
            assertEquals(0, redeemer.joins)
        }

    @Test
    fun `decline acks, so the prompt does not return every launch`() =
        runTest {
            val redeemer = Redeemer(listed = listOf(ns))
            val r = redemption(openWith("TOK"), redeemer, confirm = true)
            runCurrent()
            r.decline()
            assertEquals(InviteState.Idle, r.state.value)
            assertEquals(0, redeemer.joins)
            assertFalse(store.get("calimero:invitation-attempts").orEmpty().contains("TOK"))
            assertNull(InvitationCapture(store).peekInvitation())
        }

    @Test
    fun `the codec is handed every parameter the link carried`() =
        runTest {
            var params: Map<String, String>? = null
            val capture = InvitationCapture(store).also { it.capture("https://app.example.com/?invitation=TOK&kind=group&name=Ops") }
            redemption(capture, parser = { token, p ->
                params = p
                parse(token, p)
            })
            runCurrent()
            assertEquals(mapOf("invitation" to "TOK", "kind" to "group", "name" to "Ops"), params)
        }

    @Test
    fun `with no redeemer configured the join fails, finally`() =
        runTest {
            val r = redemption(openWith("TOK"), redeemer = null)
            runCurrent()
            assertFalse((r.state.value as InviteState.Failed).retryable)
        }

    @Test
    fun `describeInviteFailure names each reason in the app's noun, and defers on unknown`() {
        assertEquals("This invitation has expired. Ask for a new link.", describeInviteFailure(InviteFailureReason.EXPIRED))
        assertTrue(describeInviteFailure(InviteFailureReason.REFUSED, "vault")!!.contains("this vault"))
        assertTrue(describeInviteFailure(InviteFailureReason.NO_ONE_ONLINE, "space")!!.startsWith("No one in this space"))
        assertNull(describeInviteFailure(InviteFailureReason.UNKNOWN))
    }
}
