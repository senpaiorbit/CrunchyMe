package com.crunchyme

import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Google's video-downloads host ignores Range and answers every request with the whole file.
 * A player therefore has to download from byte zero before it can seek, and some players download
 * the file before playback. This loopback server keeps one sequential download per URL and exposes
 * it as a normal byte-range HTTP resource, so playback can start immediately and seeks work once
 * the requested bytes have arrived.
 *
 * Only URLs registered here are served, so the port cannot be used as an open proxy.
 */
object GoogleVideoProxy {

    private const val HOST = "video-downloads.googleusercontent.com"
    private const val MAX_ENTRIES = 6
    private const val CONNECT_TIMEOUT_SEC = 15L
    private const val READ_TIMEOUT_SEC = 30L
    private const val COPY_BUFFER = 64 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var port: Int = -1

    private data class Meta(val size: Long, val mime: String)

    internal data class Range(val start: Long, val end: Long, val partial: Boolean)

    private class Entry(
        val url: String,
        val headers: Map<String, String>,
        val size: Long,
        val mime: String,
        val file: File,
    ) {
        @Volatile var downloaded: Long = 0L
        @Volatile var done: Boolean = false
        @Volatile var error: Throwable? = null
        val active = AtomicInteger(0)
        val started = AtomicInteger(0)
    }

