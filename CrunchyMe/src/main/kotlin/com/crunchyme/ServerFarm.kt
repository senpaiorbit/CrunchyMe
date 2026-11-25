package com.crunchyme

import java.net.URLEncoder
import java.util.Locale
import org.json.JSONObject

enum class ServerKind { JSON, EMBED }

enum class ServerCategory(val title: String, val short: String) {
    STREAMING("Streaming", "stream"),
    FILE_INDEX("File index", "file"),
    ANIMATION("Anime & animation", "anime"),
}

enum class KeyType { TMDB, IMDB, TITLE }

enum class ServerSpeed { VERY_FAST, FAST, NORMAL, SLOW }

data class ServerSpec(
    val id: String,
    val name: String,
    val movieUrl: String,
    val tvUrl: String,
    val referer: String? = null,
    val kind: ServerKind = ServerKind.EMBED,
    val keyType: KeyType = KeyType.TMDB,
    val timeoutSec: Int = 15,
    val headers: Map<String, String> = emptyMap(),
    val note: String = "",
    val defaultEnabled: Boolean = true,
    val animeOnly: Boolean = false,
    val speed: ServerSpeed = ServerSpeed.NORMAL,
    val supports4k: Boolean = false,
    val hasSubtitles: Boolean = false,
    val domainKey: String? = null,
    val category: ServerCategory = ServerCategory.STREAMING,
) {
    val supportsTv: Boolean get() = tvUrl.isNotBlank()
}

object ServerFarm {

