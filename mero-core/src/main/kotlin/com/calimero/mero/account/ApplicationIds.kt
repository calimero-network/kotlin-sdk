package com.calimero.mero.account

import com.calimero.mero.crypto.BorshWriter
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.sha256
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import java.net.URLEncoder

/** A bundle entry as `GET {registry}/api/v2/bundles?package=…` lists it. */
@Serializable
data class RegistryBundle(
    val `package`: String? = null,
    val signerId: String? = null,
    val appVersion: String? = null,
    val yanked: Boolean = false,
    /**
     * The oldest node runtime that installs this bundle (`minRuntimeVersion`, or
     * `min_runtime_version`). A node older than this refuses the install outright.
     */
    val minRuntimeVersion: String? = null,
)

/** An application resolved from the registry: what a founder names in `TargetApplicationSet`. */
data class ResolvedApplication(
    val applicationId: String,
    val packageName: String,
    val signerId: String,
    val version: String,
)

/**
 * An application's id, computed the way merod computes it, for an account that installs
 * nothing and so has to derive it.
 *
 * Port of mero-js `src/account/application-id.ts`. core's `ApplicationId::for_bundle` is
 * `sha256(borsh((package, signer_id)))`: the version is not an input, so every version a
 * publisher ships under one package is the same application.
 */
@Suppress("TooManyFunctions")
object ApplicationIds {
    /** The hosted app registry mero-react defaults to. */
    const val DEFAULT_REGISTRY_URL = "https://apps.calimero.network"

    private val json = Json { ignoreUnknownKeys = true }

    /** `sha256(borsh((pkg, signerId)))` as lowercase hex. */
    fun applicationIdForBundle(
        packageName: String,
        signerId: String,
    ): String = Hex.encode(sha256(BorshWriter().string(packageName).string(signerId).toByteArray()))

    /** How many bundles [newestCompatible] reads before giving up. */
    internal const val COMPATIBLE_WALK_LIMIT = 64

    /**
     * The newest version that is not yanked. With [runtimeVersion], only versions a node on
     * that runtime can install (`minRuntimeVersion` ≤ runtime); without it, any version.
     */
    fun selectLatestBundle(
        bundles: List<RegistryBundle>,
        runtimeVersion: String? = null,
    ): RegistryBundle? =
        bundles
            .filter { !it.yanked && !it.appVersion.isNullOrEmpty() && supports(runtimeVersion, it.minRuntimeVersion) }
            .fold(null as RegistryBundle?) { best, b ->
                if (best == null || compareVersions(b.appVersion!!, best.appVersion!!) > 0) b else best
            }

    /**
     * Semver precedence: negative when [a] sorts first, 0 when equal, positive when [a] sorts
     * last. A prerelease sorts before its release (`0.12.0-rc.1` < `0.12.0`) and after every
     * earlier release (`0.11.1` < `0.12.0-rc.1`); prerelease identifiers compare numerically
     * when both are numbers (`rc.9` < `rc.10`). Build metadata is ignored.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun compareVersions(
        a: String,
        b: String,
    ): Int {
        val x = SemVer.parse(a)
        val y = SemVer.parse(b)
        for (i in 0 until maxOf(x.core.size, y.core.size)) {
            val c = x.core.getOrElse(i) { 0 }.compareTo(y.core.getOrElse(i) { 0 })
            if (c != 0) return c.sign()
        }
        if (x.pre.isEmpty() || y.pre.isEmpty()) return y.pre.size.sign() - x.pre.size.sign()
        for (i in 0 until minOf(x.pre.size, y.pre.size)) {
            val l = x.pre[i]
            val r = y.pre[i]
            val ln = l.toIntOrNull()
            val rn = r.toIntOrNull()
            val c =
                when {
                    ln != null && rn != null -> ln.compareTo(rn)
                    ln != null -> -1
                    rn != null -> 1
                    else -> l.compareTo(r)
                }
            if (c != 0) return c.sign()
        }
        return x.pre.size
            .compareTo(y.pre.size)
            .sign()
    }

    /**
     * Whether a node on [runtimeVersion] installs a bundle that needs [minRuntimeVersion]. An
     * unknown runtime or an undeclared minimum is no reason to refuse.
     */
    fun supports(
        runtimeVersion: String?,
        minRuntimeVersion: String?,
    ): Boolean {
        if (runtimeVersion.isNullOrEmpty() || minRuntimeVersion.isNullOrEmpty()) return true
        return compareVersions(minRuntimeVersion, runtimeVersion) <= 0
    }

