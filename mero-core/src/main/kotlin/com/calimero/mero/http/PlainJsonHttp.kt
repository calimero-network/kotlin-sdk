package com.calimero.mero.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A bare JSON-over-HTTP helper for the account layer's clients (cloud manager, relay
 * intents, relay login), which talk to absolute URLs on hosts other than the node a
 * [com.calimero.mero.Mero] is bound to, and carry their own authentication (a routing
 * proof, a warrant, an optional Bearer) rather than the token store's.
 *
 * Non-2xx answers come back as an [HttpResponse]; only a transport failure throws
 * ([NetworkException]).
 */
internal class PlainJsonHttp(
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun execute(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).header("Accept", "application/json")
            val requestBody = body?.toRequestBody(JSON)
            when (method.uppercase()) {
                "GET" -> builder.get()
                "DELETE" -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
                "POST" -> builder.post(requestBody ?: "".toRequestBody(JSON))
                "PUT" -> builder.put(requestBody ?: "".toRequestBody(JSON))
                else -> error("Unsupported HTTP method: $method")
            }
            headers.forEach { (k, v) -> builder.header(k, v) }
            val response =
                try {
                    client.newCall(builder.build()).execute()
                } catch (e: IOException) {
                    throw NetworkException(e.message ?: "Network error", e)
                }
            response.use { resp ->
                HttpResponse(
                    status = resp.code,
                    headers = resp.headers.names().associateWith { resp.header(it).orEmpty() },
                    body = resp.body?.string().orEmpty(),
                    url = url,
                )
            }
        }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun defaultClient(timeoutMs: Long = 15_000): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
    }
}

/** Strip trailing slashes from a base URL. */
internal fun String.trimBase(): String = trimEnd('/')
