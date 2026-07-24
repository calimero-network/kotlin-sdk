package com.calimero.mero.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * OkHttp-backed [HttpClient]. The wired [client] carries the [AuthInterceptor] (Bearer injection)
 * and the [TokenAuthenticator] (reactive 401→refresh); this class adds URL building, JSON media
 * types, and terminal `x-auth-error` detection.
 *
 * Port of mero-js `WebHttpClient` (`web-client.ts`).
 */
class OkHttpTransport(
    private val baseUrl: String,
    private val client: OkHttpClient,
    override val json: Json,
    /** Invoked when the server reports the token family is gone (terminal). Must clear the store. */
    private val onAuthRevoked: (() -> Unit)? = null,
) : HttpClient {
    override suspend fun execute(
        method: String,
        path: String,
        body: String?,
        headers: Map<String, String>,
        contentType: String,
    ): HttpResponse =
        withContext(Dispatchers.IO) {
            val url = buildUrl(path)
            val requestBody: RequestBody? = body?.toRequestBody(contentType.toMediaType())

            val builder = Request.Builder().url(url)
            when (method.uppercase()) {
                "GET" -> builder.get()
                "HEAD" -> builder.head()
                "DELETE" -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
                "POST" -> builder.post(requestBody ?: EMPTY_BODY)
                "PUT" -> builder.put(requestBody ?: EMPTY_BODY)
                "PATCH" -> builder.patch(requestBody ?: EMPTY_BODY)
                else -> error("Unsupported HTTP method: $method")
            }
            headers.forEach { (key, value) -> builder.header(key, value) }

            val response =
                try {
                    client.newCall(builder.build()).execute()
                } catch (e: IOException) {
                    throw NetworkException(e.message ?: "Network error", e)
                }

            response.use { resp ->
                val bodyText = resp.body?.string() ?: ""
                val responseHeaders = resp.headers.names().associateWith { resp.header(it) ?: "" }
                val authError = resp.header("x-auth-error")

                // Terminal auth: the family is revoked. Do not refresh/retry — clear tokens and surface
                // a distinguishable error. (mero-js §4.3 / overview §4.3.)
                if ((resp.code == 401 || resp.code == 403) && authError != null && authError in TERMINAL_AUTH_ERRORS) {
                    onAuthRevoked?.invoke()
                    throw AuthRevokedException(authError, resp.code, bodyText, responseHeaders, url)
                }

                HttpResponse(resp.code, responseHeaders, bodyText, url)
            }
        }

    private fun buildUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = baseUrl.trimEnd('/')
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    private companion object {
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
    }
}
