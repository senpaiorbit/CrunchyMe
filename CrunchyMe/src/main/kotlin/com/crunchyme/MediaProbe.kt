package com.crunchyme

import kotlin.math.min

/** Turns a raw candidate into a player-ready one: real height, audio label, subtitles. */
object MediaProbe {

    suspend fun enrich(candidate: Candidate, qualityFloor: Int): Candidate? {
        var height = candidate.qualityHint
        var audio = candidate.audioLabel
        var subtitles = candidate.subtitles
        var inline = candidate.inlineManifest
        val headers = Http.withReferer(candidate.headers, candidate.referer)

        when (candidate.kind) {
            MediaKind.HLS, MediaKind.DASH -> {
                val text = inline ?: Http.text(candidate.url, headers, 8)
                if (!text.isNullOrBlank()) {
                    val info = Manifest.analyze(text, candidate.url, candidate.kind)
                    if (info.height > height) height = info.height
                    if (info.audio.isNotEmpty()) {
                        val label = Naming.displayLabel(info.audio.joinToString(", "))
                        if (label.isNotBlank()) audio = label
                    }
                    if (subtitles.isEmpty()) subtitles = info.subtitles
                    inline = text
                }
            }

            MediaKind.FILE -> {
                if (height <= 0) height = Mp4Height.probe(candidate.url, headers)
            }
        }

        if (qualityFloor > 0 && height in 1 until qualityFloor) return null
        return candidate.copy(
            qualityHint = height,
            audioLabel = audio,
            subtitles = subtitles,
            inlineManifest = inline,
        )
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