    val all: List<ServerSpec> = listOf(
        ServerSpec(
            id = "vidy", name = "Vidy",
            movieUrl = "https://www.vidy.st/movie/{id}?color=DC2626",
            tvUrl = "https://www.vidy.st/tv/{id}/{season}/{episode}?color=DC2626",
            referer = "https://www.vidy.st/", kind = ServerKind.JSON,
            timeoutSec = 35, note = "Multi-audio", speed = ServerSpeed.NORMAL,
            supports4k = true,
        ),
        ServerSpec(
            id = "vidbolt", name = "VidBolt",
            movieUrl = "https://vidbolt.xyz/api/resolve?type=movie&id={id}",
            tvUrl = "https://vidbolt.xyz/api/resolve?type=tv&id={id}&season={season}&episode={episode}",
            referer = "https://vidbolt.xyz/", kind = ServerKind.JSON,
            timeoutSec = 25, note = "API", speed = ServerSpeed.FAST,
        ),
        ServerSpec(
            id = "vidly", name = "Vidly",
            movieUrl = "https://vidly.pro/embed/movie/{id}",
            tvUrl = "https://vidly.pro/embed/tv/{id}/{season}/{episode}",
            referer = "https://vidly.pro/", kind = ServerKind.EMBED,
            timeoutSec = 25, note = "HLS", speed = ServerSpeed.FAST,
        ),
        ServerSpec(
            id = "vidlink", name = "VidLink",
            movieUrl = "https://vidlink.pro/api/b/movie/{id}?multiLang=1",
            tvUrl = "https://vidlink.pro/api/b/tv/{id}/{season}/{episode}?multiLang=1",
            referer = "https://vidlink.pro/", kind = ServerKind.JSON,
            timeoutSec = 25, note = "Multi-lang", speed = ServerSpeed.VERY_FAST,
            supports4k = true, hasSubtitles = true,
        ),
        ServerSpec(
            id = "moviebox", name = "MovieBox",
            movieUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
            tvUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
            referer = "https://movieboxonline.net/", kind = ServerKind.JSON, keyType = KeyType.TITLE,
            timeoutSec = 60, note = "Catalog API", speed = ServerSpeed.FAST,
            supports4k = true, hasSubtitles = true,
        ),
        ServerSpec(
            id = "netnaija", name = "NetNaija",
            movieUrl = "https://netnaija.film/",
            tvUrl = "https://netnaija.film/",
            referer = "https://netnaija.film/", kind = ServerKind.JSON, keyType = KeyType.TITLE,
            timeoutSec = 60, note = "MovieBox alternative", defaultEnabled = true,
            speed = ServerSpeed.FAST, supports4k = true, hasSubtitles = true,
        ),
        ServerSpec(
            id = "netmirror", name = "NetMirror",
            movieUrl = "https://net78.cc/api/embed-tmdb/{id}?type=movie",
            tvUrl = "https://net78.cc/api/embed-tmdb/{id}?type=tv&se={season}&ep={episode}",
            referer = "https://net78.cc/", kind = ServerKind.JSON,
            timeoutSec = 40, note = "API", speed = ServerSpeed.FAST,
            hasSubtitles = true,
        ),
        ServerSpec(
            id = "bingr", name = "Bingr",
            movieUrl = "https://bingr.one/watch/movie/{id}",
            tvUrl = "https://bingr.one/watch/tv/{id}/{season}/{episode}",
            referer = "https://bingr.one/", kind = ServerKind.EMBED,
            timeoutSec = 30, note = "HLS", speed = ServerSpeed.FAST,
        ),
        ServerSpec(
            id = "vidrift", name = "Vidrift",
            movieUrl = "https://embed.vidrift.net/embed/movie/{id}",
            tvUrl = "https://embed.vidrift.net/embed/tv/{id}/{season}/{episode}",
            referer = "https://embed.vidrift.net/", kind = ServerKind.EMBED,
            timeoutSec = 25, note = "Embed", speed = ServerSpeed.FAST,
            hasSubtitles = true,
        ),
        ServerSpec(
            id = "movy", name = "Movy",
            movieUrl = "https://api.wecollege.net/seed?mediaId={id}",
            tvUrl = "https://api.wecollege.net/seed?mediaId={id}",
            referer = "https://www.movy.sx/", kind = ServerKind.JSON,
            timeoutSec = 25, note = "Encrypted API", speed = ServerSpeed.FAST,
            supports4k = true,
        ),
        ServerSpec(
            id = "atlantic", name = "Atlantic",
            movieUrl = "https://zcdn.bluevelvet.space/cdn?tmdb={id}&type=movie",
            tvUrl = "https://zcdn.bluevelvet.space/cdn?tmdb={id}&type=tv&season={season}&episode={episode}",
            referer = "https://atlantic.st/", kind = ServerKind.JSON,
            timeoutSec = 30, note = "Fast CDN", speed = ServerSpeed.VERY_FAST,
        ),
        ServerSpec(
            id = "4khdhub", name = "4KHDHub",
            movieUrl = "{base}/?s={title}",
            tvUrl = "{base}/?s={title}",
            referer = "{base}/", kind = ServerKind.EMBED, keyType = KeyType.TITLE,
            timeoutSec = 45, note = "4K files", defaultEnabled = true,
            speed = ServerSpeed.SLOW, supports4k = true, domainKey = "4khdhub",
            category = ServerCategory.FILE_INDEX,
        ),
        ServerSpec(
            id = "fsl", name = "FSLv1",
            movieUrl = "{base}/?s={title}",
            tvUrl = "{base}/?s={title}",
            referer = "{base}/", kind = ServerKind.EMBED, keyType = KeyType.TITLE,
            timeoutSec = 60, note = "VegaMovies files", defaultEnabled = true,
            speed = ServerSpeed.SLOW, supports4k = true, domainKey = "vegamovies",
            category = ServerCategory.FILE_INDEX,
        ),
        ServerSpec(
            id = "fsl2", name = "FSLv2",
            movieUrl = "{base}/?s={title}",
            tvUrl = "{base}/?s={title}",
            referer = "{base}/", kind = ServerKind.EMBED, keyType = KeyType.TITLE,
            timeoutSec = 60, note = "RogMovies files", defaultEnabled = true,
            speed = ServerSpeed.SLOW, supports4k = true, domainKey = "rogmovies",
            category = ServerCategory.FILE_INDEX,
        ),
        ServerSpec(
            id = "hindmoviez", name = "HindMoviez",
            movieUrl = "{base}/?s={title}",
            tvUrl = "{base}/?s={title}",
            referer = "{base}/", kind = ServerKind.EMBED, keyType = KeyType.TITLE,
            timeoutSec = 60, note = "HindMovie files", defaultEnabled = true,
            speed = ServerSpeed.SLOW, domainKey = "hindmoviez",
            category = ServerCategory.FILE_INDEX,
        ),
        ServerSpec(
            id = "hdhub4u", name = "HDHub4u",
            movieUrl = "{base}/search/{title}/",
            tvUrl = "{base}/search/{title}/",
            referer = "{base}/", kind = ServerKind.EMBED, keyType = KeyType.TITLE,
            timeoutSec = 60, note = "HubCloud files", defaultEnabled = true,
            speed = ServerSpeed.SLOW, supports4k = true, domainKey = "hdhub4u",
            category = ServerCategory.FILE_INDEX,
        ),
        ServerSpec(
            id = "moviesdrive", name = "MoviesDrive",
            movieUrl = "{base}/search/{title}/",
            tvUrl = "{base}/search/{title}/",
            referer = "{base}/", kind = ServerKind.EMBED, keyType = KeyType.TITLE,
            timeoutSec = 60, note = "mdrive files", defaultEnabled = true,
            speed = ServerSpeed.SLOW, supports4k = true, domainKey = "moviesdrive",
            category = ServerCategory.FILE_INDEX,
        ),
        ServerSpec(
            id = "bollyflix", name = "Bollyflix",
            movieUrl = "{base}/search/{title}/page/1/",
            tvUrl = "{base}/search/{title}/page/1/",
            referer = "{base}/", kind = ServerKind.EMBED, keyType = KeyType.TITLE,
            timeoutSec = 60, note = "Bollywood & web series", defaultEnabled = true,
            speed = ServerSpeed.SLOW, supports4k = true, domainKey = "bollyflix",
            category = ServerCategory.FILE_INDEX,
        ),
        ServerSpec(
            id = "multimovies", name = "MultiMovies",
            movieUrl = "{base}/search?q={title}",
            tvUrl = "{base}/search?q={title}",
            referer = "{base}/", kind = ServerKind.EMBED, keyType = KeyType.TITLE,
            timeoutSec = 60, note = "Multi-source embeds", defaultEnabled = true,
            speed = ServerSpeed.SLOW, supports4k = true, domainKey = "multimovies",
            category = ServerCategory.FILE_INDEX,
        ),
        ServerSpec(
            id = "vidout", name = "VidOut",
            movieUrl = "https://vidout.pages.dev/movie/{id}",
            tvUrl = "https://vidout.pages.dev/tv/{id}/{season}/{episode}",
            referer = "https://vidout.pages.dev/", kind = ServerKind.EMBED, keyType = KeyType.TMDB,
            timeoutSec = 30, note = "GitHub HLS index", defaultEnabled = true,
            speed = ServerSpeed.FAST,
        ),
        ServerSpec(
            id = "moviesmod", name = "MoviesMod",
            movieUrl = "{base}/search/{title}/page/1/",
            tvUrl = "{base}/search/{title}/page/1/",
            referer = "{base}/", kind = ServerKind.EMBED, keyType = KeyType.TITLE,
            timeoutSec = 60, note = "Driveseed files", defaultEnabled = true,
            speed = ServerSpeed.SLOW, supports4k = true, domainKey = "moviesmod",
            category = ServerCategory.FILE_INDEX,
        ),
        ServerSpec(
            id = "movies4u", name = "Movies4u",
            movieUrl = "https://new8.movies4u.garden/lookup.php?q={title}",
            tvUrl = "https://new8.movies4u.garden/lookup.php?q={title}",
            referer = "https://new8.movies4u.garden/", kind = ServerKind.JSON, keyType = KeyType.TITLE,
            timeoutSec = 60, note = "File index", defaultEnabled = false,
            speed = ServerSpeed.SLOW, supports4k = true,
            category = ServerCategory.FILE_INDEX,
        ),
        ServerSpec(
            id = "animeworld", name = "AnimeWorld",
            movieUrl = "https://watchanimeworld.one/?s={title}",
            tvUrl = "https://watchanimeworld.one/?s={title}",
            referer = "https://watchanimeworld.one/", kind = ServerKind.EMBED, keyType = KeyType.TITLE,
            timeoutSec = 40, note = "Anime", animeOnly = true,
            speed = ServerSpeed.NORMAL,
            category = ServerCategory.ANIMATION,
        ),
        ServerSpec(
            id = "toonix", name = "Toonix",
            movieUrl = "https://toonix.bond/watch/{title}",
            tvUrl = "https://toonix.bond/show/{title}/{season}/{episode}",
            referer = "https://toonix.bond/", kind = ServerKind.EMBED, keyType = KeyType.TITLE,
            timeoutSec = 40, note = "Cartoons & movies", defaultEnabled = true,
            speed = ServerSpeed.NORMAL, hasSubtitles = true,
            category = ServerCategory.ANIMATION,
        ),
        ServerSpec(
            id = "toonix_anime", name = "Toonix Anime",
            movieUrl = "https://toonix.bond/watch/{title}",
            tvUrl = "https://toonix.bond/show/{title}/{season}/{episode}",
            referer = "https://toonix.bond/", kind = ServerKind.EMBED, keyType = KeyType.TITLE,
            timeoutSec = 40, note = "Anime", animeOnly = true,
            speed = ServerSpeed.NORMAL, hasSubtitles = true,
            category = ServerCategory.ANIMATION,
        ),
        ServerSpec(
            id = "hianime", name = "HiAnime",
            movieUrl = "https://hianime.at/search?keyword={title}",
            tvUrl = "https://hianime.at/search?keyword={title}",
            referer = "https://hianime.at/", kind = ServerKind.EMBED, keyType = KeyType.TITLE,
            timeoutSec = 40, note = "Anime", animeOnly = true,
            speed = ServerSpeed.NORMAL,
            category = ServerCategory.ANIMATION,
        ),
    )

