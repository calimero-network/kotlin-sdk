package com.calimero.mero.http

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A parsed HTTP response (any status). */
data class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
    val url: String,
) {
    val isSuccessful: Boolean get() = status in 200..299

    /** Throws [HttpException] (or [AuthRevokedException]) when the status is not 2xx. */
    fun ensureSuccessful(): HttpResponse {
        if (isSuccessful) return this
        throw HttpException(status, body, headers, url)
    }
}

/**
 * The transport contract every API client is built on. The OkHttp-backed implementation
 * ([OkHttpTransport]) owns the Bearer-injection interceptor, the reactive 401→refresh
 * [okhttp3.Authenticator], and terminal `x-auth-error` detection.
 *
 * Analogous to mero-js's `HttpClient` interface, but the typed convenience methods live as
 * `inline reified` extension functions below so kotlinx.serialization can synthesize serializers
 * at the call site.
 */
interface HttpClient {
    /** JSON codec used by the typed extension helpers. */
    val json: Json

    /**
     * Execute a request and return the raw response for any status. Only terminal auth errors
     * ([AuthRevokedException]) and true transport failures ([NetworkException]) throw here; a plain
     * non-2xx is returned so callers can decide via [HttpResponse.ensureSuccessful].
     */
    suspend fun execute(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/json",
    ): HttpResponse
}

// ---- Typed helpers -------------------------------------------------------------------------

suspend inline fun <reified T> HttpClient.getJson(
    path: String,
    headers: Map<String, String> = emptyMap(),
): T {
    val res = execute("GET", path, headers = headers).ensureSuccessful()
    return json.decodeFromString(res.body)
}

suspend inline fun <reified B, reified T> HttpClient.postJson(
    path: String,
    body: B,
    headers: Map<String, String> = emptyMap(),
): T {
    val res = execute("POST", path, json.encodeToString(body), headers).ensureSuccessful()
    return json.decodeFromString(res.body)
}

suspend inline fun <reified B, reified T> HttpClient.putJson(
    path: String,
    body: B,
    headers: Map<String, String> = emptyMap(),
): T {
    val res = execute("PUT", path, json.encodeToString(body), headers).ensureSuccessful()
    return json.decodeFromString(res.body)
}

suspend inline fun <reified T> HttpClient.deleteJson(
    path: String,
    headers: Map<String, String> = emptyMap(),
): T {
    val res = execute("DELETE", path, headers = headers).ensureSuccessful()
    return json.decodeFromString(res.body)
}
