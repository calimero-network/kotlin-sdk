package com.calimero.mero.sample.mock

import androidx.compose.runtime.Composable

/**
 * Release builds carry no mock backend: mero-testkit is a debug-only dependency, and the
 * `mock` launch extra is ignored (see `MainActivity`). The debug variant of this object is the
 * real one.
 */
object MockBackend {
    const val AVAILABLE = false

    fun start(): MockSession = error("The mock backend is only in debug builds.")

    fun walletCard(
        @Suppress("UNUSED_PARAMETER") deliver: (String) -> Unit,
    ): (@Composable () -> Unit)? = null

    fun clearPending() = Unit
}
