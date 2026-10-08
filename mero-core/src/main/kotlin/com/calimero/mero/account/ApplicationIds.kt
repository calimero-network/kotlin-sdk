package com.calimero.mero.account

import com.calimero.mero.crypto.BorshWriter
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.sha256
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.OkHttpClient
import java.net.URLEncoder

/** A bundle entry as `GET {registry}/api/v2/bundles?package=…` lists it. */
@Serializable
data class RegistryBundle(
    val `package`: String? = null,
    val signerId: String? = null,
    val appVersion: String? = null,
    val yanked: Boolean = false,
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
object ApplicationIds {
    /** The hosted app registry mero-react defaults to. */
    const val DEFAULT_REGISTRY_URL = "https://apps.calimero.network"

    private val json = Json { ignoreUnknownKeys = true }

    /** `sha256(borsh((pkg, signerId)))` as lowercase hex. */
    fun applicationIdForBundle(
        packageName: String,
        signerId: String,
    ): String = Hex.encode(sha256(BorshWriter().string(packageName).string(signerId).toByteArray()))

    /** The newest version that is not yanked (numeric compare over `.`/`-` parts). */
    fun selectLatestBundle(bundles: List<RegistryBundle>): RegistryBundle? =
        bundles
            .filter { !it.yanked && !it.appVersion.isNullOrEmpty() }
            .fold(null as RegistryBundle?) { best, b -> if (best == null || newer(b.appVersion!!, best.appVersion!!)) b else best }

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
    ): ResolvedApplication = resolveFromRegistry(packageName, registryUrl, PlainJsonHttp(httpClient))

    @Suppress("ThrowsCount")
    internal suspend fun resolveFromRegistry(
        packageName: String,
        registryUrl: String,
        http: PlainJsonHttp,
    ): ResolvedApplication {
        val url = "${registryUrl.trimBase()}/api/v2/bundles?package=${URLEncoder.encode(packageName, "UTF-8")}"
        val res = http.execute("GET", url)
        if (!res.isSuccessful) throw MeroStateException("the registry has no $packageName (HTTP ${res.status})")
        val parsed = runCatching { json.parseToJsonElement(res.body) }.getOrNull()
        val bundles = (parsed as? JsonArray)?.mapNotNull { runCatching { json.decodeFromJsonElement(RegistryBundle.serializer(), it) }.getOrNull() }.orEmpty()
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

    private fun newer(
        a: String,
        b: String,
    ): Boolean {
        val x = a.split('.', '-').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }
}
