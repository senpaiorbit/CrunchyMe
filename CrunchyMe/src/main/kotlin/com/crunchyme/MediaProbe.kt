package com.crunchyme

import kotlin.math.min

/** Turns a raw candidate into a player-ready one: real height, audio label, subtitles. */
object MediaProbe {

    private data class Sniff(val kind: MediaKind, val text: String?)

    suspend fun enrich(candidate: Candidate, qualityFloor: Int): Candidate? {
        var height = candidate.qualityHint
        var audio = candidate.audioLabel
        var subtitles = candidate.subtitles
        var inline = candidate.inlineManifest
        val headers = Http.withReferer(candidate.headers, candidate.referer)
        val sniff = sniff(candidate, headers) ?: return null
        var working = candidate.copy(kind = sniff.kind)
        inline = inline ?: sniff.text

        when (working.kind) {
            MediaKind.HLS, MediaKind.DASH -> {
                val text = inline ?: Http.text(working.url, headers, 8)
                if (text.isNullOrBlank()) return null
                if (working.kind == MediaKind.HLS && !text.contains("#EXTM3U", true)) return null
                if (working.kind == MediaKind.DASH && !text.contains("<MPD", true)) return null
                val info = Manifest.analyze(text, working.url, working.kind)
                if (info.height > height) height = info.height
                if (info.audio.isNotEmpty()) {
                    val label = Naming.displayLabel(info.audio.joinToString(", "))
                    if (label.isNotBlank()) audio = label
                }
                if (subtitles.isEmpty()) subtitles = info.subtitles
                inline = text
            }

            MediaKind.FILE -> {
                if (height <= 0) height = Mp4Height.probe(working.url, headers)
            }
        }

        if (qualityFloor > 0 && height in 1 until qualityFloor) return null
        return working.copy(
            qualityHint = height,
            audioLabel = audio,
            subtitles = subtitles,
            inlineManifest = inline,
        )
    }

    /**
     * CloudStream reports "video is unsupported" when a file-index page is
     * handed to the player as a video. Confirm the response really is media and
     * correct the kind when the host serves HLS without a .m3u8 suffix.
     */
    private suspend fun sniff(candidate: Candidate, headers: Map<String, String>): Sniff? {
        val inline = candidate.inlineManifest
        if (inline != null) {
            val kind = when {
                inline.contains("#EXTM3U", true) -> MediaKind.HLS
                inline.contains("<MPD", true) -> MediaKind.DASH
                else -> candidate.kind
            }
            return Sniff(kind, inline)
        }
        val range = if (candidate.kind == MediaKind.FILE) "bytes=0-65535" else null
        val result = Http.request(candidate.url, headers, 8, range) ?: return null
        if (result.code !in 200..399 && result.code != 206) return null
        val contentType = result.headers.entries
            .firstOrNull { it.key.equals("Content-Type", true) }?.value.orEmpty().lowercase()
        val body = result.body
        val lower = body.trimStart().lowercase()
        if (contentType.contains("text/html") || lower.startsWith("<!doctype") ||
            lower.startsWith("<html") || lower.contains("cf-chl") ||
            lower.contains("just a moment")
        ) return null

        val bytes = result.bytes
        val kind = when {
            body.contains("#EXTM3U", true) || contentType.contains("mpegurl") -> MediaKind.HLS
            body.contains("<MPD", true) || contentType.contains("dash+xml") -> MediaKind.DASH
            contentType.startsWith("video/") || contentType.contains("octet-stream") ||
                looksLikeVideo(bytes) -> MediaKind.FILE
            else -> candidate.kind
        }
        if (kind == MediaKind.FILE && !looksLikeVideo(bytes) &&
            !contentType.startsWith("video/") &&
            !candidate.url.contains(".mp4", true) && !candidate.url.contains(".mkv", true) &&
            !candidate.url.contains(".webm", true)
        ) return null
        return Sniff(kind, if (kind == MediaKind.HLS || kind == MediaKind.DASH) body else null)
    }

    private fun looksLikeVideo(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val head = String(bytes, 0, minOf(12, bytes.size), Charsets.ISO_8859_1)
        if (head.length >= 8 && (head.substring(4, 8) == "ftyp" || head.substring(4, 8) == "moov" ||
                head.substring(4, 8) == "mdat" || head.substring(4, 8) == "wide")) return true
        if (bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()) return true
        return head.startsWith("RIFF") || head.startsWith("OggS")
    }
}

