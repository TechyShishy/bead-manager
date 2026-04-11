package com.techyshishy.beadmanager.data.rgp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

private val rgpJson = Json { ignoreUnknownKeys = true }

sealed class RgpParseException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    class NotGzip(cause: Throwable) : RgpParseException("Stream is not gzip-compressed", cause)
    class InvalidJson(cause: Throwable) : RgpParseException("RGP JSON is invalid or corrupt", cause)
}

/**
 * Parses a `.rgp` file from [stream].
 *
 * The stream is read once and closed by the caller. Throws [RgpParseException.NotGzip] if the
 * stream is not gzip-compressed, or [RgpParseException.InvalidJson] if the decompressed content
 * is not valid RGP JSON (including a missing `colorMapping` field, which is required).
 */
fun parseRgp(stream: InputStream): RgpProject {
    val json = try {
        GZIPInputStream(stream).bufferedReader().use { it.readText() }
    } catch (e: ZipException) {
        throw RgpParseException.NotGzip(e)
    }
    return try {
        rgpJson.decodeFromString<RgpProject>(json)
    } catch (e: SerializationException) {
        throw RgpParseException.InvalidJson(e)
    } catch (e: IllegalArgumentException) {
        throw RgpParseException.InvalidJson(e)
    }
}
