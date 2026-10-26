package com.calimero.mero.http

import com.calimero.mero.tee.SealedTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.IOException

/**
 * OkHttp-backed [HttpClient]. The wired [client] carries the [AuthInterceptor] (Bearer injection)
 * and the [TokenAuthenticator] (reactive 401→refresh); this class adds URL building, JSON media
 * types, and terminal `x-auth-error` detection. Plain http to a node that is not local is refused
 * with [InsecureTransportException] before anything is sent ([TransportSecurity]).
 *
 * **Response size.** [execute] reads a body up to [maxResponseBytes] (default
 * [DEFAULT_MAX_RESPONSE_BYTES]); a larger one fails with [NetworkException] and the connection
 * is dropped, so a hostile node, relay or man-in-the-middle cannot make the app buffer an
 * unbounded answer. [executeBytes] carries blobs and is not limited.
 *
 * Port of mero-js `WebHttpClient` (`web-client.ts`).
 */
class OkHttpTransport(
    private val baseUrl: String,
    private val client: OkHttpClient,
    override val json: Json,
    /** Invoked when the server reports the token family is gone (terminal). Must clear the store. */
    private val onAuthRevoked: (() -> Unit)? = null,
    /** The largest body [execute] reads, in bytes. */
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) : HttpClient {
    override suspend fun execute(
        method: String,
        path: String,
        body: String?,
        headers: Map<String, String>,
        contentType: String,
    ): HttpResponse {
        val res = send(method, path, body?.toRequestBody(contentType.toMediaType()), headers, maxResponseBytes)
        return HttpResponse(res.status, res.headers, res.bodyText, res.url)
    }

    override suspend fun executeBytes(
        method: String,
        path: String,
        body: ByteArray?,
        headers: Map<String, String>,
        contentType: String,
    ): HttpBytesResponse = send(method, path, body?.toRequestBody(contentType.toMediaType()), headers, limit = null)

    /**
     * One request, its response body read as raw bytes; [execute] decodes them as UTF-8.
     * [limit] is the most body bytes to accept, or null for no limit.
     */
    private suspend fun send(
        method: String,
        path: String,
        requestBody: RequestBody?,
        headers: Map<String, String>,
        limit: Long?,
    ): HttpBytesResponse =
        withContext(Dispatchers.IO) {
            val url = buildUrl(path)
            // Before the auth and proof interceptors run: nothing is attached to a refused request.
            if (!sealed) TransportSecurity.checkNodeUrl(url)

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
                } catch (e: RequestProofFailure) {
                    throw e.cause
                } catch (e: IOException) {
                    throw NetworkException(e.message ?: "Network error", e)
                }

            response.use { resp ->
                val bodyBytes = resp.body.readWithin(limit)
                val responseHeaders = resp.headers.names().associateWith { resp.header(it) ?: "" }
                val authError = resp.header("x-auth-error")

                // Terminal auth: the family is revoked. Do not refresh/retry — clear tokens and surface
                // a distinguishable error. (mero-js §4.3 / overview §4.3.)
                if ((resp.code == 401 || resp.code == 403) && authError != null && authError in TERMINAL_AUTH_ERRORS) {
                    onAuthRevoked?.invoke()
                    throw AuthRevokedException(authError, resp.code, bodyBytes.toString(Charsets.UTF_8), responseHeaders, url)
                }

                HttpBytesResponse(resp.code, responseHeaders, bodyBytes, url)
            }
        }

    /**
     * Whether [client] seals every request to an attested TEE ([SealedTransport]): end to end
     * encrypted, so plain http to its node is not refused ([TransportSecurity]).
     */
    private val sealed: Boolean = client.interceptors.any { it is SealedTransport }

    private fun buildUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = baseUrl.trimEnd('/')
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    companion object {
        /** The default cap on a response body: 16 MiB, far above any JSON answer a node gives. */
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 16L * 1024 * 1024

        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
    }
}

/**
 * The body, read up to [limit] bytes: a `Content-Length` over it is refused before the body is
 * read, and a body that grows past it is cut off there. Either way [NetworkException] is
 * thrown; the caller closing the response drops the connection.
 */
internal fun ResponseBody.boundedBytes(limit: Long): ByteArray {
    if (contentLength() > limit) throw responseTooLarge(limit)
    val source = source()
    // Buffers at most limit + 1 bytes; true means there is more than [limit].
    if (source.request(limit + 1)) throw responseTooLarge(limit)
    return source.readByteArray()
}

/** The body's bytes: all of them with no [limit], else [boundedBytes]. No body reads as empty. */
private fun ResponseBody?.readWithin(limit: Long?): ByteArray =
    when {
        this == null -> ByteArray(0)
        limit == null -> bytes()
        else -> boundedBytes(limit)
    }

/** A response body over the transport's limit (SEC-08). */
internal fun responseTooLarge(limit: Long): NetworkException = NetworkException("response body exceeds $limit bytes")
