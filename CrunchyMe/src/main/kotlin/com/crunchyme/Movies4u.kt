package com.crunchyme

import org.jsoup.Jsoup
import org.json.JSONObject

object Movies4u {

    private val FILE_HOST = Regex("""(?i)hubcloud|hubdrive|hubcdn|vcloud|pixeldrain|gdshine|mdrive|greenmotors""")

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val searchUrl = ServerFarm.urlFor(spec, request) ?: return emptyList()
        val base = spec.referer ?: ProviderSupport.origin(searchUrl)
        val body = Http.text(searchUrl, ProviderSupport.headers(base), 12) ?: return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val hits = root.optJSONArray("hits") ?: return emptyList()
        val posts = (0 until hits.length()).mapNotNull { index ->
            val item = hits.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("post_title").trim()
            val permalink = item.optString("permalink").replace("\\/", "/").trim()
            if (title.isBlank() || permalink.isBlank()) return@mapNotNull null
            val url = if (permalink.startsWith("http")) permalink
            else base.trimEnd('/') + "/" + permalink.trimStart('/')
            url to title
        }.map { it to TitleMatch.distance(request.title, it.second) }
            .filter { it.second >= 0.5 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(3)
        if (posts.isEmpty()) return emptyList()

        for ((postUrl, postTitle) in posts) {
            val html = Http.text(postUrl, ProviderSupport.headers(base), 12) ?: continue
            val doc = Jsoup.parse(html, postUrl)
            val groups = mutableListOf<Pair<String, List<String>>>()
            for (heading in doc.select("h4, h5")) {
                val box = heading.nextElementSibling() ?: continue
                if (!box.hasClass("downloads-btns-div")) continue
                val links = box.select("a[href]").map { it.absUrl("href").ifBlank { it.attr("href") } }
                    .filter { it.startsWith("http") && FILE_HOST.containsMatchIn(it) }
                    .distinct()
                if (links.isNotEmpty()) groups += heading.text().trim() to links
            }
            val chosen = if (request.isMovie || !request.hasEpisode) {
                groups
            } else {
                val matching = groups.filter { (heading, _) ->
                    ProviderSupport.episodeMatches("$heading $postTitle", request.season, request.episode)
                }
                matching.ifEmpty { groups }
            }
            val out = mutableListOf<Candidate>()
            for ((heading, links) in chosen.take(4)) {
                for (link in links.take(3)) {
                    out += FileIndex.resolveLink(
                        spec = spec,
                        url = link,
                        referer = postUrl,
                        request = request,
                        media = listOf(heading, postTitle).filter { it.isNotBlank() }.joinToString(" "),
                        depth = 0,
                    )
                }
                if (out.size >= 8) break
            }
            if (out.isNotEmpty()) return out.distinctBy { it.url }
        }
        return emptyList()
    }
}
