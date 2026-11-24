package com.crunchyme

data class ManifestInfo(
    val height: Int = 0,
    val audio: List<String> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val master: Boolean = false,
)

/** Minimal HLS/DASH reader: peak height, declared audio languages and subtitle tracks. */
object Manifest {

    private val ATTR = Regex("""([A-Za-z0-9-]+)\s*=\s*(?:"([^"]*)"|([^,\s]*))""")
    private val MPD_REPRESENTATION = Regex("""(?is)<Representation\b[^>]*>""")
    private val MPD_ADAPTATION = Regex("""(?is)<AdaptationSet\b[^>]*>""")
    private val MPD_BASE_URL = Regex("""(?is)<BaseURL>\s*([^<\s]+)\s*</BaseURL>""")

    fun attributes(line: String): Map<String, String> = ATTR.findAll(line).associate { match ->
        val quoted = match.groupValues[2]
        val bare = match.groupValues[3]
        match.groupValues[1].uppercase() to quoted.ifEmpty { bare }
    }

    fun resolveUrl(baseUrl: String, reference: String): String {
        val ref = reference.trim()
        if (ref.isEmpty()) return baseUrl
        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) return ref
        if (ref.startsWith("//")) {
            return (if (baseUrl.startsWith("https", true)) "https:" else "http:") + ref
        }
        return try {
            java.net.URI(baseUrl).resolve(ref).toString()
        } catch (t: Throwable) {
            val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: return ref
            if (ref.startsWith("/")) origin + ref else "$origin/$ref"
        }
    }

    fun analyze(text: String?, baseUrl: String, kind: MediaKind): ManifestInfo {
        if (text.isNullOrBlank()) return ManifestInfo()
        return when (kind) {
            MediaKind.HLS -> hls(text, baseUrl)
            MediaKind.DASH -> dash(text)
            MediaKind.FILE -> ManifestInfo()
        }
    }

    private fun hls(text: String, baseUrl: String): ManifestInfo {
        var height = 0
        var master = false
        val audio = LinkedHashSet<String>()
        val subtitles = LinkedHashSet<SubtitleTrack>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                    master = true
                    val attrs = attributes(line)
                    val resolution = attrs["RESOLUTION"].orEmpty()
                    val parsed = resolution.substringAfter('x', "").toIntOrNull()
                    if (parsed != null && parsed > height) height = parsed
                }

                line.startsWith("#EXT-X-MEDIA", ignoreCase = true) -> {
                    master = true
                    val attrs = attributes(line)
                    when (attrs["TYPE"]?.uppercase()) {
                        "AUDIO" -> languageOf(attrs["LANGUAGE"], attrs["NAME"])?.let { audio += it }
                        "SUBTITLES" -> {
                            val uri = attrs["URI"].orEmpty()
                            if (uri.isNotBlank()) {
                                val language = languageOf(attrs["LANGUAGE"], attrs["NAME"])
                                    ?: attrs["NAME"].orEmpty().ifBlank { "Subtitle" }
                                subtitles += SubtitleTrack(language, resolveUrl(baseUrl, uri))
                            }
                        }
                    }
                }
            }
        }
        return ManifestInfo(height, audio.toList(), subtitles.toList(), master)
    }

    private fun languageOf(code: String?, name: String?): String? {
        val fromCode = Naming.fromCode(code)
        if (fromCode.isNotBlank()) return fromCode
        val fromName = Naming.labelOf(name)
        return fromName.takeIf { it.isNotBlank() }
    }

    private fun dash(text: String): ManifestInfo {
        var height = 0
        val audio = LinkedHashSet<String>()

        for (match in MPD_REPRESENTATION.findAll(text)) {
            val attrs = attributes(match.value)
            val parsed = attrs["HEIGHT"]?.toIntOrNull() ?: attrs["MAXHEIGHT"]?.toIntOrNull()
            if (parsed != null && parsed > height) height = parsed
            val type = (attrs["CONTENTTYPE"] ?: attrs["MIMETYPE"]).orEmpty()
            if (type.contains("audio", true)) {
                Naming.fromCode(attrs["LANG"]).takeIf { it.isNotBlank() }?.let { audio += it }
            }
        }
        for (match in MPD_ADAPTATION.findAll(text)) {
            val attrs = attributes(match.value)
            val type = (attrs["CONTENTTYPE"] ?: attrs["MIMETYPE"]).orEmpty()
            if (type.contains("audio", true)) {
                Naming.fromCode(attrs["LANG"]).takeIf { it.isNotBlank() }?.let { audio += it }
            }
        }
        return ManifestInfo(height, audio.toList(), emptyList(), true)
    }

    /** First absolute BaseURL in a DASH document, used to resolve relative segment URLs. */
    fun dashBaseUrl(text: String?, fallback: String): String {
        val raw = text?.let { MPD_BASE_URL.find(it)?.groupValues?.get(1) } ?: return fallback
        return resolveUrl(fallback, raw)
    }
}
