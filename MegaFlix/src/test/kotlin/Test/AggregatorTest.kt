package Test

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.megaflix.Aggregator
import com.megaflix.MegaRef
import com.megaflix.ProviderLoad
import com.megaflix.ProviderRef
import com.megaflix.Refs
import com.megaflix.SearchConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AggregatorTest {
    private class FakeProvider(
        override var name: String,
        private val results: List<SearchResponse> = emptyList(),
        private val loads: Map<String, LoadResponse> = emptyMap(),
    ) : MainAPI() {
        override var mainUrl = "https://$name.example"
        override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

        override suspend fun search(query: String, page: Int): SearchResponseList =
            newSearchResponseList(results, false)

        override suspend fun load(url: String): LoadResponse? = loads[url]
    }

    private fun movie(api: MainAPI, name: String, year: Int?, type: TvType = TvType.Movie) =
        api.newMovieSearchResponse(name, "https://${api.name}.example/$name", type) {
            this.year = year
            posterUrl = "https://img.example/$name.jpg"
        }

    @Test
    fun groupsExplicitProviderResponses() = runBlocking {
        val a = FakeProvider("A", listOf(movie(FakeProvider("A"), "Inception", 2010)))
        val b = FakeProvider("B", listOf(movie(FakeProvider("B"), "Inception", 2010)))
        val config = SearchConfig(timeoutMs = 2_000, concurrency = 2, maxRefs = 8, includeAdult = false)

        val groups = Aggregator.searchGroups("inception", listOf(a, b), config)

        assertEquals(1, groups.size)
        assertEquals(2, groups.first().refs.size)
        assertEquals("Inception", groups.first().name)
        assertEquals(2010, groups.first().year)
    }

    @Test
    fun missingYearStillJoinsTheKnownYearBucket() = runBlocking {
        val a = FakeProvider("A", listOf(movie(FakeProvider("A"), "Dune", 2021)))
        val b = FakeProvider("B", listOf(movie(FakeProvider("B"), "Dune", null)))
        val groups = Aggregator.searchGroups(
            "dune",
            listOf(a, b),
            SearchConfig(2_000, 2, 8, false),
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.first().refs.size)
        assertEquals(2021, groups.first().year)
    }

    @Test
    fun differentYearsDoNotCollapse() = runBlocking {
        val a = FakeProvider("A", listOf(movie(FakeProvider("A"), "Dune", 1984)))
        val b = FakeProvider("B", listOf(movie(FakeProvider("B"), "Dune", 2021)))
        val groups = Aggregator.searchGroups(
            "dune",
            listOf(a, b),
            SearchConfig(2_000, 2, 8, false),
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun filtersNsfwUnlessEnabled() = runBlocking {
        val a = FakeProvider(
            "A",
            listOf(movie(FakeProvider("A"), "Adult Title", 2024, TvType.NSFW)),
        )
        val off = Aggregator.searchGroups("adult", listOf(a), SearchConfig(2_000, 2, 8, false))
        val on = Aggregator.searchGroups("adult", listOf(a), SearchConfig(2_000, 2, 8, true))
        assertTrue(off.isEmpty())
        assertEquals(1, on.size)
    }

    @Test
    fun mergesEpisodeSourcesAndEncodesEveryRef() = runBlocking {
        val api = FakeProvider("A")
        val e1 = api.newEpisode("episode-a") {
            name = "Pilot"
            season = 1
            episode = 1
        }
        val e2 = api.newEpisode("episode-b") {
            name = "Pilot"
            season = 1
            episode = 1
        }
        val loadA = api.newTvSeriesLoadResponse(
            "Show",
            "https://a.example/show",
            TvType.TvSeries,
            listOf(e1),
        ) {}
        val loadB = api.newTvSeriesLoadResponse(
            "Show",
            "https://b.example/show",
            TvType.TvSeries,
            listOf(e2),
        ) {}

        val merged = Aggregator.mergeEpisodes(
            api,
            listOf(
                ProviderLoad(ProviderRef("A", "https://a.example", "a-data"), loadA),
                ProviderLoad(ProviderRef("B", "https://b.example", "b-data"), loadB),
            ),
            "Show",
        )

        assertEquals(1, merged.size)
        val data = Refs.parse(merged.first().data) as MegaRef.Episode
        assertEquals(2, data.refs.size)
        assertEquals("Pilot", data.title)
        assertEquals(1, data.season)
        assertNotNull(merged.first().data)
    }

    @Test
    fun loadAllToleratesMissingProviders() = runBlocking {
        val api = FakeProvider("A")
        val load = api.newTvSeriesLoadResponse(
            "Show",
            "https://a.example/show",
            TvType.TvSeries,
            emptyList(),
        ) {}
        val withLoad = FakeProvider("A", loads = mapOf("show" to load))
        val refs = listOf(
            ProviderRef("A", "https://a.example", "show"),
            ProviderRef("Missing", "https://missing.example", "show"),
        )
        val loads = Aggregator.loadAll(refs, { ref ->
            if (ref.provider == "A") withLoad else null
        }, 2_000, 2)
        assertEquals(1, loads.size)
        assertEquals(load, loads.first().response)
    }
}
