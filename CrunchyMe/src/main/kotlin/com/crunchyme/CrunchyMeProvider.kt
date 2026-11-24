package com.crunchyme

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

class CrunchyMeProvider : MainAPI() {

    override var mainUrl = "https://www.themoviedb.org"
    override var name = "CrunchyMe"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage: List<MainPageData> get() = listOf(
        MainPageData(name = "Trending Movies", data = "trending_movie"),
        MainPageData(name = "Trending Series", data = "trending_tv"),
        MainPageData(name = "Popular Movies", data = "popular_movie"),
        MainPageData(name = "Popular Series", data = "popular_tv"),
        MainPageData(name = "Top Rated Movies", data = "top_movie"),
        MainPageData(name = "Top Rated Series", data = "top_tv"),
        MainPageData(name = "Upcoming Movies", data = "upcoming_movie"),
        MainPageData(name = "Airing Today", data = "airing_tv"),
        MainPageData(name = "Hindi Cinema", data = "hindi"),
        MainPageData(name = "Tamil Cinema", data = "tamil"),
        MainPageData(name = "Telugu Cinema", data = "telugu"),
        MainPageData(name = "Malayalam Cinema", data = "malayalam"),
        MainPageData(name = "Kannada Cinema", data = "kannada"),
        MainPageData(name = "Bengali Cinema", data = "bengali"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (catalog() == CatalogProvider.IMDB) return imdbHome(request)
        if (!Tmdb.hasKey()) return null
        val items = Tmdb.row(request.data, page) ?: return null
        val cards = items.mapNotNull { it.toCard() }
        if (cards.isEmpty()) return null
        return newHomePageResponse(request, cards)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return null
        if (catalog() == CatalogProvider.IMDB) {
            return Omdb.search(query).mapNotNull { it.toCard() }.takeIf { it.isNotEmpty() }
        }
        val tmdb = if (Tmdb.hasKey()) Tmdb.search(query) else null
        if (!tmdb.isNullOrEmpty()) {
            return tmdb.mapNotNull { it.toCard() }.takeIf { it.isNotEmpty() }
        }
        return Omdb.search(query).mapNotNull { it.toCard() }.takeIf { it.isNotEmpty() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val ref = Refs.parse(url) ?: return null
        if (catalog() == CatalogProvider.TMDB) {
            val tmdbDetail = ref.tmdbId?.takeIf { Tmdb.hasKey() }?.let { Tmdb.detail(it, ref.type) }
            if (tmdbDetail != null) return tmdbLoad(url, ref, tmdbDetail)
        }
        val imdbId = ref.imdbId ?: return null
        val omdb = Omdb.detail(imdbId) ?: return null
        return omdbLoad(url, omdb)
    }

    /** TMDB is the default. IMDb uses OMDb and falls back automatically when no TMDB key is set. */
    private fun catalog(): CatalogProvider =
        if (Settings.catalogProvider() == CatalogProvider.IMDB || !Tmdb.hasKey()) {
            CatalogProvider.IMDB
        } else {
            CatalogProvider.TMDB
        }

    private suspend fun imdbHome(request: MainPageRequest): HomePageResponse? {
        val query = IMDB_HOME[request.data] ?: return null
        val cards = Omdb.search(query).mapNotNull { it.toCard() }
        if (cards.isEmpty()) return null
        return newHomePageResponse(request, cards)
    }

    private val IMDB_HOME = mapOf(
        "trending_movie" to "Avengers",
        "trending_tv" to "Stranger Things",
        "popular_movie" to "Batman",
        "popular_tv" to "Wednesday",
        "top_movie" to "Godfather",
        "top_tv" to "Breaking Bad",
        "upcoming_movie" to "2026",
        "airing_tv" to "The",
        "hindi" to "Hindi",
        "tamil" to "Tamil",
        "telugu" to "Telugu",
        "malayalam" to "Malayalam",
        "kannada" to "Kannada",
        "bengali" to "Bengali",
    )

    private suspend fun tmdbLoad(
        url: String,
        ref: TitleRef,
        detail: Tmdb.Detail,
    ): LoadResponse? {
        val id = detail.id
        val dataUrl = if (ref.type == "movie") {
            Refs.movie(id, detail.imdbId)
        } else {
            Refs.tv(id, detail.imdbId)
        }

        if (ref.type == "movie") {
            return newMovieLoadResponse(detail.title, url, TvType.Movie, dataUrl) {
                posterUrl = detail.poster
                backgroundPosterUrl = detail.backdrop
                year = detail.year
                plot = detail.overview
                tags = detail.genres
                actors = detail.cast.map { ActorData(Actor(it.name, it.photo), roleString = it.character) }
                duration = detail.runtime
                detail.imdbId?.let { addImdbId(it) }
                detail.score?.let { addScore(it.toString(), 10) }
            }
        }

        val episodes = Tmdb.episodes(id)
        if (episodes.isEmpty()) return null
        val episodeList = episodes.map { ep ->
            newEpisode(Refs.episode(id, detail.imdbId, ep.season, ep.number)) {
                name = ep.name ?: "Episode ${ep.number}"
                season = ep.season
                episode = ep.number
                description = ep.overview
                posterUrl = ep.still
                ep.rating?.let { score = Score.from10(it) }
                ep.runtime?.let { runTime = it }
                ep.airDate?.let { date -> runCatching { addDate(date) } }
            }
        }

        return newTvSeriesLoadResponse(detail.title, url, TvType.TvSeries, episodeList) {
            posterUrl = detail.poster
            backgroundPosterUrl = detail.backdrop
            year = detail.year
            plot = detail.overview
            tags = detail.genres
            actors = detail.cast.map { ActorData(Actor(it.name, it.photo), roleString = it.character) }
            duration = detail.runtime
            detail.imdbId?.let { addImdbId(it) }
            detail.score?.let { addScore(it.toString(), 10) }
        }
    }

    private suspend fun omdbLoad(url: String, detail: Omdb.Detail): LoadResponse? {
        val dataUrl = Refs.imdb(detail.imdbId, detail.type)
        val cast = detail.actors.map { ActorData(Actor(it, null)) }
        if (detail.type == "movie") {
            return newMovieLoadResponse(detail.title, url, TvType.Movie, dataUrl) {
                posterUrl = detail.poster
                backgroundPosterUrl = detail.poster
                year = detail.year
                plot = detail.overview
                tags = detail.genres
                actors = cast
                duration = detail.runtime
                addImdbId(detail.imdbId)
                detail.score?.takeIf { it > 0 }?.let { addScore(it.toString(), 10) }
            }
        }

        val episodes = Omdb.episodes(detail.imdbId)
        if (episodes.isEmpty()) return null
        val episodeList = episodes.map { ep ->
            newEpisode(Refs.imdb(detail.imdbId, "tv", ep.season, ep.number)) {
                name = ep.name ?: "Episode ${ep.number}"
                season = ep.season
                episode = ep.number
                ep.rating?.takeIf { it > 0 }?.let { score = Score.from10(it) }
                ep.airDate?.let { date -> runCatching { addDate(date) } }
            }
        }
        return newTvSeriesLoadResponse(detail.title, url, TvType.TvSeries, episodeList) {
            posterUrl = detail.poster
            backgroundPosterUrl = detail.poster
            year = detail.year
            plot = detail.overview
            tags = detail.genres
            actors = cast
            duration = detail.runtime
            addImdbId(detail.imdbId)
            detail.score?.takeIf { it > 0 }?.let { addScore(it.toString(), 10) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ref = Refs.parse(data) ?: return false
        var tmdbId = ref.tmdbId ?: 0
        if (tmdbId <= 0 && ref.imdbId != null && Tmdb.hasKey()) {
            tmdbId = Tmdb.findByImdb(ref.imdbId)?.id ?: 0
        }
        val tmdbDetail = tmdbId.takeIf { it > 0 && Tmdb.hasKey() }?.let { Tmdb.detail(it, ref.type) }
        val omdbDetail = if (tmdbDetail == null && ref.imdbId != null) Omdb.detail(ref.imdbId) else null
        val mediaType = omdbDetail?.type ?: ref.type
        val imdbId = tmdbDetail?.imdbId ?: ref.imdbId
        if (mediaType == "tv" && (ref.season <= 0 || ref.episode <= 0)) {
            Logx.w("CrunchyMe", "tv request without season/episode")
            return false
        }
        val title = tmdbDetail?.title ?: omdbDetail?.title ?: run {
            Logx.w("CrunchyMe", "no metadata for $data")
            return false
        }

        val request = LinkRequest(
            tmdbId = tmdbId,
            imdbId = imdbId,
            type = mediaType,
            season = if (mediaType == "tv") ref.season else -1,
            episode = if (mediaType == "tv") ref.episode else -1,
            title = title,
            year = tmdbDetail?.year ?: omdbDetail?.year,
            originalLanguage = tmdbDetail?.originalLanguage,
            isAnime = (tmdbDetail?.genres ?: omdbDetail?.genres).orEmpty()
                .any { it.equals("Animation", true) || it.equals("Anime", true) },
        )

        val floor = Settings.qualityFloor()
        val started = System.currentTimeMillis()

        suspend fun emit(candidate: Candidate) {
            val headers = LinkedHashMap(candidate.headers)
            if (headers.keys.none { it.equals("Referer", true) } && !candidate.referer.isNullOrBlank()) {
                headers["Referer"] = candidate.referer.orEmpty()
            }
            val type = when (candidate.kind) {
                MediaKind.HLS -> ExtractorLinkType.M3U8
                MediaKind.DASH -> ExtractorLinkType.DASH
                MediaKind.FILE -> ExtractorLinkType.VIDEO
            }
            val label = Naming.displayName(
                serverName = candidate.sourceName,
                audioLabel = candidate.audioLabel,
                sizeLabel = candidate.sizeLabel,
                mediaLabel = candidate.mediaLabel,
                originalLanguage = request.originalLanguage,
            )
            val sourceLabel = Naming.priorityName(
                serverName = candidate.sourceName,
                audioLabel = candidate.audioLabel,
                mediaLabel = candidate.mediaLabel,
                originalLanguage = request.originalLanguage,
            )
            callback(
                newExtractorLink(
                    source = sourceLabel,
                    name = label,
                    url = candidate.url,
                    type = type,
                ) {
                    referer = candidate.referer ?: ""
                    quality = candidate.qualityHint.coerceAtLeast(0)
                    this.headers = headers
                },
            )
            candidate.subtitles.distinctBy { it.url }.forEach { subtitle ->
                if (subtitle.url.isNotBlank()) {
                    subtitleCallback(
                        newSubtitleFile(
                            subtitle.language.ifBlank { "Unknown" },
                            subtitle.url,
                        ),
                    )
                }
            }
        }

        val cached = LinkCache.get(request)
        if (cached != null) {
            cached.forEach { emit(it) }
            Logx.d("CrunchyMe", "emitted ${cached.size} cached links for $title")
            return cached.isNotEmpty()
        }

        val config = Settings.sourceConfig()
        val seen = ConcurrentHashMap.newKeySet<String>()
        val collected = Collections.synchronizedList(mutableListOf<Candidate>())
        val jobs = Collections.synchronizedList(mutableListOf<Job>())
        val emitGate = Mutex()
        val emitted = AtomicInteger(0)
        val probeGate = Semaphore((config.parallelism / 2).coerceIn(2, 8))

        coroutineScope {
            Sources.stream(request, config) { _, raw ->
                jobs += launch {
                    val enriched = enrich(raw, floor, probeGate)
                        .ifEmpty { enrich(raw, 0, probeGate) }
                    if (enriched.isEmpty()) return@launch
                    collected.addAll(enriched)
                    emitGate.withLock {
                        for (candidate in enriched) {
                            if (!seen.add(candidate.url)) continue
                            emit(candidate)
                            emitted.incrementAndGet()
                        }
                    }
                }
            }
            jobs.toList().joinAll()
        }

        val items = collected.toList().distinctBy { it.url }.sortedWith(
            compareByDescending<Candidate> { it.qualityHint }.thenBy { it.sourceName },
        )
        LinkCache.put(request, items)
        Logx.d(
            "CrunchyMe",
            "emitted ${emitted.get()}/${items.size} links in ${System.currentTimeMillis() - started}ms",
        )
        return emitted.get() > 0
    }

    private suspend fun enrich(
        candidates: List<Candidate>,
        floor: Int,
        gate: Semaphore = Semaphore(6),
    ): List<Candidate> =
        coroutineScope {
            candidates.distinctBy { it.url }.take(32).map { candidate ->
                async {
                    gate.withPermit {
                        runCatching { MediaProbe.enrich(candidate, floor) }.getOrElse { candidate }
                    }
                }
            }.awaitAll()
                .filterNotNull()
                .distinctBy { it.url }
                .sortedWith(
                    compareByDescending<Candidate> { it.qualityHint }
                        .thenBy { it.sourceName },
                )
        }

    private fun Omdb.Hit.toCard(): SearchResponse {
        val url = Refs.imdb(imdbId, type)
        return if (type == "movie") {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = this@toCard.poster
                year = this@toCard.year
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = this@toCard.poster
                year = this@toCard.year
            }
        }
    }

    private fun Tmdb.Item.toCard(): SearchResponse? {
        if (title.isBlank()) return null
        val url = if (type == "movie") Refs.movie(id) else Refs.tv(id)
        return if (type == "movie") {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = this@toCard.poster
                year = this@toCard.year
                score = this@toCard.score?.let { Score.from10(it) }
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = this@toCard.poster
                year = this@toCard.year
                score = this@toCard.score?.let { Score.from10(it) }
            }
        }
    }
}