    fun spec(id: String): ServerSpec? = all.firstOrNull { it.id == id }

    fun categoryOf(id: String): ServerCategory =
        spec(id)?.category ?: ServerCategory.STREAMING

    fun inCategory(category: ServerCategory): List<ServerSpec> =
        all.filter { it.category == category }

    fun faviconHost(spec: ServerSpec): String? {
        val raw = spec.referer?.takeIf { it.startsWith("http") }
            ?: spec.movieUrl.takeIf { it.startsWith("http") }
            ?: return null
        val filled = raw.replace("{base}", ProviderDomains.fallback(spec.domainKey))
        return runCatching { java.net.URI(filled).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
    }

    fun defaultEnabledIds(): Set<String> =
        all.filter { it.defaultEnabled }.map { it.id }.toSet()

    /** Providers that became enabled by default in settings schema 2. */
    val enabledInSchema2: Set<String> = setOf(
        "netnaija", "4khdhub", "fsl", "fsl2", "hindmoviez", "hdhub4u",
        "moviesdrive", "toonix", "bollyflix", "multimovies", "moviesmod", "vidout",
    )

    /** User-visible default order: the reliable stream APIs first, then everything else. */
    fun defaultPriorityOrder(): List<String> {
        val head = listOf(
            "moviebox", "vidrift", "vidlink", "vidbolt", "vidly", "netmirror",
            "vidy", "bingr", "atlantic",
        )
        val rank = mapOf(
            ServerSpeed.VERY_FAST to 0,
            ServerSpeed.FAST to 1,
            ServerSpeed.NORMAL to 2,
            ServerSpeed.SLOW to 3,
        )
        val rest = all.filterNot { it.id in head }
            .sortedWith(compareBy({ rank[it.speed] ?: 2 }, { it.name.lowercase() }))
            .map { it.id }
        return (head + rest).distinct().filterNot { it == "movy" } + "movy"
    }

    fun urlFor(spec: ServerSpec, request: LinkRequest): String? {
        val template = if (request.isMovie) spec.movieUrl else spec.tvUrl
        val key = when (spec.keyType) {
            KeyType.TMDB -> request.tmdbId.takeIf { it > 0 }?.toString()
            KeyType.IMDB -> request.imdbId?.takeIf { it.startsWith("tt") }
            KeyType.TITLE -> request.title.takeIf { it.isNotBlank() }
        } ?: return null
        val url = template
            .replace("{base}", ProviderDomains.fallback(spec.domainKey))
            .replace("{id}", URLEncoder.encode(key, "UTF-8"))
            .replace("{imdb}", request.imdbId.orEmpty())
            .replace("{season}", request.season.toString())
            .replace("{episode}", request.episode.toString())
            .replace("{title}", URLEncoder.encode(request.title, "UTF-8").replace("+", "%20"))
            .replace("{year}", request.year?.toString().orEmpty())
        return url.takeIf { it.isNotBlank() }
    }
}

/** One built-in provider. Health is tracked per [spec] id by [Sources]. */
class BuiltinServerSource(private val spec: ServerSpec) : StreamSource {

