package com.calimero.mero.ephemeral

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Encoding for a presence slice. The node never deserializes it — the encoding is chosen
 * client-side and travels client to client, so every member of a context must agree on
 * it. Port of mero-js `Codec<T>` (bytes as a [ByteArray] rather than `number[]`).
 */
interface Codec<T> {
    fun encode(value: T): ByteArray

    fun decode(bytes: ByteArray): T
}

/**
 * The default codec: [value] as JSON text, UTF-8 — byte-identical to mero-js `jsonCodec()`
 * (`TextEncoder().encode(JSON.stringify(value))`) for the same JSON value.
 */
class JsonCodec<T>(
    private val serializer: KSerializer<T>,
    private val json: Json = Json,
) : Codec<T> {
    override fun encode(value: T): ByteArray = json.encodeToString(serializer, value).encodeToByteArray()

    override fun decode(bytes: ByteArray): T = json.decodeFromString(serializer, bytes.decodeToString())
}

/** A [JsonCodec] for [T]'s serializer: `jsonCodec<Cursor>()`. */
inline fun <reified T> jsonCodec(json: Json = Json): Codec<T> = JsonCodec(serializer<T>(), json)

/** The bytes as they are — for an app that encodes its slice itself. */
object RawCodec : Codec<ByteArray> {
    override fun encode(value: ByteArray): ByteArray = value

    override fun decode(bytes: ByteArray): ByteArray = bytes
}
