package com.crunchyme

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Shared embed-page harvesting: direct media, iframes, <video>, then host extractors. */
object EmbedResolver {

    suspend fun resolve(
        url: String,
        body: String,
        referer: String?,
        headers: Map<String, String>,
        sourceId: String,
        sourceName: String,
        originalLanguage: String? = null,
        allowExtractor: Boolean = true,
    ): List<Candidate> {
        if (body.isBlank()) return emptyList()
        val found = LinkedHashMap<String, Candidate>()

        fun add(mediaUrl: String, ref: String?) {
            val clean = mediaUrl.trim()
            if (clean.isBlank() || found.containsKey(clean)) return
            found[clean] = Candidate(
                sourceId = sourceId,
                sourceName = sourceName,
                url = clean,
                kind = kindOf(clean),
                referer = ref,
                headers = headers,
                qualityHint = Naming.qualityHeight(clean),
                audioLabel = Naming.displayLabel(clean, originalLanguage),
                sizeLabel = Naming.sizeLabel(clean),
            )
        }

        HtmlHarvest.mediaUrls(body).forEach { add(it, referer) }

        if (found.isEmpty()) {
            val frames = HtmlHarvest.iframes(body, url).take(3)
            val frameBodies = coroutineScope {
                frames.map { frame ->
                    async {
                        frame to (Http.text(frame, Http.withReferer(headers, url), 8) ?: "")
                    }
                }.awaitAll()
            }
            for ((frame, frameBody) in frameBodies) {
                if (frameBody.isBlank() || HtmlHarvest.isChallenge(frameBody)) continue
                HtmlHarvest.mediaUrls(frameBody).forEach { add(it, frame) }
            }
        }

        if (found.isEmpty()) {
            HtmlHarvest.videoTag(body, url)?.let { add(it, referer) }
        }

        if (found.isEmpty() && allowExtractor) {
            val links = mutableListOf<BridgeLink>()
            val subtitles = mutableListOf<SubtitleTrack>()
            runCatching {
                ExtractorBridge.loader(url, referer, { subtitles += it }, { links += it })
            }
            links.forEach { link ->
                if (link.url.isBlank() || found.containsKey(link.url)) return@forEach
                found[link.url] = Candidate(
                    sourceId = sourceId,
                    sourceName = sourceName,
                    url = link.url,
                    kind = kindOf(link.url, link.type),
                    referer = link.referer ?: referer,
                    headers = headers,
                    qualityHint = link.quality,
                    audioLabel = Naming.displayLabel(link.url, originalLanguage),
                    subtitles = subtitles.toList(),
                )
            }
        }

        return found.values.toList()
    }

    fun kindOf(url: String, type: String = ""): MediaKind = when {
        type.equals("M3U8", true) || url.contains(".m3u8", true) -> MediaKind.HLS
        type.equals("DASH", true) || url.contains(".mpd", true) -> MediaKind.DASH
        else -> MediaKind.FILE
    }
}