    override val id: String = spec.id
    override val name: String = spec.name

    override suspend fun resolve(request: LinkRequest): List<Candidate> {
        val started = System.currentTimeMillis()
        val target = ProviderDomains.apply(spec)
        val items = when (target.id) {
            "vidy" -> Vidy.resolve(target, request)
            "vidbolt" -> VidBolt.resolve(target, request)
            "vidly" -> Vidly.resolve(target, request)
            "vidlink" -> VidLink.resolve(target, request)
            "moviebox" -> MovieBox.resolve(target, request)
            "netnaija" -> MovieBox.resolveNetNaija(target, request)
            "netmirror" -> NetMirror.resolve(target, request)
            "bingr" -> Bingr.resolve(target, request)
            "vidrift" -> Vidrift.resolve(target, request)
            "movy" -> Movy.resolve(target, request)
            "atlantic" -> Atlantic.resolve(target, request)
            "fsl", "fsl2" -> Fsl.resolve(target, request)
            "4khdhub", "hindmoviez", "hdhub4u", "moviesdrive", "moviesmod" ->
                FileIndex.resolve(target, request)
            "bollyflix" -> FileIndex.resolve(target, request)
            "multimovies" -> MultiMovies.resolve(target, request)
            "vidout" -> VidOut.resolve(target, request)
            "movies4u" -> Movies4u.resolve(target, request)
            "animeworld" -> AnimeWorld.resolve(target, request)
            "toonix", "toonix_anime" -> Toonix.resolve(target, request)
            "hianime" -> HiAnime.resolve(target, request)
            else -> if (target.kind == ServerKind.JSON) {
                JsonResolver.resolve(target, request)
            } else {
                EmbedPage.resolve(target, request)
            }
        }
        val elapsed = System.currentTimeMillis() - started
        return items.map { it.copy(sourceId = id, sourceName = name, speedMs = elapsed) }
            .distinctBy { it.url }
    }
}

private object EmbedPage {
    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val url = ServerFarm.urlFor(spec, request) ?: return emptyList()
        val headers = headersFor(spec)
        val body = Http.text(url, headers, spec.timeoutSec.toLong()) ?: return emptyList()
        if (HtmlHarvest.isChallenge(body)) {
            Logx.w(spec.id, "bot challenge, skipped")
            return emptyList()
        }
        return EmbedResolver.resolve(
            url = url,
            body = body,
            referer = spec.referer,
            headers = headers,
            sourceId = spec.id,
            sourceName = spec.name,
            originalLanguage = request.originalLanguage,
        )
    }
}

