package com.megaflix

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

data class SearchConfig(
    val timeoutMs: Long,
    val concurrency: Int,
    val maxRefs: Int,
    val includeAdult: Boolean,
)

data class SearchHit(val provider: MainAPI, val item: SearchResponse)

data class SearchGroup(
    val key: String,
    val name: String,
    val year: Int?,
    val type: TvType,
    val posterUrl: String?,
    val posterHeaders: Map<String, String>?,
    val quality: SearchQuality?,
    val score: Score?,
    val refs: List<ProviderRef>,
)

data class ProviderLoad(
    val ref: ProviderRef,
    val response: LoadResponse,
)

data class EpisodeSource(
    val ref: ProviderRef,
    val episode: Episode,
)

data class LinkBundle(
    val links: List<ExtractorLink>,
    val subtitles: List<SubtitleFile>,
)

object Aggregator {
    private suspend fun <T> attempt(timeoutMs: Long, block: suspend () -> T?): T? {
        return try {
            withTimeoutOrNull(timeoutMs) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun searchGroups(
        query: String,
        providers: List<MainAPI>,
        config: SearchConfig,
        page: Int = 1,
    ): List<SearchGroup> = coroutineScope {
        if (query.isBlank() || providers.isEmpty()) return@coroutineScope emptyList()
        val semaphore = Semaphore(config.concurrency.coerceAtLeast(1))
        val responses = providers.map { provider ->
            async {
                semaphore.withPermit {
                    attempt(config.timeoutMs) {
                        provider.search(query, page)?.items
                    }.also { items ->
                        if (items == null) ProviderHealth.recordFailure(provider.name)
                        else ProviderHealth.recordSuccess(provider.name)
                    }
                }
            }
        }.awaitAll()

        val hits = responses.flatMapIndexed { index, items ->
            val provider = providers[index]
            items.orEmpty()
                .asSequence()
                .filter { config.includeAdult || Titles.typeOf(it) != TvType.NSFW }
                .map { SearchHit(provider, it) }
                .toList()
        }

        val byTitle = hits.groupBy {
            Titles.normalize(it.item.name) + "|" + Titles.typeOf(it.item).name
        }
        val groupedHits = byTitle.values.flatMap { bucket ->
            val knownYears = bucket
                .mapNotNull { hit -> Titles.yearOf(hit.item)?.let { year -> year to hit } }
                .groupBy({ it.first }, { it.second })
            if (knownYears.size <= 1) {
                listOf(bucket)
            } else {
                val byYear = knownYears.values.map { it.toMutableList() }
                bucket.filter { Titles.yearOf(it.item) == null }
                    .forEach { byYear.first().add(it) }
                byYear
            }
        }
        groupedHits
            .mapNotNull { group ->
                val first = group.firstOrNull() ?: return@mapNotNull null
                val refs = group
                    .distinctBy { it.provider.name + "|" + it.provider.mainUrl }
                    .map { ProviderRef(it.provider.name, it.provider.mainUrl, it.item.url) }
                    .take(config.maxRefs.coerceAtLeast(1))
                if (refs.isEmpty()) return@mapNotNull null
                SearchGroup(
                    key = Titles.key(first.item),
                    name = first.item.name,
                    year = group.firstNotNullOfOrNull { Titles.yearOf(it.item) },
                    type = Titles.typeOf(first.item),
                    posterUrl = group.firstNotNullOfOrNull { it.item.posterUrl },
                    posterHeaders = group.firstNotNullOfOrNull { it.item.posterHeaders },
                    quality = group.mapNotNull { it.item.quality }
                        .maxByOrNull { it.ordinal },
                    score = group.firstNotNullOfOrNull { it.item.score },
                    refs = refs,
                )
            }
            .sortedWith(compareByDescending<SearchGroup> { it.refs.size }.thenBy { it.name.lowercase() })
    }

    suspend fun loadAll(
        refs: List<ProviderRef>,
        resolver: (ProviderRef) -> MainAPI?,
        timeoutMs: Long,
        concurrency: Int,
    ): List<ProviderLoad> = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        refs.distinctBy { it.provider + "|" + it.mainUrl + "|" + it.value }
            .map { ref ->
                async {
                    semaphore.withPermit {
                        val provider = resolver(ref)
                        val response = if (provider == null) null else attempt(timeoutMs) {
                            provider.load(ref.value)
                        }
                        if (provider != null) {
                            if (response == null) ProviderHealth.recordFailure(provider.name)
                            else ProviderHealth.recordSuccess(provider.name)
                        }
                        response?.let { ProviderLoad(ref, it) }
                    }
                }
            }.awaitAll().filterNotNull()
    }

    suspend fun loadLinks(
        refs: List<ProviderRef>,
        isCasting: Boolean,
        resolver: (ProviderRef) -> MainAPI?,
        timeoutMs: Long,
        concurrency: Int,
    ): LinkBundle = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val bundles = refs.distinctBy { it.provider + "|" + it.mainUrl + "|" + it.value }
            .map { ref ->
                async {
                    semaphore.withPermit {
                        val provider = resolver(ref) ?: return@withPermit LinkBundle(emptyList(), emptyList())
                        val links = mutableListOf<ExtractorLink>()
                        val subtitles = mutableListOf<SubtitleFile>()
                        val ok = attempt(timeoutMs) {
                            provider.loadLinks(
                                ref.value,
                                isCasting,
                                { subtitles += it },
                                { links += it },
                            )
                        } ?: false
                        if (ok || links.isNotEmpty()) ProviderHealth.recordSuccess(provider.name)
                        else ProviderHealth.recordFailure(provider.name)
                        LinkBundle(links.toList(), subtitles.toList())
                    }
                }
            }.awaitAll()

        val links = dedupeLinks(bundles.flatMap { it.links })
        val subtitles = bundles.flatMap { it.subtitles }
            .distinctBy { it.url + "|" + it.lang }
        LinkBundle(links, subtitles)
    }

