package com.calimero.mero.discovery

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Local-node discovery. Port of mero-js `src/nodeDiscovery.ts`.
//
// Calimero dev nodes expose a public, unauthenticated health endpoint at
// `GET {nodeUrl}/admin-api/health` answering `{"data":{"status":"alive"}}`. The common local
// layouts run on a small, well-known set of ports, so instead of making the person type a URL
// an app can probe those ports and offer whatever is actually running.
//
// On Android, "localhost" is the device itself. A node on the development machine is reached
// from the **emulator** at [ANDROID_EMULATOR_HOST] (`10.0.2.2`), and from a physical device over
// `adb reverse tcp:2428 tcp:2428` (then `localhost` works) or the machine's LAN address. Pass the
// host that fits. A cleartext `http://` node also needs the app's network security config to
// allow it.

/**
 * Ports probed by [discoverLocalNodes] by default: the two-node dev stacks used across the
 * Calimero apps (RPC + alt ports for node1 / node2).
 */
val DEFAULT_LOCAL_NODE_PORTS: List<Int> = listOf(2428, 2429, 2528, 2529)

/** The default host for local candidates, as mero-js uses. */
const val LOCALHOST: String = "localhost"

/** The Android emulator's alias for the development machine's loopback interface. */
const val ANDROID_EMULATOR_HOST: String = "10.0.2.2"

/** Per-probe timeout: a closed port refuses fast, but a firewalled one can hang. */
const val DEFAULT_PROBE_TIMEOUT_MS: Long = 2_000

/** The base URL of a local node on [port]: `http://localhost:2428`. */
fun localNodeUrl(
    port: Int,
    host: String = LOCALHOST,
): String {
    val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    return "http://$h:$port"
}

/**
 * Resolve an API [path] against a node [baseUrl], keeping a path prefix on the base
 * (`http://host/node1` + `admin-api/health` → `http://host/node1/admin-api/health`) instead of
 * relative resolution dropping it. A [path] with a leading `/` resolves from the host root,
 * as in mero-js (WHATWG URL resolution).
 */
fun nodeEndpoint(
    baseUrl: String,
    path: String,
): String {
    val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    return base.toHttpUrlOrNull()?.resolve(path)?.toString() ?: (base + path.trimStart('/'))
}

/**
 * Probe one node's health endpoint. True only when it answers 2xx and, when the body is JSON,
 * reports a status of `"alive"` (case-insensitive) — `{"data":{"status":…}}`, or a plain
 * `{"status":…}`. A 2xx with no status, or with a body that is not JSON, counts as alive; a
 * status that is present but not `"alive"` (including `null`) does not.
 *
 * Never throws for a network error, a timeout or a non-2xx: those are `false`. Cancelling the
 * calling coroutine cancels the probe (and propagates, as cancellation should).
 */
suspend fun probeNodeHealth(
    baseUrl: String,
    timeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS,
    client: OkHttpClient = DiscoveryHttp.client,
): Boolean {
    val url = nodeEndpoint(baseUrl, "admin-api/health").toHttpUrlOrNull() ?: return false
    val call =
        client
            .newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .build(),
            )
    return withTimeoutOrNull(timeoutMs) {
        try {
            call.await().use { res ->
                if (!res.isSuccessful) return@use false
                isAlive(res.body?.string().orEmpty())
            }
        } catch (_: IOException) {
            false
        }
    } ?: false
}

/**
 * Probe [ports] on [host] in parallel and return the base URLs that answered healthy, in the
 * order of [ports]. Empty when nothing local is running.
 *
 * ```kotlin
 * val nodes = discoverLocalNodes(host = ANDROID_EMULATOR_HOST) // from the emulator
 * ```
 */
suspend fun discoverLocalNodes(
    ports: List<Int> = DEFAULT_LOCAL_NODE_PORTS,
    host: String = LOCALHOST,
    timeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS,
    client: OkHttpClient = DiscoveryHttp.client,
): List<String> =
    coroutineScope {
        ports
            .map { port ->
                async {
                    val url = localNodeUrl(port, host)
                    url.takeIf { probeNodeHealth(it, timeoutMs, client) }
                }
            }.awaitAll()
            .filterNotNull()
    }

private val LENIENT = Json { isLenient = true }

private fun isAlive(body: String): Boolean {
    val obj = runCatching { LENIENT.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return true
    val data = obj["data"] as? JsonObject
    // mero-js: `data.status` when the key is there (even as null), else the top-level `status`.
    val status = if (data != null && data.containsKey("status")) data["status"] else obj["status"]
    return when (status) {
        null -> true
        JsonNull -> false
        is JsonPrimitive -> status.isString && status.content.equals("alive", ignoreCase = true)
        else -> false
    }
}

/** The OkHttp client discovery uses by default: no retries, so a closed port fails once and fast. */
object DiscoveryHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(DEFAULT_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DEFAULT_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}

private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    cont.resume(response) { _, value, _ -> value.close() }
                }
            },
        )
    }
