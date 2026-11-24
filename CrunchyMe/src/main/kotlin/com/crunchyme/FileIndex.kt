package com.crunchyme

import java.net.URLDecoder
import org.jsoup.Jsoup

/**
 * Shared resolver for the Indian file-index providers. They all expose a search page,
 * then a post with gateway/file-host links. The final host is resolved recursively.
 */
object FileIndex {

    private data class Hit(val url: String, val title: String)

    private val FILE_HOST = Regex(
        """(?i)hubcloud|hubdrive|hubcdn|vcloud|pixeldrain|gdshine|mdrive|filebee|gadgetsweb|""" +
            """workers\.dev|dl\.php|greenmotors|\.mkv|\.mp4|\.m3u8|\.mpd""",
    )
    private val VIDEO = Regex("""(?i)https?://[^\s"'<>\\]+\.(?:mkv|mp4|m3u8|mpd)(?:\?[^\s"'<>\\]*)?""")
    private val NON_CONTENT = Regex(
        """(?i)/(?:category|tag|author|page|feed|wp-json|wp-admin|search|contact|privacy|dmca|about)/""",
    )
    private val REURL = Regex("""var\s+reurl\s*=\s*["']([^"']+)["']""")

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val searchUrl = ServerFarm.urlFor(spec, request) ?: return emptyList()
        val base = spec.referer ?: ProviderSupport.origin(searchUrl)
        val searchHtml = Http.text(searchUrl, ProviderSupport.headers(base), spec.timeoutSec.toLong())
            ?: return emptyList()
        val hits = parseHits(searchHtml, searchUrl)
        val ranked = rank(hits, request.title, request.year).take(4)
        for (hit in ranked) {
            val post = Http.text(hit.url, ProviderSupport.headers(base), 12) ?: continue
            if (!yearMatches(hit.title, request.year, request.isMovie)) continue
            val links = fileLinks(post, hit.url, request, hit.title)
            val out = mutableListOf<Candidate>()
            for ((href, label) in links.take(10)) {
                out += resolveLink(spec, href, hit.url, request, listOf(label, hit.title).joinToString(" "), 0)
                if (out.size >= 8) break
            }
            if (out.isNotEmpty()) return out.distinctBy { it.url }
        }
        return emptyList()
    }

    private fun parseHits(html: String, base: String): List<Hit> {
        val doc = Jsoup.parse(html, base)
        val out = LinkedHashMap<String, String>()
        for (anchor in doc.select("a[href]")) {
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            if (!href.startsWith("http") || NON_CONTENT.containsMatchIn(href)) continue
            if (href == base || href.trimEnd('/') == base.trimEnd('/')) continue
            val title = anchor.attr("aria-label").removeSuffix(" details").trim()
                .ifBlank { anchor.selectFirst("h1,h2,h3,h4,.poster-title,.entry-title")?.text()?.trim().orEmpty() }
                .ifBlank { anchor.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
                .ifBlank { anchor.text().trim().take(140) }
            if (title.length < 2) continue
            out.putIfAbsent(href, title)
        }
        return out.map { Hit(it.key, it.value) }
    }

    private fun rank(hits: List<Hit>, title: String, year: Int?): List<Hit> {
        return hits.map { hit -> hit to TitleMatch.distance(title, hit.title) }
            .filter { (hit, score) ->
                score >= 0.55 && yearMatches(hit.title, year, true)
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun yearMatches(text: String, expected: Int?, movie: Boolean): Boolean {
        val found = TitleMatch.year(text) ?: return true
        if (expected == null) return true
        return if (movie) TitleMatch.matchesYear(expected, found, 0)
        else found >= expected - 1
    }

    private fun fileLinks(
        html: String,
        base: String,
        request: LinkRequest,
        postTitle: String,
    ): List<Pair<String, String>> {
        val doc = Jsoup.parse(html, base)
        val all = doc.select("a[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            if (!href.startsWith("http") || !FILE_HOST.containsMatchIn(href)) return@mapNotNull null
            if (href.contains("/admin", true) || href.contains("t.me", true)) return@mapNotNull null
            val label = anchor.text().trim().ifBlank { anchor.attr("title").trim() }
            href to label
        }.distinctBy { it.first }
        if (!request.isMovie && request.hasEpisode) {
            val episodeLinks = all.filter { (_, label) ->
                ProviderSupport.episodeMatches("$label $postTitle", request.season, request.episode)
            }
            if (episodeLinks.isNotEmpty()) return episodeLinks
        }
        return all
    }

    internal suspend fun resolveLink(
        spec: ServerSpec,
        url: String,
        referer: String,
        request: LinkRequest,
        media: String,
        depth: Int,
    ): List<Candidate> {
        if (depth > 3) return emptyList()
        val pixeldrain = Regex("""(?i)pixeldrain\.com/u/([A-Za-z0-9]+)""").find(url)
        if (pixeldrain != null) {
            return listOf(candidate(spec, "https://pixeldrain.com/api/file/${pixeldrain.groupValues[1]}", referer, media))
        }
        if (url.contains("gdshine.", true)) {
            val short = Regex("""(?i)gdshine\.[^/]+/(?:file|s)/([A-Za-z0-9]+)""").find(url)
            if (short != null) {
                val origin = ProviderSupport.origin(url)
                val info = Http.text("$origin/api/files/s/${short.groupValues[1]}", ProviderSupport.headers(referer), 8)
                val id = runCatching {
                    org.json.JSONObject(info.orEmpty()).optJSONObject("data")?.optString("id").orEmpty()
                }.getOrNull().orEmpty()
                if (id.isNotBlank()) {
                    val mirror = Http.text("$origin/api/gp/mirror/$id", ProviderSupport.headers(referer), 8)
                    val direct = runCatching {
                        org.json.JSONObject(mirror.orEmpty()).optJSONObject("data")?.optString("url").orEmpty()
                    }.getOrNull().orEmpty()
                    if (direct.startsWith("http")) return listOf(candidate(spec, direct, referer, media))
                }
            }
        }
        if (url.contains("hubcloud.", true) || url.contains("hubdrive.", true) ||
            url.contains("hubcdn.", true) || url.contains("vcloud.", true) ||
            url.contains("greenmotors", true) || url.contains("mdrive.", true)
        ) {
            val files = hubDriveFiles(url, referer, depth)
            if (files.isNotEmpty()) return files.map { (file, label) ->
                candidate(spec, file, referer, listOf(label, media).filter { it.isNotBlank() }.joinToString(" "))
            }
        }
        val decoded = decodeDlLink(url)
        if (decoded != null) return listOf(candidate(spec, decoded, referer, media))
        if (VIDEO.containsMatchIn(url)) return listOf(candidate(spec, url, referer, media))
        return emptyList()
    }

    private suspend fun hubDriveFiles(url: String, referer: String, depth: Int): List<Pair<String, String>> {
        if (depth > 3) return emptyList()
        val page = Http.text(url, ProviderSupport.headers(referer), 12) ?: return emptyList()
        val direct = VIDEO.findAll(page).map { it.value }.distinct().toList()
        if (direct.isNotEmpty()) return direct.map { it to "" }

        val reurl = REURL.find(page)?.groupValues?.get(1)
        if (reurl != null) {
            val raw = reurl.substringAfter("r=", reurl)
            val decoded = B64.decodeText(raw)
            val link = decoded?.substringAfter("link=", "")
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            if (!link.isNullOrBlank() && link.startsWith("http")) return listOf(link to "")
        }

        val doc = Jsoup.parse(page, url)
        val nested = doc.select("a[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            if (!href.startsWith("http")) return@mapNotNull null
            if (!FILE_HOST.containsMatchIn(href) && !href.contains("token", true) &&
                !href.contains("drive", true)
            ) return@mapNotNull null
            if (href == url) return@mapNotNull null
            href to anchor.text().trim()
        }.distinctBy { it.first }
        for ((href, label) in nested.take(3)) {
            val found = hubDriveFiles(href, url, depth + 1)
            if (found.isNotEmpty()) return found.map { it.first to listOf(label, it.second).filter { it.isNotBlank() }.joinToString(" ") }
        }
        val token = doc.select("a[href]").firstOrNull { it.attr("href").contains("token", true) }
            ?.absUrl("href")
        if (!token.isNullOrBlank() && token != url) {
            val tokenPage = Http.text(token, ProviderSupport.headers(url), 10).orEmpty()
            val buttons = Jsoup.parse(tokenPage, token).select("a[href]").mapNotNull { anchor ->
                val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
                if (!href.startsWith("http") || href.contains("pixel.", true) || href.contains("t.me", true)) {
                    return@mapNotNull null
                }
                href to anchor.text().trim()
            }.distinctBy { it.first }
            if (buttons.isNotEmpty()) return buttons
        }
        return emptyList()
    }

    private fun decodeDlLink(url: String): String? {
        val raw = Regex("""[?&]link=([^&\s]+)""").find(url)?.groupValues?.get(1) ?: return null
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: raw
        return decoded.takeIf { it.startsWith("http") }
    }

    private fun candidate(spec: ServerSpec, url: String, referer: String, media: String): Candidate {
        val quality = Naming.qualityHeight(media)
        return ProviderSupport.candidate(
            spec = spec,
            url = url,
            referer = referer,
            quality = quality,
            audio = Naming.displayLabel(media),
            size = Naming.sizeLabel(media),
            media = media,
        )
    }
}