    suspend fun dedupeLinks(links: List<ExtractorLink>): List<ExtractorLink> {
        val best = LinkedHashMap<String, ExtractorLink>()
        for (link in links) {
            val key = link.url.trim()
            val current = best[key]
            if (current == null || link.quality > current.quality) {
                best[key] = link
            }
        }
        return best.values
            .sortedByDescending { it.quality }
            .map { link ->
                newExtractorLink(
                    source = link.source.ifBlank { "MegaFlix" },
                    name = if (link.name.startsWith(link.source)) link.name else "${link.source} • ${link.name}",
                    url = link.url,
                    type = link.type,
                ) {
                    referer = link.referer
                    quality = link.quality
                    headers = link.headers
                    extractorData = link.extractorData
                    audioTracks = link.audioTracks
                }
            }
    }

    fun mergeEpisodes(
        api: MainAPI,
        loads: List<ProviderLoad>,
        title: String,
    ): List<Episode> {
        val grouped = LinkedHashMap<String, MutableList<EpisodeSource>>()
        for (load in loads) {
            val episodes = episodesOf(load.response)
            episodes.forEachIndexed { index, episode ->
                val season = episode.season ?: 1
                val number = episode.episode ?: (index + 1)
                val key = "$season|$number"
                grouped.getOrPut(key) { mutableListOf() }
                    .add(EpisodeSource(load.ref.copy(value = episode.data), episode))
            }
        }

        return grouped.entries
            .sortedWith(
                compareBy(
                    { it.key.substringBefore('|').toIntOrNull() ?: 0 },
                    { it.key.substringAfter('|').toIntOrNull() ?: 0 },
                ),
            )
            .map { (key, sources) ->
                val first = sources.first().episode
                val refs = sources.map { it.ref }
                    .distinctBy { it.provider + "|" + it.mainUrl + "|" + it.value }
                val season = key.substringBefore('|').toIntOrNull() ?: 1
                val number = key.substringAfter('|').toIntOrNull()
                api.newEpisode(
                    Refs.episode(
                        title = first.name ?: title,
                        season = season,
                        episode = number,
                        refs = refs,
                    ),
                ) {
                    name = first.name ?: "Episode ${number ?: 1}"
                    this.season = season
                    this.episode = number
                    posterUrl = first.posterUrl
                    score = first.score
                    description = first.description
                    date = first.date
                    runTime = first.runTime
                }
            }
    }

    fun episodesOf(response: LoadResponse): List<Episode> = when (response) {
        is TvSeriesLoadResponse -> response.episodes
        is AnimeLoadResponse -> response.episodes.values.flatten()
        else -> emptyList()
    }

    fun sourcesLabel(loads: List<ProviderLoad>): String =
        loads.map { it.ref.provider }.distinct().joinToString(" · ")
}
