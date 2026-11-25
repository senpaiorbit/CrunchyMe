package com.crunchyme

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class HttpResult(
    val code: Int,
    val body: String,
    val bytes: ByteArray,
    val finalUrl: String,
    val headers: Map<String, String>,
)

interface HttpEngine {
    suspend fun request(
        url: String,
        headers: Map<String, String>,
        timeoutSec: Long,
        range: String? = null,
    ): HttpResult?

    suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
        timeoutSec: Long,
    ): HttpResult? = null
}

/** Plain OkHttp engine: works on Android (the app ships OkHttp) and on the JVM for tests. */
object OkHttpEngine : HttpEngine {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override suspend fun request(
        url: String,
        headers: Map<String, String>,
        timeoutSec: Long,
        range: String?,
    ): HttpResult? = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(url).get()
            if (!headers.keys.any { it.equals("User-Agent", true) }) {
                builder.header("User-Agent", Http.UA)
            }
            headers.forEach { (key, value) ->
                if (key.isNotBlank() && (value.isNotBlank() || key.equals("User-Agent", true))) {
                    builder.header(key, value)
                }
            }
            if (!range.isNullOrBlank()) builder.header("Range", range)
            val call = client.newCall(builder.build())
            call.timeout().timeout(timeoutSec.coerceIn(3, 60), TimeUnit.SECONDS)
            call.execute().use { response ->
                val raw = response.body.byteStream().use { stream ->
                    Http.readLimited(stream, Http.responseLimit(range))
                }
                HttpResult(
                    code = response.code,
                    body = raw.toString(Charsets.UTF_8),
                    bytes = raw,
                    finalUrl = response.request.url.toString(),
                    headers = response.headers.names().associateWith { response.headers[it] ?: "" },
                )
            }
        } catch (t: Throwable) {
            null
        }
    }

    override suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
        timeoutSec: Long,
    ): HttpResult? = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(url)
                .post(body.toRequestBody(contentType.toMediaType()))
            if (!headers.keys.any { it.equals("User-Agent", true) }) {
                builder.header("User-Agent", Http.UA)
            }
            headers.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) builder.header(key, value)
            }
            val call = client.newCall(builder.build())
            call.timeout().timeout(timeoutSec.coerceIn(3, 60), TimeUnit.SECONDS)
            call.execute().use { response ->
                val raw = response.body.byteStream().use { stream ->
                    Http.readLimited(stream, Http.responseLimit(null))
                }
                HttpResult(
                    code = response.code,
                    body = raw.toString(Charsets.UTF_8),
                    bytes = raw,
                    finalUrl = response.request.url.toString(),
                    headers = response.headers.names().associateWith { response.headers[it] ?: "" },
                )
            }
        } catch (t: Throwable) {
            null
        }
    }
}

object Http {
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    private const val DEFAULT_MAX_BYTES = 4 * 1024 * 1024

    @Volatile
    var engine: HttpEngine = OkHttpEngine

    /** Hard cap so a server that ignores Range cannot make us download a whole film. */
    fun responseLimit(range: String?): Int {
        if (range.isNullOrBlank()) return DEFAULT_MAX_BYTES
        val match = Regex("""bytes=(\d+)-(\d+)""").find(range) ?: return DEFAULT_MAX_BYTES
        val start = match.groupValues[1].toLongOrNull() ?: return DEFAULT_MAX_BYTES
        val end = match.groupValues[2].toLongOrNull() ?: return DEFAULT_MAX_BYTES
        val size = (end - start + 1).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong())
        return (size + 1024).toInt()
    }

    fun readLimited(stream: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (total < limit) {
            val read = stream.read(buffer, 0, minOf(buffer.size, limit - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    suspend fun request(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 15,
        range: String? = null,
    ): HttpResult? = try {
        engine.request(url, headers, timeoutSec, range)
    } catch (t: Throwable) {
        null
    }

    suspend fun text(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 15,
    ): String? {
        val result = request(url, headers, timeoutSec) ?: return null
        return result.body.takeIf { result.code in 200..399 && it.isNotBlank() }
    }

    suspend fun json(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 15,
    ): String? = text(url, headers, timeoutSec)

    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String = "application/x-www-form-urlencoded; charset=UTF-8",
        timeoutSec: Long = 15,
    ): String? {
        val result = try {
            engine.post(url, body, contentType, headers, timeoutSec)
        } catch (t: Throwable) {
            null
        } ?: return null
        return result.body.takeIf { result.code in 200..399 && it.isNotBlank() }
    }

    /** 2xx/3xx/416 means alive; 404/410 means gone; anything else is unknown. */
    suspend fun alive(url: String, headers: Map<String, String> = emptyMap()): Boolean? {
        val result = request(url, headers, 6, "bytes=0-0") ?: return null
        return when {
            result.code == 404 || result.code == 410 -> false
            result.code in 200..399 || result.code == 416 -> true
            else -> null
        }
    }

    fun withReferer(headers: Map<String, String>, referer: String?): Map<String, String> {
        if (referer.isNullOrBlank()) return headers
        if (headers.keys.any { it.equals("Referer", true) }) return headers
        return headers + ("Referer" to referer)
    }
}
