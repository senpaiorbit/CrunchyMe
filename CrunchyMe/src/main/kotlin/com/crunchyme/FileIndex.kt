package com.crunchyme

import java.net.URLDecoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup

/**
 * Shared resolver for the Indian file-index providers. They all expose a search page,
 * then a post with gateway/file-host links. The final host is resolved recursively.
 */
object FileIndex {

    private data class Hit(val url: String, val title: String)

    private val FILE_HOST = Regex(
        """(?i)hubcloud|hubdrive|hubcdn|vcloud|pixeldrain|gdshine|mdrive|filebee|gadgetsweb|""" +
            """workers\.dev|dl\.php|greenmotors|fastdlserver|fastdl|nexdrive|gdflix|gdlink|filepress|""" +
            """driveseed|driveleech|unblocked|sidexfee|links\.joshapp|linkskit|boldb|hshare|\.mkv|\.mp4|\.m3u8|\.mpd""",
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
            val out = coroutineScope {
                links.take(6).map { (href, label) ->
                    async {
                        runCatching {
                            withTimeoutOrNull(14_000) {
                                resolveLink(
                                    spec, href, hit.url, request,
                                    listOf(label, hit.title).joinToString(" "), 0,
                                )
                            }.orEmpty()
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
            if (out.isNotEmpty()) return out.distinctBy { it.url }.take(10)
        }
        return emptyList()
    }

    private fun parseHits(html: String, base: String): List<Hit> {
        val doc = Jsoup.parse(html, base)
        val out = LinkedHashMap<String, String>()
        val specific = doc.select(
            "div.post-cards article a[href], article.poster-card a[href], " +
                "article.poster-card a.poster-art[href], div.poster-caption a[href], " +
                "a.movie-card[href], h1.entry-title a[href], h2.entry-title a[href], " +
                "h3.entry-title a[href], a[aria-label][href], a[data-wpel-link][href]",
        )
        val anchors = if (specific.isNotEmpty()) specific else doc.select("a[href]")
        for (anchor in anchors) {
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            if (!href.startsWith("http") || NON_CONTENT.containsMatchIn(href)) continue
            if (href == base || href.trimEnd('/') == base.trimEnd('/')) continue
            val title = anchor.attr("aria-label").removeSuffix(" details").trim()
                .ifBlank { anchor.attr("title").removePrefix("Download ").trim() }
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
            if (!href.startsWith("http")) return@mapNotNull null
            if (!FILE_HOST.containsMatchIn(href) && !href.contains("url=", true)) return@mapNotNull null
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
        Regex("""[?&]url=([^&]+)""").find(url)?.groupValues?.get(1)?.let { raw ->
            val plain = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
            val decoded = plain?.takeIf { it.startsWith("http") }
                ?: plain?.let { B64.decodeText(it) }
                ?: B64.decodeText(raw)
            if (decoded != null && decoded.startsWith("http") && decoded != url) {
                val nested = resolveLink(spec, decoded, referer, request, media, depth + 1)
                if (nested.isNotEmpty()) return nested
            }
        }
        if (url.contains("links.joshapp", true) || url.contains("linkskit", true) ||
            url.contains("boldb.", true) || url.contains("hshare.", true)
        ) {
            val files = linkIndexFiles(spec, request, url, referer, depth)
            if (files.isNotEmpty()) return files
        }
        if (url.contains("greenmotors", true)) {
            val gateway = Http.text(url, ProviderSupport.headers(referer), 12)
            val target = gateway?.let(::rotatorTarget)
            if (target != null) {
                val page = Http.text(target, ProviderSupport.headers(url), 12)
                if (page != null) {
                    val files = directLinks(page, target, spec, referer, media)
                    if (files.isNotEmpty()) return files
                }
            }
        }
        val pixeldrain = Regex("""(?i)pixeldrain\.(?:com|dev|net)/u/([A-Za-z0-9]+)""").find(url)
        if (pixeldrain != null) {
            return listOf(candidate(spec, "https://pixeldrain.dev/api/file/${pixeldrain.groupValues[1]}", referer, media))
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
        if (url.contains("fastdlserver.", true) || url.contains("fastdl.", true)) {
            val files = fastdlFiles(url, referer)
            if (files.isNotEmpty()) return files.map { (file, label) ->
                candidate(spec, playable(file), referer, listOf(label, media).filter { it.isNotBlank() }.joinToString(" "))
            }
        }
        if (url.contains("gdflix.", true) || url.contains("gdlink.", true)) {
            val files = gdflixFiles(url, referer)
            if (files.isNotEmpty()) return files.map { (file, label) ->
                candidate(spec, playable(file), referer, listOf(label, media).filter { it.isNotBlank() }.joinToString(" "))
            }
        }
        if (url.contains("driveseed.", true) || url.contains("driveleech.", true) ||
            url.contains("filepress.", true) || url.contains("nexdrive.", true) ||
            url.contains("unblocked.", true) || url.contains("sidexfee.", true)
        ) {
            val files = gatewayFiles(url, referer, depth)
            if (files.isNotEmpty()) return files.map { (file, label) ->
                candidate(spec, playable(file), referer, listOf(label, media).filter { it.isNotBlank() }.joinToString(" "))
            }
        }
        if (url.contains("hubcloud.", true) || url.contains("hubdrive.", true) ||
            url.contains("hubcdn.", true) || url.contains("vcloud.", true) ||
            url.contains("greenmotors", true) || url.contains("mdrive.", true)
        ) {
            val files = hubDriveFiles(url, referer, depth)
            if (files.isNotEmpty()) return files.map { (file, label) ->
                candidate(spec, playable(file), referer, listOf(label, media).filter { it.isNotBlank() }.joinToString(" "))
            }
        }
        val decoded = decodeDlLink(url)
        if (decoded != null) return listOf(candidate(spec, decoded, referer, media))
        if (VIDEO.containsMatchIn(url)) return listOf(candidate(spec, url, referer, media))
        if (depth < 3) {
            val page = Http.text(url, ProviderSupport.headers(referer), 12)
            if (!page.isNullOrBlank()) {
                val nested = Jsoup.parse(page, url).select("a[href]").mapNotNull { anchor ->
                    val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
                    href.takeIf { it.startsWith("http") && isUsefulNested(it) }?.let {
                        it to anchor.text().trim()
                    }
                }.distinctBy { it.first }.take(4)
                for ((href, label) in nested) {
                    val found = resolveLink(spec, href, url, request, listOf(media, label).joinToString(" "), depth + 1)
                    if (found.isNotEmpty()) return found
                }
            }
        }
        return emptyList()
    }

    private suspend fun fastdlFiles(url: String, referer: String): List<Pair<String, String>> {
        val result = Http.request(url, ProviderSupport.headers(referer), 15) ?: return emptyList()
        val final = result.finalUrl
        if (final != url && (final.contains("gdflix.", true) || final.contains("gdlink.", true))) {
            return gdflixFiles(final, url)
        }
        val reurl = REURL.find(result.body)?.groupValues?.get(1)
        val direct = reurl?.let(::decodeLink) ?: decodeDlLink(url)
        if (!direct.isNullOrBlank() && direct.startsWith("http")) return listOf(direct to "")
        return emptyList()
    }

    private suspend fun gdflixFiles(url: String, referer: String): List<Pair<String, String>> {
        val page = Http.text(url, ProviderSupport.headers(referer), 15) ?: return emptyList()
        val doc = Jsoup.parse(page, url)
        val base = ProviderSupport.origin(url)
        val out = LinkedHashMap<String, String>()
        val anchors = doc.select("div.text-center a[href], a.btn-success[href], div.card-body a[href]")
        for (anchor in anchors) {
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            if (!href.startsWith("http")) continue
            val text = anchor.text().trim()
            when {
                text.contains("GD Index", true) -> {
                    for (type in 1..2) {
                        val index = Http.text("$href?type=$type", ProviderSupport.headers(url), 10) ?: continue
                        Jsoup.parse(index, href).select("a.btn-success[href]").forEach { link ->
                            val file = link.absUrl("href").ifBlank { link.attr("href") }
                            if (file.contains(".zip", true)) return@forEach
                            if (file.startsWith("http")) out.putIfAbsent(file, "$text $type")
                        }
                    }
                }
                text.contains("FAST CLOUD", true) -> {
                    val cloud = Http.text(href, ProviderSupport.headers(url), 10) ?: continue
                    Jsoup.parse(cloud, href).select("a[href]").forEach { link ->
                        val file = link.absUrl("href").ifBlank { link.attr("href") }
                        val label = link.text().trim()
                        if (file.contains(".zip", true)) return@forEach
                        if (file.startsWith("http") && (
                                label.contains("Download", true) || label.contains("Cloud", true) ||
                                    isDirect(file) || file.contains("workers.dev", true) ||
                                    file.contains("quick.", true) || file.contains("pixeldra", true)
                                )
                        ) {
                            out.putIfAbsent(playable(file), label.ifBlank { text })
                        }
                    }
                }
                text.contains("DRIVEBOT", true) -> {
                    gatewayFiles(href, url, 1).forEach { out.putIfAbsent(it.first, it.second.ifBlank { text }) }
                }
                text.contains("Instant DL", true) -> {
                    val redirect = Http.request(href, ProviderSupport.headers(url), 10)?.finalUrl
                    val file = redirect?.substringAfter("url=", redirect) ?: href
                    if (file.startsWith("http")) out.putIfAbsent(file, text)
                }
                text.contains("FSL V2", true) || text.contains("DIRECT DL", true) ||
                    text.contains("DIRECT SERVER", true) || text.contains("CLOUD DOWNLOAD", true) ||
                    href.contains("pixeldra", true) || text.contains("GoFile", true) -> {
                    val file = playable(href)
                    out.putIfAbsent(file, text)
                }
            }
        }
        return out.map { it.key to it.value }
    }

    private suspend fun gatewayFiles(url: String, referer: String, depth: Int): List<Pair<String, String>> {
        if (depth > 3) return emptyList()
        val result = Http.request(url, ProviderSupport.headers(referer), 15) ?: return emptyList()
        val final = result.finalUrl
        if (final != url && final.startsWith("http")) {
            val nested = resolveLink(
                ServerFarm.spec("bollyflix") ?: return emptyList(),
                final, url, LinkRequest(0, null, "movie", 0, 0, "", null, null), "", depth + 1,
            )
            if (nested.isNotEmpty()) return nested.map { it.url to it.mediaLabel }
        }
        val page = result.body
        val doc = Jsoup.parse(page, url)
        val direct = doc.select("a[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            if (href.startsWith("http") && (isDirect(href) || href.contains("pixeldrain", true))) {
                playable(href) to anchor.text().trim()
            } else null
        }
        if (direct.isNotEmpty()) return direct
        val reurl = REURL.find(page)?.groupValues?.get(1)?.let(::decodeLink)
        if (!reurl.isNullOrBlank() && reurl.startsWith("http")) return listOf(reurl to "")
        val nested = doc.select("a[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            href.takeIf { it.startsWith("http") && isUsefulNested(it) }?.let { it to anchor.text().trim() }
        }.take(3)
        for ((href, label) in nested) {
            val found = gatewayFiles(href, url, depth + 1)
            if (found.isNotEmpty()) return found.map { it.first to listOf(label, it.second).filter { s -> s.isNotBlank() }.joinToString(" ") }
        }
        return emptyList()
    }

    private fun decodeLink(raw: String): String? {
        val value = raw.substringAfter("link=", raw)
        val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: value
        return decoded.takeIf { it.startsWith("http") }
    }

    private suspend fun hubDriveFiles(url: String, referer: String, depth: Int): List<Pair<String, String>> {
        if (depth > 3) return emptyList()
        val page = Http.text(url, ProviderSupport.headers(referer), 12) ?: return emptyList()
        rotatorTarget(page)?.let { target ->
            val nested = hubDriveFiles(target, url, depth + 1)
            if (nested.isNotEmpty()) return nested
        }

        val doc = Jsoup.parse(page, url)
        val anchors = doc.select("a[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            if (!href.startsWith("http")) return@mapNotNull null
            href to anchor.text().trim()
        }.distinctBy { it.first }

        val direct = anchors.map { it.first }.filter { isDirect(it) }.map { playable(it) }
        if (direct.isNotEmpty()) return direct.map { it to "" }

        val reurl = REURL.find(page)?.groupValues?.get(1)
        if (reurl != null) {
            val raw = reurl.substringAfter("r=", reurl)
            val decoded = B64.decodeText(raw)
            val link = decoded?.substringAfter("link=", "")
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            if (!link.isNullOrBlank() && link.startsWith("http")) return listOf(link to "")
        }

        val nested = anchors.filter { (href, _) ->
            href != url && isUsefulNested(href)
        }
        for ((href, label) in nested.take(3)) {
            val found = hubDriveFiles(href, url, depth + 1)
            if (found.isNotEmpty()) {
                return found.map { (file, nestedLabel) ->
                    file to listOf(label, nestedLabel).filter { it.isNotBlank() }.joinToString(" ")
                }
            }
        }
        return emptyList()
    }

    private suspend fun directLinks(
        page: String,
        base: String,
        spec: ServerSpec,
        referer: String,
        media: String,
    ): List<Candidate> {
        val anchors = Jsoup.parse(page, base).select("a[href]").mapNotNull { anchor ->
            anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
        }.distinct()
        return anchors.filter { it.startsWith("http") && isDirect(it) }
            .map { candidate(spec, playable(it), referer, media) }
    }

    private fun isUsefulNested(href: String): Boolean {
        val path = runCatching { java.net.URI.create(href).path }.getOrNull().orEmpty()
        if (path.isBlank() || path == "/" || path.startsWith("/page/") ||
            path.contains("privacy", true) || path.contains("terms", true) ||
            path.contains("dmca", true) || path.contains("contact", true) ||
            path.contains("/sign", true) || path.contains("/admin", true)
        ) return false
        return href.contains("/drive/", true) || href.contains("/file/", true) ||
            href.contains("token", true) || href.contains("hubcloud", true) ||
            href.contains("pixeldrain", true) || href.contains("gdshine", true) ||
            href.contains("workers.dev", true) || href.contains("fastdl", true) ||
            href.contains("driveseed", true) || href.contains("driveleech", true) ||
            href.contains("filepress", true) || href.contains("nexdrive", true) ||
            href.contains("gdflix", true) || href.contains("gdlink", true) ||
            href.contains("url=", true)
    }

    private fun playable(url: String): String {
        val pixel = Regex("""(?i)pixeldrain\.(?:com|dev|net)/u/([A-Za-z0-9]+)""").find(url)
        return if (pixel != null) "https://pixeldrain.dev/api/file/${pixel.groupValues[1]}" else url
    }

    private fun isDirect(href: String): Boolean {
        val path = runCatching { java.net.URI.create(href).path }.getOrNull().orEmpty()
        if (path.endsWith(".zip", true)) return false
        return path.endsWith(".mkv", true) || path.endsWith(".mp4", true) ||
            path.endsWith(".m3u8", true) || path.endsWith(".mpd", true) ||
            href.contains("r2.cloudflarestorage.com", true) ||
            href.contains("pixeldrain", true) || href.contains("workers.dev", true)
    }

    private suspend fun linkIndexFiles(
        spec: ServerSpec,
        request: LinkRequest,
        url: String,
        referer: String,
        depth: Int,
    ): List<Candidate> {
        val page = Http.text(url, ProviderSupport.headers(referer), 12) ?: return emptyList()
        val doc = Jsoup.parse(page, url)
        if (url.contains("boldb.", true) || url.contains("hshare.", true)) {
            val hosts = fileHostLinks(page, url)
            return hosts.take(6).flatMap { (host, label) ->
                resolveLink(spec, host, url, request, label, depth + 1)
            }.distinctBy { it.url }
        }
        val boldb = doc.select("a[href]").firstOrNull { it.attr("href").contains("boldb.site/?id=") }
        if (boldb != null) {
            val rawId = runCatching {
                URLDecoder.decode(boldb.attr("href").substringAfter("id=", ""), "UTF-8")
            }.getOrNull().orEmpty()
            val token = B64.encodeUrl(rawId.toByteArray(Charsets.UTF_8))
            val filePage = Http.text("https://boldb.site/dl/$token", ProviderSupport.headers("https://boldb.site/"), 12)
            if (filePage != null) {
                val hosts = fileHostLinks(filePage, "https://boldb.site/")
                return hosts.take(6).flatMap { (host, label) ->
                    val media = listOf(label, rawId).filter { it.isNotBlank() }.joinToString(" ")
                    resolveLink(spec, host, "https://boldb.site/", request, media, depth + 1)
                }.distinctBy { it.url }
            }
        }
        val hshare = doc.select("a[href]").firstOrNull { it.attr("href").contains("hshare.lol/?id=") }
        if (hshare != null) {
            val mirror = Http.text(hshare.absUrl("href"), ProviderSupport.headers(url), 12) ?: return emptyList()
            val hosts = fileHostLinks(mirror, hshare.absUrl("href"))
            return hosts.take(6).flatMap { (host, label) ->
                resolveLink(spec, host, hshare.absUrl("href"), request, label, depth + 1)
            }.distinctBy { it.url }
        }
        return emptyList()
    }

    private fun fileHostLinks(html: String, base: String): List<Pair<String, String>> =
        Jsoup.parse(html, base).select("a[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            if (!href.startsWith("http")) return@mapNotNull null
            if (!FILE_HOST.containsMatchIn(href) && !href.contains("url=", true)) return@mapNotNull null
            if (href.contains("t.me", true) || href.contains("telegram", true)) return@mapNotNull null
            href to anchor.text().trim()
        }.distinctBy { it.first }

    private fun rotatorTarget(pageHtml: String): String? {
        val blob = Regex("""'o'\s*,\s*'([A-Za-z0-9+/=]{16,})[^']*'""").find(pageHtml)
            ?.groupValues?.get(1) ?: return null
        val l1 = B64.decodeText(blob) ?: return null
        val l2 = B64.decodeText(l1) ?: return null
        val jsonText = B64.decodeText(rot13(l2)) ?: return null
        val token = runCatching { org.json.JSONObject(jsonText).optString("o") }.getOrNull() ?: return null
        return B64.decodeText(token)?.takeIf { it.startsWith("http") }
    }

    private fun rot13(text: String): String = buildString {
        for (c in text) when {
            c in 'A'..'Z' -> append('A' + (c - 'A' + 13) % 26)
            c in 'a'..'z' -> append('a' + (c - 'a' + 13) % 26)
            else -> append(c)
        }
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
