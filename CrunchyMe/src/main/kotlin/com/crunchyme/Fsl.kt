package com.crunchyme

import java.net.URLDecoder
import java.net.URLEncoder
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * FSL/VegaMovies and RogMovies publish a searchable JSON index and hand out
 * short-lived download gateways. The gateways expose a fastdl embed that
 * finally redirects to a direct Google video URL.
 */
object Fsl {

    private val GATEWAY = Regex("""(?i)nexdrive|gdflix|filepress|fastdl|vcloud|hubcloud|pixeldrain|gdshine""")
    private val FASTDL = Regex("""(?i)fastdl\.[^/]+/embed\?download=[A-Za-z0-9_\-]+""")
    private val VCLOUD = Regex("""(?i)https?://vcloud\.[^"'\s<>]+""")
    private val REURL = Regex("""var\s+reurl\s*=\s*["']([^"']+)["']""")
    private val DIRECT = Regex("""https://video-downloads\.googleusercontent\.com/[^"'\s<>]+""")
    private val VCLOUD_TOKEN = Regex(
        """atob\s*\(\s*atob\s*\(\s*['"]([A-Za-z0-9+/=]{16,})['"]\s*\)\s*\)""",
    )
    private val VCLOUD_BTN = Regex(
        """<a\b[^>]*href="([^"]+)"[^>]*>\s*(?:<[^>]+>\s*)*([^<]*(?:Download|Server|File)[^<]*)(?:<[^>]+>\s*)*</a>""",
        RegexOption.IGNORE_CASE,
    )
    private val STREAMABLE = Regex(
        """(?i)(r2\.cloudflarestorage\.com|r2\.dev|video-downloads\.googleusercontent\.com)""",
    )
    private val HEADING = Regex("""(?i)download|480p|720p|1080p|2160p|4k|web-?dl|webrip|bluray""")

