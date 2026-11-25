package com.crunchyme

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** Toonix is a cartoon/anime frontend. It exposes its player data in the RSC payload. */
object Toonix {

    private const val BASE = "https://toonix.bond"
    private const val REFERER = "$BASE/"
    private val YEAR = Regex("""\b(19|20)\d{2}\b""")

    private data class Card(val href: String, val title: String, val year: Int?, val movie: Boolean)

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val query = URLEncoder.encode(request.title, "UTF-8")
        val search = Http.text("$BASE/search?q=$query", ProviderSupport.headers(REFERER), 20)
            ?: return emptyList()
        val cards = parseSearch(search)
        if (cards.isEmpty()) return emptyList()

        val wantedMovie = request.isMovie
        val hit = cards.asSequence()
            .filter { it.movie == wantedMovie }
            .map { it to TitleMatch.distance(request.title, it.title) }
            .filter { it.second >= 0.55 }
            .maxByOrNull { it.second }
            ?.first
            ?: cards.asSequence()
                .map { it to TitleMatch.distance(request.title, it.title) }
                .filter { it.second >= 0.7 }
                .maxByOrNull { it.second }
                ?.first
            ?: return emptyList()

        val path = hit.href.removePrefix(BASE).trimEnd('/')
        val pageUrl = if (wantedMovie) {
            "$BASE/watch/${path.substringAfterLast('/')}"
        } else {
            "$BASE$path/${request.season}/${request.episode}"
        }
        val response = Http.request(pageUrl, ProviderSupport.headers(REFERER), 20)
        val html = response?.body?.takeIf { response.code in 200..399 && it.isNotBlank() }
            ?: return emptyList()
        return extract(spec, html, request)
    }

    private fun parseSearch(html: String): List<Card> {
        val doc = Jsoup.parse(html, REFERER)
        val out = LinkedHashMap<String, Card>()
        for (anchor in doc.select("a[href]")) {
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            if (!href.startsWith("$BASE/show/") && !href.startsWith("$BASE/title/")) continue
            val title = anchor.selectFirst("h3")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: continue
            val year = anchor.select("span").firstOrNull { YEAR.matches(it.text().trim()) }
                ?.text()?.trim()?.toIntOrNull()
            out.putIfAbsent(href, Card(href, title, year, href.contains("/title/")))
        }
        return out.values.toList()
    }

    private suspend fun extract(
        spec: ServerSpec,
        html: String,
        request: LinkRequest,
    ): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        val flight = html.replace("\\\"", "\"")

        fun add(url: String, kind: MediaKind, audio: String = "", quality: Int = 0) {
            val clean = url.trim()
            if (!clean.startsWith("http") || out.containsKey(clean)) return
            out[clean] = Candidate(
                sourceId = spec.id,
                sourceName = spec.name,
                url = clean,
                kind = kind,
                referer = REFERER,
                headers = mapOf("User-Agent" to Http.UA, "Referer" to REFERER),
                qualityHint = quality,
                audioLabel = audio,
                mediaLabel = request.title,
            )
        }

        val legacy = Regex(""""legacySrc":"([^"]+)"""").find(flight)?.groupValues?.get(1)
        legacy?.takeIf { it.startsWith("http") }?.let {
            add(it, if (it.contains(".mp4", true)) MediaKind.FILE else MediaKind.HLS)
        }

        val serversAt = flight.indexOf("\"servers\":[")
        if (serversAt >= 0) {
            val raw = jsonArrayAfter(flight, serversAt + "\"servers\":".length)
            val servers = runCatching { JSONArray(raw.orEmpty()) }.getOrNull()
            if (servers != null) {
                for (index in 0 until servers.length()) {
                    val server = servers.optJSONObject(index) ?: continue
                    val key = server.optString("key")
                    val src = server.optString("src").trim()
                    if (src.contains("/api/hydrax/")) {
                        resolveHydrax(src).forEach { (url, label) ->
                            val playable = if (url.contains("&filename=")) url else "$url&filename=video.mp4"
                            add(
                                playable,
                                MediaKind.FILE,
                                if (key == "ad-hx") "Server 2" else key,
                                label.removeSuffix("p").toIntOrNull() ?: 0,
                            )
                        }
                        continue
                    }
                    if (src.startsWith("/") || src.startsWith("http")) {
                        val url = if (src.startsWith("http")) src else BASE + src
                        add(
                            url,
                            if (src.contains("/api/stream/")) MediaKind.HLS else ProviderSupport.kind(url),
                            if (key == "ra") "Server 1" else key,
                        )
                    }
                    val versions = server.optJSONArray("versions") ?: continue
                    for (v in 0 until versions.length()) {
                        val version = versions.optJSONObject(v) ?: continue
                        val versionSrc = version.optString("src").trim()
                        if (versionSrc.isBlank()) continue
                        val url = if (versionSrc.startsWith("http")) versionSrc else BASE + versionSrc
                        val label = version.optString("label").ifBlank { key }
                        val server = if (key == "ra") "Server 1" else key
                        add(
                            url,
                            if (versionSrc.contains("/api/stream/")) MediaKind.HLS else ProviderSupport.kind(url),
                            listOf(server, label).filter { it.isNotBlank() }.joinToString(" · "),
                        )
                    }
                }
            }
        }

        // Last-resort scrape for a raw playlist that is not inside the player payload.
        if (out.isEmpty()) {
            Regex("""https?://[^"\\\s]+\.(?:m3u8|mp4)[^"\\\s]*""").findAll(flight)
                .map { it.value }
                .distinct()
                .take(6)
                .forEach { add(it, if (it.contains(".mp4", true)) MediaKind.FILE else MediaKind.HLS) }
        }
        return out.values.toList()
    }

    private suspend fun resolveHydrax(src: String): List<Pair<String, String>> {
        val url = if (src.startsWith("http")) src else BASE + src
        val body = Http.text(url, ProviderSupport.headers(REFERER), 15) ?: return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val qualities = root.optJSONArray("qualities") ?: return emptyList()
        return (0 until qualities.length()).mapNotNull { index ->
            val item = qualities.optJSONObject(index) ?: return@mapNotNull null
            val link = item.optString("src").trim().takeIf { it.startsWith("http") } ?: return@mapNotNull null
            link to item.optString("label")
        }
    }

    private fun jsonArrayAfter(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val c = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }
}
