package com.calimero.mero.compose

import com.calimero.mero.http.AuthRevokedException
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.NetworkException
import com.calimero.mero.sse.NodeEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** mero-react `MeroContext.test.tsx` — "an SSE 401 must not destroy the token store", as decisions. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineMonitorTest {
    private fun http(
        status: Int,
        authError: String? = null,
    ) = HttpException(status, "", authError?.let { mapOf("x-auth-error" to it) }.orEmpty(), "http://node/sse")

    @Test
    fun `stream failures map to mero-react's three answers`() {
        assertEquals(StreamVerdict.LOGOUT, streamFailureVerdict(AuthRevokedException("token_revoked", 403, "", emptyMap(), "u"), false))
        assertEquals(StreamVerdict.LOGOUT, streamFailureVerdict(http(403, "permission_denied"), false))
        assertEquals(StreamVerdict.OFFLINE, streamFailureVerdict(http(403), false))
        assertEquals(StreamVerdict.RECOVER, streamFailureVerdict(http(401, "token_expired"), false))
        assertEquals(StreamVerdict.RECOVER, streamFailureVerdict(IllegalStateException("SSE connection failed: 401"), false))
        assertEquals(StreamVerdict.OFFLINE, streamFailureVerdict(NetworkException("offline"), false))
        // A delegated stream has no token to renew: never logged out from here.
        assertEquals(StreamVerdict.OFFLINE, streamFailureVerdict(http(401), true))
        assertEquals(StreamVerdict.OFFLINE, streamFailureVerdict(AuthRevokedException("token_reuse", 401, "", emptyMap(), "u"), true))
    }

    private class FakeEvents : MeroEvents {
        override val events = MutableSharedFlow<NodeEvent>()
        override val connects = MutableSharedFlow<String>()
        override val errors = MutableSharedFlow<Throwable>()
        override val failure: StateFlow<Throwable?> = MutableStateFlow(null)
        override val isConnected: Boolean = false
        var connectCalls = 0

        override fun connect() {
            connectCalls++
        }

        override suspend fun subscribe(
            contextIds: Collection<String>,
            groupIds: Collection<String>,
        ) = Unit
    }

    private class Rig(
        recoverAnswer: Boolean,
    ) {
        val events = FakeEvents()
        var online = true
        var logouts = 0
        var recoveries = 0
        val monitor =
            OnlineMonitor(
                events,
                delegated = false,
                setOnline = { online = it },
                recover = {
                    recoveries++
                    recoverAnswer
                },
                logout = { logouts++ },
            )
    }

    @Test
    fun `a connect is online, an ordinary failure offline, with no logout`() =
        runTest {
            val rig = Rig(recoverAnswer = true)
            val job = rig.monitor.start(this)
            advanceUntilIdle()
            assertEquals(1, rig.events.connectCalls)
            rig.events.errors.emit(NetworkException("down"))
            advanceUntilIdle()
            assertFalse(rig.online)
            rig.events.connects.emit("s1")
            advanceUntilIdle()
            assertTrue(rig.online)
            assertEquals(0, rig.logouts)
            job.cancel()
        }

    @Test
    fun `a recoverable 401 reconnects instead of logging out`() =
        runTest {
            val rig = Rig(recoverAnswer = true)
            val job = rig.monitor.start(this)
            advanceUntilIdle()
            rig.events.errors.emit(http(401, "token_expired"))
            advanceUntilIdle()
            assertEquals(1, rig.recoveries)
            assertEquals(2, rig.events.connectCalls)
            assertEquals(0, rig.logouts)
            job.cancel()
        }

    @Test
    fun `a dead session logs out, and so do a revoked family and a token that cannot subscribe`() =
        runTest {
            val rig = Rig(recoverAnswer = false)
            val job = rig.monitor.start(this)
            advanceUntilIdle()
            rig.events.errors.emit(http(401))
            advanceUntilIdle()
            assertEquals(1, rig.logouts)
            rig.events.errors.emit(AuthRevokedException("token_revoked", 403, "", emptyMap(), "u"))
            advanceUntilIdle()
            rig.events.errors.emit(http(403, "permission_denied"))
            advanceUntilIdle()
            assertEquals(3, rig.logouts)
            // A 403 a new token would not fix keeps the session.
            rig.events.errors.emit(http(403))
            advanceUntilIdle()
            assertEquals(3, rig.logouts)
            job.cancel()
        }
}