private object JsonResolver {
    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val url = ServerFarm.urlFor(spec, request) ?: return emptyList()
        val headers = headersFor(spec)
        val body = Http.text(url, headers, spec.timeoutSec.toLong()) ?: return emptyList()
        if (HtmlHarvest.isChallenge(body)) return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull()
        val hits = if (root != null) {
            JsonWalk.harvest(root, url)
        } else {
            JsonWalk.mediaUrls(body).map { JsonWalk.Hit(it, 0, "", "", "") }
        }
        return hits.map { hit ->
            Candidate(
                sourceId = spec.id,
                sourceName = spec.name,
                url = hit.url,
                kind = EmbedResolver.kindOf(hit.url),
                referer = spec.referer,
                headers = headers,
                qualityHint = hit.quality,
                audioLabel = Naming.displayLabel(hit.language, request.originalLanguage),
                sizeLabel = hit.size,
                mediaLabel = hit.label,
            )
        }
    }
}

internal object VidBolt {
    private val FALLBACK_SERVERS = listOf("orion", "callisto", "vega", "quantum", "quasar")

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val url = ServerFarm.urlFor(spec, request) ?: return emptyList()
        val origin = spec.referer ?: "https://vidbolt.xyz/"
        val headers = mapOf(
            "Accept" to "application/json",
            "Referer" to origin,
            "User-Agent" to Http.UA,
        )
        val first = Http.text(url, headers, spec.timeoutSec.toLong()) ?: return emptyList()
        val direct = parse(spec, first, request)
        if (direct.isNotEmpty()) return direct

        val deadline = System.currentTimeMillis() + 12_000L
        for (server in FALLBACK_SERVERS) {
            if (System.currentTimeMillis() >= deadline) break
            val body = Http.text("$url&only=$server", headers, 6) ?: continue
            val parsed = parse(spec, body, request)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    internal fun parse(spec: ServerSpec, jsonText: String, request: LinkRequest): List<Candidate> {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val stream = root.optJSONObject("stream") ?: return emptyList()
        val sources = stream.optJSONArray("sources") ?: return emptyList()
        val origin = spec.referer ?: "https://vidbolt.xyz/"
        val out = ArrayList<Candidate>(sources.length())
        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            val rawUrl = src.optString("url").trim()
            if (!rawUrl.startsWith("http", true)) continue
            val url = Manifest.resolveUrl(origin, rawUrl)
            val master = src.optString("master")
                .takeIf { it.contains("#EXTM3U") || it.contains("<MPD", true) }
            val kind = when {
                master?.contains("<MPD", true) == true -> MediaKind.DASH
                src.optString("kind").equals("dash", true) || url.contains(".mpd", true) -> MediaKind.DASH
                src.optString("kind").equals("hls", true) || url.contains(".m3u8", true) -> MediaKind.HLS
                else -> MediaKind.FILE
            }
            val info = Manifest.analyze(master, url, kind)
            val quality = Naming.qualityHeight(src.optString("quality")).takeIf { it > 0 }
                ?: info.height
            val declaredLanguage = src.optString("language")
            val audio = if (declaredLanguage.isNotBlank()) {
                Naming.displayLabel(declaredLanguage, request.originalLanguage)
            } else {
                Naming.displayLabel(info.audio.joinToString(", "), request.originalLanguage)
            }
            val label = src.optString("label").ifBlank { src.optString("quality") }
            out += Candidate(
                sourceId = spec.id,
                sourceName = spec.name,
                url = url,
                kind = kind,
                referer = origin,
                headers = mapOf("Referer" to origin, "User-Agent" to Http.UA),
                qualityHint = quality,
                audioLabel = audio,
                sizeLabel = Naming.sizeLabel(label),
                mediaLabel = label,
                inlineManifest = master,
                subtitles = info.subtitles,
            )
        }
        return out
    }
}

