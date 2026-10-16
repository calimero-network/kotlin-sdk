package com.calimero.mero.compose.invitations

/** What the logged-out invitation screen shows — mero-react `InvitationGate`'s `data-state`. */
enum class InvitationGateStage(
    val wire: String,
) {
    /** The session is still restoring: a spinner, so the landing page does not flash. */
    CHECKING("checking"),

    /** Logged out with a readable invitation: "You're invited", Not now / Log in to join. */
    INVITED("invited"),

    /** The link cannot be read: say so, without asking for a login. */
    INVALID("invalid"),
}

/** The gate's decision: what to show (null = get out of the way), and the team it names. */
data class InvitationGateDecision(
    val stage: InvitationGateStage?,
    val teamName: String? = null,
)

/**
 * Decide the logged-out invitation screen — mero-react `InvitationGate`. Nothing to show with no
 * [pending] invitation or once signed in (the invitation dialog asks, joins and syncs then); a
 * spinner while the session is [loading]; else [InvitationGateStage.INVITED], or
 * [InvitationGateStage.INVALID] when [parse] cannot read the token. Without a [parse] the screen
 * falls back to the noun. Reading the invitation never takes it: the app's redemption still
 * receives it after login.
 */
fun decideInvitationGate(
    pending: CapturedInvitation?,
    isAuthenticated: Boolean,
    loading: Boolean,
    parse: ((token: String, params: Map<String, String>) -> ParsedInvitation?)? = null,
): InvitationGateDecision {
    if (pending == null || isAuthenticated) return InvitationGateDecision(null)
    if (loading) return InvitationGateDecision(InvitationGateStage.CHECKING)
    if (parse == null) return InvitationGateDecision(InvitationGateStage.INVITED)
    val parsed = runCatching { parse(pending.token, pending.params) }.getOrNull()
    if (parsed == null || parsed.namespaceId.isEmpty()) return InvitationGateDecision(InvitationGateStage.INVALID)
    return InvitationGateDecision(InvitationGateStage.INVITED, parsed.teamName)
}

/** The gate screen's title and body. */
data class InvitationGateCopy(
    val title: String,
    val body: String,
)

/** mero-react `InvitationGate`'s words for [stage] (not [InvitationGateStage.CHECKING], which has none). */
fun invitationGateCopy(
    stage: InvitationGateStage,
    teamName: String? = null,
    noun: String = "team",
    appName: String? = null,
): InvitationGateCopy {
    if (stage == InvitationGateStage.INVALID) {
        return InvitationGateCopy(
            "This invitation can't be read",
            "The link may have been cut short when it was shared. Ask whoever sent it for a new one.",
        )
    }
    val title = if (teamName != null) "You're invited to join “$teamName”" else "You're invited to join a $noun"
    val where = appName?.let { " in $it" }.orEmpty()
    return InvitationGateCopy(
        title,
        "Someone sent you a link to join this $noun$where. Log in to accept it; you'll be asked to confirm before you join.",
    )
}
