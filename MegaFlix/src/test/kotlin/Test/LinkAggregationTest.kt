package Test

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.megaflix.Aggregator
import com.megaflix.ProviderRef
import com.megaflix.Refs
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkAggregationTest {
    @Test
    fun keepsHighestQualityCopyOfEachUrlAndLabelsSource() = runBlocking {
        val low = newExtractorLink(
            "Source A",
            "Source A · 480p",
            "https://cdn.example/movie.m3u8",
            ExtractorLinkType.M3U8,
        ) {
            quality = 480
            referer = "https://source-a.example/"
        }
        val high = newExtractorLink(
            "Source A",
            "Source A · 1080p",
            "https://cdn.example/movie.m3u8",
            ExtractorLinkType.M3U8,
        ) {
            quality = 1080
            referer = "https://source-a.example/"
        }
        val other = newExtractorLink(
            "Source B",
            "Source B · 720p",
            "https://cdn.example/other.mp4",
            ExtractorLinkType.VIDEO,
        ) {
            quality = 720
        }

        val merged = Aggregator.dedupeLinks(listOf(low, high, other))

        assertEquals(2, merged.size)
        val best = merged.first { it.url.contains("movie") }
        assertEquals(1080, best.quality)
        assertEquals("https://source-a.example/", best.referer)
        assertEquals("Source A", best.source)
        assertTrue(best.name.contains("Source A"))
    }

    @Test
    fun loadLinksSkipsFailingProviderAndMergesTheRest() = runBlocking {
        val a = LinkProvider("A", 1080)
        val b = LinkProvider("B", 720)
        val refs = Refs.links(
            listOf(
                ProviderRef("A", "https://a.example", "data-a"),
                ProviderRef("B", "https://b.example", "data-b"),
                ProviderRef("Missing", "https://missing.example", "data-missing"),
            ),
        )

        val links = Aggregator.loadLinks(
            refs = listOf(
                ProviderRef("A", "https://a.example", "data-a"),
                ProviderRef("B", "https://b.example", "data-b"),
                ProviderRef("Missing", "https://missing.example", "data-missing"),
            ),
            isCasting = false,
            resolver = { ref -> when (ref.provider) {
                "A" -> a
                "B" -> b
                else -> null
            } },
            timeoutMs = 2_000,
            concurrency = 2,
        )

        assertTrue(refs.startsWith("https://megaflix.local/"))
        assertEquals(2, links.links.size)
        assertEquals(1080, links.links.maxOf { it.quality })
    }

    private class LinkProvider(
        override var name: String,
        private val quality: Int,
    ) : MainAPI() {
        override var mainUrl = "https://${name.lowercase()}.example"
        override val supportedTypes = setOf(TvType.Movie)

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            callback(
                newExtractorLink(
                    name,
                    "$name · ${quality}p",
                    "https://cdn.example/$name.m3u8",
                    ExtractorLinkType.M3U8,
                ) {
                    this.quality = this@LinkProvider.quality
                },
            )
            return true
        }
    }
}