internal object Vidrift {
    private const val ORIGIN = "https://embed.vidrift.net"
    private val PROVIDERS = listOf("titan", "atlas", "lyra", "orion")

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val url = ServerFarm.urlFor(spec, request) ?: return emptyList()
        val headers = mapOf("Accept" to "*/*", "User-Agent" to "")
        val body = Http.text(url, headers, 12) ?: return emptyList()
        if (HtmlHarvest.isChallenge(body)) return emptyList()
        val direct = parse(spec, body, request)
        if (direct.isNotEmpty()) return direct
        val meta = embedMeta(body) ?: return emptyList()
        return resolveApi(spec, meta, request)
    }

    internal fun parse(spec: ServerSpec, html: String, request: LinkRequest): List<Candidate> {
        val meta = embedMeta(html) ?: return emptyList()
        val warm = meta.optJSONObject("warmManifests")
        val out = LinkedHashMap<String, Candidate>()

        fun add(raw: String, name: String, maxHeight: Int) {
            val clean = raw.trim()
            if (clean.isBlank()) return
            if (!clean.contains(".m3u8", true) && !clean.contains(".mp4", true)) return
            val abs = if (clean.startsWith("http", true)) {
                clean
            } else {
                ORIGIN + if (clean.startsWith("/")) clean else "/$clean"
            }
            if (out.containsKey(abs)) return
            val master = warm?.optString(clean)
                ?.takeIf { it.contains("#EXTM3U") || it.contains("<MPD", true) }
                ?: warm?.optString(abs)?.takeIf { it.contains("#EXTM3U") || it.contains("<MPD", true) }
            val kind = when {
                master?.contains("<MPD", true) == true -> MediaKind.DASH
                abs.contains(".mpd", true) -> MediaKind.DASH
                abs.contains(".m3u8", true) -> MediaKind.HLS
                else -> MediaKind.FILE
            }
            val info = Manifest.analyze(master, abs, kind)
            val height = maxHeight.takeIf { it > 0 } ?: info.height
            out[abs] = Candidate(
                sourceId = spec.id,
                sourceName = spec.name,
                url = abs,
                kind = kind,
                referer = "$ORIGIN/",
                headers = mapOf("Referer" to "$ORIGIN/"),
                qualityHint = height,
                audioLabel = audioLabel(name, request.originalLanguage),
                mediaLabel = name,
                inlineManifest = master,
                subtitles = info.subtitles,
            )
        }

        meta.optJSONArray("orionStreams")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(o.optString("url"), o.optString("name"), maxHeight(o))
            }
        }
        if (out.isEmpty()) {
            meta.optString("evionUrl").takeIf { it.contains(".m3u8", true) }?.let {
                add(it, "", 0)
            }
        }
        return out.values.toList()
    }

    private suspend fun resolveApi(
        spec: ServerSpec,
        meta: JSONObject,
        request: LinkRequest,
    ): List<Candidate> {
        val token = meta.optString("playbackToken")
        if (token.isBlank()) return emptyList()
        val parent = meta.optString("parent")
        val path = if (request.isMovie) {
            "movie/${request.tmdbId}"
        } else {
            "tv/${request.tmdbId}/${request.season}/${request.episode}"
        }
        val headers = mapOf("Accept" to "application/json", "User-Agent" to "")
        val out = LinkedHashMap<String, Candidate>()
        for (provider in PROVIDERS) {
            if (out.isNotEmpty()) break
            val url = "$ORIGIN/api/source/$path?token=${encode(token)}" +
                "&provider=$provider&parent=${encode(parent)}"
            val body = Http.text(url, headers, 12) ?: continue
            val root = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val streams = root.optJSONArray("streams") ?: continue
            for (i in 0 until streams.length()) {
                val o = streams.optJSONObject(i) ?: continue
                val raw = o.optString("url").ifBlank { o.optString("proxyUrl") }.trim()
                if (raw.isBlank()) continue
                val abs = if (raw.startsWith("http", true)) {
                    raw
                } else {
                    ORIGIN + if (raw.startsWith("/")) raw else "/$raw"
                }
                val height = maxHeight(o)
                val kind = if (abs.contains(".mp4", true)) MediaKind.FILE else MediaKind.HLS
                out[abs] = Candidate(
                    sourceId = spec.id,
                    sourceName = spec.name,
                    url = abs,
                    kind = kind,
                    referer = "$ORIGIN/",
                    headers = mapOf("Referer" to "$ORIGIN/"),
                    qualityHint = height,
                    audioLabel = audioLabel(o.optString("name"), request.originalLanguage),
                    mediaLabel = o.optString("name"),
                )
            }
        }
        return out.values.toList()
    }

    internal fun embedMeta(html: String): JSONObject? {
        val marker = html.indexOf("var embedMeta")
        if (marker < 0) return null
        val start = html.indexOf('{', marker)
        if (start < 0) return null
        val json = balancedJson(html, start) ?: return null
        return runCatching { JSONObject(json) }.getOrNull()
    }

    private fun maxHeight(obj: JSONObject): Int {
        var height = 0
        val rungs = obj.optJSONArray("rungs") ?: return height
        for (i in 0 until rungs.length()) {
            val rung = rungs.optJSONObject(i) ?: continue
            height = maxOf(height, rung.optInt("height", 0))
        }
        return height
    }

    private fun audioLabel(name: String, originalLanguage: String?): String {
        if (name.contains("original", true)) {
            return Naming.labelOf(originalLanguage).ifBlank { "Original" }
        }
        return Naming.displayLabel(name, originalLanguage)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

internal object VidOut {
    private const val RAW = "https://raw.githubusercontent.com/Watchout2025/api/refs/heads/main/hls"

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        if (request.tmdbId <= 0) return emptyList()
        val headers = mapOf("User-Agent" to Http.UA, "Accept" to "text/plain,application/json,*/*")
        if (request.isMovie) {
            val body = Http.text("$RAW/movie/${request.tmdbId}", headers, 12) ?: return emptyList()
            return listOfNotNull(candidate(body.trim(), "VidOut", request))
        }
        val body = Http.text("$RAW/tv/${request.tmdbId}/S${request.season}.json", headers, 12)
            ?: return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val raw = root.optString(request.episode.toString()).trim()
        return listOfNotNull(candidate(raw, "VidOut S${request.season}E${request.episode}", request))
    }

    private fun candidate(raw: String, label: String, request: LinkRequest): Candidate? {
        if (raw.isBlank() || !raw.startsWith("http", true)) return null
        val kind = when {
            raw.contains(".m3u8", true) -> MediaKind.HLS
            raw.contains(".mpd", true) -> MediaKind.DASH
            raw.contains(".txt", true) &&
                (raw.contains("/hls", true) || raw.contains("/play", true)) -> MediaKind.HLS
            raw.contains(".mp4", true) || raw.contains(".mkv", true) -> MediaKind.FILE
            else -> return null
        }
        return Candidate(
            sourceId = "vidout",
            sourceName = "VidOut",
            url = raw,
            kind = kind,
            referer = "https://vidout.pages.dev/",
            headers = mapOf("Referer" to "https://vidout.pages.dev/", "User-Agent" to Http.UA),
            audioLabel = Naming.labelOf(request.originalLanguage),
            mediaLabel = label,
        )
    }
}

