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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
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
        if (!Tmdb.hasKey()) return null
        val items = Tmdb.row(request.data, page) ?: return null
        val cards = items.mapNotNull { it.toCard() }
        if (cards.isEmpty()) return null
        return newHomePageResponse(request, cards)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return null
        val tmdb = if (Tmdb.hasKey()) Tmdb.search(query) else null
        if (!tmdb.isNullOrEmpty()) {
            return tmdb.mapNotNull { it.toCard() }.takeIf { it.isNotEmpty() }
        }
        val imdb = Omdb.search(query)
        return imdb.mapNotNull { it.toCard() }.takeIf { it.isNotEmpty() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val ref = Refs.parse(url) ?: return null
        val tmdbDetail = ref.tmdbId?.takeIf { Tmdb.hasKey() }?.let { Tmdb.detail(it, ref.type) }
        if (tmdbDetail != null) return tmdbLoad(url, ref, tmdbDetail)

        val imdbId = ref.imdbId ?: return null
        val omdb = Omdb.detail(imdbId) ?: return null
        return omdbLoad(url, omdb)
    }

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
        )

        val floor = Settings.qualityFloor()
        val started = System.currentTimeMillis()
        val items = LinkCache.get(request) ?: run {
            val raw = Sources.collect(request, Settings.sourceConfig())
            val enriched = enrich(raw, floor)
            LinkCache.put(request, enriched)
            enriched
        }

        if (items.isEmpty()) {
            Logx.w("CrunchyMe", "no playable streams for $title")
            return false
        }

        var emitted = 0
        for (candidate in items) {
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
            emitted++

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

        Logx.d(
            "CrunchyMe",
            "emitted $emitted/${items.size} links in ${System.currentTimeMillis() - started}ms",
        )
        return emitted > 0
    }

    private suspend fun enrich(candidates: List<Candidate>, floor: Int): List<Candidate> =
        coroutineScope {
            val gate = Semaphore(6)
            candidates.map { candidate ->
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
