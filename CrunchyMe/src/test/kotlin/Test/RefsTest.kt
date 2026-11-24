package test

import com.crunchyme.Refs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RefsTest {

    @Test
    fun movieRoundTrip() {
        val url = Refs.movie(27205, "tt1375666")
        assertEquals("https://www.themoviedb.org/movie/27205?imdb=tt1375666", url)
        val ref = Refs.parse(url)!!
        assertEquals(27205, ref.tmdbId)
        assertEquals("tt1375666", ref.imdbId)
        assertEquals("movie", ref.type)
    }

    @Test
    fun seriesRoundTrip() {
        val url = Refs.tv(1399, "tt0944947")
        val ref = Refs.parse(url)!!
        assertEquals(1399, ref.tmdbId)
        assertEquals("tt0944947", ref.imdbId)
        assertEquals("tv", ref.type)
    }

    @Test
    fun episodeRoundTrip() {
        val url = Refs.episode(1399, "tt0944947", 1, 1)
        val ref = Refs.parse(url)!!
        assertEquals("tv", ref.type)
        assertEquals(1, ref.season)
        assertEquals(1, ref.episode)
    }

    @Test
    fun imdbOnlyUrlStillParses() {
        val ref = Refs.parse("https://www.imdb.com/title/tt1375666/")!!
        assertEquals("tt1375666", ref.imdbId)
        assertNull(ref.tmdbId)
    }

    @Test
    fun imdbCatalogUrlsRoundTrip() {
        val movie = Refs.parse(Refs.imdb("tt1375666", "movie"))!!
        assertEquals("tt1375666", movie.imdbId)
        assertEquals("movie", movie.type)
        assertNull(movie.tmdbId)

        val episode = Refs.parse(Refs.imdb("tt0944947", "tv", 2, 5))!!
        assertEquals("tv", episode.type)
        assertEquals(2, episode.season)
        assertEquals(5, episode.episode)
    }

    @Test
    fun unrelatedUrlIsRejected() {
        assertNull(Refs.parse("https://example.com/watch/123"))
        assertNull(Refs.parse(""))
        assertNull(Refs.parse(null))
    }

    @Test
    fun cacheKeySeparatesEpisodes() {
        val first = Refs.parse(Refs.episode(10, null, 1, 1))!!
        val second = Refs.parse(Refs.episode(10, null, 1, 2))!!
        assertEquals("10|tv|1|1", Refs.key(first))
        assertEquals("10|tv|1|2", Refs.key(second))
    }
}
