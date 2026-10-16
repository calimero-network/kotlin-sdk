package com.calimero.mero.account

import com.calimero.mero.http.trimBase
import com.calimero.mero.relay.AttestingRelayKeyVerifier
import com.calimero.mero.relay.DcapRelayKeyVerifier
import com.calimero.mero.relay.isLoopbackRelay
import com.calimero.mero.tee.SealedTransport
import com.calimero.mero.tee.createAttestedSealedFetch
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/*
 * Everything a delegated session sends a hosted relay is sealed to the relay's attested TD
 * (mero-js `relayTransportFetch`, `src/account/session.ts`), so TLS that ends at the relay's
 * ingress, and whoever holds its certificate, reads none of it: not the login, not the token,
 * not a query or its result, not an event.
 *
 * A relay whose node leaves auth to its proxy, and has no `[server.sealed] forward_auth`, cannot
 * guard a logged-in route inside the envelope and answers `403 sealed_route_unguarded` from
 * inside it. Only then does a logged-in request go out plain, as it did before sealing: that
 * answer is sealed under the session, so nobody on the way can forge it to force the downgrade.
 * Anything else that goes wrong — an attestation refused, a relay that does not answer — fails
 * the request; it never falls back.
 */

private val sealedRelays = ConcurrentHashMap<Pair<String, OkHttpClient>, SealedTransport>()

/** Forget every relay's sealed transport (and its session), so the next request attests again. mero-js `forgetRelaySealing`; for tests. */
fun forgetRelaySealing() {
    sealedRelays.clear()
}

/**
 * The client a delegated session uses for [relayUrl]: sealed to its attested TD, one transport
 * per relay so its session is shared — the TD's quote checked by this account's
 * [AttestingRelayKeyVerifier] (by default a [DcapRelayKeyVerifier]). This account's plain client for a loopback relay
 * (a dev rig answering mock quotes), or with [CloudAccount.sealRelays] off.
 */
fun CloudAccount.relayTransportClient(relayUrl: String): OkHttpClient {
    if (!sealRelays || isLoopbackRelay(relayUrl)) return httpClient
    val origin = relayUrl.trimBase()
    return sealedRelays
        .getOrPut(origin to httpClient) {
            val verifier = (relayKeyVerifier as? AttestingRelayKeyVerifier ?: DcapRelayKeyVerifier(httpClient = httpClient)).quoteVerifier(origin)
            createAttestedSealedFetch(origin, verifier, httpClient = httpClient).also { it.plainWhenUnguarded = true }
        }.client
}