/** Reads the video height from an MP4/MKV `moov → trak → tkhd` box using HTTP range requests. */
object Mp4Height {

    private const val FRONT_BYTES = 262_144
    private const val TAIL_BYTES = 524_288

    suspend fun probe(url: String, headers: Map<String, String>): Int {
        if (url.isBlank()) return 0
        val front = Http.request(url, headers, 6, "bytes=0-${FRONT_BYTES - 1}")?.bytes
        val moov = front?.let { findMoov(it) } ?: run {
            val total = contentLength(url, headers) ?: return 0
            if (total <= FRONT_BYTES) return 0
            val start = (total - TAIL_BYTES).coerceAtLeast(0)
            val tail = Http.request(url, headers, 6, "bytes=$start-${total - 1}")?.bytes ?: return 0
            findMoov(tail) ?: return 0
        }
        return heightFromMoov(moov)
    }

    private suspend fun contentLength(url: String, headers: Map<String, String>): Long? {
        val result = Http.request(url, headers, 6, "bytes=0-0") ?: return null
        val contentRange = result.headers.entries
            .firstOrNull { it.key.equals("Content-Range", true) }?.value
        contentRange?.substringAfterLast('/')?.toLongOrNull()?.let { return it }
        return result.headers.entries
            .firstOrNull { it.key.equals("Content-Length", true) }?.value?.toLongOrNull()
    }

    private fun findMoov(data: ByteArray): ByteArray? {
        var index = 4
        while (index + 4 <= data.size) {
            val isMoov = data[index] == 'm'.code.toByte() &&
                data[index + 1] == 'o'.code.toByte() &&
                data[index + 2] == 'o'.code.toByte() &&
                data[index + 3] == 'v'.code.toByte()
            if (isMoov) {
                val start = index - 4
                if (start >= 0) {
                    val size = read32(data, start).toLong() and 0xFFFFFFFFL
                    val end = when {
                        size == 0L -> data.size.toLong()
                        size == 1L -> if (index + 12 <= data.size) read64(data, index + 4) else -1L
                        size >= 8L -> start + size
                        else -> -1L
                    }
                    if (end in (start + 8L)..data.size.toLong()) {
                        return data.copyOfRange(start, end.toInt())
                    }
                }
            }
            index++
        }
        return null
    }

    private fun heightFromMoov(moov: ByteArray): Int {
        fun walk(start: Int, end: Int): Int {
            var offset = start
            while (offset + 8 <= end) {
                val size = read32(moov, offset)
                if (size < 8 || offset + size > end) return 0
                val type = String(moov, offset + 4, 4, Charsets.ISO_8859_1)
                if (type == "tkhd") {
                    val (width, height) = parseTkhdSize(moov, offset, size)
                    if (height > 0) return normalize(width, height)
                } else if (type == "moov" || type == "trak" || type == "mdia" ||
                    type == "minf" || type == "stbl"
                ) {
                    val nested = walk(offset + 8, offset + size)
                    if (nested > 0) return nested
                }
                offset += size
            }
            return 0
        }
        return walk(0, moov.size)
    }

    private fun normalize(width: Int, height: Int): Int {
        if (height <= 0) return 0
        if (width <= 0) return height
        return min(height, width)
    }

    private fun parseTkhdSize(data: ByteArray, offset: Int, size: Int): Pair<Int, Int> {
        if (size < 12) return 0 to 0
        val version = data[offset + 8].toInt() and 0xFF
        val base = if (version == 1) 96 else 84
        if (offset + base + 8 > data.size) return 0 to 0
        fun fixed(at: Int): Int {
            val raw = read32(data, at).toLong() and 0xFFFFFFFFL
            return (raw ushr 16).toInt().coerceAtLeast(0)
        }
        return fixed(offset + base) to fixed(offset + base + 4)
    }

    private fun read32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun read64(data: ByteArray, offset: Int): Long =
        ((read32(data, offset).toLong() and 0xFFFFFFFFL) shl 32) or
            (read32(data, offset + 4).toLong() and 0xFFFFFFFFL)
}
