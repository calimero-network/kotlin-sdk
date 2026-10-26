package com.calimero.mero.sample.mock

import com.calimero.mero.compose.MeroClient

/** A running mock backend: the [client] wired to it, and [stop] to shut the backend down. */
class MockSession(
    val client: MeroClient,
    val stop: () -> Unit,
)
