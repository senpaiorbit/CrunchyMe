package com.crunchyme

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import kotlinx.coroutines.withTimeoutOrNull

class CrunchyMeProvider : MainAPI() {

    override var mainUrl = "https://www.themoviedb.org"
    override var name = "CrunchyMe"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage: List<MainPageData> get() = listOf(
        MainPageData(name = "Trending Movies", data = "trending_movie", horizontalImages = true),
        MainPageData(name = "Trending Series", data = "trending_tv", horizontalImages = true),
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
        val metas = Btttr.enrich(items)
        val logos = if (request.horizontalImages) {
            coroutineScope {
                items.take(6).map { item ->
                    async {
                        metas[item.id]?.logo ?: withTimeoutOrNull(2_500) {
                            runCatching { Tmdb.detail(item.id, item.type)?.logo }.getOrNull()
                        }
                    }
                }.awaitAll()
            }
        } else {
            emptyList()
        }
        val cards = items.mapIndexedNotNull { index, item ->
            item.toCard(
                logo = logos.getOrNull(index),
                poster = metas[item.id]?.poster,
            )
        }
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
            val metas = Btttr.enrich(tmdb)
            return tmdb.mapNotNull { it.toCard(poster = metas[it.id]?.poster) }
                .takeIf { it.isNotEmpty() }
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
        val btttr = withTimeoutOrNull(4_000) {
            runCatching { Btttr.meta(id, detail.type) }.getOrNull()
        }
        val tvdb = if (detail.type == "tv") {
            withTimeoutOrNull(4_000) {
                runCatching { Tvdb.series(id, detail.imdbId, detail.tvdbId) }.getOrNull()
            }
        } else {
            null
        }
        val title = Naming.preferEnglish(detail.title, tvdb?.name) ?: detail.title
        val poster = btttr?.poster ?: tvdb?.image ?: detail.poster
        val backdrop = btttr?.background ?: detail.backdrop
        val logo = btttr?.logo ?: detail.logo
        val displayPlot = Naming.preferEnglish(detail.overview, tvdb?.overview)
        val displayYear = tvdb?.year ?: detail.year
        val displayRuntime = tvdb?.runtime ?: detail.runtime
        val trailers = trailerVariants(detail.trailer, btttr?.trailers)

        if (ref.type == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                year = displayYear
                plot = displayPlot
                tags = detail.genres
                actors = detail.cast.map { ActorData(Actor(it.name, it.photo), roleString = it.character) }
                duration = displayRuntime
                detail.imdbId?.let { addImdbId(it) }
                addTMDbId(id.toString())
                detail.score?.let { addScore(it.toString(), 10) }
                logoUrl = logo
                addTrailer(trailers, referer = null)
            }
        }

