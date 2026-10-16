package com.calimero.mero.compose

import com.calimero.mero.discovery.ANDROID_EMULATOR_HOST
import com.calimero.mero.discovery.DEFAULT_LOCAL_NODE_PORTS
import com.calimero.mero.discovery.LOCALHOST
import com.calimero.mero.discovery.discoverLocalNodes
import com.calimero.mero.discovery.nodeEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** The two tabs of the sign-in sheet — mero-react `LoginModalTab`. */
enum class LoginModalTab {
    /** Node discovery plus a manual URL: sign in through a node's own login page. */
    NODE,

    /** Sign in with a Calimero account (the wallet). The default on a phone. */
    CLOUD,
}

/** The Swift kit's name for [LoginModalTab]. */
typealias LoginTab = LoginModalTab

private val IPV4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
private val DOMAIN = Regex("""^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""")
private const val MAX_OCTET = 255

/**
 * Whether a typed node URL is usable — mero-react `LoginModal`'s `isValidUrl`: http(s) (a missing
 * scheme reads as https), and a host that is `localhost`, a dotted IPv4 or a domain name.
 */
fun isValidNodeUrl(input: String): Boolean {
    if (input.isBlank()) return false
    val url = normalizeNodeUrl(input).toHttpUrlOrNull() ?: return false
    val host = url.host
    if (host == LOCALHOST) return true
    if (IPV4.matches(host)) return host.split('.').all { (it.toIntOrNull() ?: -1) in 0..MAX_OCTET }
    return DOMAIN.matches(host)
}

/** [input] trimmed, with `https://` when it names no scheme, and no trailing slash. */
fun normalizeNodeUrl(input: String): String {
    val t = input.trim()
    val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    return withScheme.trimEnd('/')
}

/** A compact label for a node (`localhost:2428`). */
fun displayNodeUrl(url: String): String = url.removePrefix("http://").removePrefix("https://").trimEnd('/')

/**
 * Whether the app is likely running on the Android emulator — where the development machine's
 * `localhost` is [ANDROID_EMULATOR_HOST]. A heuristic over `android.os.Build`.
 */
fun isProbablyEmulator(): Boolean =
    runCatching {
        val b =
            android.os.Build.FINGERPRINT
                .orEmpty() +
                android.os.Build.MODEL
                    .orEmpty() +
                android.os.Build.PRODUCT
                    .orEmpty()
        listOf("generic", "emulator", "sdk_gphone", "Android SDK built for", "google_sdk").any { b.contains(it, ignoreCase = true) }
    }.getOrDefault(false)

/** Where local nodes are looked for: [ANDROID_EMULATOR_HOST] on an emulator, else `localhost`. */
fun localDiscoveryHost(): String = if (isProbablyEmulator()) ANDROID_EMULATOR_HOST else LOCALHOST

private val probeClient: OkHttpClient by lazy {
    OkHttpClient
        .Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
}

/** mero-react's manual-URL check: `GET admin-api/is-authed` answers (200, or 401 for a node that wants a login). */
suspend fun probeNodeIsAuthed(nodeUrl: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            probeClient.newCall(Request.Builder().url(nodeEndpoint(nodeUrl, "admin-api/is-authed")).build()).execute().use {
                it.isSuccessful || it.code == HTTP_UNAUTHORIZED
            }
        }.getOrDefault(false)
    }

private const val HTTP_UNAUTHORIZED = 401

/**
 * The Node tab's logic — mero-react `LoginModal`'s node section: discover running local nodes
 * (offered first, the first one selected), always offer a manual URL, and on [connect] hand back
 * the URL to start the node's login with — a discovered one at once, a typed one only once it
 * answers ([probe]).
 */
class NodeLoginState(
    private val scope: CoroutineScope,
    initialUrl: String? = null,
    private val discover: suspend () -> List<String> = { discoverLocalNodes(DEFAULT_LOCAL_NODE_PORTS, localDiscoveryHost()) },
    private val probe: suspend (String) -> Boolean = ::probeNodeIsAuthed,
) {
    private val _discovered = MutableStateFlow<List<String>>(emptyList())
    val discovered: StateFlow<List<String>> = _discovered.asStateFlow()
    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)

    /** The chosen discovered node, or null for the manual URL. */
    val selected: StateFlow<String?> = _selected.asStateFlow()
    private val _customUrl = MutableStateFlow(initialUrl.orEmpty())
    val customUrl: StateFlow<String> = _customUrl.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var scan: Job? = null

    /** Whether [connect] may run now. */
    val canConnect: Boolean
        get() = !_loading.value && !_discovering.value && (_selected.value != null || isValidNodeUrl(_customUrl.value))

    /** Look for local nodes again. */
    fun rescan() {
        scan?.cancel()
        _discovering.value = true
        _discovered.value = emptyList()
        _error.value = null
        scan =
            scope.launch {
                val nodes =
                    try {
                        discover()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                _discovered.value = nodes
                _selected.value = nodes.firstOrNull()
                _discovering.value = false
            }
    }

    /** Choose a discovered node, or null for the manual URL. */
    fun select(url: String?) {
        _selected.value = url
    }

    fun setCustomUrl(value: String) {
        _customUrl.value = value
    }

    /** The node to log in to, or null (with [error] set when a typed URL did not answer). */
    suspend fun connect(): String? {
        val chosen = _selected.value
        if (chosen != null) return chosen.trimEnd('/')
        val typed = _customUrl.value
        if (!isValidNodeUrl(typed)) return null
        val url = normalizeNodeUrl(typed)
        _loading.value = true
        _error.value = null
        try {
            if (probe(url)) return url
            _error.value = "Failed to connect. Please check the URL and try again."
            return null
        } finally {
            _loading.value = false
        }
    }
}