    /**
     * The node's runtime version, read out of the error it answers an install of a too-new
     * bundle with: "bundle requires runtime version X but current runtime is Y". The node
     * exposes its runtime on no route, so this refusal is the one place it says it.
     */
    fun runtimeVersionFromInstallError(text: String): String? {
        val start = text.indexOf("current runtime is ").takeIf { it >= 0 } ?: return null
        val version =
            text
                .substring(start + "current runtime is ".length)
                .takeWhile { it.isLetterOrDigit() || it in ".-+" }
                .trim('.', '-', '+')
        return version.ifEmpty { null }
    }

    /** The runtime a node names when it refuses a bundle too new for it. */
    internal fun runtimeVersionRefusedBy(error: Throwable): String? {
        val text = if (error is HttpException) "${error.bodyText} ${error.message}" else error.message.orEmpty()
        return runtimeVersionFromInstallError(text)
    }

    /**
     * The newest of [versions] — less [yanked] and [skipping] (already checked) — whose bundle
     * a node on [runtimeVersion] installs. Reads bundles newest first through [bundle], at
     * most [limit] of them.
     */
    internal suspend fun newestCompatible(
        versions: List<String>,
        yanked: List<String>,
        skipping: List<String>,
        runtimeVersion: String,
        limit: Int = COMPATIBLE_WALK_LIMIT,
        bundle: suspend (String) -> RegistryBundle,
    ): String? {
        val excluded = (yanked + skipping).toSet()
        val candidates =
            versions
                .distinct()
                .filter { it.isNotEmpty() && it !in excluded }
                .sortedWith { p, q -> compareVersions(q, p) }
        for (version in candidates.take(limit)) {
            val b = bundle(version)
            if (!b.yanked && supports(runtimeVersion, b.minRuntimeVersion)) return version
        }
        return null
    }

    /**
     * The application an account founds [packageName] on, learned from the registry.
     * Refused when the live versions name more than one publisher: that is two different
     * applications under one package name, and picking either would commit the founder to
     * an app it did not choose.
     */
    suspend fun resolveFromRegistry(
        packageName: String,
        registryUrl: String = DEFAULT_REGISTRY_URL,
        httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
    ): ResolvedApplication = resolveFromRegistry(packageName, registryUrl, PlainJsonHttp(httpClient, role = "registry"))

    @Suppress("ThrowsCount")
    internal suspend fun resolveFromRegistry(
        packageName: String,
        registryUrl: String,
        http: PlainJsonHttp,
    ): ResolvedApplication {
        val url = "${registryUrl.trimBase()}/api/v2/bundles?package=${URLEncoder.encode(packageName, "UTF-8")}"
        val res = http.execute("GET", url)
        if (!res.isSuccessful) throw MeroStateException("the registry has no $packageName (HTTP ${res.status})")
        val bundles = parseBundles(res.body)
        val live = bundles.filter { !it.yanked }
        val signers = live.mapNotNull { it.signerId?.ifEmpty { null } }.distinct()
        if (signers.size > 1) {
            throw MeroStateException(
                "the registry lists $packageName under more than one publisher (${signers.joinToString()}): refusing to guess which application it is",
            )
        }
        val latest = selectLatestBundle(live) ?: throw MeroStateException("the registry lists no version of $packageName")
        val signerId =
            latest.signerId?.ifEmpty { null } ?: throw MeroStateException("the registry names no publisher for $packageName ${latest.appVersion}")
        return ResolvedApplication(applicationIdForBundle(packageName, signerId), packageName, signerId, latest.appVersion!!)
    }

    /** The bundles a registry listing body names; an entry that does not decode is skipped. */
    internal fun parseBundles(body: String): List<RegistryBundle> {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
        return (parsed as? JsonArray)?.mapNotNull(::bundleOf).orEmpty()
    }

    /** One bundle (`GET {registry}/api/v2/bundles/{package}/{version}`), or null when it does not decode. */
    internal fun parseBundle(body: String): RegistryBundle? = runCatching { json.parseToJsonElement(body) }.getOrNull()?.let(::bundleOf)

    private fun bundleOf(element: JsonElement): RegistryBundle? {
        val bundle = runCatching { json.decodeFromJsonElement(RegistryBundle.serializer(), element) }.getOrNull() ?: return null
        val snake = ((element as? JsonObject)?.get("min_runtime_version") as? JsonPrimitive)?.contentOrNull
        return if (bundle.minRuntimeVersion == null && snake != null) bundle.copy(minRuntimeVersion = snake) else bundle
    }

    private fun Int.sign(): Int = compareTo(0)

    private class SemVer(
        val core: List<Int>,
        val pre: List<String>,
    ) {
        companion object {
            fun parse(raw: String): SemVer {
                val v =
                    raw
                        .trim()
                        .removePrefix("v")
                        .removePrefix("V")
                        .substringBefore('+')
                val main = v.substringBefore('-')
                val pre = if ('-' in v) v.substringAfter('-').split('.').filter { it.isNotEmpty() } else emptyList()
                return SemVer(main.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }, pre)
            }
        }
    }
}