        val tmdbEpisodes = Tmdb.episodes(id)
        val tvdbEpisodes = withTimeoutOrNull(8_000) {
            runCatching {
                Tvdb.episodes(id, detail.imdbId, detail.tvdbId, detail.seasonNumbers)
            }.getOrNull()
        }.orEmpty()
        val episodes = mergeEpisodes(tmdbEpisodes, tvdbEpisodes)
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

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            year = displayYear
            plot = displayPlot
            tags = detail.genres
            actors = detail.cast.map { ActorData(Actor(it.name, it.photo), roleString = it.character) }
            duration = displayRuntime
            detail.imdbId?.let { addImdbId(it) }
            addTMDbId(id.toString())
            detail.score?.let { addScore(it.toString(), 10) }
            logoUrl = logo
            addTrailer(trailers, referer = null)
        }
    }

    private data class EpisodeRow(
        val season: Int,
        val number: Int,
        val name: String?,
        val overview: String?,
        val still: String?,
        val airDate: String?,
        val runtime: Int?,
        val rating: Double?,
    )

    private fun mergeEpisodes(
        tmdb: List<Tmdb.Ep>,
        tvdb: List<Tvdb.Ep>,
    ): List<EpisodeRow> {
        val tvdbMap = tvdb.associateBy { it.season to it.number }
        val rows = LinkedHashMap<Pair<Int, Int>, EpisodeRow>()
        for (ep in tmdb) {
            val season = ep.season.coerceAtLeast(1)
            val number = ep.number.coerceAtLeast(1)
            if (season <= 0 || number <= 0) continue
            val extra = tvdbMap[season to number]
            rows[season to number] = EpisodeRow(
                season = season,
                number = number,
                name = Naming.preferEnglish(ep.name, extra?.name),
                overview = Naming.preferEnglish(ep.overview, extra?.overview),
                still = extra?.still ?: ep.still,
                airDate = extra?.airDate ?: ep.airDate,
                runtime = extra?.runtime ?: ep.runtime,
                rating = ep.rating,
            )
        }
        for (ep in tvdb) {
            if (ep.season <= 0 || ep.number <= 0) continue
            rows.putIfAbsent(
                ep.season to ep.number,
                EpisodeRow(
                    season = ep.season,
                    number = ep.number,
                    name = ep.name,
                    overview = ep.overview,
                    still = ep.still,
                    airDate = ep.airDate,
                    runtime = ep.runtime,
                    rating = null,
                ),
            )
        }
        return rows.values.sortedWith(compareBy({ it.season }, { it.number }))
    }

    private fun trailerVariants(first: String?, rest: List<String>?): List<String> {
        val raw = buildList {
            first?.let { add(it) }
            rest?.let { addAll(it) }
        }
        val ids = raw.mapNotNull { url ->
            Regex("""(?:[?&]v=|youtu\.be/|/embed/|/shorts/)([\w-]{11})""")
                .find(url)?.groupValues?.get(1)
        }.distinct().take(3)
        val watch = ids.map { "https://www.youtube.com/watch?v=$it" }
        val fallback = ids.take(1).flatMap { id ->
            listOf(
                "https://youtu.be/$id",
                "https://www.youtube-nocookie.com/embed/$id",
                "https://m.youtube.com/watch?v=$id",
            )
        }
        return (watch + fallback).distinct()
    }

    private suspend fun omdbLoad(url: String, detail: Omdb.Detail): LoadResponse? {
        val dataUrl = Refs.imdb(detail.imdbId, detail.type)
        val cast = detail.actors.map { ActorData(Actor(it, null)) }
        val btttr = withTimeoutOrNull(4_000) {
            runCatching { Btttr.metaByImdb(detail.imdbId, detail.type) }.getOrNull()
        }
        val tvdb = if (detail.type == "tv") {
            withTimeoutOrNull(4_000) {
                runCatching { Tvdb.series(0, detail.imdbId, null) }.getOrNull()
            }
        } else {
            null
        }
        val poster = btttr?.poster ?: Btttr.posterByImdb(detail.imdbId, detail.type)
            ?: tvdb?.image ?: detail.poster
        val title = Naming.preferEnglish(detail.title, tvdb?.name) ?: detail.title
        val trailers = trailerVariants(null, btttr?.trailers)
        if (detail.type == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
                posterUrl = poster
                backgroundPosterUrl = btttr?.background ?: detail.poster
                year = tvdb?.year ?: detail.year
                plot = Naming.preferEnglish(detail.overview, tvdb?.overview)
                tags = detail.genres
                actors = cast
                duration = tvdb?.runtime ?: detail.runtime
                addImdbId(detail.imdbId)
                detail.score?.takeIf { it > 0 }?.let { addScore(it.toString(), 10) }
                logoUrl = btttr?.logo
                addTrailer(trailers, referer = null)
            }
        }

        val omdbEpisodes = Omdb.episodes(detail.imdbId)
        val tvdbEpisodes = withTimeoutOrNull(8_000) {
            runCatching {
                Tvdb.episodes(
                    tmdbId = 0,
                    imdbId = detail.imdbId,
                    tvdbId = null,
                    seasons = (1..detail.totalSeasons.coerceAtMost(40)).toList(),
                )
            }.getOrNull()
        }.orEmpty()
        val episodes = mergeEpisodes(
            omdbEpisodes.map {
                Tmdb.Ep(
                    season = it.season,
                    number = it.number,
                    name = it.name,
                    overview = null,
                    still = null,
                    airDate = it.airDate,
                    rating = it.rating,
                    runtime = null,
                )
            },
            tvdbEpisodes,
        )
        if (episodes.isEmpty()) return null
        val episodeList = episodes.map { ep ->
            newEpisode(Refs.imdb(detail.imdbId, "tv", ep.season, ep.number)) {
                name = ep.name ?: "Episode ${ep.number}"
                season = ep.season
                episode = ep.number
                description = ep.overview
                posterUrl = ep.still
                ep.rating?.takeIf { it > 0 }?.let { score = Score.from10(it) }
                ep.runtime?.let { runTime = it }
                ep.airDate?.let { date -> runCatching { addDate(date) } }
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            posterUrl = poster
            backgroundPosterUrl = btttr?.background ?: detail.poster
            year = tvdb?.year ?: detail.year
            plot = Naming.preferEnglish(detail.overview, tvdb?.overview)
            tags = detail.genres
            actors = cast
            duration = tvdb?.runtime ?: detail.runtime
            addImdbId(detail.imdbId)
            detail.score?.takeIf { it > 0 }?.let { addScore(it.toString(), 10) }
            logoUrl = btttr?.logo
            addTrailer(trailers, referer = null)
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
            isAnime = (
                tmdbDetail?.genres?.takeIf { it.isNotEmpty() } ?: omdbDetail?.genres.orEmpty()
                ).any { it.equals("Animation", true) || it.equals("Anime", true) },
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
            val probed = candidates.distinctBy { it.url }.take(32).map { candidate ->
                async {
                    gate.withPermit {
                        runCatching { MediaProbe.enrich(candidate, 0) }.getOrElse { candidate }
                    }
                }
            }.awaitAll()
                .filterNotNull()
                .distinctBy { it.url }
            val filtered = probed.filter { floor <= 0 || it.qualityHint <= 0 || it.qualityHint >= floor }
            (filtered.ifEmpty { probed }).sortedWith(
                compareByDescending<Candidate> { it.qualityHint }
                    .thenBy { it.sourceName },
            )
        }

    private fun Omdb.Hit.toCard(): SearchResponse {
        val url = Refs.imdb(imdbId, type)
        val poster = Btttr.posterByImdb(imdbId, type) ?: this@toCard.poster
        return if (type == "movie") {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
                year = this@toCard.year
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
                year = this@toCard.year
            }
        }
    }

    private fun Tmdb.Item.toCard(logo: String? = null, poster: String? = null): SearchResponse? {
        if (title.isBlank()) return null
        val url = if (type == "movie") Refs.movie(id) else Refs.tv(id)
        val image = logo ?: poster ?: this@toCard.poster
        return if (type == "movie") {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = image
                year = this@toCard.year
                score = this@toCard.score?.let { Score.from10(it) }
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = image
                year = this@toCard.year
                score = this@toCard.score?.let { Score.from10(it) }
            }
        }
    }
}
