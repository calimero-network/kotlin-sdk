package com.calimero.mero.compose.invitations

import com.calimero.mero.account.BootstrapResult
import com.calimero.mero.account.DelegatedSession
import com.calimero.mero.account.JoinStep
import com.calimero.mero.admin.InviteFailureReason
import com.calimero.mero.admin.JoinContextResponseData
import com.calimero.mero.admin.JoinNamespaceRequest
import com.calimero.mero.admin.JoinNamespaceResponseData
import com.calimero.mero.admin.RedeemOutcome
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.compose.MemoryKeyValueStore
import com.calimero.mero.compose.MeroSession
import com.calimero.mero.compose.isReturningFromWallet
import com.calimero.mero.compose.state.StateFakeAdmin
import com.calimero.mero.http.HttpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
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

/**
 * Ports of mero-react `useJoinSync.test.tsx`, `InvitationDialog.test.tsx` (its stage logic),
 * `InvitationGate.test.tsx` (its state logic), the `join` tests and `isReturningFromWallet`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinSyncAndDialogTest {
    @Before
    fun setUp() = JustJoinedNamespaces.resetForTests()

    @After
    fun tearDown() = JustJoinedNamespaces.resetForTests()

    // ---- JoinSync -------------------------------------------------------------------------

    @Test
    fun `the gate covers only a namespace this process just joined, until its data settles`() =
        runTest {
            val sync = JoinSync(backgroundScope) { testScheduler.currentTime }
            sync.update("ns", settled = false)
            runCurrent()
            assertFalse(sync.isSyncing.value)
            markNamespaceJustJoined("ns")
            runCurrent()
            assertTrue(sync.isSyncing.value)
            sync.update("other", settled = false)
            runCurrent()
            assertFalse(sync.isSyncing.value)
            sync.update(null, settled = false)
            runCurrent()
            assertFalse(sync.isSyncing.value)
            // Settled (an empty answer counts) lifts it and forgets the namespace.
            sync.update("ns", settled = true)
            runCurrent()
            assertFalse(sync.isSyncing.value)
            assertFalse(isNamespaceJustJoined("ns"))
            sync.close()
        }

    @Test
    fun `the gate expires at the base window when nothing is in flight`() =
        runTest {
            markNamespaceJustJoined("ns")
            val sync = JoinSync(backgroundScope) { testScheduler.currentTime }
            sync.update("ns", settled = false)
            runCurrent()
            advanceTimeBy(JOIN_SYNC_WATCHDOG_MS - 1)
            runCurrent()
            assertTrue(sync.isSyncing.value)
            advanceTimeBy(2)
            runCurrent()
            assertFalse(sync.isSyncing.value)
            sync.close()
        }

    @Test
    fun `an active sync holds the gate past the base window, but never past the ceiling`() =
        runTest {
            markNamespaceJustJoined("ns")
            val sync = JoinSync(backgroundScope) { testScheduler.currentTime }
            sync.update("ns", settled = false, active = true)
            runCurrent()
            advanceTimeBy(JOIN_SYNC_WATCHDOG_MS + JOIN_SYNC_POLL_MS * 3)
            runCurrent()
            assertTrue(sync.isSyncing.value)
            advanceTimeBy(JOIN_SYNC_MAX_MS)
            runCurrent()
            assertFalse(sync.isSyncing.value)
            sync.close()
        }

    @Test
    fun `the gate can be dismissed by hand, and a namespace marked twice is listed once`() =
        runTest {
            markNamespaceJustJoined("ns")
            markNamespaceJustJoined("ns")
            assertEquals(setOf("ns"), JustJoinedNamespaces.namespaces.value)
            val sync = JoinSync(backgroundScope)
            sync.update("ns", settled = false)
            runCurrent()
            sync.dismiss()
            runCurrent()
            assertFalse(sync.isSyncing.value)
            sync.close()
        }

    // ---- InvitationDialogModel --------------------------------------------------------------

    private val ns = "ns-1"
    private val invitation = SignedGroupOpenInvitation(JsonObject(emptyMap()))

    private fun kotlinx.coroutines.test.TestScope.dialog(
        outcome: CompletableDeferred<RedeemOutcome>,
        confirm: Boolean = true,
    ): Pair<InviteRedemption<BasicParsedInvitation>, InvitationDialogModel> {
        val capture = InvitationCapture(MemoryKeyValueStore()).also { it.capture("calimero://app/join?invitation=TOK") }
        val redemption =
            InviteRedemption(
                backgroundScope,
                parse = { _, _ -> BasicParsedInvitation(ns, invitation, "Design") },
                redeem = { outcome.await() },
                confirm = confirm,
                capture = capture,
            )
        return redemption to InvitationDialogModel(redemption, backgroundScope)
    }

    @Test
    fun `the dialog asks, joins, syncs until the flag clears, says so, and closes itself`() =
        runTest {
            val outcome = CompletableDeferred<RedeemOutcome>()
            val (redemption, model) = dialog(outcome)
            runCurrent()
            assertEquals(InvitationDialogStage.AWAITING_CONFIRMATION, model.stage.value)
            redemption.accept()
            runCurrent()
            assertEquals(InvitationDialogStage.JOINING, model.stage.value)
            // Not closed mid-join.
            model.requestClose()
            assertEquals(InvitationDialogStage.JOINING, model.stage.value)
            outcome.complete(RedeemOutcome.Joined(ns, "Design"))
            runCurrent()
            assertEquals(InvitationDialogStage.SYNCING, model.stage.value)
            model.requestClose()
            assertEquals(InvitationDialogStage.SYNCING, model.stage.value)
            clearNamespaceJustJoined(ns)
            runCurrent()
            assertEquals(InvitationDialogStage.READY, model.stage.value)
            advanceTimeBy(INVITATION_READY_MS + 1)
            runCurrent()
            assertNull(model.stage.value)
            model.close()
        }

    @Test
    fun `the app root can end the wait with synced`() =
        runTest {
            val outcome = CompletableDeferred<RedeemOutcome>(RedeemOutcome.Joined(ns, null))
            val (_, model) = dialog(outcome, confirm = false)
            runCurrent()
            assertEquals(InvitationDialogStage.SYNCING, model.stage.value)
            model.setSynced(true)
            runCurrent()
            assertEquals(InvitationDialogStage.READY, model.stage.value)
            model.close()
        }

    @Test
    fun `a slow sync offers the background, and the ceiling still lets go of the flag`() =
        runTest {
            val (_, model) = dialog(CompletableDeferred(RedeemOutcome.Joined(ns, null)), confirm = false)
            runCurrent()
            assertFalse(model.slow.value)
            advanceTimeBy(JOIN_SYNC_WATCHDOG_MS + 1)
            runCurrent()
            assertTrue(model.slow.value)
            model.continueInBackground()
            runCurrent()
            assertNull(model.stage.value)
            assertTrue(isNamespaceJustJoined(ns))
            advanceTimeBy(JOIN_SYNC_MAX_MS)
            runCurrent()
            assertFalse(isNamespaceJustJoined(ns))
            model.close()
        }

    @Test
    fun `declining from the dialog acks, and a failure closes with Close`() =
        runTest {
            val (_, model) = dialog(CompletableDeferred())
            runCurrent()
            model.requestClose()
            runCurrent()
            assertNull(model.stage.value)
            val failed = CompletableDeferred<RedeemOutcome>(RedeemOutcome.Failed(ns, "gone", InviteFailureReason.EXPIRED, false))
            val (_, second) = dialog(failed, confirm = false)
            runCurrent()
            assertEquals(InvitationDialogStage.FAILED, second.stage.value)
            second.requestClose()
            runCurrent()
            assertNull(second.stage.value)
        }

    @Test
    fun `the dialog's words explain each stage, a failure in plain words`() {
        val asking = invitationDialogCopy(InvitationDialogStage.AWAITING_CONFIRMATION, InviteState.AwaitingConfirmation("Design"), "vault")
        assertEquals("Join “Design”?", asking.title)
        assertTrue(asking.body!!.contains("the vault's data"))
        assertEquals("Joining this team…", invitationDialogCopy(InvitationDialogStage.JOINING, InviteState.Joining()).title)
        assertEquals("Step 2 of 3", invitationDialogCopy(InvitationDialogStage.JOINING, InviteState.Joining(), status = "Step 2 of 3").body)
        assertTrue(invitationDialogCopy(InvitationDialogStage.SYNCING, InviteState.Joined(ns), slow = true).body!!.startsWith("Still waiting"))
        assertEquals("You're already in “D”", invitationDialogCopy(InvitationDialogStage.ALREADY_MEMBER, InviteState.AlreadyMember(ns, "D")).title)
        val failed = InviteState.Failed("node said no", InviteFailureReason.EXPIRED, false)
        assertEquals("This invitation has expired. Ask for a new link.", invitationDialogCopy(InvitationDialogStage.FAILED, failed).body)
        val unknown = InviteState.Failed("node said no", InviteFailureReason.UNKNOWN, true)
        assertEquals("node said no", invitationDialogCopy(InvitationDialogStage.FAILED, unknown).body)
    }

    // ---- InvitationGate ---------------------------------------------------------------------

    private fun pending(token: String = "TOK"): CapturedInvitation {
        val capture = InvitationCapture(MemoryKeyValueStore())
        capture.capture("calimero://app/join?invitation=$token")
        return capture.peekInvitation()!!
    }

    @Test
    fun `the gate shows only while logged out with a pending invitation`() {
        val parse: (String, Map<String, String>) -> ParsedInvitation? = { t, _ -> if (t == "BAD") null else BasicParsedInvitation(ns, invitation, "Design") }
        assertNull(decideInvitationGate(null, isAuthenticated = false, loading = false, parse).stage)
        assertNull(decideInvitationGate(pending(), isAuthenticated = true, loading = false, parse).stage)
        assertEquals(InvitationGateStage.CHECKING, decideInvitationGate(pending(), false, loading = true, parse).stage)
        assertEquals(InvitationGateDecision(InvitationGateStage.INVITED, "Design"), decideInvitationGate(pending(), false, false, parse))
        assertEquals(InvitationGateStage.INVALID, decideInvitationGate(pending("BAD"), false, false, parse).stage)
        assertEquals(InvitationGateDecision(InvitationGateStage.INVITED, null), decideInvitationGate(pending(), false, false, null))
        assertEquals("You're invited to join a vault", invitationGateCopy(InvitationGateStage.INVITED, null, "vault").title)
        assertTrue(invitationGateCopy(InvitationGateStage.INVITED, "Ops", appName = "Docs").body.contains("this team in Docs"))
        assertEquals("This invitation can't be read", invitationGateCopy(InvitationGateStage.INVALID).title)
    }

    // ---- JoinInvitation ------------------------------------------------------------------------

    @Test
    fun `isFinalInvitationError keeps the invitation for anything transient or unfamiliar`() {
        assertTrue(isFinalInvitationError("Invitation expired at 1700"))
        assertTrue(isFinalInvitationError("signature verification failed"))
        assertTrue(isFinalInvitationError("You are already a member"))
        assertFalse(isFinalInvitationError("connection reset"))
        assertFalse(isFinalInvitationError(null))
    }

    private val session = DelegatedSession("a".repeat(64), "d".repeat(64), "cred")

    @Test
    fun `on a node the join is the namespace, then the named context`() =
        runTest {
            val calls = mutableListOf<String>()
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun joinNamespace(
                        namespaceId: String,
                        request: JoinNamespaceRequest,
                    ): JoinNamespaceResponseData {
                        calls += "ns:$namespaceId"
                        return kotlinx.serialization.json.Json
                            .decodeFromString(JoinNamespaceResponseData.serializer(), "{\"namespaceId\":\"$namespaceId\",\"memberIdentity\":\"m\"}")
                    }

                    override suspend fun joinContext(contextId: String): JoinContextResponseData {
                        calls += "ctx:$contextId"
                        return JoinContextResponseData(contextId, "pk")
                    }
                }
            val none = DelegatedBootstrap({ null }, { _, _, _, _ -> error("no") }, { _, _, _, _ -> })
            val join = JoinInvitation(MutableStateFlow(MeroSession(admin = admin, rawAdmin = admin)), none)
            assertEquals(JoinInvitationResult.Ok, join.joinInvitation(JoinInvitationInput("ns", invitation)))
            assertEquals(listOf("ns:ns"), calls)
            join.joinInvitation(JoinInvitationInput("ns", invitation, contextId = "c"))
            assertEquals(listOf("ns:ns", "ns:ns", "ctx:c"), calls)
        }

    @Test
    fun `a node join failure carries its status and whether the invitation is dead`() =
        runTest {
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun joinNamespace(
                        namespaceId: String,
                        request: JoinNamespaceRequest,
                    ): JoinNamespaceResponseData = error("invalid invitation")
                }
            val join = JoinInvitation(MutableStateFlow(MeroSession(admin = admin, rawAdmin = admin)), DelegatedBootstrap({ null }, { _, _, _, _ -> error("") }, { _, _, _, _ -> }))
            val r = join.joinInvitation(JoinInvitationInput("ns", invitation)) as JoinInvitationResult.Failed
            assertEquals(JoinInvitationStep.NODE_JOIN, r.step)
            assertTrue(r.final)
            val noNode = JoinInvitation(MutableStateFlow(MeroSession()), DelegatedBootstrap({ null }, { _, _, _, _ -> error("") }, { _, _, _, _ -> }))
            assertEquals("Not connected to a node.", (noNode.joinInvitation(JoinInvitationInput("ns", invitation)) as JoinInvitationResult.Failed).reason)
        }

    @Test
    fun `an account bootstraps, adopts the joined session, and labels a failed step`() =
        runTest {
            val adopted = mutableListOf<String>()
            var answer: BootstrapResult = BootstrapResult.Success(session.copy(relayUrl = "https://relay"), relay = null, published = true)
            val boot =
                DelegatedBootstrap(
                    session = { session },
                    run = { _, _, _, _ -> answer },
                    adopt = { success, _, nsId, _ -> adopted += "${success.session.relayUrl}:$nsId" },
                )
            assertTrue(boot.needsBootstrap)
            val join = JoinInvitation(MutableStateFlow(MeroSession(isDelegated = true)), boot)
            assertEquals(JoinInvitationResult.Ok, join.joinInvitation(JoinInvitationInput("ns", invitation)))
            assertEquals(listOf("https://relay:ns"), adopted)

            answer = BootstrapResult.Failure(JoinStep.ADMIT, "refused", 403)
            val failed = join.joinInvitation(JoinInvitationInput("ns", invitation)) as JoinInvitationResult.Failed
            assertEquals(JoinInvitationStep.ADMIT, failed.step)
            assertEquals("The admitter refused the join. refused", failed.reason)
            assertTrue(failed.final)
            assertEquals(403, failed.status)
            answer = BootstrapResult.Failure(JoinStep.INVITED_UNREACHABLE, "later", null)
            assertFalse((join.joinInvitation(JoinInvitationInput("ns", invitation)) as JoinInvitationResult.Failed).final)

            // Its redeemer throws with the status, so redeemInvitation classifies the refusal.
            answer = BootstrapResult.Failure(JoinStep.ADMIT, "nope", 403)
            val thrown = runCatching { join.invitationRedeemer(JoinInvitationInput("ns", invitation)).join("ns", invitation) }.exceptionOrNull()
            assertEquals(403, (thrown as HttpException).status)
        }

    @Test
    fun `bootstrapping with no enrolled account names that step`() =
        runTest {
            val boot = DelegatedBootstrap({ null }, { _, _, _, _ -> error("not reached") }, { _, _, _, _ -> })
            assertFalse(boot.needsBootstrap)
            val r = boot.bootstrap("ns", invitation) as BootstrapResult.Failure
            assertEquals(JoinStep.NO_CREDENTIAL, r.step)
            assertEquals(r, boot.result.value)
        }

    @Test
    fun `isReturningFromWallet reads a completed or declined enrolment without consuming it`() {
        assertFalse(isReturningFromWallet("myapp://enrol"))
        assertFalse(isReturningFromWallet(null))
        assertTrue(isReturningFromWallet("myapp://enrol#credential=c&account=a&device=d&state=s"))
        assertTrue(isReturningFromWallet("myapp://enrol#error=cancelled"))
        assertFalse(isReturningFromWallet("myapp://enrol#credential=c"))
    }
}
