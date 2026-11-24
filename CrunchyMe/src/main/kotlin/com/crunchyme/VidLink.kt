package com.crunchyme

import org.json.JSONObject

/** VidLink encrypts a short-lived media token; the token is the {id} in its API URL. */
object VidLink {

    private const val KEY_HEX = "c75136c5668bbfe65a7ecad431a745db68b5f381555b38d8f6c699449cf11fcd"
    private val NONCE = ByteArray(24)
    private val OFFSETS = listOf(480L, 0L, 960L)
    private val PLAYER_HEADERS = mapOf("User-Agent" to "ExoPlayer")

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val mediaId = request.tmdbId.takeIf { it > 0 }?.toString()
            ?: request.imdbId?.takeIf { it.startsWith("tt") }
            ?: return emptyList()
        val mediaPage = if (request.isMovie) {
            "https://vidlink.pro/movie/$mediaId"
        } else {
            "https://vidlink.pro/tv/$mediaId/${request.season}/${request.episode}"
        }
        for (offset in OFFSETS) {
            val api = apiUrl(spec, request, mediaId, offset)
            val body = Http.text(api, headers(mediaPage), 10) ?: continue
            if (body.trim() == "null") continue
            val root = runCatching { JSONObject(body) }.getOrNull() ?: continue
            if (root.has("error")) {
                val code = root.optJSONObject("error")?.optInt("code", -1) ?: -1
                if (code == 2004) continue
                return emptyList()
            }
            val parsed = parse(spec, root, mediaPage)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    internal fun apiUrl(spec: ServerSpec, request: LinkRequest, mediaId: String, offset: Long): String {
        val token = token(mediaId, System.currentTimeMillis() / 1000L + offset)
        val template = if (request.isMovie) spec.movieUrl else spec.tvUrl
        return template
            .replace("{id}", token)
            .replace("{season}", request.season.toString())
            .replace("{episode}", request.episode.toString())
    }

    internal fun token(mediaId: String, timestampSeconds: Long): String {
        val key = KEY_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val id = mediaId.toByteArray(Charsets.UTF_8)
        val message = ByteArray(id.size + 8)
        id.copyInto(message, 0)
        for (index in 0 until 8) {
            message[id.size + 7 - index] = ((timestampSeconds ushr (index * 8)) and 0xFF).toByte()
        }
        val box = CryptoKit.secretbox(message, NONCE, key)
        val payload = ByteArray(NONCE.size + box.size)
        NONCE.copyInto(payload, 0)
        box.copyInto(payload, NONCE.size)
        return B64.encodeUrl(payload)
    }

    private fun headers(mediaPage: String): Map<String, String> = mapOf(
        "User-Agent" to Http.UA,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to mediaPage,
        "Origin" to "https://vidlink.pro",
    )

    private suspend fun parse(spec: ServerSpec, root: JSONObject, mediaPage: String): List<Candidate> {
        val stream = root.optJSONObject("stream") ?: return emptyList()
        val out = mutableListOf<Candidate>()
        val qualities = stream.optJSONObject("qualities")
        if (qualities != null) {
            for (key in qualities.keys()) {
                val quality = qualities.optJSONObject(key) ?: continue
                val url = quality.optString("url").trim().takeIf { it.startsWith("http") } ?: continue
                val audio = firstString(quality, "languages", "language", "lang", "audio", "label", "title")
                    .ifBlank { stream.optString("languages") }
                val playback = playbackHeaders(quality)
                out += ProviderSupport.candidate(
                    spec = spec,
                    url = url,
                    referer = playback["Referer"] ?: mediaPage,
                    quality = key.toIntOrNull() ?: 0,
                    audio = Naming.displayLabel(audio),
                    size = Naming.bytesLabel(quality.optString("size").toLongOrNull() ?: 0L),
                    media = quality.optString("codecName"),
                    headers = playback,
                )
            }
        }
        if (out.isNotEmpty()) return out.distinctBy { it.url }

        val masterUrl = stream.optString("playlist").trim().takeIf { it.startsWith("http") }
            ?: root.optString("url").trim().takeIf { it.startsWith("http") }
            ?: return emptyList()
        val masterText = Http.text(masterUrl, headers(mediaPage), 8)
        val kind = ProviderSupport.kind(masterUrl)
        val info = Manifest.analyze(masterText, masterUrl, kind)
        return listOf(
            Candidate(
                sourceId = spec.id,
                sourceName = spec.name,
                url = masterUrl,
                kind = kind,
                referer = mediaPage,
                headers = PLAYER_HEADERS,
                qualityHint = info.height,
                audioLabel = Naming.displayLabel(info.audio.joinToString(", ")),
                inlineManifest = masterText,
                subtitles = info.subtitles,
            ),
        )
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun playbackHeaders(quality: JSONObject): Map<String, String> {
        val declared = quality.optJSONObject("headers")
        val referer = declared?.optString("referer")?.takeIf { it.isNotBlank() }
        val origin = declared?.optString("origin")?.takeIf { it.isNotBlank() }
        if (referer == null && origin == null) return PLAYER_HEADERS
        return buildMap {
            put("User-Agent", Http.UA)
            referer?.let { put("Referer", it) }
            origin?.let { put("Origin", it) }
        }
    }
}
