package Test

import com.lagradost.cloudstream3.TvType
import com.megaflix.MegaRef
import com.megaflix.ProviderRef
import com.megaflix.Refs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MegaFlixRefsTest {
    private val refs = listOf(
        ProviderRef("Source A", "https://a.example", "https://a.example/watch?id=1&x=2"),
        ProviderRef("Источник", "https://б.example", "{\"data\":\"значение\"}"),
    )

    @Test
    fun resultRoundTripsUnicodeAndUrls() {
        val encoded = Refs.result("Café & <Friends>", 2024, TvType.TvSeries, refs)
        assertTrue(encoded.startsWith("https://megaflix.local/"))
        val parsed = Refs.parse(encoded) as MegaRef.Result
        assertEquals("Café & <Friends>", parsed.title)
        assertEquals(2024, parsed.year)
        assertEquals(TvType.TvSeries, parsed.type)
        assertEquals(refs, parsed.refs)
    }

    @Test
    fun episodeRoundTrips() {
        val encoded = Refs.episode("Pilot", 2, 5, refs)
        val parsed = Refs.parse(encoded) as MegaRef.Episode
        assertEquals("Pilot", parsed.title)
        assertEquals(2, parsed.season)
        assertEquals(5, parsed.episode)
        assertEquals(refs, parsed.refs)
    }

    @Test
    fun linksRoundTrips() {
        val parsed = Refs.parse(Refs.links(refs)) as MegaRef.Links
        assertEquals(refs, parsed.refs)
    }

    @Test
    fun homeRoundTrips() {
        val parsed = Refs.parse(
            Refs.home("Source A", "https://a.example", "Trending", "{\"row\":1}", true),
        ) as MegaRef.Home
        assertEquals("Source A", parsed.provider)
        assertEquals("Trending", parsed.rowName)
        assertEquals("{\"row\":1}", parsed.rowData)
        assertTrue(parsed.horizontal)
    }

    @Test
    fun rejectsForeignAndMalformedValues() {
        assertNull(Refs.parse("https://example.com/watch/1"))
        assertNull(Refs.parse(""))
        assertNull(Refs.parse(null))
        assertNull(Refs.parse("https://megaflix.local/r/not-base64"))
    }
}
