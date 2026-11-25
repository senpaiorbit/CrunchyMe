package Test

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.megaflix.Titles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TitlesTest {
    private val api = object : com.lagradost.cloudstream3.MainAPI() {
        override var name = "Test"
    }

    @Test
    fun normalizesPunctuationYearsAndCase() {
        assertEquals("inception", Titles.normalize("Inception (2010)"))
        assertEquals("the office", Titles.normalize("The  Office!"))
        assertEquals("amelie", Titles.normalize("Amélie"))
    }

    @Test
    fun readsYearAndTypeFromConcreteSearchResponses() {
        val movie = api.newMovieSearchResponse("Inception", "https://a/1") {
            year = 2010
        }
        val series = api.newTvSeriesSearchResponse("Dark", "https://a/2") {
            year = 2017
        }
        assertEquals(2010, Titles.yearOf(movie))
        assertEquals(TvType.Movie, Titles.typeOf(movie))
        assertEquals(2017, Titles.yearOf(series))
        assertEquals(TvType.TvSeries, Titles.typeOf(series))
    }

    @Test
    fun sourceCountLabelOnlyAppearsWhenUseful() {
        assertEquals("Inception", Titles.displayName("Inception", 1, true))
        assertEquals("Inception · 3 sources", Titles.displayName("Inception", 3, true))
        assertEquals("Inception", Titles.displayName("Inception", 3, false))
    }

    @Test
    fun recognizesLikelySameTitles() {
        assertTrue(Titles.isSameTitle("The Office", "Office, The"))
        assertTrue(Titles.isSameTitle("Spider-Man: No Way Home", "Spider Man No Way Home"))
        assertFalse(Titles.isSameTitle("Dark", "Daredevil"))
    }
}