    private data class Hit(val url: String, val title: String)

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        if (request.title.isBlank()) return emptyList()
        val base = (spec.referer ?: ProviderSupport.origin(ServerFarm.urlFor(spec, request).orEmpty()))
            .trimEnd('/')
        val hits = search(base, request) ?: return FileIndex.resolve(spec, request)
        for (hit in hits.take(3)) {
            val post = Http.text(hit.url, ProviderSupport.headers(base), spec.timeoutSec.toLong())
                ?: continue
            run {
                val groups = groups(post, hit.url)
                if (groups.isEmpty()) return@run
                val picked = groups.entries
                    .sortedByDescending { Naming.qualityHeight(it.key) }
                    .take(14)
                val out = LinkedHashMap<String, Candidate>()
                for ((label, gateways) in picked) {
                    if (!request.isMovie && request.hasEpisode &&
                        !ProviderSupport.episodeMatches(label, request.season, request.episode)
                    ) continue
                    for (gateway in gateways.take(2)) {
                        for (url in expand(gateway, hit.url, spec, request)) {
                            val key = url.substringBefore("?").ifBlank { url }
                            if (out.containsKey(key)) continue
                            out[key] = ProviderSupport.candidate(
                                spec = spec,
                                url = url,
                                referer = "https://fastdl.beer/",
                                quality = Naming.qualityHeight(label),
                                audio = Naming.displayLabel(label),
                                size = Naming.sizeLabel("$label ${hit.title}"),
                                media = listOf(label, hit.title).filter { it.isNotBlank() }.joinToString(" "),
                            )
                        }
                        if (out.size >= 10) break
                    }
                    if (out.size >= 10) break
                }
                if (out.isNotEmpty()) return out.values.toList()
            }
        }
        // Last resort: the generic index can still handle a direct host on the post.
        return FileIndex.resolve(spec, request)
    }

    private suspend fun search(base: String, request: LinkRequest): List<Hit>? {
        val query = URLEncoder.encode(request.title, "UTF-8").replace("+", "%20")
        val searchUrl = "$base/ts-search.php?q=$query&page=1"
        val body = Http.text(searchUrl, ProviderSupport.headers("$base/"), 20) ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val hits = root.optJSONArray("hits") ?: return null
        val rows = mutableListOf<Hit>()
        for (i in 0 until hits.length()) {
            val doc = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
            val path = doc.optString("permalink").replace("\\/", "/").trim()
            if (path.isBlank()) continue
            val url = if (path.startsWith("http")) path else base.trimEnd('/') + "/" + path.trimStart('/')
            val title = doc.optString("post_title").trim().ifBlank { doc.optString("title") }
            if (title.isBlank()) continue
            rows += Hit(url, title)
        }
        return rows.map { it to TitleMatch.distance(request.title, it.title) }
            .filter { (hit, score) ->
                score >= 0.55 && (request.year == null || TitleMatch.matchesYear(request.year, TitleMatch.year(hit.title)))
            }
            .sortedWith(compareByDescending<Pair<Hit, Double>> { it.second }.thenBy { it.first.title.length })
            .map { it.first }
    }

    private fun groups(html: String, base: String): Map<String, List<String>> {
        val doc = Jsoup.parse(html, base)
        val root = doc.selectFirst("div.entry-content, article, .post-content, main") ?: doc.body()
        val groups = LinkedHashMap<String, MutableList<String>>()
        var heading = ""
        for (element in root.getAllElements()) {
            val tag = element.tagName().lowercase()
            if (tag in setOf("h1", "h2", "h3", "h4", "h5", "h6")) {
                val text = element.text().trim()
                if (text.isNotBlank() && HEADING.containsMatchIn(text)) heading = text
                continue
            }
            if (tag != "a") continue
            val href = element.absUrl("href").ifBlank { element.attr("href") }.trim()
            if (!href.startsWith("http") || !GATEWAY.containsMatchIn(href)) continue
            if (href.contains("t.me", true) || href.contains("/admin", true)) continue
            groups.getOrPut(heading.ifBlank { "Download" }) { mutableListOf() }.add(href)
        }
        return groups
    }

    private suspend fun expand(
        gateway: String,
        referer: String,
        spec: ServerSpec,
        request: LinkRequest,
    ): List<String> {
        if (FASTDL.containsMatchIn(gateway)) return fastdl(gateway)
        if (gateway.contains("vcloud.", true)) return vcloud(gateway)
        val page = Http.text(gateway, ProviderSupport.headers(referer), 15) ?: return emptyList()
        val out = mutableListOf<String>()
        val cloud = VCLOUD.findAll(page).map { it.value }.distinct().take(3)
        for (url in cloud) out += vcloud(url)
        val fast = FASTDL.findAll(page).map { it.value }
            .map { if (it.startsWith("http")) it else "https://$it" }
            .distinct().toList()
        for (embed in fast) out += fastdl(embed)
        if (out.isEmpty() && GATEWAY.containsMatchIn(gateway)) {
            out += FileIndex.resolveLink(spec, gateway, referer, request, "", 1).map { it.url }
        }
        return out.distinct()
    }

    private suspend fun fastdl(url: String): List<String> {
        val page = Http.text(url, ProviderSupport.headers("https://fastdl.beer/"), 15) ?: return emptyList()
        val direct = extractFastdlDirect(page) ?: return emptyList()
        return listOf(direct)
    }

    internal fun extractFastdlDirect(page: String): String? {
        DIRECT.find(page)?.value?.let { return it }
        val reurl = REURL.find(page)?.groupValues?.get(1) ?: return null
        val raw = Regex("""[?&]link=([^&\s]+)""").find(reurl)?.groupValues?.get(1)
            ?: return reurl.takeIf { DIRECT.matches(it) }
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
            ?.takeIf { it.startsWith("http") }
    }

    private suspend fun vcloud(url: String): List<String> {
        val page = Http.text(url, ProviderSupport.headers("https://vcloud.my/"), 15) ?: return emptyList()
        val tokenUrl = VCLOUD_TOKEN.find(page)?.groupValues?.get(1)
            ?.let(::b64Twice)
            ?.takeIf { it.startsWith("http") } ?: return emptyList()
        val token = Http.text(tokenUrl, ProviderSupport.headers(url), 15) ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (match in VCLOUD_BTN.findAll(token)) {
            val raw = match.groupValues[1].trim()
            val absolute = if (raw.startsWith("http")) raw else ProviderSupport.absolute(tokenUrl, raw)
            if (STREAMABLE.containsMatchIn(absolute) || absolute.contains(".mp4", true) ||
                absolute.contains(".mkv", true) || absolute.contains(".m3u8", true) ||
                absolute.contains(".mpd", true)
            ) out += absolute
        }
        return out.toList()
    }

    private fun b64Twice(value: String): String? {
        val first = B64.decodeText(value) ?: return null
        return B64.decodeText(first)
    }
}
