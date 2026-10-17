package com.calimero.mero.compose.invitations

import com.calimero.mero.admin.InviteFailureReason

/**
 * What to tell a person about a failed join, or null to show the node's own message (an
 * [InviteFailureReason.UNKNOWN] failure has nothing better to offer) — mero-react
 * `describeInviteFailure`. [noun] is what the app calls the thing being joined: "team", "space".
 */
fun describeInviteFailure(
    reason: InviteFailureReason,
    noun: String = "team",
): String? =
    when (reason) {
        InviteFailureReason.EXPIRED -> "This invitation has expired. Ask for a new link."
        InviteFailureReason.INVALID -> "This invitation link isn't valid. Ask for a new one."
        InviteFailureReason.REFUSED -> "You can't join this $noun with this invitation. Ask an admin to invite you again."
        InviteFailureReason.SIGNED_OUT -> "Your session with your node has ended. Sign in again to finish joining."
        InviteFailureReason.NO_ONE_ONLINE ->
            "No one in this $noun is online to let you in yet. It will try again the next time you open the app."
        InviteFailureReason.NODE_UNREACHABLE -> "Couldn't reach your node. Check that it is running, then try again."
        InviteFailureReason.UNKNOWN -> null
    }
