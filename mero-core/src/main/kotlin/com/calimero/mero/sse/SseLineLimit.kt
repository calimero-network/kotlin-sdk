package com.calimero.mero.sse

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException

/**
 * Holds an event stream's lines to a size limit (SEC-08). okhttp-sse buffers a line however
 * long it grows, and joins an event's `data:` lines however many there are, so a hostile node,
 * relay or man-in-the-middle could make the app hold an endless one.
 *
 * A line ends at `\n`, `\r\n` or `\r`, as the SSE spec has it. A line over [limit] bytes, or
 * an event whose fields (every non-comment line since the last blank one) add up to more, fails
 * the read with [SseLimitExceeded], which drops the connection; [SseConnection] reports it and
 * reconnects. Comment lines (`:` keep-alives) never count towards an event. Only a
 * `text/event-stream` answer is touched.
 */
internal class SseLineLimit(
    private val limit: Long,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        val type = body.contentType()
        if (type?.type != "text" || type.subtype != "event-stream") return response
        return response.newBuilder().body(LimitedBody(body, limit)).build()
    }

    private class LimitedBody(
        private val body: ResponseBody,
        limit: Long,
    ) : ResponseBody() {
        private val source = LineLimitedSource(body.source(), limit).buffer()

        override fun contentType(): MediaType? = body.contentType()

        override fun contentLength(): Long = body.contentLength()

        override fun source(): BufferedSource = source
    }
}

/** A line or an event over the stream's limit. An [IOException], so it reads as a dropped stream. */
internal class SseLimitExceeded(
    what: String,
    limit: Long,
) : IOException("SSE $what exceeds $limit bytes")

/** Counts every byte that passes, failing the read once a line or an event is over [limit]. */
internal class LineLimitedSource(
    delegate: Source,
    private val limit: Long,
) : ForwardingSource(delegate) {
    private val scratch = Buffer()
    private var lineBytes = 0L
    private var eventBytes = 0L
    private var lineIsComment = false
    private var afterCR = false

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        val n = super.read(scratch, byteCount)
        if (n <= 0) return n
        val bytes = scratch.readByteArray()
        for (byte in bytes) count(byte.toInt())
        sink.write(bytes)
        return n
    }

    private fun count(byte: Int) {
        if (afterCR) {
            afterCR = false
            if (byte == LF) return // the `\n` of a `\r\n`
        }
        if (byte == LF || byte == CR) {
            afterCR = byte == CR
            // A blank line ends the event.
            if (lineBytes == 0L) eventBytes = 0
            lineBytes = 0
            return
        }
        if (lineBytes == 0L) lineIsComment = byte == COLON
        if (lineBytes >= limit) throw SseLimitExceeded("line", limit)
        lineBytes++
        if (!lineIsComment && ++eventBytes > limit) throw SseLimitExceeded("event", limit)
    }

    private companion object {
        const val LF = 0x0a
        const val CR = 0x0d
        const val COLON = 0x3a
    }
}
