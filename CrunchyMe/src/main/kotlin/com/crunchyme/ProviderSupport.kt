package com.crunchyme

object TitleMatch {

    fun normalize(value: String?): String = (value ?: "")
        .lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    fun year(value: String?): Int? =
        Regex("""\b(19|20)\d{2}\b""").find(value ?: "")?.value?.toIntOrNull()

    fun distance(query: String, candidate: String): Double {
        val left = normalize(query)
        val right = normalize(candidate)
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        if (right.startsWith(left)) return 0.9
        if (right.contains(left)) return 0.8
        val wanted = left.split(' ').filter { it.length > 1 }
        if (wanted.isEmpty()) return 0.0
        val got = right.split(' ').toSet()
        return wanted.count { token -> got.any { it.startsWith(token) } }.toDouble() / wanted.size * 0.75
    }

    fun matchesYear(expected: Int?, candidate: Int?, tolerance: Int = 1): Boolean {
        if (expected == null || candidate == null) return true
        return kotlin.math.abs(expected - candidate) <= tolerance
    }
}

object ProviderSupport {

    fun headers(referer: String?, userAgent: String = Http.UA): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
        referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
    }

    fun kind(url: String): MediaKind = when {
        url.contains(".mpd", true) -> MediaKind.DASH
        url.contains(".m3u8", true) -> MediaKind.HLS
        else -> MediaKind.FILE
    }

    fun candidate(
        spec: ServerSpec,
        url: String,
        referer: String?,
        quality: Int = 0,
        audio: String = "",
        size: String = "",
        media: String = "",
        headers: Map<String, String> = emptyMap(),
        subtitles: List<SubtitleTrack> = emptyList(),
    ): Candidate = Candidate(
        sourceId = spec.id,
        sourceName = spec.name,
        url = url,
        kind = kind(url),
        referer = referer,
        headers = headers,
        qualityHint = quality.takeIf { it > 0 } ?: Naming.qualityHeight(media),
        audioLabel = audio,
        sizeLabel = size,
        mediaLabel = media,
        subtitles = subtitles,
    )

    fun origin(url: String): String = Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1)
        ?: url.substringBefore('/', url)

    fun absolute(base: String, href: String): String {
        val value = href.trim()
        if (value.startsWith("http")) return value
        val origin = origin(base)
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> origin + value
            else -> base.substringBeforeLast('/') + "/" + value
        }
    }

    fun decodeEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#8217;", "'")
        .replace("&#8211;", "-")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("<[^>]+>"), "")
        .trim()

    fun episodeMatches(text: String, season: Int, episode: Int): Boolean {
        if (season <= 0 || episode <= 0) return true
        val s = season.toString().padStart(2, '0')
        val e = episode.toString().padStart(2, '0')
        return Regex("""(?i)\bS0?$s[\s._-]*(?:E|EP|EPISODE)[\s._-]*0?$episode\b""").containsMatchIn(text) ||
            Regex("""(?i)\b${season}\s*x\s*$episode\b""").containsMatchIn(text) ||
            Regex("""(?i)\b(?:EP?|EPISODE)[\s._-]*0?$episode\b""").containsMatchIn(text) ||
            Regex("""(?i)\bSeason\s*0?$season\b""").containsMatchIn(text)
    }

    fun language(text: String?): String = Naming.displayLabel(text)
}
