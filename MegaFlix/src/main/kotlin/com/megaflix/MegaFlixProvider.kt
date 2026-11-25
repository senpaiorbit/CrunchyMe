package com.megaflix

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TorrentSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlin.coroutines.cancellation.CancellationException

class MegaFlixProvider : MainAPI() {
    override var name = "MegaFlix"
    override var mainUrl = "https://megaflix.local"
    override var lang = "en"
    override var canBeOverridden = false

    override val hasMainPage: Boolean get() = Settings.homeRows() > 0
    override val hasQuickSearch = true
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L
    override val loadLinksTimeoutMs: Long? = 180_000L

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.AnimeMovie,
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Anime,
        TvType.OVA,
        TvType.Torrent,
        TvType.Documentary,
        TvType.AsianDrama,
        TvType.Live,
        TvType.NSFW,
        TvType.Others,
    )

    override val mainPage: List<MainPageData>
        get() {
            val providers = ProviderRegistry.homeProviders(this, Settings.homeRows())
            if (providers.isEmpty()) {
                return listOf(MainPageData("MegaFlix", "none", false))
            }
            val rows = providers.flatMap { provider ->
                runCatching { provider.mainPage }.getOrNull().orEmpty()
                    .filter { it.data.isNotBlank() && it.data != "NONE" }
                    .take(2)
                    .map { row ->
                        MainPageData(
                            name = provider.name + " · " + row.name.ifBlank { "Latest" },
                            data = Refs.home(
                                provider = provider.name,
                                mainUrl = provider.mainUrl,
                                rowName = row.name,
                                rowData = row.data,
                                horizontal = row.horizontalImages,
                            ),
                            horizontalImages = row.horizontalImages,
                        )
                    }
            }.take(16)
            return rows.ifEmpty { listOf(MainPageData("MegaFlix", "none", false)) }
        }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? {
        val ref = Refs.parse(request.data) as? MegaRef.Home ?: return null
        val provider = ProviderRegistry.resolve(
            ProviderRef(ref.provider, ref.mainUrl, ""),
        ) ?: return null
        val response = try {
            provider.getMainPage(
                page,
                MainPageRequest(ref.rowName, ref.rowData, ref.horizontal),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: return null
        val cards = response.items
            .flatMap { it.list }
            .mapNotNull { item -> wrapItem(provider, item) }
        if (cards.isEmpty()) return null
        return newHomePageResponse(
            HomePageList(request.name, cards, request.horizontalImages),
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        val groups = Aggregator.searchGroups(
            query = query,
            providers = ProviderRegistry.candidates(this),
            config = searchConfig(),
            page = page,
        )
        return newSearchResponseList(
            groups.mapNotNull { toSearchResponse(it) },
            false,
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? =
        search(query, 1)?.items

    override suspend fun quickSearch(query: String): List<SearchResponse>? =
        search(query, 1)?.items

    override suspend fun load(url: String): LoadResponse? {
        val ref = Refs.parse(url) as? MegaRef.Result ?: return null
        val loads = Aggregator.loadAll(
            refs = ref.refs.take(Settings.maxLoadProviders()),
            resolver = ProviderRegistry::resolve,
            timeoutMs = Settings.timeoutMs(),
            concurrency = Settings.concurrency(),
        )
        if (loads.isEmpty()) return null
        val hasEpisodes = loads.any { Aggregator.episodesOf(it.response).isNotEmpty() }
        val primary = if (hasEpisodes) {
            loads.firstOrNull { Aggregator.episodesOf(it.response).isNotEmpty() } ?: loads.first()
        } else {
            loads.first()
        }
        val sources = Aggregator.sourcesLabel(loads)
        return when {
            ref.type == TvType.Live -> liveLoad(url, ref, primary, loads, sources)
            hasEpisodes -> seriesLoad(url, ref, primary, loads, sources)
            else -> movieLoad(url, ref, primary, loads, sources)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parsed = Refs.parse(data)
        val refs = when (parsed) {
            is MegaRef.Links -> parsed.refs
            is MegaRef.Episode -> parsed.refs
            else -> return false
        }
        val selected = if (Settings.aggregateLinks()) refs else refs.take(1)
        if (selected.isEmpty()) return false
        val bundle = Aggregator.loadLinks(
            refs = selected.take(16),
            isCasting = isCasting,
            resolver = ProviderRegistry::resolve,
            timeoutMs = Settings.timeoutMs() * 2,
            concurrency = Settings.concurrency(),
        )
        bundle.subtitles.forEach(subtitleCallback)
        bundle.links.forEach(callback)
        return bundle.links.isNotEmpty()
    }

    private fun searchConfig() = SearchConfig(
        timeoutMs = Settings.timeoutMs(),
        concurrency = Settings.concurrency(),
        maxRefs = Settings.maxLoadProviders(),
        includeAdult = Settings.includeAdult(),
    )

    private fun wrapItem(provider: MainAPI, item: SearchResponse): SearchResponse? {
        val type = Titles.typeOf(item)
        if (type == TvType.NSFW && !Settings.includeAdult()) return null
        val ref = ProviderRef(provider.name, provider.mainUrl, item.url)
        val group = SearchGroup(
            key = Titles.key(item),
            name = item.name,
            year = Titles.yearOf(item),
            type = type,
            posterUrl = item.posterUrl,
            posterHeaders = item.posterHeaders,
            quality = item.quality,
            score = item.score,
            refs = listOf(ref),
        )
        return toSearchResponse(group)
    }

    private fun toSearchResponse(group: SearchGroup): SearchResponse? {
        if (group.refs.isEmpty()) return null
        val display = Titles.displayName(
            group.name,
            group.refs.size,
            Settings.showSourceCount(),
        )
        val url = Refs.result(group.name, group.year, group.type, group.refs)
        val response: SearchResponse = when (group.type) {
            TvType.Movie,
            TvType.AnimeMovie,
            TvType.Torrent,
            TvType.Video,
            -> newMovieSearchResponse(display, url, group.type, fix = false)

            TvType.TvSeries,
            TvType.Cartoon,
            TvType.Documentary,
            TvType.AsianDrama,
            TvType.Others,
            -> newTvSeriesSearchResponse(display, url, group.type, fix = false)

            TvType.Anime,
            TvType.OVA,
            -> newAnimeSearchResponse(display, url, group.type, fix = false)

            TvType.Live -> newLiveSearchResponse(display, url, group.type, fix = false)
            TvType.NSFW -> newMovieSearchResponse(display, url, group.type, fix = false)
            else -> newMovieSearchResponse(display, url, group.type, fix = false)
        }
        response.posterUrl = group.posterUrl
        response.posterHeaders = group.posterHeaders
        response.quality = group.quality
        response.score = group.score
        when (response) {
            is MovieSearchResponse -> response.year = group.year
            is TvSeriesSearchResponse -> response.year = group.year
            is AnimeSearchResponse -> response.year = group.year
            is LiveSearchResponse -> response.lang = lang
            is TorrentSearchResponse -> Unit
        }
        return response
    }

    private suspend fun movieLoad(
        url: String,
        ref: MegaRef.Result,
        primary: ProviderLoad,
        loads: List<ProviderLoad>,
        sources: String,
    ): LoadResponse? {
        val linkRefs = linkRefs(loads)
        if (linkRefs.isEmpty()) return null
        return newMovieLoadResponse(
            name = primary.response.name,
            url = url,
            type = ref.type,
            dataUrl = Refs.links(linkRefs),
        ) {
            copyMeta(this, primary.response, url, sources)
        }
    }

    private suspend fun liveLoad(
        url: String,
        ref: MegaRef.Result,
        primary: ProviderLoad,
        loads: List<ProviderLoad>,
        sources: String,
    ): LoadResponse? {
        val linkRefs = linkRefs(loads)
        if (linkRefs.isEmpty()) return null
        return newLiveStreamLoadResponse(
            name = primary.response.name,
            url = url,
            dataUrl = Refs.links(linkRefs),
        ) {
            copyMeta(this, primary.response, url, sources)
        }
    }

    private suspend fun seriesLoad(
        url: String,
        ref: MegaRef.Result,
        primary: ProviderLoad,
        loads: List<ProviderLoad>,
        sources: String,
    ): LoadResponse? {
        val episodes = Aggregator.mergeEpisodes(this, loads, ref.title)
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(
            name = primary.response.name,
            url = url,
            type = ref.type,
            episodes = episodes,
        ) {
            copyMeta(this, primary.response, url, sources)
            (primary.response as? TvSeriesLoadResponse)?.let { source ->
                showStatus = source.showStatus
                seasonNames = source.seasonNames
                nextAiring = source.nextAiring
            }
        }
    }

    private fun linkRefs(loads: List<ProviderLoad>): List<ProviderRef> =
        loads.mapNotNull { load ->
            val data = when (val response = load.response) {
                is MovieLoadResponse -> response.dataUrl
                is LiveStreamLoadResponse -> response.dataUrl
                else -> null
            }
            data?.takeIf { it.isNotBlank() }?.let {
                ProviderRef(load.ref.provider, load.ref.mainUrl, it)
            }
        }

    private fun copyMeta(
        target: LoadResponse,
        source: LoadResponse,
        url: String,
        sources: String,
    ) {
        target.posterUrl = source.posterUrl
        target.year = source.year
        target.plot = listOfNotNull(
            source.plot,
            "MegaFlix matched: $sources",
        ).joinToString("\n\n")
        target.score = source.score
        target.tags = source.tags
        target.duration = source.duration
        target.trailers = source.trailers.toMutableList()
        target.actors = source.actors
        target.comingSoon = source.comingSoon
        target.syncData = source.syncData.toMutableMap()
        target.posterHeaders = source.posterHeaders
        target.backgroundPosterUrl = source.backgroundPosterUrl
        target.logoUrl = source.logoUrl
        target.contentRating = source.contentRating
        target.uniqueUrl = url
    }
}
