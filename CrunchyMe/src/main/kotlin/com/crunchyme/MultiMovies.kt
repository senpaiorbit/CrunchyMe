package com.crunchyme

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup

/**
 * MultiMovies is a discovery page rather than a file host. Its detail page lists
 * third-party embeds; each embed is resolved through the shared embed pipeline.
 */
object MultiMovies {

    private val EMBED_HOST = Regex(
        """(?i)(?:vidrift|vidbolt|vidout|modiplay|iqsmartgames|moviesnexus|""" +
            """2embed|vidsrc|videm|vidsync|bingr|filmu|gdriveplayer|streamtape|""" +
            """dood|filemoon|mixdrop|upcloud|streamwish)""",
    )

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val searchUrl = ServerFarm.urlFor(spec, request) ?: return emptyList()
        val base = spec.referer?.trimEnd('/') ?: ProviderSupport.origin(searchUrl)
        val html = Http.text(searchUrl, ProviderSupport.headers(base), spec.timeoutSec.toLong())
            ?: return emptyList()
        val hits = hits(html, searchUrl, request.title, request.year).take(3)
        if (hits.isEmpty()) return emptyList()

        val out = LinkedHashMap<String, Candidate>()
        for ((url, title) in hits) {
            val post = Http.text(url, ProviderSupport.headers(base), spec.timeoutSec.toLong()) ?: continue
            val embeds = embeds(post, url).take(8)
            val resolved = coroutineScope {
                embeds.map { embed ->
                    async {
                        runCatching {
                            withTimeoutOrNull(12_000) { resolveEmbed(spec, embed, url, request, title) }
                                .orEmpty()
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
            for (candidate in resolved) {
                out.putIfAbsent(candidate.url, candidate.copy(
                    sourceId = spec.id,
                    sourceName = spec.name,
                    mediaLabel = listOf(title, candidate.mediaLabel)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                ))
            }
            if (out.size >= 12) break
        }
        return out.values.toList()
    }

    private fun hits(html: String, base: String, title: String, year: Int?): List<Pair<String, String>> {
        val doc = Jsoup.parse(html, base)
        val anchors = doc.select(
            "article.poster-card a.poster-art[href], article.poster-card a[href], " +
                "div.poster-caption a[href], div.catalog-grid article a[href]",
        )
        val rows = LinkedHashMap<String, String>()
        for (anchor in anchors) {
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            if (!href.startsWith("http")) continue
            if (href.endsWith("/movies") || href.endsWith("/series") ||
                href.contains("/movies/") && !href.contains("/movie/")
            ) continue
            val name = anchor.selectFirst("img")?.attr("alt")?.trim().orEmpty()
                .ifBlank { anchor.text().trim() }
                .ifBlank { anchor.attr("title").trim() }
            if (name.isBlank()) continue
            rows.putIfAbsent(href, name)
        }
        return rows.map { it.key to it.value }
            .map { it to TitleMatch.distance(title, it.second) }
            .filter { (hit, score) ->
                score >= 0.5 && (year == null || TitleMatch.matchesYear(year, TitleMatch.year(hit.second)))
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun embeds(html: String, base: String): List<String> {
        val text = HtmlHarvest.unescape(html)
        val found = LinkedHashSet<String>()
        HtmlHarvest.iframes(text, base).forEach { if (EMBED_HOST.containsMatchIn(it)) found += it }
        Regex("""(?i)https?://[^\s"'<>\\]+""").findAll(text).forEach { match ->
            val url = match.value.trimEnd('"', '\'', ',', ';')
            if (EMBED_HOST.containsMatchIn(url) && !url.contains("image.tmdb.org", true)) {
                found += url
            }
        }
        return found.filter { it.startsWith("http") }.take(12)
    }

    private suspend fun resolveEmbed(
        spec: ServerSpec,
        url: String,
        referer: String,
        request: LinkRequest,
        title: String,
    ): List<Candidate> {
        if (url.contains("vidbolt.", true)) {
            val vidbolt = ServerFarm.spec("vidbolt") ?: return emptyList()
            return VidBolt.resolve(vidbolt, request)
        }
        if (url.contains("vidout.", true) || url.contains("vidy.st", true)) {
            val vidout = ServerFarm.spec("vidout") ?: return emptyList()
            return VidOut.resolve(vidout, request)
        }
        if (url.contains("vidrift.", true)) {
            val vidrift = ServerFarm.spec("vidrift")?.copy(
                movieUrl = url,
                tvUrl = url,
                referer = ProviderSupport.origin(url) + "/",
                keyType = KeyType.TITLE,
            ) ?: return emptyList()
            return Vidrift.resolve(vidrift, request)
        }
        val body = Http.text(url, ProviderSupport.headers(referer), 12) ?: return emptyList()
        if (HtmlHarvest.isChallenge(body)) return emptyList()
        return EmbedResolver.resolve(
            url = url,
            body = body,
            referer = referer,
            headers = ProviderSupport.headers(referer),
            sourceId = spec.id,
            sourceName = spec.name,
            originalLanguage = request.originalLanguage,
        ).map { it.copy(mediaLabel = listOf(title, it.mediaLabel).filter { s -> s.isNotBlank() }.joinToString(" · ")) }
    }
}