private object Vidly {
    private val M3U8 = Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""", RegexOption.IGNORE_CASE)

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val page = ServerFarm.urlFor(spec, request) ?: return emptyList()
        val headers = headersFor(spec)
        val body = Http.text(page, headers, 12) ?: return emptyList()
        if (HtmlHarvest.isChallenge(body)) return emptyList()
        val urls = M3U8.findAll(HtmlHarvest.unescape(body))
            .map { it.value.trim() }
            .filter { it.startsWith("http") }
            .distinct()
            .take(4)
            .toList()
        val out = urls.mapNotNull { url ->
            val master = Http.text(url, headers, 8)
            val info = Manifest.analyze(master, url, MediaKind.HLS)
            Candidate(
                sourceId = spec.id,
                sourceName = spec.name,
                url = url,
                kind = MediaKind.HLS,
                referer = spec.referer,
                headers = headers,
                qualityHint = info.height,
                audioLabel = Naming.displayLabel(info.audio.joinToString(", "), request.originalLanguage),
                inlineManifest = master,
                subtitles = info.subtitles,
            )
        }
        return out.ifEmpty {
            EmbedResolver.resolve(
                page, body, spec.referer, headers, spec.id, spec.name,
                request.originalLanguage,
            )
        }
    }
}

private object Bingr {
    private val MASTER = Regex(
        """https?://[^"'\s<>\\]+master\.m3u8[^"'\s<>\\]*""",
        RegexOption.IGNORE_CASE,
    )

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val page = ServerFarm.urlFor(spec, request) ?: return emptyList()
        val headers = headersFor(spec)
        val body = Http.text(page, headers, 12) ?: return emptyList()
        if (HtmlHarvest.isChallenge(body)) return emptyList()
        val urls = MASTER.findAll(HtmlHarvest.unescape(body))
            .map { it.value.trim() }
            .filter { it.startsWith("http") }
            .distinct()
            .take(3)
            .toList()
        val out = urls.mapNotNull { url ->
            val master = Http.text(url, headers, 8)
            val info = Manifest.analyze(master, url, MediaKind.HLS)
            Candidate(
                sourceId = spec.id,
                sourceName = spec.name,
                url = url,
                kind = MediaKind.HLS,
                referer = spec.referer,
                headers = headers,
                qualityHint = info.height,
                audioLabel = Naming.displayLabel(info.audio.joinToString(", "), request.originalLanguage),
                inlineManifest = master,
                subtitles = info.subtitles,
            )
        }
        return out.ifEmpty {
            EmbedResolver.resolve(
                page, body, spec.referer, headers, spec.id, spec.name,
                request.originalLanguage,
            )
        }
    }
}

private object NetMirror {
    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val url = ServerFarm.urlFor(spec, request) ?: return emptyList()
        val headers = headersFor(spec)
        val body = Http.text(url, headers, spec.timeoutSec.toLong()) ?: return emptyList()
        if (HtmlHarvest.isChallenge(body)) return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (!root.optBoolean("ok", true)) return emptyList()
        val playHeaders = mapOf(
            "Referer" to "https://videodownloader.site/",
            "User-Agent" to Http.UA,
        )
        val out = LinkedHashMap<String, Candidate>()

