package com.calimero.mero.compose.invitations

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** How long "You're in" stays up before the dialog closes by itself. */
const val INVITATION_READY_MS = 1_200L

/** The stage the invitation dialog shows: redemption's, plus the wait for the data. */
enum class InvitationDialogStage(
    /** mero-react's `data-stage`. */
    val wire: String,
) {
    AWAITING_CONFIRMATION("awaiting-confirmation"),
    JOINING("joining"),
    SYNCING("syncing"),
    READY("ready"),
    ALREADY_MEMBER("already-member"),
    FAILED("failed"),
}

/**
 * The logic of mero-react's `InvitationDialog`, without the UI: ask, join, wait for the data,
 * done.
 *
 * `SYNCING` is the stage apps used to skip: a join returns before the namespace's data has
 * arrived. It lasts while the namespace's just-joined flag is set ([JustJoinedNamespaces], set by
 * redemption) and ends when the screen that reads the data clears it ([JoinSync] with
 * `settled`), when the app says so here ([setSynced]), or at [JOIN_SYNC_MAX_MS]. Past
 * [JOIN_SYNC_WATCHDOG_MS] the sync is [slow] and may be sent to the background
 * ([continueInBackground]); the ceiling still clears the flag then. `READY` closes itself after
 * [INVITATION_READY_MS].
 */
class InvitationDialogModel(
    private val redemption: InviteRedemption<*>,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val _stage = MutableStateFlow<InvitationDialogStage?>(null)
    val stage: StateFlow<InvitationDialogStage?> = _stage.asStateFlow()
    private val _slow = MutableStateFlow(false)

    /** The sync is past the watchdog: offer "Continue in background". */
    val slow: StateFlow<Boolean> = _slow.asStateFlow()

    private val synced = MutableStateFlow(false)
    private var backgroundId: String? = null
    private var timedFor: Pair<String?, Boolean>? = null
    private var timers: Job? = null
    private var readyTimer: Job? = null

    private val job: Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(redemption.state, JustJoinedNamespaces.namespaces, synced) { state, _, isSynced -> state to isSynced }
                .collect { (state, isSynced) -> recompute(state, isSynced) }
        }

    /** The joined namespace's data has been read (for an app whose root already knows). */
    fun setSynced(value: Boolean) {
        synced.value = value
    }

    /**
     * What closing means for the stage: decline while asking, nothing mid-join or mid-sync (a
     * stray tap must not drop the person on a screen whose data has not arrived), dismiss otherwise.
     */
    fun requestClose() {
        when (_stage.value) {
            InvitationDialogStage.AWAITING_CONFIRMATION -> redemption.decline()
            InvitationDialogStage.JOINING, InvitationDialogStage.SYNCING, null -> Unit
            else -> redemption.dismiss()
        }
    }

    /** Leave a slow sync to finish in the background; the flag stays for the screen that reads the data. */
    fun continueInBackground() = redemption.dismiss()

    private fun recompute(
        state: InviteState,
        isSynced: Boolean,
    ) {
        val joinedId = (state as? InviteState.Joined)?.namespaceId
        if (joinedId != null) backgroundId = joinedId
        val waitingId = joinedId ?: backgroundId
        val flagged = JustJoinedNamespaces.isJustJoined(waitingId)
        if (joinedId == null && backgroundId != null && !flagged) backgroundId = null
        if (waitingId != null && flagged && isSynced) JustJoinedNamespaces.clear(waitingId)
        retime(waitingId, flagged)
        val next =
            when (state) {
                InviteState.Idle -> null
                is InviteState.Joined -> if (flagged) InvitationDialogStage.SYNCING else InvitationDialogStage.READY
                is InviteState.AwaitingConfirmation -> InvitationDialogStage.AWAITING_CONFIRMATION
                is InviteState.Joining -> InvitationDialogStage.JOINING
                is InviteState.AlreadyMember -> InvitationDialogStage.ALREADY_MEMBER
                is InviteState.Failed -> InvitationDialogStage.FAILED
            }
        if (next != _stage.value) {
            _stage.value = next
            readyTimer?.cancel()
            readyTimer =
                if (next == InvitationDialogStage.READY) {
                    scope.launch {
                        delay(INVITATION_READY_MS)
                        redemption.dismiss()
                    }
                } else {
                    null
                }
        }
    }

    /** The slow mark and the ceiling run per (waiting namespace, flagged). */
    private fun retime(
        waitingId: String?,
        flagged: Boolean,
    ) {
        val key = waitingId to flagged
        if (key == timedFor) return
        timedFor = key
        timers?.cancel()
        _slow.value = false
        if (waitingId == null || !flagged) return
        timers =
            scope.launch {
                launch {
                    delay(JOIN_SYNC_WATCHDOG_MS)
                    _slow.value = true
                }
                delay(JOIN_SYNC_MAX_MS)
                JustJoinedNamespaces.clear(waitingId)
            }
    }

    override fun close() {
        job.cancel()
        timers?.cancel()
        readyTimer?.cancel()
    }
}

/** The dialog's icon. */
enum class InvitationIcon { INVITE, SPINNER, DONE, ERROR }

/** What the dialog says at a stage. */
data class InvitationDialogCopy(
    val title: String,
    val body: String?,
    val icon: InvitationIcon,
)

/**
 * The dialog's words for [stage] — mero-react `InvitationDialog`'s copy. [detail] replaces the
 * question's body; [status] says which step a slow join is on.
 */
@Suppress("LongParameterList")
fun invitationDialogCopy(
    stage: InvitationDialogStage,
    state: InviteState,
    noun: String = "team",
    slow: Boolean = false,
    detail: String? = null,
    status: String? = null,
): InvitationDialogCopy {
    val team = state.namedTeam
    val named = if (team != null) "“$team”" else "this $noun"
    return when (stage) {
        InvitationDialogStage.AWAITING_CONFIRMATION ->
            InvitationDialogCopy(
                if (team != null) "Join “$team”?" else "Join this $noun?",
                detail ?: "You have been invited. Joining makes this device a member, and the $noun's data syncs to it from the other members.",
                InvitationIcon.INVITE,
            )
        InvitationDialogStage.JOINING ->
            InvitationDialogCopy(
                "Joining $named…",
                status?.ifEmpty { null } ?: "Asking a member to let you in. With few members online this can take up to a minute.",
                InvitationIcon.SPINNER,
            )
        InvitationDialogStage.SYNCING ->
            InvitationDialogCopy(
                "Syncing $named…",
                if (slow) {
                    "Still waiting on the other members. You can carry on; the $noun fills in as its data arrives."
                } else {
                    "You're in. Getting the $noun's data from the other members."
                },
                InvitationIcon.SPINNER,
            )
        InvitationDialogStage.READY -> InvitationDialogCopy("You're in $named", null, InvitationIcon.DONE)
        InvitationDialogStage.ALREADY_MEMBER -> InvitationDialogCopy("You're already in $named", null, InvitationIcon.DONE)
        InvitationDialogStage.FAILED ->
            InvitationDialogCopy(
                "Couldn't join",
                (state as? InviteState.Failed)?.let { describeInviteFailure(it.reason, noun) ?: it.message },
                InvitationIcon.ERROR,
            )
    }
}
