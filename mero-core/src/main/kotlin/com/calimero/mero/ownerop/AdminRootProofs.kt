package com.calimero.mero.ownerop

import com.calimero.mero.admin.AdminApi
import com.calimero.mero.admin.SetTeeAdmissionPolicyRequest
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.http.MeroStateException

/**
 * Mint the `rootProof` for [op] on [groupId], reading the namespace and the guarded-op
 * counter from the node first (`GET /admin-api/groups/{groupId}`), as the root-signer
 * overloads of the owner-op calls on [AdminApi] do.
 *
 * For a root op ([OwnerOpPlane.ROOT]) [groupId] is the namespace root, which is then both
 * the namespace and the group the proof names — core's `guard_root_op`.
 *
 * @throws MeroStateException when the node reports no `namespaceId` / `ownerOpCounter` (it
 *   predates root-guarded ops, core rc.83).
 */
suspend fun AdminApi.mintRootProof(
    groupId: String,
    op: OwnerOp,
    root: DeviceSigner,
    keyEpoch: Long = 0,
    chain: List<String> = emptyList(),
): String {
    val info = getGroupInfo(groupId)
    val counter =
        info.ownerOpCounter
            ?: throw MeroStateException("the node reported no ownerOpCounter for $groupId; it predates root-guarded owner ops")
    val namespaceId =
        if (op.plane == OwnerOpPlane.ROOT) {
            groupId
        } else {
            info.namespaceId
                ?: throw MeroStateException("the node reported no namespaceId for $groupId; it predates root-guarded owner ops")
        }
    return signOwnerOpProof(
        OwnerOpProofInput(
            namespaceId = namespaceId,
            groupId = groupId,
            op = op,
            counter = counter,
            signer = root,
            keyEpoch = keyEpoch,
            chain = chain,
        ),
    )
}

/** core's tag prefix on a node release (`calimero_tee_release::NODE_RELEASE_TAG_PREFIX`). */
private const val NODE_RELEASE_TAG_PREFIX = "mero-tee-v"

/**
 * The op the node builds from a `PUT …/settings/tee-admission-policy` body: the
 * signed-release form when [SetTeeAdmissionPolicyRequest.signedRelease] is set, the
 * measurement form otherwise. `minReleaseVersion` is normalised as the node normalises it
 * (trimmed, `mero-tee-v` dropped), so the proof is for the op it builds.
 */
fun teeAdmissionPolicyOpOf(request: SetTeeAdmissionPolicyRequest): OwnerOp {
    val release = request.signedRelease
    return if (release != null) {
        teeReleaseAdmissionPolicyOp(
            TeeReleaseAdmissionPolicyOpInput(
                allowedProfiles = release.allowedProfiles,
                minReleaseVersion = release.minReleaseVersion?.trim()?.removePrefix(NODE_RELEASE_TAG_PREFIX),
                allowedTcbStatuses = request.allowedTcbStatuses,
                acceptMock = request.acceptMock,
                mode = request.mode,
            ),
        )
    } else {
        teeAdmissionPolicyOp(
            TeeAdmissionPolicyOpInput(
                allowedMrtd = request.allowedMrtd,
                allowedRtmr0 = request.allowedRtmr0,
                allowedRtmr1 = request.allowedRtmr1,
                allowedRtmr2 = request.allowedRtmr2,
                allowedRtmr3 = request.allowedRtmr3,
                allowedTcbStatuses = request.allowedTcbStatuses,
                acceptMock = request.acceptMock,
                mode = request.mode,
            ),
        )
    }
}
