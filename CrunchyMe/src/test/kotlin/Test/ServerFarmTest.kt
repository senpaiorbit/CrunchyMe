package Test

import com.crunchyme.LinkRequest
import com.crunchyme.ServerFarm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerFarmTest {

    private val movie = LinkRequest(
        tmdbId = 27205,
        imdbId = "tt1375666",
        type = "movie",
        season = -1,
        episode = -1,
        title = "Inception",
        year = 2010,
        originalLanguage = "en",
    )

    private val episode = movie.copy(type = "tv", season = 1, episode = 2)

    @Test
    fun serverIdsAreUnique() {
        val ids = ServerFarm.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun buildsMovieAndEpisodeUrls() {
        val vidbolt = ServerFarm.spec("vidbolt")!!
        assertEquals(
            "https://vidbolt.xyz/api/resolve?type=movie&id=27205",
            ServerFarm.urlFor(vidbolt, movie),
        )
        assertEquals(
            "https://vidbolt.xyz/api/resolve?type=tv&id=27205&season=1&episode=2",
            ServerFarm.urlFor(vidbolt, episode),
        )
        val netmirror = ServerFarm.spec("netmirror")!!
        assertEquals(
            "https://net78.cc/api/embed-tmdb/27205?type=tv&se=1&ep=2",
            ServerFarm.urlFor(netmirror, episode),
        )
    }

    @Test
    fun defaultsEnableTheWorkingServers() {
        val defaults = ServerFarm.defaultEnabledIds()
        assertTrue("vidbolt" in defaults)
        assertTrue("vidrift" in defaults)
        assertTrue("vidly" in defaults)
        assertTrue("bingr" in defaults)
        assertTrue("netmirror" in defaults)
        assertFalse("atlantic" in defaults)
    }
}
