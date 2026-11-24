package com.crunchyme

import java.net.URLEncoder
import org.jsoup.Jsoup
import org.json.JSONObject

object AnimeWorld {

    private const val CATALOG = "https://watchanimeworld.one"
    private const val PLAYER = "https://play.zephyrix.org"
    private val EPISODE = Regex("""(?i)/episode/[^"']*?-(\d+)x(\d+)/""")
    private val PLAYER_ID = Regex("""play\.zephyrix\.org/video/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val POST_ID = Regex("""data-post="(\d+)"""")
    private val SEASON = Regex("""data-season="(\d+)"""")

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val query = URLEncoder.encode(request.title, "UTF-8")
        val search = Http.text("$CATALOG/?s=$query", ProviderSupport.headers("$CATALOG/"), 15)
            ?: return emptyList()
        val doc = Jsoup.parse(search, "$CATALOG/")
        val cards = doc.select("article.post, article").mapNotNull { card ->
            val link = card.selectFirst("h2.entry-title a[href], a.lnk-blk[href], h2 a[href]")
                ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val title = card.selectFirst("h2.entry-title")?.text()?.trim()
                ?: link.attr("title").trim().ifBlank { link.text().trim() }
            if (!href.startsWith("http") || title.isBlank()) return@mapNotNull null
            Triple(href, title, href.contains("/movies/"))
        }.distinctBy { it.first }
        val movie = request.isMovie
        val hit = cards.filter { it.third == movie }
            .maxByOrNull { TitleMatch.distance(request.title, it.second) }
            ?.takeIf { TitleMatch.distance(request.title, it.second) >= 0.6 }
            ?: return emptyList()

        val seriesHtml = Http.text(hit.first, ProviderSupport.headers("$CATALOG/"), 15) ?: return emptyList()
        val pageUrl = if (movie) hit.first else episodePage(seriesHtml, request) ?: return emptyList()
        val pageHtml = if (pageUrl == hit.first) seriesHtml
        else Http.text(pageUrl, ProviderSupport.headers("$CATALOG/"), 15) ?: return emptyList()
        val playerId = PLAYER_ID.find(pageHtml)?.groupValues?.get(1) ?: return emptyList()
        val body = Http.post(
            "$PLAYER/player/index.php?data=$playerId&do=getVideo",
            "hash=$playerId&r=" + URLEncoder.encode("$CATALOG/", "UTF-8"),
            headers = mapOf(
                "User-Agent" to Http.UA,
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to PLAYER,
                "Referer" to "$CATALOG/",
            ),
            timeoutSec = 15,
        ) ?: return emptyList()
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val url = json.optString("securedLink").ifBlank { json.optString("videoSource") }
            .takeIf { it.startsWith("http") } ?: return emptyList()
        return listOf(
            ProviderSupport.candidate(
                spec = spec,
                url = url,
                referer = "$PLAYER/",
                audio = "Japanese",
                media = request.title,
                headers = mapOf("Referer" to "$PLAYER/"),
            ),
        )
    }

    private suspend fun episodePage(seriesHtml: String, request: LinkRequest): String? {
        val rendered = EPISODE.findAll(seriesHtml).map { it.value }.distinct().toList()
        rendered.firstOrNull { url ->
            val match = EPISODE.find(url)
            match?.groupValues?.get(1)?.toIntOrNull() == request.season &&
                match.groupValues[2].toIntOrNull() == request.episode
        }?.let { return it }
        val post = POST_ID.find(seriesHtml)?.groupValues?.get(1) ?: return null
        val seasons = SEASON.findAll(seriesHtml).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
        val season = request.season.takeIf { it in seasons } ?: seasons.firstOrNull() ?: return null
        val seasonHtml = Http.text(
            "$CATALOG/wp-admin/admin-ajax.php?action=action_select_season&season=$season&post=$post",
            mapOf(
                "User-Agent" to Http.UA,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$CATALOG/",
            ),
            12,
        ) ?: return null
        return EPISODE.findAll(seasonHtml).map { it.value }.distinct()
            .firstOrNull { url ->
                val match = EPISODE.find(url)
                match?.groupValues?.get(1)?.toIntOrNull() == request.season &&
                    match.groupValues[2].toIntOrNull() == request.episode
            }
    }
}

object HiAnime {

    private const val INDEX = "https://hianime.at"
    private const val EMBED = "ZokoAnime"
    private const val EMBED_REFERER = "https://zokoanime.video/"
    private const val XOR_KEY = "otaku-embed-v1"
    private val PLAYER_BLOB = Regex("""window\.__P\s*=\s*"([^"]+)"""")

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val query = URLEncoder.encode(request.title, "UTF-8")
        val search = Http.text("$INDEX/search?keyword=$query", headers("$INDEX/"), 15) ?: return emptyList()
        val doc = Jsoup.parse(search, "$INDEX/")
        val rows = doc.select("a.dynamic-name[href], a[href].dynamic-name").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            val id = Regex("""-(\d+)/?$""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = anchor.attr("title").trim().ifBlank { anchor.text().trim() }
            if (title.isBlank()) return@mapNotNull null
            Triple(id, title, href)
        }.distinctBy { it.first }
        val hit = rows.maxByOrNull { TitleMatch.distance(request.title, it.second) }
            ?.takeIf { TitleMatch.distance(request.title, it.second) >= 0.6 }
            ?: return emptyList()

        val episodesJson = Http.text(
            "$INDEX/api/theme/episode/list/${hit.first}",
            ajaxHeaders(),
            15,
        ) ?: return emptyList()
        val episodesHtml = runCatching { JSONObject(episodesJson).optString("html") }.getOrDefault("")
        val episodes = Jsoup.parse(episodesHtml).select("a[data-number][data-id]").mapNotNull { anchor ->
            val number = anchor.attr("data-number").toIntOrNull() ?: return@mapNotNull null
            val id = anchor.attr("data-id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            number to id
        }
        if (episodes.isEmpty()) return emptyList()
        val wanted = if (request.isMovie) episodes.first().first else request.episode
        val episode = episodes.firstOrNull { it.first == wanted } ?: return emptyList()

        val serversJson = Http.text(
            "$INDEX/api/theme/episode/servers?episodeId=${episode.second}",
            ajaxHeaders(),
            15,
        ) ?: return emptyList()
        val serversHtml = runCatching { JSONObject(serversJson).optString("html") }.getOrDefault("")
        val servers = Jsoup.parse(serversHtml).select("div.server-item[data-hash]").mapNotNull { item ->
            val name = item.attr("data-server-name").trim()
            val type = item.attr("data-type").trim()
            val hash = item.attr("data-hash").trim()
            val url = B64.decodeText(hash)?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            Triple(name, type, url)
        }.filter { it.first.equals(EMBED, true) }
        if (servers.isEmpty()) return emptyList()

        val out = mutableListOf<Candidate>()
        for ((_, type, url) in servers) {
            val html = Http.text(
                url,
                mapOf("User-Agent" to Http.UA, "Referer" to EMBED_REFERER),
                15,
            ) ?: continue
            val blob = PLAYER_BLOB.find(html)?.groupValues?.get(1) ?: continue
            val plain = xorDecode(blob) ?: continue
            val json = runCatching { JSONObject(plain) }.getOrNull() ?: continue
            val src = json.optString("src").takeIf { it.startsWith("http") } ?: continue
            val dub = type.equals("dub", true)
            out += Candidate(
                sourceId = spec.id,
                sourceName = if (dub) "${spec.name} English" else "${spec.name} Japanese",
                url = src,
                kind = ProviderSupport.kind(src),
                referer = EMBED_REFERER,
                headers = mapOf("Referer" to EMBED_REFERER, "User-Agent" to Http.UA),
                audioLabel = if (dub) "English" else "Japanese",
                mediaLabel = request.title,
            )
        }
        return out.distinctBy { it.url }
    }

    private fun headers(referer: String): Map<String, String> = mapOf(
        "User-Agent" to Http.UA,
        "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Referer" to referer,
    )

    private fun ajaxHeaders(): Map<String, String> = mapOf(
        "User-Agent" to Http.UA,
        "Accept" to "application/json, text/plain, */*",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$INDEX/",
    )

    private fun xorDecode(blob: String): String? = try {
        val raw = B64.decode(blob) ?: return null
        String(ByteArray(raw.size) { index -> (raw[index].toInt() xor XOR_KEY[index % XOR_KEY.length].code).toByte() })
    } catch (t: Throwable) {
        null
    }
}