    internal fun isGoogleVideo(url: String?): Boolean {
        val text = url?.trim().orEmpty()
        if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) return false
        val host = runCatching { java.net.URI(text).host }.getOrNull()?.lowercase() ?: return false
        return host == HOST || host.endsWith(".$HOST")
    }

    /**
     * Returns a loopback URL for [url], or null when the URL cannot be served. The download starts
     * in the background; playback can begin while it is still filling.
     */
    suspend fun register(url: String, headers: Map<String, String>): String? {
        if (!isGoogleVideo(url)) return null
        val id = idOf(url)
        synchronized(lock) { entries[id]?.let { return urlOf(id) } }

        val meta = meta(url, headers) ?: return null
        val directory = Settings.cacheDir() ?: File(System.getProperty("java.io.tmpdir"), "crunchyme-google")
        if (!directory.exists() && !directory.mkdirs()) return null
        val file = File(directory, "gv-$id.bin")
        val entry = Entry(url, headers, meta.size, meta.mime, file)

        if (file.isFile && file.length() == meta.size) {
            entry.downloaded = meta.size
            entry.done = true
        } else {
            file.delete()
            if (!file.createNewFile()) return null
        }

        synchronized(lock) {
            if (entries[id] == null) {
                entries[id] = entry
                trimLocked()
            }
            ensureServerLocked()
        }
        if (port <= 0) {
            synchronized(lock) { entries.remove(id) }
            return null
        }
        return urlOf(id)
    }

    private suspend fun meta(url: String, headers: Map<String, String>): Meta? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .head()
            .apply {
                if (headers.keys.none { it.equals("User-Agent", true) }) header("User-Agent", Http.UA)
                headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) header(k, v) }
            }
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val mime = response.header("Content-Type").orEmpty()
                if (mime.startsWith("text/", true) || mime.contains("json", true)) return@withContext null
                val size = response.header("Content-Length")?.toLongOrNull() ?: return@withContext null
                if (size <= 0L) return@withContext null
                Meta(size, mime.ifBlank { "application/octet-stream" })
            }
        }.getOrNull()
    }

    private fun startDownload(entry: Entry) {
        scope.launch {
            var output: FileOutputStream? = null
            try {
                val request = Request.Builder()
                    .url(entry.url)
                    .apply {
                        if (entry.headers.keys.none { it.equals("User-Agent", true) }) header("User-Agent", Http.UA)
                        entry.headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) header(k, v) }
                    }
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body
                    output = FileOutputStream(entry.file, false)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(COPY_BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            output.flush()
                            entry.downloaded = (entry.downloaded + read).coerceAtMost(entry.size)
                            if (entry.downloaded >= entry.size) break
                        }
                    }
                }
                output?.close()
                output = null
                if (entry.downloaded < entry.size) throw IOException("short download")
                entry.done = true
            } catch (t: Throwable) {
                entry.error = t
                entry.file.delete()
            } finally {
                runCatching { output?.close() }
            }
        }
    }

    private fun ensureServerLocked() {
        if (port > 0 && server?.isClosed == false) return
        val socket = runCatching {
            ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        }.getOrNull() ?: return
        server = socket
        port = socket.localPort
        Thread({ acceptLoop(socket) }, "crunchyme-google-video").apply { isDaemon = true }.start()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val clientSocket = runCatching { socket.accept() }.getOrNull() ?: continue
            Thread({ serve(clientSocket) }, "crunchyme-google-video-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(clientSocket: Socket) {
        try {
            clientSocket.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream(), StandardCharsets.US_ASCII))
            val requestLine = reader.readLine().orEmpty()
            if (requestLine.isBlank()) return
            val method = requestLine.substringBefore(' ')
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?')?.trim('/').orEmpty()
            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", true)) rangeHeader = line.substringAfter(':').trim()
            }

            val output = clientSocket.getOutputStream()
            val entry = synchronized(lock) { entries[path] }
            if (entry == null) {
                writeEmpty(output, "404 Not Found")
                return
            }
            val range = rangeFor(entry.size, rangeHeader)
            if (range == null) {
                val head = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                    "Content-Range: bytes */${entry.size}\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
                output.write(head.toByteArray(StandardCharsets.US_ASCII))
                return
            }

            val length = range.end - range.start + 1
            val status = if (range.partial) "206 Partial Content" else "200 OK"
            val head = buildString {
                append("HTTP/1.1 ").append(status).append("\r\n")
                append("Content-Type: ").append(entry.mime).append("\r\n")
                append("Content-Length: ").append(length).append("\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (range.partial) {
                    append("Content-Range: bytes ").append(range.start).append('-').append(range.end)
                        .append('/').append(entry.size).append("\r\n")
                }
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(head.toByteArray(StandardCharsets.US_ASCII))
            if (!method.equals("HEAD", true)) {
                if (!entry.done && entry.started.compareAndSet(0, 1)) startDownload(entry)
                entry.active.incrementAndGet()
                try {
                    GrowingFileInputStream(entry, range.start, range.end).use { input ->
                        val buffer = ByteArray(COPY_BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                } finally {
                    entry.active.decrementAndGet()
                }
            }
            output.flush()
        } catch (_: Throwable) {
            // The player disconnects when it has enough; the download continues in the background.
        } finally {
            runCatching { clientSocket.close() }
        }
    }

    private fun writeEmpty(output: java.io.OutputStream, status: String) {
        output.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    internal fun rangeFor(size: Long, header: String?): Range? {
        if (size <= 0L) return null
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return Range(0L, size - 1, false)
        val match = Regex("""bytes=(\d*)-(\d*)""").matchEntire(raw) ?: return null
        val from = match.groupValues[1]
        val to = match.groupValues[2]
        if (from.isEmpty() && to.isEmpty()) return null
        val start: Long
        val end: Long
        if (from.isEmpty()) {
            val suffix = to.toLongOrNull() ?: return null
            if (suffix <= 0L) return null
            start = (size - suffix).coerceAtLeast(0L)
            end = size - 1
        } else {
            start = from.toLongOrNull() ?: return null
            end = to.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
        }
        if (start < 0L || start >= size || start > end) return null
        return Range(start, end, true)
    }

    private fun trimLocked() {
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.entries.firstOrNull() ?: break
            entries.remove(oldest.key)
            if (oldest.value.active.get() == 0) {
                oldest.value.file.delete()
            }
        }
    }

    private fun idOf(url: String): String = UUID.nameUUIDFromBytes(url.toByteArray()).toString()

    private fun urlOf(id: String): String = "http://127.0.0.1:$port/$id"

    /** Reads a byte range from the growing file, waiting while the download catches up. */
    private class GrowingFileInputStream(
        private val entry: Entry,
        start: Long,
        private val end: Long,
    ) : InputStream() {
        private var position = start
        private val file = RandomAccessFile(entry.file, "r")
        private val single = ByteArray(1)

        override fun read(): Int = if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xFF

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position > end) return -1
            while (true) {
                val available = entry.downloaded - position
                if (available > 0L) {
                    val max = minOf(length.toLong(), available, end - position + 1).toInt()
                    file.seek(position)
                    val read = file.read(buffer, offset, max)
                    if (read > 0) {
                        position += read
                        return read
                    }
                }
                entry.error?.let { throw IOException("Google video download failed", it) }
                if (entry.done) {
                    return if (position >= entry.size || position > end) -1
                    else throw IOException("Google video ended before the requested range")
                }
                Thread.sleep(40L)
            }
        }

        override fun close() {
            runCatching { file.close() }
        }
    }
}