        fun add(streamUrl: String, height: Int, size: Long) {
            if (!streamUrl.startsWith("http", true) || out.containsKey(streamUrl)) return
            out[streamUrl] = Candidate(
                sourceId = spec.id,
                sourceName = spec.name,
                url = streamUrl,
                kind = MediaKind.FILE,
                referer = playHeaders["Referer"],
                headers = playHeaders,
                qualityHint = height,
                audioLabel = if (streamUrl.contains("tran-audio", true)) "Multi" else "",
                sizeLabel = bytesLabel(size),
            )
        }

        root.optJSONArray("streams")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val height = o.optInt("resolution", 0).takeIf { it > 0 }
                    ?: Naming.qualityHeight(o.optString("resolution"))
                add(o.optString("url"), height, o.optLong("size", 0))
            }
        }
        root.optString("mp4").takeIf { it.startsWith("http") }?.let {
            val height = root.optInt("resolution", 0).takeIf { it > 0 }
                ?: Naming.qualityHeight(root.optString("resolution"))
            add(it, height, 0)
        }
        return out.values.toList()
    }
}

private object Atlantic {
    private val PROVIDERS = listOf("vidbox", "q62", "amnesty", "shegu", "moscow", "mbox")

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        val headers = mapOf("Referer" to "https://atlantic.st/", "User-Agent" to Http.UA)
        val id = request.tmdbId
        for (provider in PROVIDERS) {
            val api = if (request.isMovie) {
                "https://stream.hls.lol/$provider?tmdbId=$id&type=movie"
            } else {
                "https://stream.hls.lol/$provider?tmdbId=$id&type=tv" +
                    "&seasonId=${request.season}&episodeId=${request.episode}"
            }
            val body = Http.text(api, headers, 8) ?: continue
            val root = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val streamUrl = firstStream(root) ?: continue
            val master = Http.text(streamUrl, headers, 8)
            val info = Manifest.analyze(master, streamUrl, MediaKind.HLS)
            val subtitles = ArrayList<SubtitleTrack>()
            root.optJSONArray("subtitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val file = o.optString("file")
                    if (file.startsWith("http")) {
                        subtitles += SubtitleTrack(o.optString("label").ifBlank { o.optString("language") }, file)
                    }
                }
            }
            return listOf(
                Candidate(
                    sourceId = spec.id,
                    sourceName = spec.name,
                    url = streamUrl,
                    kind = MediaKind.HLS,
                    referer = "https://atlantic.st/",
                    headers = headers,
                    qualityHint = info.height,
                    audioLabel = Naming.displayLabel(info.audio.joinToString(", "), request.originalLanguage),
                    inlineManifest = master,
                    subtitles = subtitles.ifEmpty { info.subtitles },
                ),
            )
        }
        val fallback = JsonResolver.resolve(spec, request)
        if (fallback.isNotEmpty()) return fallback
        return EmbedPage.resolve(spec, request)
    }

    private fun firstStream(root: JSONObject): String? {
        val sources = root.optJSONObject("sources")
        if (sources != null) {
            val names = sources.names() ?: return null
            var first: String? = null
            for (i in 0 until names.length()) {
                val o = sources.optJSONObject(names.optString(i)) ?: continue
                val url = o.optString("url").takeIf { it.startsWith("http") } ?: continue
                if (o.optString("type").equals("hls", true)) return url
                if (first == null) first = url
            }
            if (first != null) return first
        }
        return root.optString("url").takeIf { it.startsWith("http") }
    }
}

private fun headersFor(spec: ServerSpec): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    headers["User-Agent"] = Http.UA
    spec.referer?.let { headers["Referer"] = it }
    headers.putAll(spec.headers)
    return headers
}

private fun bytesLabel(bytes: Long): String {
    if (bytes <= 0) return ""
    val gb = bytes / 1_073_741_824.0
    return if (gb >= 1.0) {
        String.format(Locale.US, "%.1f GB", gb)
    } else {
        String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
    }
}

private fun balancedJson(text: String, start: Int): String? {
    if (start >= text.length || text[start] != '{') return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        if (escaped) {
            escaped = false
            continue
        }
        if (inString) {
            when (c) {
                '\\' -> escaped = true
                '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
    }
    return null
}

internal fun parseVidboltJson(json: String, request: LinkRequest): List<Candidate> {
    val spec = ServerFarm.spec("vidbolt") ?: return emptyList()
    return VidBolt.parse(spec, json, request)
}

internal fun parseVidriftHtml(html: String, request: LinkRequest): List<Candidate> {
    val spec = ServerFarm.spec("vidrift") ?: return emptyList()
    return Vidrift.parse(spec, html, request)
}
