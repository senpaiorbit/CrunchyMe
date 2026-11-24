package test

import com.crunchyme.Tmdb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions

class LiveTmdbTest {

    private fun requireKey(): String {
        val key = TestKeys.tmdb()
        Assumptions.assumeTrue(key != null, "TMDB_API_KEY or tmdb.api.key not configured")
        Tmdb.keyProvider = { key }
        return key!!
    }

    @Test
    fun keyIsAccepted() = runBlocking {
        val key = requireKey()
        assertTrue(Tmdb.testKey(key))
    }

    @Test
    fun searchFindsInception() = runBlocking {
        requireKey()
        val results = assertNotNull(Tmdb.search("inception"))
        assertTrue(results.any { it.id == 27205 && it.type == "movie" }, "Inception not in results")
        assertEquals("Inception", results.first { it.id == 27205 }.title)
    }

    @Test
    fun detailExposesImdbIdAndCast() = runBlocking {
        requireKey()
        val detail = assertNotNull(Tmdb.detail(27205, "movie"))
        assertEquals("Inception", detail.title)
        assertEquals("tt1375666", detail.imdbId)
        assertEquals(2010, detail.year)
        assertTrue(detail.genres.isNotEmpty())
        assertTrue(detail.cast.any { it.name == "Leonardo DiCaprio" })
    }

    @Test
    fun seriesHasSeasonsAndEpisodes() = runBlocking {
        requireKey()
        val detail = assertNotNull(Tmdb.detail(1399, "tv"))
        assertEquals("Game of Thrones", detail.title)
        assertTrue(detail.seasonNumbers.contains(1))
        val episodes = Tmdb.seasonEpisodes(1399, 1)
        assertTrue(episodes.size >= 10)
        assertEquals(1, episodes.first().season)
        assertEquals(1, episodes.first().number)
    }

    @Test
    fun rowsReturnCards() = runBlocking {
        requireKey()
        val trending = assertNotNull(Tmdb.row("trending_movie", 1))
        assertTrue(trending.isNotEmpty())
        val hindi = assertNotNull(Tmdb.row("hindi", 1))
        assertTrue(hindi.isNotEmpty())
    }
}
